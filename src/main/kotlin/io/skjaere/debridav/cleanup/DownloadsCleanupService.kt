package io.skjaere.debridav.cleanup

import io.ipfs.multibase.Base58
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.torrent.Status
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class DownloadsCleanupService(
    private val debridFileRepository: DebridFileContentsRepository,
    private val databaseFileService: DatabaseFileService,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val categoryService: CategoryService,
    private val torrentRepository: TorrentRepository
) {
    private val logger = LoggerFactory.getLogger(DownloadsCleanupService::class.java)

    private val ROOT_NODE = "ROOT"
    
    // Mutex to prevent concurrent cleanup operations
    private val cleanupLock = ReentrantLock()

    /**
     * Finds abandoned files in the downloads folder.
     * A file is considered abandoned if:
     * - It's in the downloads folder (or subdirectories)
     * - It's not linked to any active torrent or usenet download
     * - It's older than the configured cleanup age threshold
     */
    fun findAbandonedFiles(cleanupAgeHours: Long = 6): List<RemotelyCachedEntity> {
        val downloadPathPrefix = convertPathToLtree(debridavConfigurationProperties.downloadPath)
        val cutoffTime = Instant.now().minus(Duration.ofHours(cleanupAgeHours)).toEpochMilli()

        logger.info("Searching for abandoned files in downloads folder (older than {} hours)", cleanupAgeHours)
        
        val abandonedFiles = debridFileRepository.findAbandonedFilesInDownloads(
            downloadPathPrefix = downloadPathPrefix,
            cutoffTime = cutoffTime
        )

        logger.info("Found {} abandoned file(s) in downloads folder", abandonedFiles.size)
        return abandonedFiles
    }

    /**
     * Gets ARR categories dynamically from the database.
     * These are categories with download_path='/downloads' that appear in
     * /api/v2/torrents/info?category=<category-name> API calls.
     */
    fun getArrCategories(): List<String> {
        return categoryService.getArrCategories(debridavConfigurationProperties.downloadPath)
    }

    /**
     * Finds abandoned files that are NOT linked to any Sonarr/Radarr torrents.
     * These files are invisible to Sonarr/Radarr via their API calls and can be
     * cleaned up after a shorter time frame.
     * 
     * A file is considered abandoned if:
     * - It's in the downloads folder (or subdirectories)
     * - It's not linked to any active torrent or usenet download
     * - It's NOT linked to any torrent in ARR categories (dynamically determined)
     * - It's older than the configured cleanup age threshold
     */
    fun findAbandonedFilesNotLinkedToArr(
        cleanupAgeHours: Long = 1,
        arrCategories: List<String>? = null
    ): List<RemotelyCachedEntity> {
        val arrCategoriesList = arrCategories ?: getArrCategories()
        val downloadPathPrefix = convertPathToLtree(debridavConfigurationProperties.downloadPath)
        val cutoffTime = Instant.now().minus(Duration.ofHours(cleanupAgeHours)).toEpochMilli()

        logger.info("Searching for abandoned files not linked to ARR categories {} in downloads folder (older than {} hours)", 
            arrCategoriesList.joinToString(", "), cleanupAgeHours)
        
        val abandonedFiles = debridFileRepository.findAbandonedFilesNotLinkedToArrCategories(
            downloadPathPrefix = downloadPathPrefix,
            cutoffTime = cutoffTime,
            arrCategories = arrCategoriesList
        )

        logger.info("Found {} abandoned file(s) not linked to ARR categories in downloads folder", abandonedFiles.size)
        return abandonedFiles
    }

    /**
     * Cleans up abandoned files from the downloads folder.
     * Uses a two-tier approach:
     * 1. Files not linked to ARR categories (radarr/sonarr) are cleaned up after a shorter time
     * 2. All other abandoned files are cleaned up after the standard time
     * 
     * Returns the number of files deleted.
     * 
     * This method is synchronized to prevent concurrent execution from multiple threads.
     */
    @Transactional
    fun cleanupAbandonedFiles(
        cleanupAgeHours: Long = 6,
        arrCleanupAgeHours: Long = 1,
        arrCategories: List<String>? = null,
        dryRun: Boolean = false
    ): CleanupResult {
        // Use lock to prevent concurrent cleanup operations
        return cleanupLock.withLock {
            cleanupAbandonedFilesInternal(cleanupAgeHours, arrCleanupAgeHours, arrCategories, dryRun)
        }
    }
    
    @Transactional
    private fun cleanupAbandonedFilesInternal(
        cleanupAgeHours: Long = 6,
        arrCleanupAgeHours: Long = 1,
        arrCategories: List<String>? = null,
        dryRun: Boolean = false
    ): CleanupResult {
        val arrCategoriesList = arrCategories ?: getArrCategories()
        // First, find files not linked to ARR categories (shorter time frame)
        val arrAbandonedFiles = findAbandonedFilesNotLinkedToArr(arrCleanupAgeHours, arrCategoriesList)
        
        // Then, find all other abandoned files (longer time frame)
        val allAbandonedFiles = findAbandonedFiles(cleanupAgeHours)
        
        // Combine both lists, removing duplicates
        // Files not linked to ARR will appear in both lists, but we want to process them with the shorter time frame
        val arrAbandonedFileIds = arrAbandonedFiles.map { it.id }.toSet()
        val otherAbandonedFiles = allAbandonedFiles.filter { it.id !in arrAbandonedFileIds }
        val abandonedFiles = arrAbandonedFiles + otherAbandonedFiles
        
        logger.info("Found {} file(s) not linked to ARR categories (will use {} hour threshold) and {} other abandoned file(s) (will use {} hour threshold)", 
            arrAbandonedFiles.size, arrCleanupAgeHours, otherAbandonedFiles.size, cleanupAgeHours)
        
        // Find empty directories in downloads folder
        val downloadPathPrefix = convertPathToLtree(debridavConfigurationProperties.downloadPath)
        logger.trace("Querying database for empty directories in downloads folder...")
        val emptyDirectories = debridFileRepository.findEmptyDirectoriesInDownloads(downloadPathPrefix)
        logger.debug("Found {} empty directory(ies) in downloads folder", emptyDirectories.size)
        logger.trace("Empty directories: {}", emptyDirectories.map { 
            val path = it.fileSystemPath() ?: it.path ?: "unknown"
            "$path (id=${it.id}, name=${it.name})"
        }.joinToString(", "))
        
        if (abandonedFiles.isEmpty() && emptyDirectories.isEmpty()) {
            logger.info("No abandoned files or empty directories found to clean up")
            return CleanupResult(deletedCount = 0, totalSizeBytes = 0L, deletedDirectoriesCount = 0, dryRun = dryRun)
        }

        var deletedCount = 0
        var totalSizeBytes = 0L
        val errors = mutableListOf<String>()

        for (file in abandonedFiles) {
            try {
                val fileSize = file.size ?: 0L
                val filePath = file.directory?.let { dir ->
                    dir.fileSystemPath()?.let { 
                        "$it/${file.name}" 
                    } ?: file.name
                } ?: file.name ?: "unknown"

                if (dryRun) {
                    logger.debug("Would delete abandoned file: {} (size: {} bytes, age: {} days)", 
                        filePath, fileSize, 
                        Duration.ofMillis(System.currentTimeMillis() - (file.lastModified ?: 0)).toDays())
                    deletedCount++
                    totalSizeBytes += fileSize
                } else {
                    // Check if file still exists before attempting deletion
                    val fileId = file.id
                    if (fileId != null && debridFileRepository.findById(fileId).isPresent) {
                        logger.debug("Deleting abandoned file: {} (size: {} bytes)", filePath, fileSize)
                        try {
                            databaseFileService.deleteFile(file)
                            deletedCount++
                            totalSizeBytes += fileSize
                        } catch (e: org.hibernate.StaleStateException) {
                            // File was already deleted by another thread - this is expected in concurrent scenarios
                            logger.debug("File {} (id={}) was already deleted by another thread, skipping", filePath, fileId)
                        } catch (e: org.springframework.orm.ObjectOptimisticLockingFailureException) {
                            // File was already deleted by another thread - this is expected in concurrent scenarios
                            logger.debug("File {} (id={}) was already deleted by another thread, skipping", filePath, fileId)
                        }
                    } else {
                        logger.debug("File {} (id={}) no longer exists in database, skipping", filePath, fileId)
                    }
                }
            } catch (e: Exception) {
                // Only log non-StaleStateException errors as errors
                if (e is org.hibernate.StaleStateException || e is org.springframework.orm.ObjectOptimisticLockingFailureException) {
                    logger.debug("File ${file.name} (id=${file.id}) was already deleted, skipping")
                } else {
                    val errorMsg = "Failed to delete file ${file.name}: ${e.message}"
                    logger.error(errorMsg, e)
                    errors.add(errorMsg)
                }
            }
        }
        
        // Clean up empty directories after files are deleted
        // Use a loop to recursively clean up parent directories that become empty
        var deletedDirectoriesCount = 0
        var iteration = 0
        var currentEmptyDirectories = emptyDirectories
        
        while (currentEmptyDirectories.isNotEmpty() && iteration < 100) { // Safety limit of 100 iterations
            iteration++
            logger.debug("Empty directory cleanup iteration {}: found {} empty directory(ies)", iteration, currentEmptyDirectories.size)
            
            // Process directories from deepest to shallowest (already ordered by nlevel DESC)
            for (directory in currentEmptyDirectories) {
                try {
                    val dirPath = directory.fileSystemPath() ?: directory.path ?: "unknown"
                    
                    if (dryRun) {
                        logger.info("Would delete empty directory: {} (id={})", dirPath, directory.id)
                        deletedDirectoriesCount++
                    } else {
                        // Check if directory still exists before attempting deletion
                        val dirId = directory.id
                        if (dirId != null && debridFileRepository.findById(dirId).isPresent) {
                            logger.info("Deleting empty directory: {} (id={})", dirPath, directory.id)
                            logger.trace("Directory details: path={}, name={}, lastModified={}", 
                                directory.path, directory.name, directory.lastModified)
                            try {
                                databaseFileService.deleteFile(directory)
                                deletedDirectoriesCount++
                                logger.trace("Successfully deleted directory: {}", dirPath)
                            } catch (e: org.hibernate.StaleStateException) {
                                // Directory was already deleted by another thread
                                logger.debug("Directory {} (id={}) was already deleted by another thread, skipping", dirPath, dirId)
                            } catch (e: org.springframework.orm.ObjectOptimisticLockingFailureException) {
                                // Directory was already deleted by another thread
                                logger.debug("Directory {} (id={}) was already deleted by another thread, skipping", dirPath, dirId)
                            }
                        } else {
                            logger.debug("Directory {} (id={}) no longer exists in database, skipping", dirPath, dirId)
                        }
                    }
                } catch (e: Exception) {
                    // Only log non-StaleStateException errors as errors
                    if (e is org.hibernate.StaleStateException || e is org.springframework.orm.ObjectOptimisticLockingFailureException) {
                        logger.debug("Directory ${directory.name} (id=${directory.id}) was already deleted, skipping")
                    } else {
                        val errorMsg = "Failed to delete empty directory ${directory.name}: ${e.message}"
                        logger.error(errorMsg, e)
                        errors.add(errorMsg)
                    }
                }
            }
            
            // Re-query for empty directories (parent directories may have become empty)
            if (!dryRun) {
                currentEmptyDirectories = debridFileRepository.findEmptyDirectoriesInDownloads(downloadPathPrefix)
                if (currentEmptyDirectories.isNotEmpty()) {
                    logger.debug("Found {} additional empty directory(ies) after deletion, continuing cleanup", currentEmptyDirectories.size)
                }
            } else {
                // In dry run, we can't actually delete, so we simulate by checking what would become empty
                // For simplicity, just break after first iteration in dry run
                break
            }
        }
        
        if (iteration >= 100) {
            logger.warn("Empty directory cleanup reached iteration limit (100), there may be circular references or other issues")
        }

        val result = CleanupResult(
            deletedCount = deletedCount,
            totalSizeBytes = totalSizeBytes,
            deletedDirectoriesCount = deletedDirectoriesCount,
            errors = errors,
            dryRun = dryRun
        )

        if (dryRun) {
            logger.info("Dry run: Would delete {} abandoned file(s) and {} empty directory(ies), total size: {} MB", 
                deletedCount, deletedDirectoriesCount, String.format("%.2f", totalSizeBytes / 1_000_000.0))
        } else {
            logger.info("Cleaned up {} abandoned file(s) and {} empty directory(ies), total size: {} MB", 
                deletedCount, deletedDirectoriesCount, String.format("%.2f", totalSizeBytes / 1_000_000.0))
            if (errors.isNotEmpty()) {
                logger.warn("Encountered {} error(s) during cleanup", errors.size)
            }
        }

        return result
    }

    private fun convertPathToLtree(path: String): String {
        return if (path == "/") ROOT_NODE else {
            path.split("/").filter { it.isNotBlank() }
                .joinToString(separator = ".") { Base58.encode(it.encodeToByteArray()) }
                .let { "$ROOT_NODE.$it" }
        }
    }

    /**
     * Automatically cleans up torrents and files that are no longer linked to any active category.
     * This is called after a new torrent is added to proactively clean up orphaned content.
     * 
     * A torrent/file is considered orphaned if:
     * - It's in the downloads folder
     * - It's not linked to any category that exists in the database with download_path='/downloads'
     * - If time-based cleanup is enabled: It's older than the configured threshold
     * - If immediate cleanup (default): No age check, all orphaned content is removed
     * 
     * This method uses a lock to prevent concurrent execution from multiple threads.
     * The transaction is started inside the lock to ensure proper synchronization.
     */
    fun cleanupOrphanedTorrentsAndFiles(
        dryRun: Boolean = false
    ): CleanupResult {
        // Use lock to prevent concurrent cleanup operations
        // The lock must be acquired BEFORE any transaction starts to prevent race conditions
        val threadName = Thread.currentThread().name
        logger.trace("Thread {} attempting to acquire cleanup lock", threadName)
        return cleanupLock.withLock {
            logger.debug("Thread {} acquired cleanup lock, starting cleanup", threadName)
            try {
                cleanupOrphanedTorrentsAndFilesInternal(dryRun)
            } finally {
                logger.trace("Thread {} releasing cleanup lock", threadName)
            }
        }
    }
    
    @Transactional
    private fun cleanupOrphanedTorrentsAndFilesInternal(
        dryRun: Boolean = false
    ): CleanupResult {
        logger.debug("=== Starting orphaned torrents/files cleanup (dryRun={}) ===", dryRun)
        
        val arrCategories = getArrCategories()
        logger.trace("ARR categories found: {}", arrCategories.joinToString(", "))
        
        if (arrCategories.isEmpty()) {
            logger.debug("No ARR categories found, skipping orphaned cleanup")
            return CleanupResult(deletedCount = 0, totalSizeBytes = 0L, dryRun = dryRun)
        }

        val useTimeBased = debridavConfigurationProperties.enableDownloadsCleanupTimeBased
        val thresholdMinutes = debridavConfigurationProperties.downloadsCleanupTimeBasedThresholdMinutes
        
        if (useTimeBased) {
            logger.debug("Cleaning up torrents/files not linked to active categories (time-based, threshold: {} minutes): {}", 
                thresholdMinutes, arrCategories.joinToString(", "))
        } else {
            logger.debug("Cleaning up torrents/files not linked to active categories (immediate, no age check): {}", 
                arrCategories.joinToString(", "))
        }
        
        val cutoffTime = if (useTimeBased) {
            Instant.now().minus(Duration.ofMinutes(thresholdMinutes)).toEpochMilli()
        } else {
            Long.MAX_VALUE // No age check - use MAX_VALUE so condition "last_modified < MAX_VALUE" is always true
        }
        
        val downloadPathPrefix = convertPathToLtree(debridavConfigurationProperties.downloadPath)
        logger.trace("Download path prefix (ltree): {}", downloadPathPrefix)
        logger.trace("Cutoff time: {} (useTimeBased={})", if (useTimeBased) Instant.ofEpochMilli(cutoffTime) else "N/A (immediate)", useTimeBased)
        
        // Find empty directories in downloads folder (for cleanup after files are deleted)
        logger.trace("Querying database for empty directories in downloads folder...")
        val emptyDirectories = debridFileRepository.findEmptyDirectoriesInDownloads(downloadPathPrefix)
        logger.debug("Found {} empty directory(ies) in downloads folder", emptyDirectories.size)
        logger.trace("Empty directories: {}", emptyDirectories.map { 
            val path = it.fileSystemPath() ?: it.path ?: "unknown"
            "$path (id=${it.id}, name=${it.name})"
        }.joinToString(", "))
        
        // Find files not linked to any active category
        logger.trace("Querying database for orphaned files...")
        val orphanedFiles = debridFileRepository.findAbandonedFilesNotLinkedToArrCategories(
            downloadPathPrefix = downloadPathPrefix,
            cutoffTime = cutoffTime,
            arrCategories = arrCategories
        )
        logger.debug("Found {} orphaned file(s) not linked to ARR categories", orphanedFiles.size)
        logger.trace("Orphaned files: {}", orphanedFiles.map { file ->
            val path = file.directory?.let { dir ->
                dir.fileSystemPath()?.let { dirPath -> "$dirPath/${file.name}" } ?: file.name
            } ?: file.name ?: "unknown"
            "$path (id=${file.id}, size=${file.size}, lastModified=${file.lastModified})"
        }.joinToString(", "))
        
        // Find torrents not linked to any active category
        logger.trace("Querying database for all LIVE torrents...")
        val allTorrents = torrentRepository.findAll().filter { it.status == Status.LIVE }
        logger.trace("Found {} LIVE torrent(s) total", allTorrents.size)
        
        val orphanedTorrents = allTorrents.filter { torrent ->
            val categoryName = torrent.category?.name
            val isOrphaned = categoryName == null || categoryName !in arrCategories
            logger.trace("Torrent '{}' (id={}, category={}, savePath={}): isOrphaned={}", 
                torrent.name, torrent.id, categoryName ?: "null", torrent.savePath, isOrphaned)
            isOrphaned
        }.filter { torrent ->
            // Only include torrents in downloads folder
            val inDownloadsFolder = torrent.savePath?.startsWith(debridavConfigurationProperties.downloadPath) == true
            val ageCheck = if (useTimeBased) {
                val created = torrent.created?.toEpochMilli() ?: Long.MAX_VALUE
                val passesAgeCheck = created < cutoffTime
                logger.trace("Torrent '{}' age check: created={}, cutoff={}, passes={}", 
                    torrent.name, if (created != Long.MAX_VALUE) Instant.ofEpochMilli(created) else "null", 
                    Instant.ofEpochMilli(cutoffTime), passesAgeCheck)
                passesAgeCheck
            } else {
                true
            }
            val shouldInclude = inDownloadsFolder && ageCheck
            logger.trace("Torrent '{}' filter: inDownloadsFolder={}, ageCheck={}, shouldInclude={}", 
                torrent.name, inDownloadsFolder, ageCheck, shouldInclude)
            shouldInclude
        }
        logger.debug("Found {} orphaned torrent(s) not linked to ARR categories", orphanedTorrents.size)
        logger.trace("Orphaned torrents: {}", orphanedTorrents.map { 
            "${it.name} (id=${it.id}, category=${it.category?.name ?: "null"}, files=${it.files.size}, created=${it.created})"
        }.joinToString(", "))
        
        var deletedCount = 0
        var totalSizeBytes = 0L
        val errors = mutableListOf<String>()
        
        // Delete orphaned files
        logger.debug("Processing {} orphaned file(s) for deletion", orphanedFiles.size)
        for (file in orphanedFiles) {
            try {
                val fileSize = file.size ?: 0L
                val filePath = file.directory?.let { dir ->
                    dir.fileSystemPath()?.let { "$it/${file.name}" } ?: file.name
                } ?: file.name ?: "unknown"
                val fileSizeMB = String.format("%.2f", fileSize / 1_000_000.0)
                
                if (dryRun) {
                    logger.info("Would delete orphaned file: {} (size: {} bytes, {} MB, id={})", 
                        filePath, fileSize, fileSizeMB, file.id)
                    deletedCount++
                    totalSizeBytes += fileSize
                } else {
                    // Check if file still exists before attempting deletion
                    val fileId = file.id
                    if (fileId != null && debridFileRepository.findById(fileId).isPresent) {
                        logger.info("Deleting orphaned file: {} (size: {} bytes, {} MB, id={})", 
                            filePath, fileSize, fileSizeMB, file.id)
                        logger.trace("File details: name={}, directory={}, lastModified={}, hash={}", 
                            file.name, file.directory?.fileSystemPath(), file.lastModified, file.hash)
                        try {
                            databaseFileService.deleteFile(file)
                            deletedCount++
                            totalSizeBytes += fileSize
                            logger.trace("Successfully deleted file: {}", filePath)
                        } catch (e: org.hibernate.StaleStateException) {
                            // File was already deleted by another thread - this is expected in concurrent scenarios
                            logger.debug("File {} (id={}) was already deleted by another thread, skipping", filePath, fileId)
                        } catch (e: org.springframework.orm.ObjectOptimisticLockingFailureException) {
                            // File was already deleted by another thread - this is expected in concurrent scenarios
                            logger.debug("File {} (id={}) was already deleted by another thread, skipping", filePath, fileId)
                        }
                    } else {
                        logger.debug("File {} (id={}) no longer exists in database, skipping", filePath, fileId)
                    }
                }
            } catch (e: Exception) {
                // Only log non-StaleStateException errors as errors
                if (e is org.hibernate.StaleStateException || e is org.springframework.orm.ObjectOptimisticLockingFailureException) {
                    logger.debug("File ${file.name} (id=${file.id}) was already deleted, skipping")
                } else {
                    val errorMsg = "Failed to delete orphaned file ${file.name}: ${e.message}"
                    logger.error(errorMsg, e)
                    errors.add(errorMsg)
                }
            }
        }
        
        // Delete orphaned torrents and their files
        logger.debug("Processing {} orphaned torrent(s) for deletion", orphanedTorrents.size)
        for (torrent in orphanedTorrents) {
            try {
                val torrentSize = torrent.files.sumOf { it.size ?: 0L }
                val torrentName = torrent.name ?: "unknown"
                val torrentSizeMB = String.format("%.2f", torrentSize / 1_000_000.0)
                val torrentPath = torrent.savePath ?: "unknown"
                
                if (dryRun) {
                    logger.info("Would delete orphaned torrent: {} ({} files, {} bytes, {} MB, id={}, path={})", 
                        torrentName, torrent.files.size, torrentSize, torrentSizeMB, torrent.id, torrentPath)
                    logger.trace("Torrent files: {}", torrent.files.map { 
                        "${it.name} (id=${it.id}, size=${it.size})" 
                    }.joinToString(", "))
                    deletedCount += torrent.files.size
                    totalSizeBytes += torrentSize
                } else {
                    logger.info("Deleting orphaned torrent: {} ({} files, {} bytes, {} MB, id={}, path={})", 
                        torrentName, torrent.files.size, torrentSize, torrentSizeMB, torrent.id, torrentPath)
                    logger.trace("Torrent details: category={}, hash={}, created={}, status={}", 
                        torrent.category?.name, torrent.hash, torrent.created, torrent.status)
                    
                    // Delete all files in the torrent
                    torrent.files.forEachIndexed { index, file ->
                        try {
                            val filePath = file.directory?.let { dir ->
                                dir.fileSystemPath()?.let { "$it/${file.name}" } ?: file.name
                            } ?: file.name ?: "unknown"
                            val fileId = file.id
                            
                            // Check if file still exists before attempting deletion
                            if (fileId != null && debridFileRepository.findById(fileId).isPresent) {
                                logger.debug("Deleting file {}/{} from torrent '{}': {} (id={})", 
                                    index + 1, torrent.files.size, torrentName, filePath, file.id)
                                try {
                                    databaseFileService.deleteFile(file)
                                    logger.trace("Successfully deleted file: {}", filePath)
                                } catch (e: org.hibernate.StaleStateException) {
                                    // File was already deleted by another thread
                                    logger.debug("File {} (id={}) was already deleted by another thread, skipping", filePath, fileId)
                                } catch (e: org.springframework.orm.ObjectOptimisticLockingFailureException) {
                                    // File was already deleted by another thread
                                    logger.debug("File {} (id={}) was already deleted by another thread, skipping", filePath, fileId)
                                }
                            } else {
                                logger.debug("File {} (id={}) no longer exists in database, skipping", filePath, fileId)
                            }
                        } catch (e: Exception) {
                            // Only log non-StaleStateException errors as warnings
                            if (e is org.hibernate.StaleStateException || e is org.springframework.orm.ObjectOptimisticLockingFailureException) {
                                logger.debug("File ${file.name} (id=${file.id}) was already deleted, skipping")
                            } else {
                                logger.warn("Failed to delete file ${file.name} from torrent ${torrent.name}: ${e.message}", e)
                            }
                        }
                    }
                    
                    // Mark torrent as deleted
                    logger.trace("Marking torrent '{}' (id={}) as DELETED", torrentName, torrent.id)
                    torrent.status = Status.DELETED
                    torrentRepository.save(torrent)
                    
                    deletedCount += torrent.files.size
                    totalSizeBytes += torrentSize
                    logger.debug("Successfully deleted torrent '{}' and {} file(s)", torrentName, torrent.files.size)
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to delete orphaned torrent ${torrent.name}: ${e.message}"
                logger.error(errorMsg, e)
                errors.add(errorMsg)
            }
        }
        
        // Clean up empty directories after files are deleted
        // Use a loop to recursively clean up parent directories that become empty
        var deletedDirectoriesCount = 0
        var iteration = 0
        var currentEmptyDirectories = emptyDirectories
        
        while (currentEmptyDirectories.isNotEmpty() && iteration < 100) { // Safety limit of 100 iterations
            iteration++
            logger.debug("Empty directory cleanup iteration {}: found {} empty directory(ies)", iteration, currentEmptyDirectories.size)
            
            // Process directories from deepest to shallowest (already ordered by nlevel DESC)
            for (directory in currentEmptyDirectories) {
                try {
                    val dirPath = directory.fileSystemPath() ?: directory.path ?: "unknown"
                    
                    if (dryRun) {
                        logger.info("Would delete empty directory: {} (id={})", dirPath, directory.id)
                        deletedDirectoriesCount++
                    } else {
                        // Check if directory still exists before attempting deletion
                        val dirId = directory.id
                        if (dirId != null && debridFileRepository.findById(dirId).isPresent) {
                            logger.info("Deleting empty directory: {} (id={})", dirPath, directory.id)
                            logger.trace("Directory details: path={}, name={}, lastModified={}", 
                                directory.path, directory.name, directory.lastModified)
                            try {
                                databaseFileService.deleteFile(directory)
                                deletedDirectoriesCount++
                                logger.trace("Successfully deleted directory: {}", dirPath)
                            } catch (e: org.hibernate.StaleStateException) {
                                // Directory was already deleted by another thread
                                logger.debug("Directory {} (id={}) was already deleted by another thread, skipping", dirPath, dirId)
                            } catch (e: org.springframework.orm.ObjectOptimisticLockingFailureException) {
                                // Directory was already deleted by another thread
                                logger.debug("Directory {} (id={}) was already deleted by another thread, skipping", dirPath, dirId)
                            }
                        } else {
                            logger.debug("Directory {} (id={}) no longer exists in database, skipping", dirPath, dirId)
                        }
                    }
                } catch (e: Exception) {
                    // Only log non-StaleStateException errors as errors
                    if (e is org.hibernate.StaleStateException || e is org.springframework.orm.ObjectOptimisticLockingFailureException) {
                        logger.debug("Directory ${directory.name} (id=${directory.id}) was already deleted, skipping")
                    } else {
                        val errorMsg = "Failed to delete empty directory ${directory.name}: ${e.message}"
                        logger.error(errorMsg, e)
                        errors.add(errorMsg)
                    }
                }
            }
            
            // Re-query for empty directories (parent directories may have become empty)
            if (!dryRun) {
                currentEmptyDirectories = debridFileRepository.findEmptyDirectoriesInDownloads(downloadPathPrefix)
                if (currentEmptyDirectories.isNotEmpty()) {
                    logger.debug("Found {} additional empty directory(ies) after deletion, continuing cleanup", currentEmptyDirectories.size)
                }
            } else {
                // In dry run, we can't actually delete, so we simulate by checking what would become empty
                // For simplicity, just break after first iteration in dry run
                break
            }
        }
        
        if (iteration >= 100) {
            logger.warn("Empty directory cleanup reached iteration limit (100), there may be circular references or other issues")
        }
        
        val result = CleanupResult(
            deletedCount = deletedCount,
            totalSizeBytes = totalSizeBytes,
            deletedDirectoriesCount = deletedDirectoriesCount,
            errors = errors,
            dryRun = dryRun
        )
        
        val cleanupMode = if (useTimeBased) {
            "time-based (${thresholdMinutes} minutes)"
        } else {
            "immediate"
        }
        
        if (dryRun) {
            logger.info("=== Cleanup dry run completed ({}) ===", cleanupMode)
            logger.info("Would delete: {} orphaned file(s), {} orphaned torrent(s), {} empty directory(ies), total size: {} MB", 
                orphanedFiles.size, orphanedTorrents.size, deletedDirectoriesCount, String.format("%.2f", totalSizeBytes / 1_000_000.0))
        } else {
            logger.info("=== Cleanup completed ({}) ===", cleanupMode)
            logger.info("Deleted: {} orphaned file(s), {} orphaned torrent(s), {} empty directory(ies), total size: {} MB", 
                orphanedFiles.size, orphanedTorrents.size, deletedDirectoriesCount, String.format("%.2f", totalSizeBytes / 1_000_000.0))
            if (errors.isNotEmpty()) {
                logger.warn("Encountered {} error(s) during orphaned cleanup", errors.size)
                errors.forEach { error ->
                    logger.warn("Cleanup error: {}", error)
                }
            }
        }
        logger.trace("=== End orphaned cleanup ===")
        
        return result
    }

    /**
     * Diagnostic method to check why a specific file/folder path isn't being cleaned up.
     * This helps understand if files are linked to torrents/categories in the database.
     */
    fun diagnoseFileCleanupStatus(filePath: String): String {
        val arrCategories = getArrCategories()
        val downloadPathPrefix = convertPathToLtree(debridavConfigurationProperties.downloadPath)
        
        val diagnostic = StringBuilder()
        diagnostic.appendLine("=== Cleanup Diagnostic for: $filePath ===")
        diagnostic.appendLine("ARR Categories: ${arrCategories.joinToString(", ")}")
        diagnostic.appendLine("Download Path: ${debridavConfigurationProperties.downloadPath}")
        diagnostic.appendLine("Download Path Prefix (ltree): $downloadPathPrefix")
        
        // Try to find the file in the database
        val normalizedPath = filePath.removePrefix(debridavConfigurationProperties.downloadPath).trimStart('/')
        val pathParts = normalizedPath.split("/")
        if (pathParts.isNotEmpty()) {
            val folderName = pathParts[0]
            diagnostic.appendLine("Folder name: $folderName")
            
            // Check if there's a torrent with this name
            val allTorrents = torrentRepository.findAll()
            val matchingTorrents = allTorrents.filter { 
                it.status == Status.LIVE && 
                (it.name?.contains(folderName, ignoreCase = true) == true || 
                 it.savePath?.contains(folderName, ignoreCase = true) == true)
            }
            
            diagnostic.appendLine("Matching torrents found: ${matchingTorrents.size}")
            matchingTorrents.forEach { torrent ->
                diagnostic.appendLine("  - Torrent: ${torrent.name} (id=${torrent.id})")
                diagnostic.appendLine("    Category: ${torrent.category?.name ?: "null"} (downloadPath=${torrent.category?.downloadPath})")
                diagnostic.appendLine("    Save Path: ${torrent.savePath}")
                diagnostic.appendLine("    Status: ${torrent.status}")
                diagnostic.appendLine("    Created: ${torrent.created}")
                diagnostic.appendLine("    Files: ${torrent.files.size}")
                diagnostic.appendLine("    Is in ARR categories: ${torrent.category?.name in arrCategories}")
                torrent.files.take(5).forEach { file ->
                    val dbFilePath = file.directory?.let { dir ->
                        dir.fileSystemPath()?.let { "$it/${file.name}" } ?: file.name
                    } ?: file.name
                    diagnostic.appendLine("      - File: $dbFilePath (id=${file.id}, size=${file.size})")
                }
                if (torrent.files.size > 5) {
                    diagnostic.appendLine("      ... and ${torrent.files.size - 5} more files")
                }
            }
            
            if (matchingTorrents.isEmpty()) {
                diagnostic.appendLine("No matching torrents found in database for folder: $folderName")
                diagnostic.appendLine("This folder may not be tracked in the database, or the torrent may have been deleted.")
            }
            
            // Check for files in the directory structure
            val folderPathLtree = convertPathToLtree("${debridavConfigurationProperties.downloadPath}/$folderName")
            diagnostic.appendLine("")
            diagnostic.appendLine("Checking for files in directory structure...")
            
            // Try to find the directory in the database
            val directory = debridFileRepository.getDirectoryByPath(folderPathLtree)
            if (directory != null) {
                diagnostic.appendLine("Directory found in database: id=${directory.id}, path=${directory.path}")
                diagnostic.appendLine("Recursively checking directory and all subdirectories...")
                diagnostic.appendLine("")
                
                // Recursively traverse directory tree
                var totalFiles = 0
                var totalDirectories = 0
                var filesNotLinkedToTorrents = 0
                
                fun traverseDirectory(dir: DbDirectory, indent: String = "") {
                    val dirPath = dir.fileSystemPath() ?: dir.path ?: "unknown"
                    diagnostic.appendLine("${indent}Directory: $dirPath (id=${dir.id})")
                    totalDirectories++
                    
                    val children = debridFileRepository.getByDirectory(dir)
                    val subdirectories = debridFileRepository.getChildrenByDirectory(dir)
                    
                    // Process files first
                    children.filterIsInstance<RemotelyCachedEntity>().forEach { file ->
                        totalFiles++
                        val filePath = file.directory?.let { d ->
                            d.fileSystemPath()?.let { "$it/${file.name}" } ?: file.name
                        } ?: file.name
                        val fileSizeMB = String.format("%.2f", (file.size ?: 0L) / 1_000_000.0)
                        diagnostic.appendLine("${indent}  - File: $filePath (id=${file.id}, size=${file.size} bytes, ${fileSizeMB} MB)")
                        
                        // Check if file is linked to any torrent
                        val linkedTorrents = torrentRepository.findAll().filter { torrent ->
                            torrent.files.any { it.id == file.id }
                        }
                        if (linkedTorrents.isNotEmpty()) {
                            linkedTorrents.forEach { torrent ->
                                diagnostic.appendLine("${indent}    Linked to torrent: ${torrent.name} (id=${torrent.id}, category=${torrent.category?.name})")
                                diagnostic.appendLine("${indent}      Is in ARR categories: ${torrent.category?.name in arrCategories}")
                            }
                        } else {
                            filesNotLinkedToTorrents++
                            diagnostic.appendLine("${indent}    NOT linked to any torrent - should be cleaned up!")
                        }
                    }
                    
                    // Process subdirectories recursively
                    subdirectories.forEach { subdir ->
                        traverseDirectory(subdir, "$indent  ")
                    }
                }
                
                traverseDirectory(directory)
                
                diagnostic.appendLine("")
                diagnostic.appendLine("Summary:")
                diagnostic.appendLine("  Total directories found: $totalDirectories")
                diagnostic.appendLine("  Total files found: $totalFiles")
                diagnostic.appendLine("  Files NOT linked to torrents: $filesNotLinkedToTorrents")
                if (filesNotLinkedToTorrents > 0) {
                    diagnostic.appendLine("  ⚠️  These files should be cleaned up if they meet age requirements")
                }
            } else {
                diagnostic.appendLine("Directory not found in database with path: $folderPathLtree")
                diagnostic.appendLine("This directory may exist on filesystem but not in database.")
            }
        }
        
        diagnostic.appendLine("=== End Diagnostic ===")
        return diagnostic.toString()
    }

    data class CleanupResult(
        val deletedCount: Int,
        val totalSizeBytes: Long,
        val deletedDirectoriesCount: Int = 0,
        val errors: List<String> = emptyList(),
        val dryRun: Boolean = false
    ) {
        val totalSizeMB: Double
            get() = totalSizeBytes / 1_000_000.0
    }
}

