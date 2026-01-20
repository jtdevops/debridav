package io.skjaere.debridav.webdav.folder.sync

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.webdav.folder.WebDavFolderMappingEntity
import io.skjaere.debridav.webdav.folder.WebDavFolderMappingProperties
import io.skjaere.debridav.webdav.folder.WebDavFolderMappingRepository
import io.skjaere.debridav.webdav.folder.WebDavSyncedFileEntity
import io.skjaere.debridav.webdav.folder.WebDavSyncedFileRepository
import io.skjaere.debridav.webdav.folder.webdav.WebDavFile
import io.skjaere.debridav.webdav.folder.webdav.WebDavFolderService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class WebDavFolderSyncService(
    private val folderMappingRepository: WebDavFolderMappingRepository,
    private val syncedFileRepository: WebDavSyncedFileRepository,
    private val webDavFolderService: WebDavFolderService,
    private val fileMappingService: FileMappingService,
    private val syncedFileContentService: SyncedFileContentService,
    private val databaseFileService: DatabaseFileService,
    private val folderMappingProperties: WebDavFolderMappingProperties
) {
    private val logger = LoggerFactory.getLogger(WebDavFolderSyncService::class.java)

    @Transactional
    suspend fun syncAllMappings() {
        val mappings = folderMappingRepository.findByEnabled(true)
        logger.info("Starting sync for ${mappings.size} folder mappings")

        mappings.forEach { mapping ->
            try {
                syncMappingWithRetry(mapping)
            } catch (e: Exception) {
                logger.error("Error syncing mapping ${mapping.id} (${mapping.providerName}:${mapping.externalPath})", e)
                // Continue with other mappings even if one fails
            }
        }
    }

    private suspend fun syncMappingWithRetry(mapping: WebDavFolderMappingEntity, maxRetries: Int = 3) {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                syncMapping(mapping)
                return // Success, exit retry loop
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    logger.warn("Sync attempt ${attempt + 1} failed for mapping ${mapping.id}, retrying...", e)
                    kotlinx.coroutines.delay(1000L * (attempt + 1)) // Exponential backoff
                }
            }
        }
        // All retries failed
        throw lastException ?: Exception("Unknown error during sync")
    }

    @Transactional
    suspend fun syncMapping(mapping: WebDavFolderMappingEntity) {
        logger.debug("Syncing mapping ${mapping.id}: ${mapping.providerName} -> ${mapping.internalPath}")

        // Log root folders if enabled for this provider
        val providerName = mapping.providerName
        if (providerName != null && providerName in folderMappingProperties.getLogRootFoldersList()) {
            webDavFolderService.logRootFolders(providerName)
        }

        syncWebDavMapping(mapping)

        // Update last synced timestamp
        mapping.lastSynced = Instant.now()
        folderMappingRepository.save(mapping)
    }

    private suspend fun syncWebDavMapping(mapping: WebDavFolderMappingEntity) {
        try {
            val files = webDavFolderService.listFiles(mapping)
            logger.info("WebDAV sync found ${files.size} total items for mapping ${mapping.id}")
            
            val actualFiles = files.filter { !it.isDirectory }
            val directories = files.filter { it.isDirectory }
            logger.info("WebDAV sync: ${actualFiles.size} files, ${directories.size} directories")
            
            // Filter files by allowed extensions
            val filteredFiles = actualFiles.filter { file ->
                val fileName = fileMappingService.getVfsFileName(file.path)
                val shouldSync = folderMappingProperties.shouldSyncFile(fileName)
                if (!shouldSync) {
                    logger.debug("Skipping file (extension not allowed): {}", fileName)
                }
                shouldSync
            }
            logger.info("After extension filtering: ${filteredFiles.size} files to sync")
            
            val existingFiles = syncedFileRepository.findByFolderMapping(mapping)
                .associateBy { it.providerFileId }

            val providerFileIds = mutableSetOf<String>()
            var newFilesCount = 0
            var updatedFilesCount = 0

            filteredFiles.forEach { webDavFile ->
                try {
                    val fileId = generateFileId(webDavFile.path, webDavFile.name)
                    providerFileIds.add(fileId)

                    val existingFile = existingFiles[fileId]
                    if (existingFile == null) {
                        // Create new synced file
                        createSyncedFileFromWebDav(mapping, webDavFile, fileId)
                        newFilesCount++
                        logger.debug("Created new synced file: ${webDavFile.path}")
                    } else {
                        // Update existing file
                        updateSyncedFileFromWebDav(existingFile, webDavFile)
                        updatedFilesCount++
                    }
                } catch (e: Exception) {
                    logger.error("Error processing WebDAV file ${webDavFile.path} for mapping ${mapping.id}", e)
                    // Continue with other files
                }
            }
            
            logger.info("WebDAV sync completed for mapping ${mapping.id}: $newFilesCount new, $updatedFilesCount updated")

            // Mark files as deleted if they're no longer in provider
            try {
                syncedFileRepository.markAsDeletedForMissingFiles(mapping, providerFileIds.toList())
            } catch (e: Exception) {
                logger.error("Error marking files as deleted for mapping ${mapping.id}", e)
            }
        } catch (e: Exception) {
            logger.error("Error syncing WebDAV mapping ${mapping.id}", e)
            throw e
        }
    }

    private suspend fun createSyncedFileFromWebDav(
        mapping: WebDavFolderMappingEntity,
        webDavFile: WebDavFile,
        fileId: String
    ) {
        val vfsPath = fileMappingService.mapToVfsPath(mapping, webDavFile.path)
        val vfsFileName = fileMappingService.getVfsFileName(webDavFile.path)

        logger.info("Creating synced file: vfsPath='{}', vfsFileName='{}', providerPath='{}'", 
            vfsPath, vfsFileName, webDavFile.path)

        val syncedFile = WebDavSyncedFileEntity().apply {
            folderMapping = mapping
            providerFileId = fileId
            providerFilePath = webDavFile.path
            this.vfsPath = vfsPath
            this.vfsFileName = vfsFileName
            fileSize = webDavFile.size
            mimeType = webDavFile.mimeType
            providerLink = webDavFile.downloadLink
            lastChecked = Instant.now()
            isDeleted = false
        }

        val savedFile = syncedFileRepository.save(syncedFile)
        logger.info("Saved synced file with id: {}", savedFile.id)

        // Create VFS entry - convert providerName to DebridProvider
        val provider = mapProviderNameToDebridProvider(mapping.providerName ?: "")
        if (provider != null) {
            createVfsEntry(savedFile, provider)
        } else {
            logger.warn("Cannot create VFS entry: unknown provider name '${mapping.providerName}'")
        }
    }

    private suspend fun updateSyncedFileFromWebDav(
        existingFile: WebDavSyncedFileEntity,
        webDavFile: WebDavFile
    ) {
        // Update file metadata
        existingFile.providerFilePath = webDavFile.path
        existingFile.fileSize = webDavFile.size
        existingFile.mimeType = webDavFile.mimeType
        existingFile.providerLink = webDavFile.downloadLink
        existingFile.lastChecked = Instant.now()
        existingFile.isDeleted = false

        syncedFileRepository.save(existingFile)

        // Update VFS entry if needed
        updateVfsEntry(existingFile)
    }

    private suspend fun createVfsEntry(syncedFile: WebDavSyncedFileEntity, provider: DebridProvider) {
        try {
            val vfsPath = syncedFile.vfsPath
            if (vfsPath.isNullOrBlank()) {
                logger.warn("Cannot create VFS entry: vfsPath is null or blank for synced file ${syncedFile.id}")
                return
            }
            
            val vfsFileName = syncedFile.vfsFileName ?: syncedFile.providerFilePath?.substringAfterLast("/")
            if (vfsFileName.isNullOrBlank()) {
                logger.warn("Cannot create VFS entry: vfsFileName is null or blank for synced file ${syncedFile.id}")
                return
            }

            val fullVfsPath = if (vfsPath.endsWith("/")) {
                "$vfsPath$vfsFileName"
            } else {
                "$vfsPath/$vfsFileName"
            }

            logger.info("Creating VFS entry at path: {}", fullVfsPath)

            val contents = syncedFileContentService.createDebridFileContents(syncedFile, provider)
            val hash = syncedFile.providerFileId ?: generateHash(fullVfsPath)

            logger.debug("VFS entry contents: size={}, mimeType={}, link={}", 
                contents.size, contents.mimeType, 
                (contents.debridLinks.firstOrNull() as? CachedFile)?.link?.take(50))

            // Skip LocalEntity conversion for folder mapping sync - we want to keep files as 
            // RemotelyCachedEntity pointing to the provider's WebDAV URL for direct streaming
            val createdFile = databaseFileService.createDebridFile(fullVfsPath, hash, contents, skipLocalEntityConversion = true)
            logger.info("Successfully created VFS entry: {} (id: {})", fullVfsPath, createdFile.id)
        } catch (e: Exception) {
            logger.error("Error creating VFS entry for synced file ${syncedFile.id}: ${e.message}", e)
        }
    }

    private suspend fun updateVfsEntry(syncedFile: WebDavSyncedFileEntity) {
        try {
            val vfsPath = syncedFile.vfsPath ?: return
            val vfsFileName = syncedFile.vfsFileName ?: syncedFile.providerFilePath?.substringAfterLast("/") ?: return

            val fullVfsPath = if (vfsPath.endsWith("/")) {
                "$vfsPath$vfsFileName"
            } else {
                "$vfsPath/$vfsFileName"
            }

            val existingFile = databaseFileService.getFileAtPath(fullVfsPath)
            if (existingFile is io.skjaere.debridav.fs.RemotelyCachedEntity) {
                val provider = mapProviderNameToDebridProvider(syncedFile.folderMapping?.providerName ?: "")
                if (provider != null) {
                    val updatedContents = syncedFileContentService.updateDebridFileContents(
                        existingFile.contents ?: return,
                        syncedFile,
                        provider
                    )
                    databaseFileService.writeDebridFileContentsToFile(existingFile, updatedContents)
                    logger.debug("Updated VFS entry: $fullVfsPath")
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating VFS entry for synced file ${syncedFile.id}", e)
        }
    }

    private fun generateFileId(path: String, name: String): String {
        // Generate a file ID from path and name
        // For WebDAV, we use path as ID since there's no UUID
        return path
    }

    private fun generateHash(path: String): String {
        // Generate a hash from path for use as file hash
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Map provider name (string) to DebridProvider enum
     * Returns null for custom providers that don't map to a DebridProvider
     */
    private fun mapProviderNameToDebridProvider(providerName: String): DebridProvider? {
        return when (providerName.lowercase().trim()) {
            "premiumize" -> DebridProvider.PREMIUMIZE
            "real_debrid", "realdebrid" -> DebridProvider.REAL_DEBRID
            "torbox" -> DebridProvider.TORBOX
            else -> null // Custom provider - we'll need to handle this differently
        }
    }
}
