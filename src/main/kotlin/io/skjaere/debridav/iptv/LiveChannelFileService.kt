package io.skjaere.debridav.iptv

import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.model.ContentType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class LiveChannelFileService(
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val liveChannelFilterService: LiveChannelFilterService
) {
    private val logger = LoggerFactory.getLogger(LiveChannelFileService::class.java)
    
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
     * Returns list of provider names that have live content and live sync enabled
     */
    fun getLiveProviders(): List<String> {
        val providerConfigs = iptvConfigurationService.getProviderConfigurations()
        
        return providerConfigs
            .filter { it.liveSyncEnabled }
            .map { it.name }
            .filter { providerName ->
                // Check if provider has any active live channels
                val hasLiveChannels = iptvContentRepository.findByProviderName(providerName)
                    .any { it.contentType == ContentType.LIVE && it.isActive }
                hasLiveChannels
            }
    }
    
    /**
     * Returns categories for a provider after applying file filtering.
     * Returns empty list if provider doesn't exist or live sync is disabled.
     */
    fun getLiveCategories(providerName: String): List<String> {
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName }
            ?: return emptyList()
        
        if (!providerConfig.liveSyncEnabled) {
            return emptyList()
        }
        
        // Get all live channels for this provider
        val allLiveChannels = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE && it.isActive }
        
        // Get all live categories
        val liveCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, "live")
        
        // Filter categories based on file filtering rules
        val filteredCategories = liveCategories
            .filter { category ->
                liveChannelFilterService.shouldIncludeCategory(
                    categoryName = category.categoryName,
                    categoryId = category.categoryId,
                    config = providerConfig
                )
            }
            .filter { category ->
                // Only include categories that have at least one channel that passes filtering
                allLiveChannels.any { channel ->
                    channel.category?.categoryId == category.categoryId &&
                    liveChannelFilterService.shouldIncludeChannel(
                        channelName = channel.title,
                        categoryName = category.categoryName,
                        categoryId = category.categoryId,
                        config = providerConfig
                    )
                }
            }
            .map { it.categoryName }
            .distinct()
        
        // Apply sorting if enabled
        return if (iptvConfigurationProperties.liveSortAlphabetically) {
            filteredCategories.sorted()
        } else {
            filteredCategories
        }
    }
    
    /**
     * Returns channels for a provider and optionally a category, after applying file filtering.
     * If categoryName is null, returns all channels for the provider.
     */
    fun getLiveChannels(providerName: String, categoryName: String? = null): List<IptvContentEntity> {
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName }
            ?: return emptyList()
        
        if (!providerConfig.liveSyncEnabled) {
            return emptyList()
        }
        
        // Get all live channels for this provider
        val allLiveChannels = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE && it.isActive }
        
        // Apply file filtering
        val filteredChannels = allLiveChannels.filter { channel ->
            val category = channel.category
            if (category == null) {
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
        
        // Filter by category name if specified
        val categoryFiltered = if (categoryName != null) {
            filteredChannels.filter { channel ->
                val sanitizedCategoryName = sanitizeFileName(categoryName)
                val channelCategoryName = channel.category?.categoryName?.let { sanitizeFileName(it) }
                channelCategoryName == sanitizedCategoryName
            }
        } else {
            filteredChannels
        }
        
        // Apply sorting if enabled
        return if (iptvConfigurationProperties.liveSortAlphabetically) {
            categoryFiltered.sortedBy { it.title }
        } else {
            categoryFiltered
        }
    }
    
    /**
     * Returns a specific channel file entity by provider, category, and channel name.
     * Returns null if not found or doesn't pass filtering.
     */
    fun getLiveChannelFile(
        providerName: String,
        categoryName: String,
        channelName: String
    ): RemotelyCachedEntity? {
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName }
            ?: return null
        
        if (!providerConfig.liveSyncEnabled) {
            return null
        }
        
        // Get all live channels for this provider
        val allLiveChannels = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE && it.isActive }
        
        // Find matching channel
        val sanitizedCategoryName = sanitizeFileName(categoryName)
        val sanitizedChannelName = sanitizeFileName(channelName)
        
        val channel = allLiveChannels.firstOrNull { channel ->
            val channelCategoryName = channel.category?.categoryName?.let { sanitizeFileName(it) }
            val channelTitle = sanitizeFileName(channel.title)
            channelCategoryName == sanitizedCategoryName && channelTitle == sanitizedChannelName
        } ?: return null
        
        // Check if channel passes filtering
        val category = channel.category ?: return null
        
        val includeCategory = liveChannelFilterService.shouldIncludeCategory(
            categoryName = category.categoryName,
            categoryId = category.categoryId,
            config = providerConfig
        )
        
        if (!includeCategory) {
            return null
        }
        
        val includeChannel = liveChannelFilterService.shouldIncludeChannel(
            channelName = channel.title,
            categoryName = category.categoryName,
            categoryId = category.categoryId,
            config = providerConfig
        )
        
        if (!includeChannel) {
            return null
        }
        
        // Create virtual entity
        val filePath = "/live/${sanitizeFileName(providerName)}/$sanitizedCategoryName/$sanitizedChannelName.ts"
        return createVirtualRemotelyCachedEntity(channel, filePath)
    }
    
    /**
     * Creates a virtual RemotelyCachedEntity for a channel.
     * This entity is not persisted to the database.
     * Gets provider config internally.
     */
    fun createVirtualRemotelyCachedEntity(
        channel: IptvContentEntity,
        filePath: String
    ): RemotelyCachedEntity {
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == channel.providerName }
            ?: throw IllegalArgumentException("Provider configuration not found: ${channel.providerName}")
        
        val extension = "ts"
        
        // Use estimated size for live streams (similar to how MOVIES/SERIES use estimates)
        // Live streams are continuous, so we use a reasonable estimate for directory listing
        // This prevents I/O errors when listing the /live folder
        // Using episode size (1GB) since live channels are more like episodes than movies
        val estimatedSize = 1_000_000_000L // ~1GB estimate (same as episodes)
        
        // Create DebridIptvContent
        val debridIptvContent = DebridIptvContent().apply {
            this.iptvProviderName = channel.providerName
            this.iptvContentId = channel.contentId
            this.size = estimatedSize // Use estimated size for live streams (prevents I/O errors)
            this.mimeType = when (extension.lowercase()) {
                "m3u8" -> "application/vnd.apple.mpegurl"
                "ts" -> "video/mp2t"
                else -> "video/mp4"
            }
        }
        
        // Create IptvFile link
        val iptvFile = IptvFile(
            path = "${sanitizeFileName(channel.title)}.$extension",
            size = estimatedSize, // Use estimated size (same as DebridIptvContent)
            mimeType = debridIptvContent.mimeType ?: "video/mp4",
            link = channel.url, // Use tokenized URL
            params = emptyMap(),
            lastChecked = Instant.now().toEpochMilli()
        )
        
        debridIptvContent.debridLinks.add(iptvFile)
        
        // Create virtual RemotelyCachedEntity (not persisted)
        // Set size to match DebridIptvContent.size (same pattern as DBItems)
        val entity = RemotelyCachedEntity().apply {
            this.name = filePath.substringAfterLast("/")
            this.lastModified = Instant.now().toEpochMilli()
            this.size = debridIptvContent.size // Use same size as DebridIptvContent (prevents I/O errors)
            this.mimeType = debridIptvContent.mimeType
            this.contents = debridIptvContent
            this.hash = "${channel.providerName}_${channel.contentId}".hashCode().toString()
            this.directory = null // Will be set when needed
        }
        
        return entity
    }
    
    /**
     * Logs paths that would be created (for debugging).
     * Returns list of paths that pass filtering.
     */
    fun getLiveChannelPaths(providerName: String): List<String> {
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName }
            ?: return emptyList()
        
        if (!providerConfig.liveSyncEnabled) {
            return emptyList()
        }
        
        val channels = getLiveChannels(providerName)
        val paths = mutableListOf<String>()
        
        channels.forEach { channel ->
            val category = channel.category ?: return@forEach
            val sanitizedProviderName = sanitizeFileName(providerName)
            val sanitizedCategoryName = sanitizeFileName(category.categoryName)
            val sanitizedChannelName = sanitizeFileName(channel.title)
            val filePath = "/live/$sanitizedProviderName/$sanitizedCategoryName/$sanitizedChannelName.ts"
            paths.add(filePath)
        }
        
        return paths.sorted()
    }
}
