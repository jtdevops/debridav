package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LiveChannelFilterService {
    private val logger = LoggerFactory.getLogger(LiveChannelFilterService::class.java)

    /**
     * Combines indexed and comma-separated lists.
     * Indexed entries are added first, followed by comma-separated entries.
     */
    private fun combineLists(indexed: List<String>?, commaSeparated: List<String>?): List<String> {
        val combined = mutableListOf<String>()
        indexed?.let { combined.addAll(it) }
        commaSeparated?.let { combined.addAll(it) }
        return combined
    }

    /**
     * Checks if a pattern matches a value using regex.
     */
    private fun matchesPattern(pattern: String, value: String): Boolean {
        return try {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            regex.matches(value)
        } catch (e: Exception) {
            logger.warn("Invalid regex pattern: $pattern", e)
            // Fallback to simple contains check if regex is invalid
            value.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * Checks if a category should be included in VFS (/live folder).
     * Uses both include and exclude lists. Exclude takes precedence over include.
     * 
     * @param categoryName Category name from provider
     * @param categoryId Category ID from provider
     * @param config Provider configuration
     * @return true if category should be included in VFS, false if excluded
     */
    fun shouldIncludeCategory(
        categoryName: String,
        categoryId: String,
        config: IptvProviderConfiguration
    ): Boolean {
        val excludeList = combineLists(config.liveCategoryExcludeIndex, config.liveCategoryExclude)
        val includeList = combineLists(config.liveCategoryIncludeIndex, config.liveCategoryInclude)

        // Exclude takes precedence - if category matches exclude pattern, exclude it
        if (excludeList.isNotEmpty()) {
            val isExcluded = excludeList.any { pattern ->
                matchesPattern(pattern, categoryName) || matchesPattern(pattern, categoryId)
            }
            if (isExcluded) {
                return false
            }
        }

        // If include list is empty, include all (unless excluded above)
        if (includeList.isEmpty()) {
            return true
        }

        // Check if category matches any include pattern
        return includeList.any { pattern ->
            matchesPattern(pattern, categoryName) || matchesPattern(pattern, categoryId)
        }
    }

    /**
     * Checks if a channel should be included in VFS (/live folder).
     * Uses both include and exclude lists. Exclude takes precedence over include.
     * Note: Category exclusion cascades - if category is excluded, this method should not be called.
     * 
     * @param channelName Channel name from provider
     * @param categoryName Category name (for logging/debugging)
     * @param categoryId Category ID (for logging/debugging)
     * @param config Provider configuration
     * @return true if channel should be included in VFS, false if excluded
     */
    fun shouldIncludeChannel(
        channelName: String,
        categoryName: String,
        categoryId: String,
        config: IptvProviderConfiguration
    ): Boolean {
        val excludeList = combineLists(config.liveChannelExcludeIndex, config.liveChannelExclude)
        val includeList = combineLists(config.liveChannelIncludeIndex, config.liveChannelInclude)

        // Exclude takes precedence - if channel matches exclude pattern, exclude it
        if (excludeList.isNotEmpty()) {
            val isExcluded = excludeList.any { pattern ->
                matchesPattern(pattern, channelName)
            }
            if (isExcluded) {
                return false
            }
        }

        // If include list is empty, include all channels in included categories (unless excluded above)
        if (includeList.isEmpty()) {
            return true
        }

        // Check if channel matches any include pattern
        return includeList.any { pattern ->
            matchesPattern(pattern, channelName)
        }
    }
}
