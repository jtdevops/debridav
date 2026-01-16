package io.skjaere.debridav.iptv

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.repository.DebridFileContentsRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class LiveChannelSyncService(
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val databaseFileService: DatabaseFileService,
    private val liveChannelFilterService: LiveChannelFilterService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val debridFileContentsRepository: DebridFileContentsRepository
) {
    private val logger = LoggerFactory.getLogger(LiveChannelSyncService::class.java)

    /**
     * Sanitizes a file name by removing invalid file system characters
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[<>:\"/\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Syncs live channels from database to VFS under /live
     * Only operates when iptv.live.enabled=true
     */
    @Transactional
    fun syncLiveChannelsToVfs(providerName: String) {
        // Get provider configuration
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName }
            ?: run {
                logger.warn("Provider configuration not found: $providerName")
                return
            }

        // Check if live sync is enabled for this provider (per-provider opt-in, defaults to false)
        if (!providerConfig.liveSyncEnabled) {
            logger.debug("Live channel VFS sync skipped - Live sync is disabled for provider: $providerName")
            return
        }

        logger.info("Starting live channel VFS sync for provider: $providerName")

        // Query all live channels from database for this provider
        val allLiveChannels = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE && it.isActive }

        if (allLiveChannels.isEmpty()) {
            logger.debug("No active live channels found in database for provider: $providerName")
            return
        }

        // Get all live categories for this provider
        val liveCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, "live")
        val categoryMap = liveCategories.associateBy { it.categoryId }

        // Apply VFS filtering and group by category
        val channelsToSync = allLiveChannels.filter { channel ->
            val category = channel.category
            if (category == null) {
                // If no category, exclude (shouldn't happen for live content)
                logger.warn("Live channel ${channel.title} has no category, excluding from VFS sync")
                false
            } else {
                // Check if category should be included
                val includeCategory = liveChannelFilterService.shouldIncludeCategory(
                    categoryName = category.categoryName,
                    categoryId = category.categoryId,
                    config = providerConfig
                )

                if (!includeCategory) {
                    // Category excluded, exclude all channels in it
                    false
                } else {
                    // Category included, check channel
                    liveChannelFilterService.shouldIncludeChannel(
                        channelName = channel.title,
                        categoryName = category.categoryName,
                        categoryId = category.categoryId,
                        config = providerConfig
                    )
                }
            }
        }

        logger.info("Filtered ${channelsToSync.size} live channels from ${allLiveChannels.size} total for VFS sync")

        // Group channels by category for processing
        // Note: Path structure is always /live/{provider}/{category}/{channel}.ts
        // channelsToSync has already been filtered using VFS filtering rules:
        // - IPTV_PROVIDER_{NAME}_LIVE_CATEGORY_INCLUDE/EXCLUDE
        // - IPTV_PROVIDER_{NAME}_LIVE_CHANNEL_INCLUDE/EXCLUDE
        val channelsByCategory = channelsToSync.groupBy { it.category?.categoryName ?: "Unknown" }

        val activeChannelIds = mutableSetOf<String>()
        var createdCount = 0
        var updatedCount = 0
        var errorCount = 0
        
        // Collect paths for logging if enabled
        // Only paths that pass VFS filtering will be logged (channelsToSync is already filtered)
        val pathsToLog = if (iptvConfigurationProperties.liveLogVfsPaths) {
            mutableListOf<String>()
        } else {
            null
        }

        // Process each category
        channelsByCategory.forEach { (categoryName, channels) ->
            val sanitizedCategoryName = sanitizeFileName(categoryName)

            channels.forEach { channel ->
                try {
                    val category = channel.category
                    if (category == null) {
                        logger.warn("Channel ${channel.title} has no category, skipping")
                        return@forEach
                    }

                    val sanitizedChannelName = sanitizeFileName(channel.title)
                    val sanitizedProviderName = sanitizeFileName(providerName)

                    // Always use .ts extension for VFS paths (regardless of configured extension or URL)
                    // The configured extension is used when resolving the URL for streaming, not for VFS paths
                    val extension = "ts"

                    // Construct file path: /live/{provider}/{category}/{channel}.ts
                    val filePath = "/live/$sanitizedProviderName/$sanitizedCategoryName/$sanitizedChannelName.$extension"
                    
                    // Log path if logging is enabled
                    if (pathsToLog != null) {
                        pathsToLog.add(filePath)
                    }

                    // Check if file already exists and if name has changed
                    val existingFile = databaseFileService.getFileAtPath(filePath)
                    val expectedHash = "${providerName}_${channel.contentId}".hashCode().toString()

                    if (existingFile != null) {
                        // File exists - check if it's the same channel (by hash) or if name changed
                        val existingHash = (existingFile as? io.skjaere.debridav.fs.RemotelyCachedEntity)?.hash
                        if (existingHash == expectedHash) {
                            // Same channel, file path is correct - no update needed
                            activeChannelIds.add(channel.contentId)
                            return@forEach
                        } else {
                            // Different channel at this path, or name changed
                            // Need to update file path if name changed
                            // For now, we'll recreate the file (old one will be replaced)
                            logger.debug("Updating live channel file: $filePath (name may have changed)")
                            updatedCount++
                        }
                    } else {
                        // New channel
                        createdCount++
                    }

                    // Create or update the file
                    createLiveChannelFile(channel, categoryName, filePath, expectedHash, providerConfig)
                    activeChannelIds.add(channel.contentId)
                } catch (e: Exception) {
                    logger.error("Error creating live channel file for channel: ${channel.title}", e)
                    errorCount++
                }
            }
        }

        // Remove stale channels (channels that are no longer in the filtered list)
        // This includes channels excluded by VFS filtering, even if they're active in database
        removeStaleLiveChannels(providerName, activeChannelIds, providerConfig)
        
        // Clean up empty category directories after removing stale channels
        // Use active categories from filtered channels
        cleanupEmptyLiveCategoryDirectories(providerName, channelsByCategory.keys, providerConfig)

        // Log paths if logging is enabled
        // These paths have already been filtered using VFS filtering rules (category/channel include/exclude)
        // Path structure: /live/{provider}/{category}/{channel}.ts (always .ts extension)
        if (pathsToLog != null && pathsToLog.isNotEmpty()) {
            logger.info("Live channel VFS paths that would be created for provider $providerName (${pathsToLog.size} paths, after VFS filtering):")
            pathsToLog.sorted().forEach { path ->
                logger.info("  $path")
            }
        }

        logger.info("Live channel VFS sync completed for provider $providerName: created=$createdCount, updated=$updatedCount, errors=$errorCount")
    }

    /**
     * Creates a live channel file in VFS
     */
    private fun createLiveChannelFile(
        channel: IptvContentEntity,
        categoryName: String,
        filePath: String,
        hash: String,
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ) {
        // Always use .ts extension for VFS paths (regardless of configured extension or URL)
        // The configured extension is used when resolving the URL for streaming, not for VFS paths
        val extension = "ts"

        // Create DebridIptvContent
        val debridIptvContent = DebridIptvContent().apply {
            this.iptvProviderName = channel.providerName
            this.iptvContentId = channel.contentId
            this.size = 0L // Live streams don't have a fixed size
            this.mimeType = when (extension.lowercase()) {
                "m3u8" -> "application/vnd.apple.mpegurl"
                "ts" -> "video/mp2t"
                else -> "video/mp4"
            }
        }

        // Create IptvFile link
        val iptvFile = IptvFile(
            path = "${sanitizeFileName(channel.title)}.$extension",
            size = 0L,
            mimeType = debridIptvContent.mimeType ?: "video/mp4",
            link = channel.url, // Use tokenized URL
            params = emptyMap(),
            lastChecked = Instant.now().toEpochMilli()
        )

        debridIptvContent.debridLinks.add(iptvFile)

        // Create virtual file (createDebridFile already uses runBlocking internally, so no need to wrap)
        try {
            databaseFileService.createDebridFile(filePath, hash, debridIptvContent)
            logger.debug("Created live channel file: $filePath")
        } catch (e: Exception) {
            logger.error("Failed to create live channel file: $filePath", e)
            throw e
        }
    }

    /**
     * Extracts file extension from URL
     */
    private fun extractExtensionFromUrl(url: String): String? {
        // Try to extract extension from URL
        // Format: {baseUrl}/live/{username}/{password}/{stream_id}.{extension}
        // Or: {baseUrl}/live/{username}/{password}/{stream_id}
        val lastDot = url.lastIndexOf('.')
        val lastSlash = url.lastIndexOf('/')
        
        if (lastDot > lastSlash && lastDot < url.length - 1) {
            val extension = url.substring(lastDot + 1)
            // Remove any query parameters
            val cleanExtension = extension.split('?', '#').first()
            if (cleanExtension.isNotEmpty() && cleanExtension.length <= 10) {
                return cleanExtension.lowercase()
            }
        }
        
        return null
    }

    /**
     * Removes stale live channels from VFS.
     * This includes:
     * 1. Channels that are no longer in the active filtered list (excluded by VFS filtering)
     * 2. Channels that are inactive in the database
     * 
     * @param providerName Provider name
     * @param activeChannelIds Set of channel IDs that passed VFS filtering and should remain in VFS
     * @param providerConfig Provider configuration for VFS filtering rules
     */
    private fun removeStaleLiveChannels(
        providerName: String, 
        activeChannelIds: Set<String>,
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ) {
        // Get all live channels from database for this provider (including inactive)
        val allLiveChannels = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE }

        // Find channels that should be removed from VFS:
        // 1. Channels not in activeChannelIds (excluded by VFS filtering or inactive)
        // 2. Also check all channels against VFS filtering to catch any edge cases
        val channelsToRemove = allLiveChannels.filter { channel ->
            // Remove if not in active list
            if (channel.contentId !in activeChannelIds) {
                return@filter true
            }
            
            // Double-check VFS filtering for channels in active list (in case filtering rules changed)
            val category = channel.category
            if (category == null) {
                return@filter true // No category = exclude
            }
            
            // Check category filtering
            val includeCategory = liveChannelFilterService.shouldIncludeCategory(
                categoryName = category.categoryName,
                categoryId = category.categoryId,
                config = providerConfig
            )
            
            if (!includeCategory) {
                return@filter true // Category excluded
            }
            
            // Check channel filtering
            val includeChannel = liveChannelFilterService.shouldIncludeChannel(
                channelName = channel.title,
                categoryName = category.categoryName,
                categoryId = category.categoryId,
                config = providerConfig
            )
            
            !includeChannel // Remove if channel is excluded
        }

        if (channelsToRemove.isEmpty()) {
            return
        }

        logger.info("Removing ${channelsToRemove.size} live channels from VFS for provider: $providerName (excluded by VFS filtering or inactive)")

        channelsToRemove.forEach { channel ->
            try {
                val category = channel.category
                if (category == null) {
                    return@forEach
                }

                val sanitizedProviderName = sanitizeFileName(providerName)
                val sanitizedCategoryName = sanitizeFileName(category.categoryName)
                val sanitizedChannelName = sanitizeFileName(channel.title)
                
                // Always use .ts extension for VFS paths
                val extension = "ts"
                val filePath = "/live/$sanitizedProviderName/$sanitizedCategoryName/$sanitizedChannelName.$extension"

                val existingFile = databaseFileService.getFileAtPath(filePath)
                if (existingFile != null && existingFile is io.skjaere.debridav.fs.RemotelyCachedEntity) {
                    // Delete the file
                    try {
                        val hash = "${providerName}_${channel.contentId}".hashCode().toString()
                        // Use repository to delete by hash
                        debridFileContentsRepository.deleteDbEntityByHash(hash)
                        logger.debug("Removed live channel file from VFS (excluded by filtering): $filePath")
                    } catch (e: Exception) {
                        logger.warn("Failed to remove live channel file: $filePath", e)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error removing live channel: ${channel.title}", e)
            }
        }
    }
    
    /**
     * Cleans up empty category directories under /live/{provider}/{category} structure.
     * Only keeps directories for active categories that have channels and pass VFS filtering.
     * 
     * @param providerName Provider name
     * @param activeCategoryNames Set of category names that have channels passing VFS filtering
     * @param providerConfig Provider configuration for VFS filtering rules
     */
    private fun cleanupEmptyLiveCategoryDirectories(
        providerName: String, 
        activeCategoryNames: Set<String>,
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ) {
        try {
            val sanitizedProviderName = sanitizeFileName(providerName)
            val liveBasePath = "/live/$sanitizedProviderName"
            
            // Get provider directory
            val providerDir = debridFileContentsRepository.getDirectoryByPath(liveBasePath)
            if (providerDir == null) {
                return
            }
            
            // Get all category directories under this provider
            val categoryDirectories = debridFileContentsRepository.getChildrenByDirectory(providerDir)
            
            // Get all live categories from database to check VFS filtering
            val allLiveCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, "live")
            
            categoryDirectories.forEach { categoryDir ->
                try {
                    val categoryName = categoryDir.name
                    if (categoryName == null) {
                        logger.warn("Category directory has null name: ${categoryDir.path}, skipping")
                        return@forEach
                    }
                    
                    val sanitizedCategoryName = sanitizeFileName(categoryName)
                    
                    // Find matching category from database
                    val dbCategory = allLiveCategories.find { 
                        sanitizeFileName(it.categoryName) == sanitizedCategoryName 
                    }
                    
                    // Check if this category should be included in VFS
                    val shouldIncludeInVfs = if (dbCategory != null) {
                        liveChannelFilterService.shouldIncludeCategory(
                            categoryName = dbCategory.categoryName,
                            categoryId = dbCategory.categoryId,
                            config = providerConfig
                        )
                    } else {
                        // Category not in database, check if it's in active list
                        activeCategoryNames.contains(categoryName) || 
                        activeCategoryNames.any { sanitizeFileName(it) == sanitizedCategoryName }
                    }
                    
                    // Check if category is in active list (has channels passing VFS filtering)
                    val isInActiveList = activeCategoryNames.contains(categoryName) || 
                                        activeCategoryNames.any { sanitizeFileName(it) == sanitizedCategoryName }
                    
                    if (!shouldIncludeInVfs || !isInActiveList) {
                        // Category excluded by VFS filtering or not in active list, check if directory is empty
                        val children = debridFileContentsRepository.getByDirectory(categoryDir)
                        if (children.isEmpty()) {
                            logger.info("Deleting empty category directory (excluded by VFS filtering): ${categoryDir.path}")
                            databaseFileService.deleteFile(categoryDir)
                        } else {
                            logger.debug("Category directory ${categoryDir.path} is not empty (${children.size} items), keeping it")
                        }
                    } else {
                        // Category should be included, but check if directory is empty (shouldn't happen, but be safe)
                        val children = debridFileContentsRepository.getByDirectory(categoryDir)
                        if (children.isEmpty()) {
                            logger.warn("Category directory ${categoryDir.path} should have channels but is empty, this shouldn't happen")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error checking category directory ${categoryDir.path}", e)
                }
            }
            
            // Check if provider directory is empty after cleanup
            val remainingChildren = debridFileContentsRepository.getByDirectory(providerDir)
            if (remainingChildren.isEmpty()) {
                logger.info("Deleting empty live provider directory: $liveBasePath")
                databaseFileService.deleteFile(providerDir)
            }
        } catch (e: Exception) {
            logger.warn("Error cleaning up empty live category directories for provider $providerName", e)
        }
    }
}
