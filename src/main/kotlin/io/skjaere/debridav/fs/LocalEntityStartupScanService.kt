package io.skjaere.debridav.fs

import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.webdav.folder.WebDavProviderConfigurationService
import io.skjaere.debridav.webdav.folder.WebDavSyncedFileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy

@Service
class LocalEntityStartupScanService(
    private val debridFileContentsRepository: DebridFileContentsRepository,
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val fileChunkCachingService: FileChunkCachingService,
    private val webDavSyncedFileRepository: WebDavSyncedFileRepository?,
    private val webDavProviderConfigService: WebDavProviderConfigurationService?
) {
    private val logger = LoggerFactory.getLogger(LocalEntityStartupScanService::class.java)
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun scanAndConvertRemoteToLocal() {
        if (!debridavConfigurationProperties.enableLocalEntityConversionStartupScan) {
            logger.debug("Local entity conversion startup scan is disabled")
            return
        }

        if (debridavConfigurationProperties.localEntityAlwaysStoreExtensions.isEmpty()) {
            logger.debug("No file extensions configured for local entity storage, skipping startup scan")
            return
        }

        logger.info(
            "Starting startup scan to convert RemotelyCachedEntity items to LocalEntity for extensions: {}",
            debridavConfigurationProperties.localEntityAlwaysStoreExtensions
        )

        scanScope.launch {
            try {
                val allRemoteEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
                logger.info("Found {} RemotelyCachedEntity items to scan", allRemoteEntities.size)

                var scanned = 0
                var converted = 0
                var failed = 0
                var skipped = 0

                for (entity in allRemoteEntities) {
                    try {
                        scanned++
                        val fileName = entity.name ?: continue
                        
                        // Check if this is a file from a previous failed conversion (has '_to-local' suffix)
                        val isRetry = fileName.contains("_to-local")
                        val originalFileName = if (isRetry) {
                            // Extract original filename by removing '_to-local' suffix
                            removeToLocalSuffix(fileName)
                        } else {
                            fileName
                        }
                        
                        // Check if file extension matches whitelist (using original filename)
                        if (!debridavConfigurationProperties.shouldAlwaysStoreAsLocalEntity(originalFileName)) {
                            skipped++
                            continue
                        }

                        // Reload entity to ensure lazy properties are loaded
                        val reloadedEntity = databaseFileService.reloadRemotelyCachedEntity(entity)
                        if (reloadedEntity == null) {
                            logger.warn("Could not reload entity with id {} (name: {}), skipping", entity.id, fileName)
                            skipped++
                            continue
                        }

                        val debridFileContents = reloadedEntity.contents
                        if (debridFileContents == null) {
                            logger.warn("Entity {} has no contents, skipping", fileName)
                            skipped++
                            continue
                        }

                        // Check if we have debrid links available for download
                        val hasDownloadableLink = when {
                            debridFileContents is DebridIptvContent -> {
                                val iptvFile = debridFileContents.debridLinks.firstOrNull { it is IptvFile } as? IptvFile
                                iptvFile?.link != null
                            }
                            else -> {
                                val cachedFile = debridFileContents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
                                cachedFile?.link != null
                            }
                        }

                        if (!hasDownloadableLink) {
                            logger.debug("File {} has whitelisted extension but no download link available yet, skipping", fileName)
                            skipped++
                            continue
                        }

                        // Get file path using original filename
                        val directoryPath = reloadedEntity.directory?.fileSystemPath() ?: "/"
                        val filePath = if (directoryPath == "/") {
                            "/$originalFileName"
                        } else {
                            "$directoryPath/$originalFileName"
                        }

                        // Check if LocalEntity already exists at this path (using original filename)
                        val existingFile = reloadedEntity.directory?.let { dir ->
                            debridFileContentsRepository.findByDirectoryAndName(dir, originalFileName)
                        }
                        if (existingFile is LocalEntity) {
                            logger.debug("LocalEntity already exists at {}, skipping conversion", filePath)
                            skipped++
                            continue
                        }

                        if (isRetry) {
                            logger.info("Retrying conversion of {} from RemotelyCachedEntity to LocalEntity (previous attempt failed)", filePath)
                        } else {
                            logger.info("Converting {} from RemotelyCachedEntity to LocalEntity", filePath)
                            
                            // Rename the RemotelyCachedEntity by adding '_to-local' suffix before extension
                            // This allows us to reprocess if something fails
                            val renamedFileName = addToLocalSuffix(fileName)
                            reloadedEntity.name = renamedFileName
                            debridFileContentsRepository.save(reloadedEntity)
                            logger.debug("Renamed RemotelyCachedEntity {} to {} for safe conversion", fileName, renamedFileName)
                        }

                        // Check if this is a WebDAV synced file (has synced_file_id in params)
                        val cachedFile = debridFileContents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
                        val isWebDavFile = cachedFile?.params?.containsKey("synced_file_id") == true
                        val authHeader = if (isWebDavFile && webDavSyncedFileRepository != null && webDavProviderConfigService != null) {
                            // Get WebDAV auth header for this file
                            val syncedFileId = cachedFile.params?.get("synced_file_id")?.toLongOrNull()
                            if (syncedFileId != null) {
                                val syncedFile = webDavSyncedFileRepository.findById(syncedFileId).orElse(null)
                                if (syncedFile != null) {
                                    getWebDavAuthHeader(syncedFile, cachedFile.link ?: "")
                                } else {
                                    logger.warn("WebDAV synced file with id {} not found, skipping auth header", syncedFileId)
                                    null
                                }
                            } else {
                                null
                            }
                        } else {
                            null
                        }

                        // Download and store as LocalEntity (with WebDAV auth if needed)
                        databaseFileService.downloadAndStoreAsLocalEntity(filePath, debridFileContents, authHeader)

                        // Delete the renamed RemotelyCachedEntity after successful LocalEntity creation
                        when (reloadedEntity.contents) {
                            is DebridCachedTorrentContent -> {
                                debridFileContentsRepository.unlinkFileFromTorrents(reloadedEntity)
                            }
                            is DebridCachedUsenetReleaseContent -> {
                                debridFileContentsRepository.unlinkFileFromUsenet(reloadedEntity)
                            }
                            is DebridIptvContent -> {
                                debridFileContentsRepository.unlinkFileFromTorrents(reloadedEntity)
                            }
                        }
                        fileChunkCachingService.deleteChunksForFile(reloadedEntity)
                        debridFileContentsRepository.deleteDbEntityByHash(reloadedEntity.hash!!)
                        logger.debug("Deleted renamed RemotelyCachedEntity {}", reloadedEntity.name)

                        converted++
                        logger.info("Successfully converted {} to LocalEntity", filePath)
                    } catch (e: Exception) {
                        failed++
                        logger.error(
                            "Failed to convert RemotelyCachedEntity {} (id: {}) to LocalEntity: {}",
                            entity.name,
                            entity.id,
                            e.message,
                            e
                        )
                    }
                }

                logger.info(
                    "Startup scan completed. Scanned: {}, Converted: {}, Failed: {}, Skipped: {}",
                    scanned,
                    converted,
                    failed,
                    skipped
                )
            } catch (e: Exception) {
                logger.error("Error during startup scan for remote to local conversion: {}", e.message, e)
            }
        }
    }

    @PreDestroy
    fun cleanup() {
        scanScope.cancel()
    }

    /**
     * Adds '_to-local' suffix before the file extension.
     * Example: "file.srt" -> "file_to-local.srt"
     */
    private fun addToLocalSuffix(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            val nameWithoutExt = fileName.substring(0, lastDotIndex)
            val extension = fileName.substring(lastDotIndex)
            "${nameWithoutExt}_to-local$extension"
        } else {
            "${fileName}_to-local"
        }
    }

    /**
     * Removes '_to-local' suffix from filename to get the original filename.
     * Example: "file_to-local.srt" -> "file.srt"
     */
    private fun removeToLocalSuffix(fileName: String): String {
        return fileName.replace("_to-local", "")
    }

    /**
     * Gets WebDAV auth header for downloading files (same logic as WebDavFolderSyncService)
     */
    private fun getWebDavAuthHeader(syncedFile: io.skjaere.debridav.webdav.folder.WebDavSyncedFileEntity, downloadUrl: String): String? {
        val providerName = syncedFile.folderMapping?.providerName ?: return null
        val config = webDavProviderConfigService?.getConfiguration(providerName) ?: return null
        
        return when (config.authType) {
            io.skjaere.debridav.webdav.folder.WebDavAuthType.BASIC -> {
                if (config.hasCredentials()) {
                    val credentials = "${config.username}:${config.password}"
                    "Basic ${java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())}"
                } else null
            }
            io.skjaere.debridav.webdav.folder.WebDavAuthType.BEARER -> {
                if (config.hasCredentials()) {
                    "Bearer ${config.bearerToken}"
                } else null
            }
        }
    }
}
