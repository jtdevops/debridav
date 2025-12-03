package io.skjaere.debridav.cleanup

import io.ipfs.multibase.Base58
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
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
     */
    @Transactional
    fun cleanupAbandonedFiles(
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
        
        if (abandonedFiles.isEmpty()) {
            logger.info("No abandoned files found to clean up")
            return CleanupResult(deletedCount = 0, totalSizeBytes = 0L, dryRun = dryRun)
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
                    logger.debug("Deleting abandoned file: {} (size: {} bytes)", filePath, fileSize)
                    databaseFileService.deleteFile(file)
                    deletedCount++
                    totalSizeBytes += fileSize
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to delete file ${file.name}: ${e.message}"
                logger.error(errorMsg, e)
                errors.add(errorMsg)
            }
        }

        val result = CleanupResult(
            deletedCount = deletedCount,
            totalSizeBytes = totalSizeBytes,
            errors = errors,
            dryRun = dryRun
        )

        if (dryRun) {
            logger.info("Dry run: Would delete {} abandoned file(s), total size: {} MB", 
                deletedCount, String.format("%.2f", totalSizeBytes / 1_000_000.0))
        } else {
            logger.info("Cleaned up {} abandoned file(s), total size: {} MB", 
                deletedCount, String.format("%.2f", totalSizeBytes / 1_000_000.0))
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
     */
    @Transactional
    fun cleanupOrphanedTorrentsAndFiles(
        dryRun: Boolean = false
    ): CleanupResult {
        val arrCategories = getArrCategories()
        
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
        
        // Find files not linked to any active category
        val orphanedFiles = debridFileRepository.findAbandonedFilesNotLinkedToArrCategories(
            downloadPathPrefix = downloadPathPrefix,
            cutoffTime = cutoffTime,
            arrCategories = arrCategories
        )
        
        // Find torrents not linked to any active category
        val allTorrents = torrentRepository.findAll().filter { it.status == Status.LIVE }
        val orphanedTorrents = allTorrents.filter { torrent ->
            val categoryName = torrent.category?.name
            categoryName == null || categoryName !in arrCategories
        }.filter { torrent ->
            // Only include torrents in downloads folder
            torrent.savePath?.startsWith(debridavConfigurationProperties.downloadPath) == true &&
            // Apply age check only if time-based cleanup is enabled
            (!useTimeBased || (torrent.created?.toEpochMilli() ?: Long.MAX_VALUE) < cutoffTime)
        }
        
        var deletedCount = 0
        var totalSizeBytes = 0L
        val errors = mutableListOf<String>()
        
        // Delete orphaned files
        for (file in orphanedFiles) {
            try {
                val fileSize = file.size ?: 0L
                val filePath = file.directory?.let { dir ->
                    dir.fileSystemPath()?.let { "$it/${file.name}" } ?: file.name
                } ?: file.name ?: "unknown"
                
                if (dryRun) {
                    logger.debug("Would delete orphaned file: {} (size: {} bytes)", filePath, fileSize)
                    deletedCount++
                    totalSizeBytes += fileSize
                } else {
                    logger.debug("Deleting orphaned file: {} (size: {} bytes)", filePath, fileSize)
                    databaseFileService.deleteFile(file)
                    deletedCount++
                    totalSizeBytes += fileSize
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to delete orphaned file ${file.name}: ${e.message}"
                logger.error(errorMsg, e)
                errors.add(errorMsg)
            }
        }
        
        // Delete orphaned torrents and their files
        for (torrent in orphanedTorrents) {
            try {
                val torrentSize = torrent.files.sumOf { it.size ?: 0L }
                val torrentName = torrent.name ?: "unknown"
                
                if (dryRun) {
                    logger.debug("Would delete orphaned torrent: {} ({} files, {} bytes)", 
                        torrentName, torrent.files.size, torrentSize)
                    deletedCount += torrent.files.size
                    totalSizeBytes += torrentSize
                } else {
                    logger.debug("Deleting orphaned torrent: {} ({} files, {} bytes)", 
                        torrentName, torrent.files.size, torrentSize)
                    
                    // Delete all files in the torrent
                    torrent.files.forEach { file ->
                        try {
                            databaseFileService.deleteFile(file)
                        } catch (e: Exception) {
                            logger.warn("Failed to delete file ${file.name} from torrent ${torrent.name}: ${e.message}")
                        }
                    }
                    
                    // Mark torrent as deleted
                    torrent.status = Status.DELETED
                    torrentRepository.save(torrent)
                    
                    deletedCount += torrent.files.size
                    totalSizeBytes += torrentSize
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to delete orphaned torrent ${torrent.name}: ${e.message}"
                logger.error(errorMsg, e)
                errors.add(errorMsg)
            }
        }
        
        val result = CleanupResult(
            deletedCount = deletedCount,
            totalSizeBytes = totalSizeBytes,
            errors = errors,
            dryRun = dryRun
        )
        
        val cleanupMode = if (useTimeBased) {
            "time-based (${thresholdMinutes} minutes)"
        } else {
            "immediate"
        }
        
        if (dryRun) {
            logger.info("Dry run ({}): Would delete {} orphaned file(s) and {} orphaned torrent(s), total size: {} MB", 
                cleanupMode, orphanedFiles.size, orphanedTorrents.size, String.format("%.2f", totalSizeBytes / 1_000_000.0))
        } else {
            logger.info("Cleaned up ({}) {} orphaned file(s) and {} orphaned torrent(s), total size: {} MB", 
                cleanupMode, orphanedFiles.size, orphanedTorrents.size, String.format("%.2f", totalSizeBytes / 1_000_000.0))
            if (errors.isNotEmpty()) {
                logger.warn("Encountered {} error(s) during orphaned cleanup", errors.size)
            }
        }
        
        return result
    }

    data class CleanupResult(
        val deletedCount: Int,
        val totalSizeBytes: Long,
        val errors: List<String> = emptyList(),
        val dryRun: Boolean = false
    ) {
        val totalSizeMB: Double
            get() = totalSizeBytes / 1_000_000.0
    }
}

