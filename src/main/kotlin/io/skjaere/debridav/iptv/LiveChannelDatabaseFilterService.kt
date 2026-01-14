package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LiveChannelDatabaseFilterService {
    private val logger = LoggerFactory.getLogger(LiveChannelDatabaseFilterService::class.java)

    /**
     * Combines indexed and comma-separated exclude lists.
     * Indexed entries are added first, followed by comma-separated entries.
     */
    private fun combineExcludeLists(indexed: List<String>?, commaSeparated: List<String>?): List<String> {
        val combined = mutableListOf<String>()
        indexed?.let { combined.addAll(it) }
        commaSeparated?.let { combined.addAll(it) }
        return combined
    }

    /**
     * Checks if a category should be included in database sync.
     * Only uses exclude lists - all categories are included unless explicitly excluded.
     * 
     * @param categoryName Category name from provider
     * @param categoryId Category ID from provider
     * @param config Provider configuration
     * @return true if category should be included (not excluded), false if excluded
     */
    fun shouldIncludeCategoryForDatabase(
        categoryName: String,
        categoryId: String,
        config: IptvProviderConfiguration
    ): Boolean {
        val excludeList = combineExcludeLists(config.liveDbCategoryExcludeIndex, config.liveDbCategoryExclude)
        
        if (excludeList.isEmpty()) {
            return true // No exclusions, include all
        }

        // Check if category name or ID matches any exclude pattern (regex supported)
        return !excludeList.any { pattern ->
            try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                regex.matches(categoryName) || regex.matches(categoryId)
            } catch (e: Exception) {
                logger.warn("Invalid regex pattern in live database category exclude: $pattern", e)
                // Fallback to simple contains check if regex is invalid
                categoryName.contains(pattern, ignoreCase = true) || categoryId.contains(pattern, ignoreCase = true)
            }
        }
    }

    /**
     * Checks if a channel should be included in database sync.
     * Only uses exclude lists - all channels are included unless explicitly excluded.
     * Note: If the category is excluded, this method should not be called (category exclusion cascades).
     * 
     * @param channelName Channel name from provider
     * @param categoryName Category name (for logging/debugging)
     * @param categoryId Category ID (for logging/debugging)
     * @param config Provider configuration
     * @return true if channel should be included (not excluded), false if excluded
     */
    fun shouldIncludeChannelForDatabase(
        channelName: String,
        categoryName: String,
        categoryId: String,
        config: IptvProviderConfiguration
    ): Boolean {
        val excludeList = combineExcludeLists(config.liveDbChannelExcludeIndex, config.liveDbChannelExclude)
        
        if (excludeList.isEmpty()) {
            return true // No exclusions, include all
        }

        // Check if channel name matches any exclude pattern (regex supported)
        return !excludeList.any { pattern ->
            try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                regex.matches(channelName)
            } catch (e: Exception) {
                logger.warn("Invalid regex pattern in live database channel exclude: $pattern", e)
                // Fallback to simple contains check if regex is invalid
                channelName.contains(pattern, ignoreCase = true)
            }
        }
    }
}
