package io.skjaere.debridav.fs

import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.repository.DebridFileContentsRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class LocalEntityStartupScanService(
    private val debridFileContentsRepository: DebridFileContentsRepository,
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val fileChunkCachingService: FileChunkCachingService
) {
    private val logger = LoggerFactory.getLogger(LocalEntityStartupScanService::class.java)

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

        runBlocking {
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
                        
                        // Check if file extension matches whitelist
                        if (!debridavConfigurationProperties.shouldAlwaysStoreAsLocalEntity(fileName)) {
                            skipped++
                            continue
                        }

                        // Reload entity to ensure lazy properties are loaded
                        val reloadedEntity = databaseFileService.reloadRemotelyCachedEntity(entity)
                            ?: run {
                                logger.warn("Could not reload entity with id {} (name: {}), skipping", entity.id, fileName)
                                skipped++
                                continue
                            }

                        val debridFileContents = reloadedEntity.contents
                            ?: run {
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

                        // Get file path
                        val directoryPath = reloadedEntity.directory?.fileSystemPath() ?: "/"
                        val filePath = if (directoryPath == "/") {
                            "/$fileName"
                        } else {
                            "$directoryPath/$fileName"
                        }

                        // Check if LocalEntity already exists at this path
                        val existingFile = reloadedEntity.directory?.let { dir ->
                            debridFileContentsRepository.findByDirectoryAndName(dir, fileName)
                        }
                        if (existingFile is LocalEntity) {
                            logger.debug("LocalEntity already exists at {}, skipping conversion", filePath)
                            skipped++
                            continue
                        }

                        logger.info("Converting {} from RemotelyCachedEntity to LocalEntity", filePath)

                        // Download and store as LocalEntity
                        databaseFileService.downloadAndStoreAsLocalEntity(filePath, debridFileContents)

                        // Delete the RemotelyCachedEntity
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
}
