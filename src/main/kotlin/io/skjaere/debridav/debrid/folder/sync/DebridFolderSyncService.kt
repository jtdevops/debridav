package io.skjaere.debridav.debrid.folder.sync

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.folder.DebridFolderMappingEntity
import io.skjaere.debridav.debrid.folder.DebridFolderMappingRepository
import io.skjaere.debridav.debrid.folder.DebridSyncedFileEntity
import io.skjaere.debridav.debrid.folder.DebridSyncedFileRepository
import io.skjaere.debridav.debrid.folder.SyncMethod
import io.skjaere.debridav.debrid.folder.api.DebridFile
import io.skjaere.debridav.debrid.folder.api.DebridFolderApiClient
import io.skjaere.debridav.debrid.folder.webdav.DebridWebDavClient
import io.skjaere.debridav.debrid.folder.webdav.WebDavFile
import io.skjaere.debridav.debrid.folder.webdav.WebDavFolderService
import io.skjaere.debridav.fs.DatabaseFileService
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class DebridFolderSyncService(
    private val folderMappingRepository: DebridFolderMappingRepository,
    private val syncedFileRepository: DebridSyncedFileRepository,
    private val webDavFolderService: WebDavFolderService,
    private val apiClients: List<DebridFolderApiClient>,
    private val fileMappingService: FileMappingService,
    private val syncedFileContentService: SyncedFileContentService,
    private val databaseFileService: DatabaseFileService
) {
    private val logger = LoggerFactory.getLogger(DebridFolderSyncService::class.java)
    private val apiClientCache = ConcurrentHashMap<DebridProvider, DebridFolderApiClient>()

    init {
        // Initialize API client cache
        apiClients.forEach { client ->
            when (client) {
                is io.skjaere.debridav.debrid.folder.api.RealDebridFolderApiClient -> 
                    apiClientCache[DebridProvider.REAL_DEBRID] = client
                is io.skjaere.debridav.debrid.folder.api.PremiumizeFolderApiClient -> 
                    apiClientCache[DebridProvider.PREMIUMIZE] = client
                is io.skjaere.debridav.debrid.folder.api.TorBoxFolderApiClient -> 
                    apiClientCache[DebridProvider.TORBOX] = client
            }
        }
    }

    @Transactional
    suspend fun syncAllMappings() {
        val mappings = folderMappingRepository.findByEnabled(true)
        logger.info("Starting sync for ${mappings.size} folder mappings")

        mappings.forEach { mapping ->
            try {
                syncMappingWithRetry(mapping)
            } catch (e: Exception) {
                logger.error("Error syncing mapping ${mapping.id} (${mapping.provider}:${mapping.externalPath})", e)
                // Continue with other mappings even if one fails
            }
        }
    }

    private suspend fun syncMappingWithRetry(mapping: DebridFolderMappingEntity, maxRetries: Int = 3) {
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
    suspend fun syncMapping(mapping: DebridFolderMappingEntity) {
        logger.debug("Syncing mapping ${mapping.id}: ${mapping.provider} -> ${mapping.internalPath}")

        when (mapping.syncMethod) {
            SyncMethod.WEBDAV -> syncWebDavMapping(mapping)
            SyncMethod.API_SYNC -> syncApiMapping(mapping)
            null -> logger.warn("Mapping ${mapping.id} has no sync method configured, skipping")
        }

        // Update last synced timestamp
        mapping.lastSynced = Instant.now()
        folderMappingRepository.save(mapping)
    }

    private suspend fun syncWebDavMapping(mapping: DebridFolderMappingEntity) {
        try {
            val files = webDavFolderService.listFiles(mapping)
            logger.info("WebDAV sync found ${files.size} total items for mapping ${mapping.id}")
            
            val actualFiles = files.filter { !it.isDirectory }
            val directories = files.filter { it.isDirectory }
            logger.info("WebDAV sync: ${actualFiles.size} files, ${directories.size} directories")
            
            val existingFiles = syncedFileRepository.findByFolderMapping(mapping)
                .associateBy { it.providerFileId }

            val providerFileIds = mutableSetOf<String>()
            var newFilesCount = 0
            var updatedFilesCount = 0

            actualFiles.forEach { webDavFile ->
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

    private suspend fun syncApiMapping(mapping: DebridFolderMappingEntity) {
        val apiClient = apiClientCache[mapping.provider]
        if (apiClient == null) {
            logger.warn("No API client found for provider: ${mapping.provider}")
            return
        }

        try {
            val files = apiClient.listFiles(mapping.externalPath ?: "")
            val existingFiles = syncedFileRepository.findByFolderMapping(mapping)
                .associateBy { it.providerFileId }

            val providerFileIds = mutableSetOf<String>()

            files.forEach { debridFile ->
                try {
                    providerFileIds.add(debridFile.id)

                    val existingFile = existingFiles[debridFile.id]
                    if (existingFile == null) {
                        // Create new synced file
                        createSyncedFileFromApi(mapping, debridFile)
                    } else {
                        // Update existing file (check for moves/renames)
                        updateSyncedFileFromApi(existingFile, debridFile, mapping)
                    }
                } catch (e: Exception) {
                    logger.error("Error processing API file ${debridFile.id} for mapping ${mapping.id}", e)
                    // Continue with other files
                }
            }

            // Mark files as deleted if they're no longer in provider
            try {
                syncedFileRepository.markAsDeletedForMissingFiles(mapping, providerFileIds.toList())
            } catch (e: Exception) {
                logger.error("Error marking files as deleted for mapping ${mapping.id}", e)
            }
        } catch (e: Exception) {
            logger.error("Error syncing API mapping ${mapping.id}", e)
            throw e
        }
    }

    private suspend fun createSyncedFileFromWebDav(
        mapping: DebridFolderMappingEntity,
        webDavFile: WebDavFile,
        fileId: String
    ) {
        val vfsPath = fileMappingService.mapToVfsPath(mapping, webDavFile.path)
        val vfsFileName = fileMappingService.getVfsFileName(webDavFile.path)

        val syncedFile = DebridSyncedFileEntity().apply {
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

        syncedFileRepository.save(syncedFile)

        // Create VFS entry
        createVfsEntry(syncedFile, mapping.provider!!)
    }

    private suspend fun createSyncedFileFromApi(
        mapping: DebridFolderMappingEntity,
        debridFile: DebridFile
    ) {
        val vfsPath = fileMappingService.mapToVfsPath(mapping, debridFile.path)
        val vfsFileName = fileMappingService.getVfsFileName(debridFile.path)

        val syncedFile = DebridSyncedFileEntity().apply {
            folderMapping = mapping
            providerFileId = debridFile.id
            providerFilePath = debridFile.path
            this.vfsPath = vfsPath
            this.vfsFileName = vfsFileName
            fileSize = debridFile.size
            mimeType = debridFile.mimeType
            providerLink = debridFile.downloadLink
            lastChecked = Instant.now()
            isDeleted = false
        }

        syncedFileRepository.save(syncedFile)

        // Create VFS entry
        createVfsEntry(syncedFile, mapping.provider!!)
    }

    private suspend fun updateSyncedFileFromWebDav(
        existingFile: DebridSyncedFileEntity,
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

    private suspend fun updateSyncedFileFromApi(
        existingFile: DebridSyncedFileEntity,
        debridFile: DebridFile,
        mapping: DebridFolderMappingEntity
    ) {
        // Check if file was moved/renamed using UUID/ID tracking
        val newVfsPath = fileMappingService.mapToVfsPath(mapping, debridFile.path)
        val fileWasMoved = existingFile.vfsPath != newVfsPath
        
        if (fileWasMoved) {
            logger.debug("File ${debridFile.id} was moved from ${existingFile.vfsPath} to $newVfsPath")
            // File was moved in provider - update VFS path
            existingFile.vfsPath = newVfsPath
            existingFile.vfsFileName = fileMappingService.getVfsFileName(debridFile.path)
            
            // Update VFS entry path if it exists
            try {
                val oldFileEntity = databaseFileService.getFileAtPath(existingFile.vfsPath ?: "")
                if (oldFileEntity != null) {
                    // File exists at old path - we may need to move it or recreate at new path
                    // For now, we'll update the existing entry and let the sync handle the move
                }
            } catch (e: Exception) {
                logger.warn("Error checking old VFS path for moved file ${debridFile.id}", e)
            }
        }

        // Update file metadata
        existingFile.providerFilePath = debridFile.path
        existingFile.fileSize = debridFile.size
        existingFile.mimeType = debridFile.mimeType
        existingFile.providerLink = debridFile.downloadLink
        existingFile.lastChecked = Instant.now()
        existingFile.isDeleted = false

        syncedFileRepository.save(existingFile)

        // Update VFS entry
        updateVfsEntry(existingFile)
    }

    private suspend fun createVfsEntry(syncedFile: DebridSyncedFileEntity, provider: DebridProvider) {
        try {
            val vfsPath = syncedFile.vfsPath ?: return
            val vfsFileName = syncedFile.vfsFileName ?: syncedFile.providerFilePath?.substringAfterLast("/") ?: return

            val fullVfsPath = if (vfsPath.endsWith("/")) {
                "$vfsPath$vfsFileName"
            } else {
                "$vfsPath/$vfsFileName"
            }

            val contents = syncedFileContentService.createDebridFileContents(syncedFile, provider)
            val hash = syncedFile.providerFileId ?: generateHash(fullVfsPath)

            databaseFileService.createDebridFile(fullVfsPath, hash, contents)
            logger.debug("Created VFS entry: $fullVfsPath")
        } catch (e: Exception) {
            logger.error("Error creating VFS entry for synced file ${syncedFile.id}", e)
        }
    }

    private suspend fun updateVfsEntry(syncedFile: DebridSyncedFileEntity) {
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
                val provider = syncedFile.folderMapping?.provider ?: return
                val updatedContents = syncedFileContentService.updateDebridFileContents(
                    existingFile.contents ?: return,
                    syncedFile,
                    provider
                )
                databaseFileService.writeDebridFileContentsToFile(existingFile, updatedContents)
                logger.debug("Updated VFS entry: $fullVfsPath")
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
}
