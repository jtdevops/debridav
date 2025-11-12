package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.model.ContentType
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI

@Service
class IptvContentService(
    private val iptvContentRepository: IptvContentRepository,
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val iptvSyncHashRepository: IptvSyncHashRepository,
    private val iptvSeriesMetadataRepository: IptvSeriesMetadataRepository,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(IptvContentService::class.java)

    @PostConstruct
    fun logLanguagePrefixes() {
        val prefixes = iptvConfigurationProperties.languagePrefixes
        if (prefixes.isNotEmpty()) {
            logger.info("IPTV language prefixes configured: $prefixes (count: ${prefixes.size})")
            prefixes.forEachIndexed { index, prefix ->
                val cleaned = stripQuotes(prefix)
                logger.info("  [$index] Original: '$prefix' -> Cleaned: '$cleaned'")
            }
        } else {
            logger.debug("No IPTV language prefixes configured")
        }
    }

    fun searchContent(query: String, contentType: ContentType?): List<IptvContentEntity> {
        val normalizedQuery = normalizeTitle(query)
        
        // Get currently configured providers
        val configuredProviderNames = iptvConfigurationService.getProviderConfigurations()
            .map { it.name }
            .toSet()
        
        // Try language prefixes first if configured
        val languagePrefixes = iptvConfigurationProperties.languagePrefixes
        logger.debug("Configured language prefixes: $languagePrefixes (count: ${languagePrefixes.size})")
        
        if (languagePrefixes.isNotEmpty()) {
            for (prefix in languagePrefixes) {
                val cleanedPrefix = stripQuotes(prefix)
                val prefixedQuery = normalizeTitle("$cleanedPrefix$query")
                logger.debug("Trying prefix '$cleanedPrefix' (original: '$prefix') with query '$query' -> normalized: '$prefixedQuery'")
                
                val prefixedResults = if (contentType != null) {
                    iptvContentRepository.findByNormalizedTitleContainingAndContentType(prefixedQuery, contentType)
                } else {
                    iptvContentRepository.findByNormalizedTitleContaining(prefixedQuery)
                }
                
                val filteredPrefixedResults = prefixedResults.filter { it.providerName in configuredProviderNames }
                
                logger.debug("Prefix '$cleanedPrefix' returned ${filteredPrefixedResults.size} results (before filtering: ${prefixedResults.size})")
                
                if (filteredPrefixedResults.isNotEmpty()) {
                    logger.debug("Found ${filteredPrefixedResults.size} results with prefix '$cleanedPrefix' for query '$query', returning early")
                    return filteredPrefixedResults
                }
            }
            logger.debug("No results found with any language prefix, falling back to search without prefix")
        } else {
            logger.debug("No language prefixes configured, searching without prefix")
        }
        
        // Fallback to search without prefix
        val results = if (contentType != null) {
            iptvContentRepository.findByNormalizedTitleContainingAndContentType(normalizedQuery, contentType)
        } else {
            iptvContentRepository.findByNormalizedTitleContaining(normalizedQuery)
        }
        
        // Filter to only include content from currently configured providers
        return results.filter { it.providerName in configuredProviderNames }
    }

    fun findExactMatch(title: String, contentType: ContentType?): IptvContentEntity? {
        val normalizedTitle = normalizeTitle(title)
        val candidates = searchContent(normalizedTitle, contentType)
        
        // Find exact match (normalized titles match exactly)
        return candidates.firstOrNull { it.normalizedTitle == normalizedTitle }
    }

    fun getContentByProviderAndId(providerName: String, contentId: String): IptvContentEntity? {
        // Verify provider is still configured
        val configuredProviderNames = iptvConfigurationService.getProviderConfigurations()
            .map { it.name }
            .toSet()
        
        if (providerName !in configuredProviderNames) {
            logger.warn("Requested content from removed provider: $providerName")
            return null
        }
        
        val content = iptvContentRepository.findByProviderNameAndContentId(providerName, contentId)
        
        // Also check if content is active
        return if (content != null && content.isActive) content else null
    }

    fun deleteProviderContent(providerName: String): Int {
        // Delete content items (streams)
        val contentToDelete = iptvContentRepository.findByProviderName(providerName)
        val contentCount = contentToDelete.size
        
        if (contentCount > 0) {
            logger.info("Deleting $contentCount content items for provider: $providerName")
            iptvContentRepository.deleteAll(contentToDelete)
        } else {
            logger.info("No content found for provider: $providerName")
        }
        
        // Delete categories
        val categoriesToDelete = iptvCategoryRepository.findByProviderName(providerName)
        val categoryCount = categoriesToDelete.size
        if (categoryCount > 0) {
            logger.info("Deleting $categoryCount categories for provider: $providerName")
            iptvCategoryRepository.deleteByProviderName(providerName)
        }
        
        // Delete sync hashes
        val hashesToDelete = iptvSyncHashRepository.findByProviderName(providerName)
        val hashCount = hashesToDelete.size
        if (hashCount > 0) {
            logger.info("Deleting $hashCount sync hashes for provider: $providerName")
            iptvSyncHashRepository.deleteByProviderName(providerName)
        }
        
        // Delete series metadata cache
        val metadataCount = iptvSeriesMetadataRepository.countByProviderName(providerName)
        if (metadataCount > 0) {
            logger.info("Deleting $metadataCount series metadata cache entries for provider: $providerName")
            iptvSeriesMetadataRepository.deleteByProviderName(providerName)
        }
        
        logger.info("Deleted all data for provider: $providerName (content: $contentCount, categories: $categoryCount, hashes: $hashCount, metadata: $metadataCount)")
        return contentCount
    }

    fun normalizeTitle(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove special characters
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
    }

    /**
     * Strips surrounding single or double quotes from a string if present.
     * This helps preserve trailing spaces when configuring prefixes in properties files.
     * Examples:
     *   "EN - " -> EN - 
     *   'EN| ' -> EN| 
     *   EN |  -> EN |  (no quotes, unchanged)
     */
    private fun stripQuotes(value: String): String {
        return when {
            (value.startsWith("\"") && value.endsWith("\"")) || 
            (value.startsWith("'") && value.endsWith("'")) -> {
                value.substring(1, value.length - 1)
            }
            else -> value
        }
    }

    fun resolveIptvUrl(tokenizedUrl: String, providerName: String): String {
        // Check if this is a series placeholder URL (should not be resolved)
        if (tokenizedUrl.startsWith("SERIES_PLACEHOLDER:")) {
            throw IllegalArgumentException("Cannot resolve series placeholder URL. Episodes must be fetched on-demand.")
        }
        
        val providerConfigs = iptvConfigurationService.getProviderConfigurations()
        val providerConfig = providerConfigs.find { it.name == providerName }
            ?: throw IllegalArgumentException("IPTV provider $providerName not found")
        
        var resolved = tokenizedUrl
        
        // Replace placeholders with actual values
        when (providerConfig.type) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> {
                providerConfig.m3uUrl?.let { m3uUrl ->
                    try {
                        val uri = URI(m3uUrl)
                        val baseUrl = "${uri.scheme}://${uri.host}${uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""}"
                        resolved = resolved.replace("{BASE_URL}", baseUrl)
                    } catch (e: Exception) {
                        logger.warn("Could not extract base URL from m3u-url: $m3uUrl", e)
                    }
                }
            }
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> {
                providerConfig.xtreamBaseUrl?.let { 
                    resolved = resolved.replace("{BASE_URL}", it)
                }
                providerConfig.xtreamUsername?.let {
                    resolved = resolved.replace("{USERNAME}", it)
                }
                providerConfig.xtreamPassword?.let {
                    resolved = resolved.replace("{PASSWORD}", it)
                }
            }
        }
        
        // Also handle URL-encoded placeholders
        resolved = resolved.replace("%7BBASE_URL%7D", providerConfig.xtreamBaseUrl ?: "")
        resolved = resolved.replace("%7BUSERNAME%7D", providerConfig.xtreamUsername ?: "")
        resolved = resolved.replace("%7BPASSWORD%7D", providerConfig.xtreamPassword ?: "")
        
        return resolved
    }
}

