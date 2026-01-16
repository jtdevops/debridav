package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.model.ContentType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer

@Service
class LiveChannelSyncService(
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val liveChannelFilterService: LiveChannelFilterService
) {
    private val logger = LoggerFactory.getLogger(LiveChannelSyncService::class.java)

    /**
     * Sanitizes a file name by removing invalid file system characters and converting to ASCII-only.
     * Handles Unicode characters including subscripts/superscripts by normalizing and replacing non-ASCII with underscores.
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName
            // Normalize Unicode characters (decompose, then remove combining marks)
            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            // Replace non-ASCII characters with underscores
            .replace(Regex("[^\\x00-\\x7F]"), "_")
            // Replace invalid file system characters with underscores
            .replace(Regex("[<>:\"/\\|?*]"), "_")
            // Replace spaces with underscores
            .replace(Regex("\\s+"), "_")
            // Consolidate multiple consecutive underscores into a single underscore
            .replace(Regex("_+"), "_")
            // Trim leading and trailing underscores
            .trim('_')
    }

    /**
     * Syncs live channels from database to VFS under /live
     * This method is now a no-op as file entries are generated at runtime.
     * Only logs paths if logging is enabled.
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
            logger.debug("Live channel file sync skipped - Live sync is disabled for provider: $providerName")
            return
        }

        // If logging is enabled, log paths that would be generated
        if (iptvConfigurationProperties.liveLogFilePaths) {
            logger.info("Logging live channel file paths for provider: $providerName")
            
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

            // Apply file filtering and group by category
            val channelsToSync = allLiveChannels.filter { channel ->
                val category = channel.category
                if (category == null) {
                    logger.warn("Live channel ${channel.title} has no category, excluding from file sync")
                    false
                } else {
                    // Check if category should be included
                    val includeCategory = liveChannelFilterService.shouldIncludeCategory(
                        categoryName = category.categoryName,
                        categoryId = category.categoryId,
                        config = providerConfig
                    )

                    if (!includeCategory) {
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

            logger.info("Filtered ${channelsToSync.size} live channels from ${allLiveChannels.size} total for file logging")

            // Group channels by category and collect paths
            val channelsByCategory = channelsToSync.groupBy { it.category?.categoryName ?: "Unknown" }
            val pathsToLog = mutableListOf<String>()

            channelsByCategory.forEach { (categoryName, channels) ->
                val sanitizedCategoryName = sanitizeFileName(categoryName)

                channels.forEach { channel ->
                    val category = channel.category
                    if (category == null) {
                        return@forEach
                    }

                    val sanitizedChannelName = sanitizeFileName(channel.title)
                    val sanitizedProviderName = sanitizeFileName(providerName)
                    val filePath = "/live/$sanitizedProviderName/$sanitizedCategoryName/$sanitizedChannelName.ts"
                    pathsToLog.add(filePath)
                }
            }

            // Log paths
            if (pathsToLog.isNotEmpty()) {
                logger.info("Live channel file paths that would be generated for provider $providerName (${pathsToLog.size} paths, after file filtering):")
                pathsToLog.sorted().forEach { path ->
                    logger.info("  $path")
                }
            }
        } else {
            logger.debug("Live channel file sync skipped for provider $providerName - file entries are now generated at runtime")
        }
    }
}
