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

    fun searchContent(title: String, year: Int?, contentType: ContentType?, useArticleVariations: Boolean = true): List<IptvContentEntity> {
        val normalizedTitle = normalizeTitle(title)
        
        // Get currently configured providers sorted by priority (lower number = higher priority)
        val configuredProviders = iptvConfigurationService.getProviderConfigurations()
        val configuredProviderNames = configuredProviders.map { it.name }.toSet()
        // Create a map of provider name to priority for sorting results
        val providerPriorityMap = configuredProviders.associate { it.name to it.priority }
        
        // Try language prefixes first if configured
        val languagePrefixes = iptvConfigurationProperties.languagePrefixes
        logger.debug("Configured language prefixes: $languagePrefixes (count: ${languagePrefixes.size})")
        
        if (languagePrefixes.isNotEmpty()) {
            for (prefix in languagePrefixes) {
                val cleanedPrefix = stripQuotes(prefix)
                
                // Try the title as-is first
                val titleVariations = mutableListOf(title)
                
                // Only add article variations if useArticleVariations is true
                // This handles cases where IPTV content has "The Breakfast Club" but search query is "Breakfast Club"
                // Skip article variations when metadata is provided (e.g., from IMDB ID) as it should be accurate
                if (useArticleVariations && !title.matches(Regex("^(?i)(the|a|an)\\s+.*"))) {
                    titleVariations.add("The $title")
                    titleVariations.add("A $title")
                    titleVariations.add("An $title")
                }
                
                for (titleVariation in titleVariations) {
                    val prefixedTitle = normalizeTitle("$cleanedPrefix$titleVariation")
                    logger.debug("Trying prefix '$cleanedPrefix' (original: '$prefix') with title '$titleVariation' -> normalized: '$prefixedTitle'")
                    
                    // Use word boundary matching to prevent partial word matches
                    val prefixedResults = if (contentType != null) {
                        iptvContentRepository.findByNormalizedTitleWordBoundaryAndContentType(prefixedTitle, contentType)
                    } else {
                        iptvContentRepository.findByNormalizedTitleWordBoundary(prefixedTitle)
                    }
                    
                    val filteredPrefixedResults = prefixedResults.filter { it.providerName in configuredProviderNames }
                    
                    logger.debug("Prefix '$cleanedPrefix' with title '$titleVariation' returned ${filteredPrefixedResults.size} results (before filtering: ${prefixedResults.size})")
                    
                    if (filteredPrefixedResults.isNotEmpty()) {
                        logger.debug("Found ${filteredPrefixedResults.size} results with prefix '$cleanedPrefix' for title '$titleVariation', returning early")
                        // Filter by year if provided, then sort by relevance and provider priority
                        // Use original normalized title (without prefix) for scoring relevance
                        val yearFiltered = filterByYear(filteredPrefixedResults, year)
                        return sortByRelevanceAndProviderPriority(yearFiltered, normalizedTitle, year, providerPriorityMap)
                    }
                }
            }
            logger.debug("No results found with any language prefix, falling back to search without prefix")
        } else {
            logger.debug("No language prefixes configured, searching without prefix")
        }
        
        // Fallback to search without prefix - search by title only (year excluded from search)
        // First try word boundary matching to prevent partial word matches (e.g., "twister" won't match "twisters")
        val results = mutableListOf<IptvContentEntity>()
        
        // Try word boundary matching first
        val wordBoundaryResults = if (contentType != null) {
            iptvContentRepository.findByNormalizedTitleWordBoundaryAndContentType(normalizedTitle, contentType)
        } else {
            iptvContentRepository.findByNormalizedTitleWordBoundary(normalizedTitle)
        }
        results.addAll(wordBoundaryResults)
        
        // If no results with word boundary matching, try fuzzy matching with LIKE
        // This handles cases where the title might have articles or other words in between
        // For example: searching "breakfast club" should match "en the breakfast club"
        // The fuzzy search will catch article variations automatically, so we don't need to try them explicitly
        if (results.isEmpty()) {
            logger.debug("Word boundary search returned 0 results, trying fuzzy LIKE search for: $normalizedTitle")
            val fuzzyResults = if (contentType != null) {
                iptvContentRepository.findByNormalizedTitleContainingAndContentType(normalizedTitle, contentType)
            } else {
                iptvContentRepository.findByNormalizedTitleContaining(normalizedTitle)
            }
            
            // Filter fuzzy results to only include those that contain the search terms as whole words/phrases
            // This prevents partial matches like "breakfast" matching "breakfasting"
            // This also handles article variations automatically (e.g., "breakfast club" matches "the breakfast club")
            val filteredFuzzyResults = fuzzyResults.filter { entity ->
                val entityTitle = entity.normalizedTitle ?: ""
                // Check if all words from the search query appear as whole words in the entity title
                val searchWords = normalizedTitle.split("\\s+".toRegex()).filter { it.isNotBlank() }
                searchWords.all { word ->
                    // Match whole word boundaries: word at start, end, or surrounded by spaces/non-word chars
                    // Use word boundary regex that works in Kotlin
                    Regex("(^|[^a-z0-9])$word([^a-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(entityTitle)
                }
            }
            
            results.addAll(filteredFuzzyResults)
        }
        
        // Remove duplicates (same entity might match multiple variations)
        val uniqueResults = results.distinctBy { it.id }
        
        // Filter to only include content from currently configured providers
        val filteredResults = uniqueResults.filter { it.providerName in configuredProviderNames }
        
        // Filter by year if provided, then sort by relevance and provider priority
        val yearFiltered = filterByYear(filteredResults, year)
        return sortByRelevanceAndProviderPriority(yearFiltered, normalizedTitle, year, providerPriorityMap)
    }
    
    /**
     * Sorts search results by relevance score (higher is better) first, then by provider priority.
     * Relevance scoring prioritizes exact matches and closer matches.
     * Optionally limits results based on configuration.
     */
    private fun sortByRelevanceAndProviderPriority(
        results: List<IptvContentEntity>,
        searchTitle: String,
        searchYear: Int?,
        providerPriorityMap: Map<String, Int>
    ): List<IptvContentEntity> {
        if (results.isEmpty()) {
            return results
        }
        
        val scoredResults = results.map { entity ->
            val score = calculateRelevanceScore(entity, searchTitle, searchYear)
            logger.debug("Relevance score for '${entity.title}': $score (search: '$searchTitle', year: $searchYear)")
            Pair(entity, score)
        }
        
        val sorted = scoredResults.sortedWith(compareByDescending<Pair<IptvContentEntity, Int>> { it.second }
            .thenBy { entity ->
                // Secondary sort by provider priority (lower number = higher priority)
                providerPriorityMap[entity.first.providerName] ?: Int.MAX_VALUE
            })
        
        val maxResults = iptvConfigurationProperties.maxSearchResults
        val limitedResults = if (maxResults > 0 && sorted.size > maxResults) {
            logger.debug("Limiting results from ${sorted.size} to $maxResults (top scores: ${sorted.take(maxResults).joinToString { "${it.first.title}=${it.second}" }})")
            sorted.take(maxResults)
        } else {
            logger.debug("Sorted ${sorted.size} results by relevance score (top 3 scores: ${sorted.take(3).joinToString { "${it.first.title}=${it.second}" }})")
            sorted
        }
        
        return limitedResults.map { it.first }
    }
    
    /**
     * Calculates a relevance score for a search result.
     * Higher scores indicate better matches.
     * 
     * Scoring factors:
     * - Exact normalized title match: 1000 points
     * - Title starts with search query: 500 points
     * - Title contains search query as whole words: 300 points
     * - Title contains all search words: 100 points
     * - Year match bonus: +200 points (if year provided and matches)
     * - Year mismatch penalty: -100 points (if year provided but doesn't match)
     */
    private fun calculateRelevanceScore(entity: IptvContentEntity, searchTitle: String, searchYear: Int?): Int {
        var entityNormalizedTitle = entity.normalizedTitle ?: ""
        
        // Strip language prefixes from entity title for comparison
        // Language prefixes are typically in format "en " or "nl| " at the start
        val languagePrefixPattern = Regex("^[a-z]{2,3}\\s*[|\\-]?\\s+", RegexOption.IGNORE_CASE)
        entityNormalizedTitle = languagePrefixPattern.replace(entityNormalizedTitle, "").trim()
        
        var score = 0
        
        // Exact match (highest priority)
        if (entityNormalizedTitle == searchTitle) {
            score += 1000
        }
        // Starts with search query
        else if (entityNormalizedTitle.startsWith(searchTitle)) {
            score += 500
            // Bonus for shorter titles (more specific matches)
            // If title is just the search query plus a small amount, give bonus
            val remainingAfterSearch = entityNormalizedTitle.removePrefix(searchTitle).trim()
            if (remainingAfterSearch.isEmpty() || remainingAfterSearch.length <= 10) {
                score += 50 // Bonus for very close matches
            }
        }
        // Contains search query as whole phrase (word boundary match)
        else {
            val searchWords = searchTitle.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val allWordsMatch = searchWords.all { word ->
                Regex("(^|[^a-z0-9])$word([^a-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(entityNormalizedTitle)
            }
            
            if (allWordsMatch) {
                // Check if words appear in order as a phrase
                val searchPhrase = searchWords.joinToString("\\s+")
                if (Regex(searchPhrase, RegexOption.IGNORE_CASE).containsMatchIn(entityNormalizedTitle)) {
                    score += 300 // Whole phrase match
                } else {
                    score += 100 // All words present but not as phrase
                }
            }
        }
        
        // Year match bonus/penalty
        if (searchYear != null) {
            val entityYear = extractYearFromTitle(entity.title)
            when {
                entityYear == searchYear -> score += 200 // Year match bonus
                entityYear != null -> score -= 100 // Year mismatch penalty
                // No year in entity title: no penalty (neutral)
            }
        }
        
        return score
    }
    
    /**
     * Sorts search results by provider priority (lower priority number = higher priority).
     * Results from providers with lower priority numbers will appear first.
     * @deprecated Use sortByRelevanceAndProviderPriority instead for better search accuracy
     */
    @Deprecated("Use sortByRelevanceAndProviderPriority for better search accuracy")
    private fun sortByProviderPriority(results: List<IptvContentEntity>, providerPriorityMap: Map<String, Int>): List<IptvContentEntity> {
        return results.sortedBy { entity ->
            // Get priority for this provider, default to Int.MAX_VALUE if not found (shouldn't happen)
            providerPriorityMap[entity.providerName] ?: Int.MAX_VALUE
        }
    }
    
    /**
     * Filters search results by year if year is provided.
     * Year filtering is optional and follows these rules:
     * - If no year is provided, return all results
     * - If year is provided:
     *   - Include results with matching year
     *   - Include results with no year
     *   - Exclude results with non-matching year ONLY if there are results with matching year or no year
     *   - If ALL results have non-matching years (and none have matching year or no year), return all results
     */
    private fun filterByYear(results: List<IptvContentEntity>, year: Int?): List<IptvContentEntity> {
        if (year == null) {
            logger.debug("No year filter specified, returning all ${results.size} results")
            return results
        }
        
        logger.debug("Filtering ${results.size} results by year: $year")
        
        // Categorize results by year match status
        val resultsWithMatchingYear = mutableListOf<IptvContentEntity>()
        val resultsWithNoYear = mutableListOf<IptvContentEntity>()
        val resultsWithNonMatchingYear = mutableListOf<IptvContentEntity>()
        
        results.forEach { entity ->
            val extractedYear = extractYearFromTitle(entity.title)
            when {
                extractedYear == null -> {
                    resultsWithNoYear.add(entity)
                    logger.debug("Result '${entity.title}' has no year - will be included")
                }
                extractedYear == year -> {
                    resultsWithMatchingYear.add(entity)
                    logger.debug("Result '${entity.title}' (year: $extractedYear) matches filter year: $year - will be included")
                }
                else -> {
                    resultsWithNonMatchingYear.add(entity)
                    logger.debug("Result '${entity.title}' (year: $extractedYear) does not match filter year: $year - may be excluded")
                }
            }
        }
        
        // Determine which results to return
        val filtered = when {
            // If we have results with matching year or no year, exclude non-matching year results
            resultsWithMatchingYear.isNotEmpty() || resultsWithNoYear.isNotEmpty() -> {
                val included = resultsWithMatchingYear + resultsWithNoYear
                logger.info("Year filter: Found ${resultsWithMatchingYear.size} results with matching year and ${resultsWithNoYear.size} results with no year. Excluding ${resultsWithNonMatchingYear.size} results with non-matching year.")
                included
            }
            // If ALL results have non-matching years, return all results (year filter is optional)
            else -> {
                logger.info("Year filter: All ${results.size} results have non-matching years. Returning all results (year filter is optional).")
                results
            }
        }
        
        logger.info("Year filter returned ${filtered.size} results (from ${results.size} total)")
        return filtered
    }
    
    /**
     * Extracts year from a title string.
     * Handles formats like:
     * - "Title (1996)"
     * - "Title 1996"
     * - "Title (1996) Extra"
     * Returns null if no valid year is found.
     */
    private fun extractYearFromTitle(title: String): Int? {
        // Try to match year in parentheses first: "Title (1996)"
        val parenthesesPattern = Regex("""\((\d{4})\)""")
        val parenthesesMatch = parenthesesPattern.find(title)
        if (parenthesesMatch != null) {
            return parenthesesMatch.groupValues[1].toIntOrNull()
        }
        
        // Try to match year as standalone 4-digit number: "Title 1996" (but not "Title 1996-1997")
        // Look for 4-digit years that are likely release years (1900-2099)
        val yearPattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        val yearMatch = yearPattern.find(title)
        if (yearMatch != null) {
            return yearMatch.groupValues[1].toIntOrNull()
        }
        
        return null
    }

    fun findExactMatch(title: String, contentType: ContentType?): IptvContentEntity? {
        val normalizedTitle = normalizeTitle(title)
        val candidates = searchContent(normalizedTitle, null, contentType)
        
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

