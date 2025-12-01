package io.skjaere.debridav.iptv.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "iptv")
data class IptvConfigurationProperties(
    val enabled: Boolean = false,
    val syncInterval: Duration = Duration.ofHours(24),
    val initialSyncDelay: Duration = Duration.ofSeconds(30),
    val providers: List<String> = emptyList(),
    val responseSaveFolder: String? = null,
    val useLocalResponses: Boolean = false,
    val seriesMetadataCacheTtl: Duration = Duration.ofHours(168), // Default: 7 days (168 hours)
    val seriesMetadataPurgeInterval: Duration = Duration.ofHours(24), // Default: purge check every 24 hours
    val imdbMetadataCacheTtl: Duration = Duration.ofHours(8760), // Default: 365 days (8760 hours) - IMDB data rarely changes
    val imdbMetadataPurgeInterval: Duration = Duration.ofHours(24), // Default: purge check every 24 hours
    val languagePrefixes: List<String> = emptyList(), // Language prefixes to try when searching (e.g., "EN - ", "EN| ", "EN | ")
    val languagePrefixExpansionSeparators: List<String> = listOf("| ", "- ", " - "), // Separators used to expand each prefix into variations (default: ["| ", "- ", " - "])
    val userAgent: String = "TiviMate/5.2.0 (Android)", // User-Agent string for IPTV media requests (default: TiviMate)
    val includeProviderInMagnetTitle: Boolean = false, // Include provider name in magnet title after -IPTV (e.g., -IPTV-mega-NL) for debugging
    val maxSearchResults: Int = 0, // Maximum number of search results to return (0 = unlimited, default: unlimited)
    val redirectHandlingMode: RedirectHandlingMode = RedirectHandlingMode.AUTOMATIC, // How to handle HTTP redirects: AUTOMATIC (use Ktor's HttpRedirect plugin) or MANUAL (preserve Range headers)
    val loginRateLimit: Duration = Duration.ofMinutes(1) // Rate limit for IPTV provider login/credential verification calls (default: 1 minute)
) {
    /**
     * Base language prefixes (without separators) extracted from configuration.
     * Used to check if an extracted prefix is in the configured English content list.
     */
    val baseLanguagePrefixes: Set<String> by lazy {
        extractBasePrefixes(languagePrefixes)
    }
    
    /**
     * Expanded language prefixes with all separator variations.
     * If a prefix contains comma separators, it will be split into individual prefixes.
     * Each prefix is then expanded into variations using languagePrefixExpansionSeparators.
     */
    val expandedLanguagePrefixes: List<String> by lazy {
        expandLanguagePrefixes(languagePrefixes, languagePrefixExpansionSeparators)
    }
    
    init {
        // Note: It's valid to have IPTV enabled with no providers - 
        // the sync service will simply skip processing in that case
    }
    
    /**
     * Extracts base prefixes (without separators) from the configuration.
     */
    private fun extractBasePrefixes(prefixes: List<String>): Set<String> {
        val basePrefixes = mutableSetOf<String>()
        val expansionSeparators = languagePrefixExpansionSeparators
        
        for (prefix in prefixes) {
            val cleanedPrefix = prefix.trim().removeSurrounding("\"").removeSurrounding("'")
            
            // Check if this prefix contains comma separator (indicating multiple prefixes)
            if (cleanedPrefix.contains(",")) {
                // Split by comma
                val splitPrefixes = cleanedPrefix.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                
                // Remove separators from each split prefix
                for (splitPrefix in splitPrefixes) {
                    val basePrefix = removeSeparatorFromPrefix(splitPrefix, expansionSeparators)
                    basePrefixes.add(basePrefix)
                }
            } else {
                // Remove separator if present
                val basePrefix = removeSeparatorFromPrefix(cleanedPrefix, expansionSeparators)
                basePrefixes.add(basePrefix)
            }
        }
        
        return basePrefixes
    }
    
    /**
     * Removes expansion separators from the end of a prefix to get the base prefix.
     */
    private fun removeSeparatorFromPrefix(prefix: String, separators: List<String>): String {
        var result = prefix
        // Try removing each separator (longest first to avoid partial matches)
        val sortedSeparators = separators.sortedByDescending { it.length }
        for (separator in sortedSeparators) {
            if (result.endsWith(separator, ignoreCase = false)) {
                result = result.removeSuffix(separator)
                break
            }
        }
        return result.trim()
    }
    
    /**
     * Expands language prefixes by:
     * 1. Splitting prefixes that contain comma separators
     * 2. Expanding each prefix into variations using languagePrefixExpansionSeparators
     */
    private fun expandLanguagePrefixes(
        prefixes: List<String>,
        expansionSeparators: List<String>
    ): List<String> {
        val expanded = mutableListOf<String>()
        
        for (prefix in prefixes) {
            val cleanedPrefix = prefix.trim().removeSurrounding("\"").removeSurrounding("'")
            
            // Check if this prefix contains comma separator (indicating multiple prefixes)
            if (cleanedPrefix.contains(",")) {
                // Split by comma
                val splitPrefixes = cleanedPrefix.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                
                // Expand each split prefix
                for (splitPrefix in splitPrefixes) {
                    expanded.addAll(expandSinglePrefix(splitPrefix, expansionSeparators))
                }
            } else {
                // Expand single prefix
                expanded.addAll(expandSinglePrefix(cleanedPrefix, expansionSeparators))
            }
        }
        
        return expanded.distinct()
    }
    
    /**
     * Expands a single prefix into separator variations using the provided expansion separators.
     * If the prefix already ends with one of the expansion separators, it is used as-is without further expansion.
     */
    private fun expandSinglePrefix(prefix: String, expansionSeparators: List<String>): List<String> {
        // Check if prefix already ends with one of the expansion separators
        val alreadyHasSeparator = expansionSeparators.any { separator ->
            prefix.endsWith(separator, ignoreCase = false)
        }
        
        // If it already has a separator, use it as-is without expansion
        if (alreadyHasSeparator) {
            return listOf(prefix)
        }
        
        // Otherwise, expand with all separator variations
        return expansionSeparators.map { separator ->
            "$prefix$separator"
        }
    }
}

