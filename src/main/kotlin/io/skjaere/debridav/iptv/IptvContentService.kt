package io.skjaere.debridav.iptv

import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.repository.DebridFileContentsRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI

@Service
class IptvContentService(
    private val iptvContentRepository: IptvContentRepository,
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val iptvSyncHashRepository: IptvSyncHashRepository,
    private val iptvSeriesMetadataRepository: IptvSeriesMetadataRepository,
    private val iptvMovieMetadataRepository: IptvMovieMetadataRepository,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val debridFileContentsRepository: DebridFileContentsRepository,
    private val databaseFileService: DatabaseFileService
) {
    private val logger = LoggerFactory.getLogger(IptvContentService::class.java)

    @PostConstruct
    fun logLanguagePrefixes() {
        val indexedPrefixes = iptvConfigurationProperties.languagePrefixesIndex
        val commaSeparatedPrefixes = iptvConfigurationProperties.languagePrefixes
        val combinedPrefixes = iptvConfigurationProperties.combinedLanguagePrefixes
        val expandedPrefixes = iptvConfigurationProperties.expandedLanguagePrefixes
        
        if (combinedPrefixes.isNotEmpty()) {
            logger.info("IPTV language prefixes configured:")
            if (indexedPrefixes.isNotEmpty()) {
                logger.info("  Indexed prefixes: $indexedPrefixes (count: ${indexedPrefixes.size})")
            }
            if (commaSeparatedPrefixes.isNotEmpty()) {
                logger.info("  Comma-separated prefixes: $commaSeparatedPrefixes (count: ${commaSeparatedPrefixes.size})")
            }
            logger.info("  Combined prefixes: $combinedPrefixes (count: ${combinedPrefixes.size})")
            logger.info("  Expanded language prefixes: $expandedPrefixes (count: ${expandedPrefixes.size})")
            combinedPrefixes.forEachIndexed { index, prefix ->
                val cleaned = stripQuotes(prefix)
                logger.info("  [$index] Original: '$prefix' -> Cleaned: '$cleaned'")
            }
        } else {
            logger.debug("No IPTV language prefixes configured")
        }
    }
    
    @PostConstruct
    fun logLiveFilteringConfiguration() {
        if (!iptvConfigurationProperties.enabled) {
            return
        }
        
        val providerConfigs = iptvConfigurationService.getProviderConfigurations()
            .filter { it.liveSyncEnabled }
        
        if (providerConfigs.isEmpty()) {
            logger.debug("No IPTV providers with live sync enabled - skipping live filtering configuration log")
            return
        }
        
        providerConfigs.forEach { providerConfig ->
            val hasDbCategoryExclude = providerConfig.liveDbCategoryExclude?.isNotEmpty() == true || providerConfig.liveDbCategoryExcludeIndex?.isNotEmpty() == true
            val hasDbChannelExclude = providerConfig.liveDbChannelExclude?.isNotEmpty() == true || providerConfig.liveDbChannelExcludeIndex?.isNotEmpty() == true
            val hasCategoryInclude = providerConfig.liveCategoryInclude?.isNotEmpty() == true || providerConfig.liveCategoryIncludeIndex?.isNotEmpty() == true
            val hasCategoryExclude = providerConfig.liveCategoryExclude?.isNotEmpty() == true || providerConfig.liveCategoryExcludeIndex?.isNotEmpty() == true
            val hasChannelInclude = providerConfig.liveChannelInclude?.isNotEmpty() == true || providerConfig.liveChannelIncludeIndex?.isNotEmpty() == true
            val hasChannelExclude = providerConfig.liveChannelExclude?.isNotEmpty() == true || providerConfig.liveChannelExcludeIndex?.isNotEmpty() == true
            
            if (hasDbCategoryExclude || hasDbChannelExclude || hasCategoryInclude || hasCategoryExclude || hasChannelInclude || hasChannelExclude) {
                logger.info("IPTV Live filtering configuration for provider '${providerConfig.name}':")
                
                // Database filtering (exclude only)
                if (hasDbCategoryExclude) {
                    val combined = mutableListOf<String>()
                    providerConfig.liveDbCategoryExcludeIndex?.let { combined.addAll(it) }
                    providerConfig.liveDbCategoryExclude?.let { combined.addAll(it) }
                    logger.info("  Database category exclude patterns: $combined (count: ${combined.size})")
                    combined.forEachIndexed { index, pattern ->
                        logger.info("    [$index] '$pattern'")
                    }
                }
                
                if (hasDbChannelExclude) {
                    val combined = mutableListOf<String>()
                    providerConfig.liveDbChannelExcludeIndex?.let { combined.addAll(it) }
                    providerConfig.liveDbChannelExclude?.let { combined.addAll(it) }
                    logger.info("  Database channel exclude patterns: $combined (count: ${combined.size})")
                    combined.forEachIndexed { index, pattern ->
                        logger.info("    [$index] '$pattern'")
                    }
                }
                
                // VFS filtering (include/exclude)
                if (hasCategoryInclude) {
                    val combined = mutableListOf<String>()
                    providerConfig.liveCategoryIncludeIndex?.let { combined.addAll(it) }
                    providerConfig.liveCategoryInclude?.let { combined.addAll(it) }
                    logger.info("  VFS category include patterns: $combined (count: ${combined.size})")
                    combined.forEachIndexed { index, pattern ->
                        logger.info("    [$index] '$pattern'")
                    }
                }
                
                if (hasCategoryExclude) {
                    val combined = mutableListOf<String>()
                    providerConfig.liveCategoryExcludeIndex?.let { combined.addAll(it) }
                    providerConfig.liveCategoryExclude?.let { combined.addAll(it) }
                    logger.info("  VFS category exclude patterns: $combined (count: ${combined.size})")
                    combined.forEachIndexed { index, pattern ->
                        logger.info("    [$index] '$pattern'")
                    }
                }
                
                if (hasChannelInclude) {
                    val combined = mutableListOf<String>()
                    providerConfig.liveChannelIncludeIndex?.let { combined.addAll(it) }
                    providerConfig.liveChannelInclude?.let { combined.addAll(it) }
                    logger.info("  VFS channel include patterns: $combined (count: ${combined.size})")
                    combined.forEachIndexed { index, pattern ->
                        logger.info("    [$index] '$pattern'")
                    }
                }
                
                if (hasChannelExclude) {
                    val combined = mutableListOf<String>()
                    providerConfig.liveChannelExcludeIndex?.let { combined.addAll(it) }
                    providerConfig.liveChannelExclude?.let { combined.addAll(it) }
                    logger.info("  VFS channel exclude patterns: $combined (count: ${combined.size})")
                    combined.forEachIndexed { index, pattern ->
                        logger.info("    [$index] '$pattern'")
                    }
                }
            } else {
                logger.debug("No IPTV Live filtering configured for provider '${providerConfig.name}'")
            }
        }
    }

    fun searchContent(title: String, year: Int?, contentType: ContentType?, useArticleVariations: Boolean = true): List<IptvContentEntity> {
        val normalizedTitle = normalizeTitle(title)
        
        // Get currently configured providers sorted by priority (lower number = higher priority)
        val configuredProviders = iptvConfigurationService.getProviderConfigurations()
        val configuredProviderNames = configuredProviders.map { it.name }.toSet()
        // Create a map of provider name to priority for sorting results
        val providerPriorityMap = configuredProviders.associate { it.name to it.priority }
        
        // Search by title only (year excluded from search)
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
        
        // Filter by year if provided, then filter spin-offs, then sort by relevance and provider priority
        // Note: For now, we only pass year (startYear) - year range support can be added later if needed
        val yearFiltered = filterByYear(filteredResults, year)
        val spinOffFiltered = filterSpinOffs(yearFiltered, normalizedTitle)
        val finalResults = sortByRelevanceAndProviderPriority(spinOffFiltered, normalizedTitle, year, providerPriorityMap)
        
        // Log search results summary at INFO level
        if (finalResults.isEmpty()) {
            logger.info("IPTV search returned 0 results for title='$title'${year?.let { ", year=$it" } ?: ""}${contentType?.let { ", type=$it" } ?: ""}")
        } else {
            logger.debug("IPTV search returned ${finalResults.size} results for title='$title'${year?.let { ", year=$it" } ?: ""}${contentType?.let { ", type=$it" } ?: ""}")
        }
        
        return finalResults
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
            logger.trace("Relevance score for '${entity.title}': $score (search: '$searchTitle', year: $searchYear)")
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
     * Filters out spin-off titles that contain non-alphanumeric characters like ':' or '-' after the main title.
     * Spin-offs are typically indicated by these characters (e.g., "Dexter: New Blood", "Dexter - Resurrection").
     * 
     * Filtering rules:
     * - If there are results without spin-off indicators, exclude those with spin-off indicators
     * - If ALL results have spin-off indicators, return all results (don't filter everything out)
     * - Language prefixes are stripped before checking for spin-off indicators
     */
    private fun filterSpinOffs(results: List<IptvContentEntity>, searchTitle: String): List<IptvContentEntity> {
        if (results.isEmpty()) {
            return results
        }
        
        logger.debug("Filtering ${results.size} results for spin-offs (search title: '$searchTitle')")
        
        // Characters that typically indicate spin-offs when appearing after the main title
        val spinOffIndicators = listOf(':', '-', '–', '—')
        
        // Categorize results by spin-off status
        val resultsWithoutSpinOffs = mutableListOf<IptvContentEntity>()
        val resultsWithSpinOffs = mutableListOf<IptvContentEntity>()
        
        results.forEach { entity ->
            // Remove language prefixes before checking for spin-off indicators
            val titleWithoutPrefix = removeLanguagePrefixesForSpinOffCheck(entity.title)
            
            // Check if title contains spin-off indicators after the main title
            // Look for patterns like "Title: Subtitle" or "Title - Subtitle"
            val hasSpinOffIndicator = spinOffIndicators.any { indicator ->
                // Check if indicator appears after the main title (not at the very beginning)
                val indicatorIndex = titleWithoutPrefix.indexOf(indicator)
                if (indicatorIndex > 0) {
                    // Make sure it's not part of a year pattern like "(2006-2013)" or "2006-2013"
                    val beforeIndicator = titleWithoutPrefix.substring(0, indicatorIndex).trim()
                    val afterIndicator = titleWithoutPrefix.substring(indicatorIndex + 1).trim()
                    
                    // Skip if it's a year range pattern (e.g., "2006-2013" or "(2006-2013)")
                    val yearRangePattern = Regex("""\d{4}[-–—]\d{4}""")
                    if (yearRangePattern.containsMatchIn(titleWithoutPrefix)) {
                        false
                    } else {
                        // Check if there's meaningful content after the indicator (not just whitespace)
                        afterIndicator.isNotBlank() && afterIndicator.length > 2
                    }
                } else {
                    false
                }
            }
            
            if (hasSpinOffIndicator) {
                resultsWithSpinOffs.add(entity)
                logger.trace("Result '${entity.title}' detected as spin-off (contains spin-off indicator)")
            } else {
                resultsWithoutSpinOffs.add(entity)
                logger.trace("Result '${entity.title}' is not a spin-off")
            }
        }
        
        // Determine which results to return
        val filtered = when {
            // If we have results without spin-offs, exclude those with spin-offs
            resultsWithoutSpinOffs.isNotEmpty() -> {
                logger.debug("Spin-off filter: Found ${resultsWithoutSpinOffs.size} results without spin-offs and ${resultsWithSpinOffs.size} results with spin-offs. Excluding spin-offs.")
                resultsWithoutSpinOffs
            }
            // If ALL results have spin-off indicators, return all results (don't filter everything out)
            else -> {
                logger.debug("Spin-off filter: All ${results.size} results have spin-off indicators. Returning all results.")
                results
            }
        }
        
        logger.debug("Spin-off filter returned ${filtered.size} results (from ${results.size} total)")
        return filtered
    }
    
    /**
     * Removes language prefixes from title for spin-off detection.
     * Handles patterns like "EN| ", "EN - ", "NL| ", etc.
     */
    private fun removeLanguagePrefixesForSpinOffCheck(title: String): String {
        // First try configured language prefixes
        val languagePrefixes = iptvConfigurationProperties.expandedLanguagePrefixes
        for (prefix in languagePrefixes) {
            val cleanedPrefix = stripQuotes(prefix)
            if (title.startsWith(cleanedPrefix, ignoreCase = false)) {
                return title.removePrefix(cleanedPrefix).trimStart()
            }
        }
        
        // Fallback to regex pattern for language codes
        val languagePrefixPattern = Regex("^[A-Z]{2,}\\s*[|\\-]\\s+", RegexOption.IGNORE_CASE)
        return languagePrefixPattern.replace(title, "").trimStart()
    }
    
    /**
     * Filters search results by year if year is provided.
     * Year filtering is optional and follows these rules:
     * - If no year is provided, return all results
     * - If year is provided:
     *   - Include results with matching year OR results that fall within year range (if endYear provided)
     *   - Include results with no year
     *   - Exclude results with non-matching year ONLY if there are results with matching year or no year
     *   - If ALL results have non-matching years (and none have matching year or no year), return all results
     */
    private fun filterByYear(results: List<IptvContentEntity>, year: Int?, startYear: Int? = null, endYear: Int? = null): List<IptvContentEntity> {
        val searchStartYear = startYear ?: year
        if (searchStartYear == null) {
            logger.debug("No year filter specified, returning all ${results.size} results")
            return results
        }
        
        val yearRangeStr = if (endYear != null) "$searchStartYear–$endYear" else "$searchStartYear"
        logger.debug("Filtering ${results.size} results by year: $yearRangeStr")
        
        // Categorize results by year match status
        val resultsWithMatchingYear = mutableListOf<IptvContentEntity>()
        val resultsWithNoYear = mutableListOf<IptvContentEntity>()
        val resultsWithNonMatchingYear = mutableListOf<IptvContentEntity>()
        
        results.forEach { entity ->
            val extractedYear = extractYearFromTitle(entity.title)
            when {
                extractedYear == null -> {
                    resultsWithNoYear.add(entity)
                    logger.trace("Result '${entity.title}' has no year - will be included")
                }
                extractedYear == searchStartYear -> {
                    // Exact match
                    resultsWithMatchingYear.add(entity)
                    logger.trace("Result '${entity.title}' (year: $extractedYear) matches filter year: $searchStartYear - will be included")
                }
                endYear != null && extractedYear >= searchStartYear && extractedYear <= endYear -> {
                    // Falls within year range (extractedYear is guaranteed to be non-null here since first branch handles null)
                    resultsWithMatchingYear.add(entity)
                    logger.trace("Result '${entity.title}' (year: $extractedYear) falls within year range $yearRangeStr - will be included")
                }
                else -> {
                    resultsWithNonMatchingYear.add(entity)
                    logger.trace("Result '${entity.title}' (year: $extractedYear) does not match filter year: $yearRangeStr - may be excluded")
                }
            }
        }
        
        // Determine which results to return
        val filtered = when {
            // If we have results with matching year or no year, exclude non-matching year results
            resultsWithMatchingYear.isNotEmpty() || resultsWithNoYear.isNotEmpty() -> {
                val included = resultsWithMatchingYear + resultsWithNoYear
                logger.debug("Year filter: Found ${resultsWithMatchingYear.size} results with matching year and ${resultsWithNoYear.size} results with no year. Excluding ${resultsWithNonMatchingYear.size} results with non-matching year.")
                included
            }
            // If ALL results have non-matching years, return all results (year filter is optional)
            else -> {
                logger.debug("Year filter: All ${results.size} results have non-matching years. Returning all results (year filter is optional).")
                results
            }
        }
        
        logger.debug("Year filter returned ${filtered.size} results (from ${results.size} total)")
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
    
    /**
     * Finds files (RemotelyCachedEntity) that are linked to inactive IPTV content items.
     * Returns a map of inactive IPTV content to their linked files.
     */
    fun findFilesLinkedToInactiveContent(): Map<IptvContentEntity, List<RemotelyCachedEntity>> {
        val inactiveContent = iptvContentRepository.findInactiveContent()
        logger.debug("Found ${inactiveContent.size} inactive IPTV content items")
        
        val result = mutableMapOf<IptvContentEntity, List<RemotelyCachedEntity>>()
        
        inactiveContent.forEach { content ->
            val files = debridFileContentsRepository.findByIptvContentRefId(content.id!!)
            if (files.isNotEmpty()) {
                result[content] = files
                logger.debug("Found ${files.size} files linked to inactive content: ${content.title} (id: ${content.id})")
            }
        }
        
        logger.info("Found ${result.size} inactive IPTV content items with linked files (total ${result.values.sumOf { it.size }} files)")
        return result
    }
    
    /**
     * Deletes inactive IPTV content items that are not linked to any files.
     * Also deletes metadata (movie and series) linked to the deleted content items.
     * Returns the number of deleted items.
     */
    @Transactional
    fun deleteInactiveContentWithoutFiles(): Int {
        val inactiveContent = iptvContentRepository.findInactiveContent()
        logger.debug("Found ${inactiveContent.size} inactive IPTV content items to check")
        
        val contentToDelete = mutableListOf<IptvContentEntity>()
        
        inactiveContent.forEach { content ->
            val files = debridFileContentsRepository.findByIptvContentRefId(content.id!!)
            if (files.isEmpty()) {
                contentToDelete.add(content)
            } else {
                logger.debug("Skipping deletion of inactive content ${content.title} (id: ${content.id}) - has ${files.size} linked files")
            }
        }
        
        if (contentToDelete.isNotEmpty()) {
            logger.info("Deleting ${contentToDelete.size} inactive IPTV content items without linked files")
            
            // Group content by provider and content type for efficient metadata deletion
            val contentByProvider = contentToDelete.groupBy { it.providerName }
            
            contentByProvider.forEach { (providerName, contentList) ->
                // Separate movies and series
                val movieIds = contentList.filter { it.contentType == ContentType.MOVIE }.map { it.contentId }
                val seriesIds = contentList.filter { it.contentType == ContentType.SERIES }.map { it.contentId }
                
                // Delete movie metadata
                if (movieIds.isNotEmpty()) {
                    val deletedMovieMetadata = iptvMovieMetadataRepository.deleteByProviderNameAndMovieIds(providerName, movieIds)
                    logger.debug("Deleted $deletedMovieMetadata movie metadata entries for provider $providerName")
                }
                
                // Delete series metadata
                if (seriesIds.isNotEmpty()) {
                    val deletedSeriesMetadata = iptvSeriesMetadataRepository.deleteByProviderNameAndSeriesIds(providerName, seriesIds)
                    logger.debug("Deleted $deletedSeriesMetadata series metadata entries for provider $providerName")
                }
            }
            
            // Delete the content items
            iptvContentRepository.deleteAll(contentToDelete)
            logger.info("Deleted ${contentToDelete.size} inactive IPTV content items and their associated metadata")
        } else {
            logger.info("No inactive IPTV content items to delete (all have linked files)")
        }
        
        return contentToDelete.size
    }
    
    /**
     * Deletes inactive IPTV content items for a specific provider that are not linked to any files.
     * Also deletes metadata (movie and series) linked to the deleted content items.
     * Returns the number of deleted items.
     */
    @Transactional
    fun deleteInactiveContentWithoutFilesForProvider(providerName: String): Int {
        val inactiveContent = iptvContentRepository.findInactiveContent()
            .filter { it.providerName == providerName }
        logger.debug("Found ${inactiveContent.size} inactive IPTV content items for provider $providerName to check")
        
        val contentToDelete = mutableListOf<IptvContentEntity>()
        
        inactiveContent.forEach { content ->
            val files = debridFileContentsRepository.findByIptvContentRefId(content.id!!)
            if (files.isEmpty()) {
                contentToDelete.add(content)
            } else {
                logger.debug("Skipping deletion of inactive content ${content.title} (id: ${content.id}) for provider $providerName - has ${files.size} linked files")
            }
        }
        
        if (contentToDelete.isNotEmpty()) {
            logger.info("Deleting ${contentToDelete.size} inactive IPTV content items without linked files for provider $providerName")
            
            // Separate movies and series
            val movieIds = contentToDelete.filter { it.contentType == ContentType.MOVIE }.map { it.contentId }
            val seriesIds = contentToDelete.filter { it.contentType == ContentType.SERIES }.map { it.contentId }
            
            // Delete movie metadata
            if (movieIds.isNotEmpty()) {
                val deletedMovieMetadata = iptvMovieMetadataRepository.deleteByProviderNameAndMovieIds(providerName, movieIds)
                logger.debug("Deleted $deletedMovieMetadata movie metadata entries for provider $providerName")
            }
            
            // Delete series metadata
            if (seriesIds.isNotEmpty()) {
                val deletedSeriesMetadata = iptvSeriesMetadataRepository.deleteByProviderNameAndSeriesIds(providerName, seriesIds)
                logger.debug("Deleted $deletedSeriesMetadata series metadata entries for provider $providerName")
            }
            
            // Delete the content items
            iptvContentRepository.deleteAll(contentToDelete)
            logger.info("Deleted ${contentToDelete.size} inactive IPTV content items and their associated metadata for provider $providerName")
        } else {
            logger.debug("No inactive IPTV content items to delete for provider $providerName (all have linked files)")
        }
        
        return contentToDelete.size
    }
    
    /**
     * Deletes inactive live categories and their associated live streams for a specific provider.
     * This is called after live sync to clean up categories that were excluded via configuration.
     * 
     * Note: Live streams are deleted even if they have linked files, since live streams are ephemeral
     * and can change at any time (unlike Movies/Series which are stable media files).
     * VFS files linked to live streams are also deleted.
     * 
     * Returns the number of deleted categories and streams.
     */
    @Transactional
    fun deleteInactiveLiveCategoriesAndStreamsForProvider(providerName: String): Pair<Int, Int> {
        // Find inactive live categories for this provider
        val inactiveLiveCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, "live")
            .filter { !it.isActive }
        
        if (inactiveLiveCategories.isEmpty()) {
            logger.debug("No inactive live categories to delete for provider $providerName")
            return Pair(0, 0)
        }
        
        logger.info("Found ${inactiveLiveCategories.size} inactive live categories for provider $providerName, deleting categories and their associated streams")
        
        // Find all live streams linked to these inactive categories
        val categoryIds = inactiveLiveCategories.map { it.id!! }.toSet()
        val liveStreamsToDelete = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE && it.category?.id in categoryIds }
        
        if (liveStreamsToDelete.isEmpty()) {
            logger.info("No live streams found for inactive categories, deleting ${inactiveLiveCategories.size} categories only")
            iptvCategoryRepository.deleteAll(inactiveLiveCategories)
            cleanupEmptyLiveDirectories(providerName, inactiveLiveCategories)
            return Pair(inactiveLiveCategories.size, 0)
        }
        
        logger.info("Found ${liveStreamsToDelete.size} live streams linked to inactive categories for provider $providerName")
        
        // Only check for VFS files if VFS creation is enabled
        // If VFS is disabled, skip file operations entirely to avoid unnecessary database queries
        val vfsEnabled = iptvConfigurationProperties.liveCreateVfsEntries
        logger.debug("VFS entry creation enabled: $vfsEnabled for provider $providerName")
        
        if (vfsEnabled) {
            val streamIds = liveStreamsToDelete.mapNotNull { it.id }.toSet()
            
            if (streamIds.isEmpty()) {
                logger.debug("No stream IDs found, skipping VFS file cleanup")
            } else {
                logger.info("Checking for VFS files linked to ${streamIds.size} streams using bulk query")
                
                // Use bulk query to fetch all files in a single database query (or a few queries if needed)
                // This avoids N+1 query problems - instead of 26,892+ queries, we do 1-2 queries
                val allLinkedFiles = try {
                    // Some databases have limits on IN clause size, so chunk if needed
                    // PostgreSQL typically handles 1000s of values, but we'll be safe with 5000
                    streamIds.chunked(5000).flatMap { chunk ->
                        try {
                            debridFileContentsRepository.findByIptvContentRefIds(chunk)
                        } catch (e: Exception) {
                            logger.warn("Error fetching VFS files for chunk of ${chunk.size} stream IDs", e)
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error fetching VFS files for streams", e)
                    emptyList()
                }
                
                val totalFilesToDelete = allLinkedFiles.size
                
                if (totalFilesToDelete > 0) {
                    logger.info("Found $totalFilesToDelete VFS files linked to ${liveStreamsToDelete.size} streams, deleting them")
                    
                    // Delete VFS files using bulk deleteAll instead of individual hash deletes
                    // This is much more efficient and reduces CPU usage
                    // Delete in batches to avoid huge transactions
                    allLinkedFiles.chunked(500).forEachIndexed { batchIndex, fileBatch ->
                        try {
                            // Use deleteAll for bulk deletion - much more efficient than individual deletes
                            debridFileContentsRepository.deleteAll(fileBatch)
                            logger.info("Deleted batch ${batchIndex + 1}: ${fileBatch.size} VFS files (total: ${(batchIndex + 1) * 500} out of $totalFilesToDelete)")
                        } catch (e: Exception) {
                            logger.error("Error deleting batch ${batchIndex + 1} of VFS files", e)
                            // Fallback to individual deletes for this batch if bulk delete fails
                            fileBatch.forEach { file ->
                                try {
                                    val hash = file.hash
                                    if (hash != null) {
                                        debridFileContentsRepository.deleteDbEntityByHash(hash)
                                    }
                                } catch (e2: Exception) {
                                    logger.warn("Failed to delete VFS file with hash ${file.hash}", e2)
                                }
                            }
                        }
                    }
                    logger.info("Deleted $totalFilesToDelete VFS files linked to inactive live streams")
                } else {
                    logger.info("No VFS files found for inactive live streams, skipping file deletion")
                }
            }
        } else {
            logger.info("Skipping VFS file cleanup - VFS entry creation is disabled (IPTV_LIVE_CREATE_VFS_ENTRIES=false)")
        }
        
        // Delete all live streams linked to inactive categories in batches to avoid CPU spikes
        logger.info("Deleting ${liveStreamsToDelete.size} live streams linked to inactive categories for provider $providerName")
        
        // Delete streams in batches to avoid overwhelming the database and reduce CPU usage
        liveStreamsToDelete.chunked(1000).forEachIndexed { batchIndex, batch ->
            try {
                iptvContentRepository.deleteAll(batch)
                logger.info("Deleted batch ${batchIndex + 1}: ${batch.size} live streams (total: ${(batchIndex + 1) * 1000} out of ${liveStreamsToDelete.size})")
            } catch (e: Exception) {
                logger.error("Error deleting batch ${batchIndex + 1} of live streams", e)
                throw e // Re-throw to fail the transaction
            }
        }
        
        // Delete the inactive categories
        iptvCategoryRepository.deleteAll(inactiveLiveCategories)
        logger.info("Deleted ${inactiveLiveCategories.size} inactive live categories and ${liveStreamsToDelete.size} associated live streams for provider $providerName")
        
        // Clean up empty VFS directories for deleted categories
        cleanupEmptyLiveDirectories(providerName, inactiveLiveCategories)
        
        return Pair(inactiveLiveCategories.size, liveStreamsToDelete.size)
    }
    
    /**
     * Cleans up empty VFS directories for inactive live categories.
     * Removes empty category directories under /live/{provider}/{category} structure.
     */
    private fun cleanupEmptyLiveDirectories(providerName: String, deletedCategories: List<IptvCategoryEntity>) {
        if (deletedCategories.isEmpty()) {
            return
        }
        
        val sanitizedProviderName = sanitizeFileName(providerName)
        val liveBasePath = "/live/$sanitizedProviderName"
        
        deletedCategories.forEach { category ->
            try {
                val sanitizedCategoryName = sanitizeFileName(category.categoryName)
                val categoryPath = "$liveBasePath/$sanitizedCategoryName"
                
                // Get the category directory
                val categoryDir = debridFileContentsRepository.getDirectoryByPath(categoryPath)
                if (categoryDir != null) {
                    // Check if directory is empty (no files or subdirectories)
                    val children = debridFileContentsRepository.getByDirectory(categoryDir)
                    if (children.isEmpty()) {
                        logger.info("Deleting empty live category directory: $categoryPath")
                        databaseFileService.deleteFile(categoryDir)
                    } else {
                        logger.debug("Category directory $categoryPath is not empty (${children.size} items), keeping it")
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error cleaning up empty directory for category ${category.categoryName}", e)
            }
        }
        
        // Also check if provider directory is empty after cleanup
        try {
            val providerDir = debridFileContentsRepository.getDirectoryByPath(liveBasePath)
            if (providerDir != null) {
                val children = debridFileContentsRepository.getByDirectory(providerDir)
                if (children.isEmpty()) {
                    logger.info("Deleting empty live provider directory: $liveBasePath")
                    databaseFileService.deleteFile(providerDir)
                }
            }
        } catch (e: Exception) {
            logger.warn("Error checking if provider directory is empty: $liveBasePath", e)
        }
    }
    
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
     * Deletes inactive Movies and Series categories and their associated content for a specific provider.
     * Only deletes categories/content that are not linked to VFS files (those with linked files remain as inactive).
     * 
     * Returns the number of deleted categories and content items.
     */
    @Transactional
    fun deleteInactiveMoviesAndSeriesCategoriesAndContentForProvider(providerName: String): Pair<Int, Int> {
        // Find inactive VOD and Series categories for this provider
        val inactiveVodCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, "vod")
            .filter { !it.isActive }
        val inactiveSeriesCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, "series")
            .filter { !it.isActive }
        
        val allInactiveCategories = inactiveVodCategories + inactiveSeriesCategories
        
        if (allInactiveCategories.isEmpty()) {
            logger.debug("No inactive Movies/Series categories to delete for provider $providerName")
            return Pair(0, 0)
        }
        
        logger.info("Found ${allInactiveCategories.size} inactive Movies/Series categories for provider $providerName, checking for deletion")
        
        // Find all Movies and Series content linked to these inactive categories
        val categoryIds = allInactiveCategories.map { it.id!! }.toSet()
        val contentToCheck = iptvContentRepository.findByProviderName(providerName)
            .filter { 
                (it.contentType == ContentType.MOVIE || it.contentType == ContentType.SERIES) && 
                it.category?.id in categoryIds
            }
        
        // Separate content with and without linked files
        val contentToDelete = mutableListOf<IptvContentEntity>()
        val contentToKeep = mutableListOf<IptvContentEntity>()
        
        contentToCheck.forEach { content ->
            val files = debridFileContentsRepository.findByIptvContentRefId(content.id!!)
            if (files.isEmpty()) {
                contentToDelete.add(content)
            } else {
                contentToKeep.add(content)
                logger.debug("Keeping inactive content ${content.title} (id: ${content.id}) for provider $providerName - has ${files.size} linked files")
            }
        }
        
        // Delete content without linked files
        if (contentToDelete.isNotEmpty()) {
            logger.info("Deleting ${contentToDelete.size} Movies/Series content items linked to inactive categories for provider $providerName")
            
            // Separate movies and series for metadata deletion
            val movieIds = contentToDelete.filter { it.contentType == ContentType.MOVIE }.map { it.contentId }
            val seriesIds = contentToDelete.filter { it.contentType == ContentType.SERIES }.map { it.contentId }
            
            // Delete movie metadata
            if (movieIds.isNotEmpty()) {
                val deletedMovieMetadata = iptvMovieMetadataRepository.deleteByProviderNameAndMovieIds(providerName, movieIds)
                logger.debug("Deleted $deletedMovieMetadata movie metadata entries for provider $providerName")
            }
            
            // Delete series metadata
            if (seriesIds.isNotEmpty()) {
                val deletedSeriesMetadata = iptvSeriesMetadataRepository.deleteByProviderNameAndSeriesIds(providerName, seriesIds)
                logger.debug("Deleted $deletedSeriesMetadata series metadata entries for provider $providerName")
            }
            
            iptvContentRepository.deleteAll(contentToDelete)
        }
        
        if (contentToKeep.isNotEmpty()) {
            logger.info("Keeping ${contentToKeep.size} inactive Movies/Series content items (they have linked files) for provider $providerName")
        }
        
        // Only delete categories that have no content left (all content was deleted or category has no content)
        val categoriesToDelete = allInactiveCategories.filter { category ->
            val remainingContent = iptvContentRepository.findByProviderName(providerName)
                .any { 
                    (it.contentType == ContentType.MOVIE || it.contentType == ContentType.SERIES) && 
                    it.category?.id == category.id
                }
            !remainingContent
        }
        
        if (categoriesToDelete.isNotEmpty()) {
            iptvCategoryRepository.deleteAll(categoriesToDelete)
            logger.info("Deleted ${categoriesToDelete.size} inactive Movies/Series categories for provider $providerName")
        }
        
        val totalDeletedCategories = categoriesToDelete.size
        val totalDeletedContent = contentToDelete.size
        
        if (totalDeletedCategories > 0 || totalDeletedContent > 0) {
            logger.info("Deleted $totalDeletedCategories inactive Movies/Series categories and $totalDeletedContent associated content items for provider $providerName")
        }
        
        return Pair(totalDeletedCategories, totalDeletedContent)
    }
}

