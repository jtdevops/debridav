package io.skjaere.debridav.iptv.api

import io.skjaere.debridav.iptv.IptvRequestService
import io.skjaere.debridav.iptv.IptvContentRepository
import io.skjaere.debridav.iptv.IptvSyncHashRepository
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.metadata.MetadataService
import io.skjaere.debridav.iptv.metadata.MetadataConfigurationProperties
import io.skjaere.debridav.iptv.model.ContentType
import java.time.Instant
import java.time.Duration
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress

@RestController
@RequestMapping("/api/iptv")
class IptvApiController(
    private val iptvRequestService: IptvRequestService,
    private val metadataService: MetadataService,
    private val metadataConfigurationProperties: MetadataConfigurationProperties,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvSyncHashRepository: IptvSyncHashRepository,
    private val iptvConfigurationService: IptvConfigurationService
) {
    private val logger = LoggerFactory.getLogger(IptvApiController::class.java)

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) category: String?,
        // New parameters from ARR indexer API
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) qTest: String?,
        @RequestParam(required = false) imdbid: String?,
        @RequestParam(required = false) tmdbid: String?,
        @RequestParam(required = false) traktid: String?,
        @RequestParam(required = false) doubanid: String?,
        @RequestParam(required = false) year: String?,
        @RequestParam(required = false) genre: String?,
        // TV show specific parameters
        @RequestParam(required = false) season: String?,
        @RequestParam(required = false) ep: String?,
        @RequestParam(required = false) episode: String?,
        @RequestParam(required = false) tvdbid: String?,
        @RequestParam(required = false) rid: String?,
        // File size retrieval toggle (from Prowlarr config)
        // Accept as String to handle Prowlarr's format (e.g., ".True", ".False")
        // Note: When Prowlarr disables this option, it sends an empty string, which should be treated as false
        @RequestParam(required = false) fetchFileSize: String?,
        // Maximum number of results to process (from Prowlarr custom field)
        // Accept as String to handle Prowlarr's format
        // If not specified, empty string, or "0", process all results (default behavior)
        @RequestParam(required = false) limit: String?,
        request: HttpServletRequest
    ): ResponseEntity<List<IptvRequestService.IptvSearchResult>> {
        logger.debug("IPTV search request received - query='{}', type='{}', category='{}', fullQueryString='{}'", 
            query, type, category, request.queryString)
        
        // Parse fetchFileSize string to boolean, handling Prowlarr's format (e.g., ".True", ".False")
        // When parameter is missing (null), default to true. When empty string, treat as false (disabled).
        val fetchFileSizeBool = parseBooleanParameter(fetchFileSize, defaultValue = true)
        logger.debug("Parsed fetchFileSize parameter: '{}' -> {}", fetchFileSize, fetchFileSizeBool)
        
        // Resolve hostname from IP address
        val remoteAddr = request.remoteAddr
        val remoteInfo = try {
            val hostname = InetAddress.getByName(remoteAddr).hostName
            if (hostname != remoteAddr) {
                "$remoteAddr/$hostname"
            } else {
                remoteAddr
            }
        } catch (e: Exception) {
            remoteAddr
        }
        logger.debug("Request URI: {}, Method: {}, RemoteAddr: {}", request.requestURI, request.method, remoteInfo)
        
        // Log all request parameters for debugging
        request.parameterMap.forEach { (key, values) ->
            logger.debug("Request parameter: {} = {}", key, values.joinToString(", "))
        }
        
        val contentType = type?.let {
            try {
                ContentType.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid content type '{}', ignoring", it)
                null
            }
        }
        
        // Determine search query following hierarchy:
        // 1. Check ID fields (imdbid first, others for future use)
        // 2. Fallback to 'q' parameter
        // 3. Fallback to 'qTest' parameter (testing data) - ONLY if q and imdbid are NOT provided
        // 4. Fallback to legacy 'query' parameter
        // Note: qTest should only be used when q and imdbid are not provided.
        // If q or imdbid are provided but fail to find results, we don't fall back to qTest.
        
        // Check if this is a test request (only qTest parameter, no q or imdbid)
        // IMPORTANT: When only qTest is provided, this is a connectivity test from Sonarr/Radarr.
        // For connectivity tests, we should:
        // 1. If qTest is an IMDb ID, resolve it to a title (using OMDB API) so we can search IPTV content
        // 2. Query IPTV content using the resolved title (or qTest text if not an IMDb ID)
        // 3. Skip fetching other metadata (resolution, codec, file size, etc.) for the IPTV content results
        // 4. Return only the first valid result (since we're just testing connectivity)
        val hasQTest = qTest?.takeIf { it.isNotBlank() } != null
        val hasQ = q?.takeIf { it.isNotBlank() } != null
        val hasImdbid = imdbid?.takeIf { it.isNotBlank() } != null
        val isTestRequest = hasQTest && !hasQ && !hasImdbid
        logger.debug("Test request detection: qTest='{}' (hasQTest={}), q='{}' (hasQ={}), imdbid='{}' (hasImdbid={}), isTestRequest={}", 
            qTest, hasQTest, q, hasQ, imdbid, hasImdbid, isTestRequest)
        
        val searchQuery = determineSearchQuery(
            imdbid = imdbid,
            tmdbid = tmdbid,
            traktid = traktid,
            doubanid = doubanid,
            q = q,
            qTest = qTest,
            query = query,
            contentType = contentType,
            isTestRequest = isTestRequest
        )
        
        if (searchQuery == null) {
            if (isTestRequest) {
                // For test requests, provide detailed failure reason
                val failureReason = determineFailureReason(qTest, null, contentType)
                logger.warn("Prowlarr test connection failed - no valid search query: qTest='{}', reason={}", qTest, failureReason)
            } else {
                // For non-test requests, provide detailed failure reason
                val failureReason = determineFailureReason(imdbid ?: q ?: query, null, contentType)
                logger.warn("IPTV search failed - no valid search query found: imdbid='{}', q='{}', query='{}', reason={}", 
                    imdbid ?: "N/A", q ?: "N/A", query ?: "N/A", failureReason)
            }
            return ResponseEntity.ok(emptyList())
        }
        
        // Use episode parameter (e.g., "S08" or "S08E01") for magnet title
        // Parse limit parameter: if not specified, empty string, or "0", process all results (default behavior)
        val resultLimit = parseLimitParameter(limit)
        val results = iptvRequestService.searchIptvContent(searchQuery.title, searchQuery.year, contentType, searchQuery.useArticleVariations, episode, searchQuery.startYear, searchQuery.endYear, isTestRequest, fetchFileSizeBool, resultLimit)
        
        // For test requests (connectivity tests), only return the first valid result and skip detailed logging
        // This is a connectivity test - we just need to verify IPTV content can be queried.
        // Note: We may have resolved an IMDb ID to a title, but we skip fetching other metadata (resolution, codec, file size, etc.) for the IPTV content
        val finalResults = if (isTestRequest && results.isNotEmpty()) {
            listOf(results.first())
        } else {
            results
        }
        
        // For test requests, only log if there are no results (WARN level)
        if (isTestRequest) {
            if (finalResults.isEmpty()) {
                // Determine the reason for failure
                val failureReason = determineFailureReason(qTest, searchQuery, contentType)
                logger.warn("Prowlarr test connection failed - no results found: qTest='{}', reason={}", qTest, failureReason)
            }
            // Don't log INFO for test requests with results
        } else {
            // Normal requests: log at INFO level with main details
            val yearInfo = when {
                searchQuery.endYear != null -> "${searchQuery.startYear ?: searchQuery.year}-${searchQuery.endYear}"
                searchQuery.startYear != null -> searchQuery.startYear.toString()
                searchQuery.year != null -> searchQuery.year.toString()
                else -> null
            }
            val episodeInfo = episode?.takeIf { it.isNotBlank() }
            
            if (results.isEmpty()) {
                // No results - use WARN to indicate potential issue
                val failureReason = determineFailureReason(null, searchQuery, contentType)
                logger.warn("IPTV search returned no results: title='{}'{}{}{}, reason={}", 
                    searchQuery.title,
                    yearInfo?.let { ", year=$it" } ?: "",
                    contentType?.let { ", type=$it" } ?: "",
                    episodeInfo?.let { ", episode=$it" } ?: "",
                    failureReason)
            } else {
                // Results found - log at INFO level
                val firstResultTitle = results.first().title
                logger.info("IPTV search completed: title='{}'{}{}{}, results={}, firstResultTitle='{}'", 
                    searchQuery.title,
                    yearInfo?.let { ", year=$it" } ?: "",
                    contentType?.let { ", type=$it" } ?: "",
                    episodeInfo?.let { ", episode=$it" } ?: "",
                    results.size,
                    firstResultTitle                )
            }
        }
        return ResponseEntity.ok(finalResults)
    }
    
    /**
     * Data class to hold search query with title and optional year
     */
    private data class SearchQuery(
        val title: String,
        val year: Int?, // Start year (for backward compatibility)
        val startYear: Int? = null,
        val endYear: Int? = null, // End year if it's a range (e.g., TV series)
        val useArticleVariations: Boolean = false // Whether to use article variations (The, A, An) in search
    )
    
    /**
     * Determines the search query following the hierarchy:
     * 1. Check ID fields (imdbid) - query external API to get title/year
     * 2. Fallback to 'q' parameter
     * 3. Fallback to 'qTest' parameter (only if q and imdbid are NOT provided)
     * 4. Fallback to legacy 'query' parameter
     * 
     * Returns SearchQuery with title and optional year extracted separately
     * 
     * Note: qTest should only be used when q and imdbid are not provided.
     * If q or imdbid are provided but fail to find results, we don't fall back to qTest.
     * 
     * IMPORTANT: When isTestRequest is true (only qTest parameter provided), this is a connectivity test.
     * For connectivity tests:
     * - If qTest is an IMDb ID, we still resolve it to a title (using OMDB API) so we can search IPTV content
     * - Once we have the title, we query IPTV content but skip fetching other metadata (resolution, codec, file size, etc.)
     * - If qTest is text, we use it directly to search IPTV content
     * This ensures we can properly search IPTV content while avoiding unnecessary metadata fetching for connectivity tests.
     * 
     * @param isTestRequest Whether this is a test request (affects logging level and metadata fetching)
     */
    private fun determineSearchQuery(
        imdbid: String?,
        tmdbid: String?,
        traktid: String?,
        doubanid: String?,
        q: String?,
        qTest: String?,
        query: String?,
        contentType: ContentType?,
        isTestRequest: Boolean = false
    ): SearchQuery? {
        // Check if q or imdbid are provided - if so, qTest should not be used
        val hasImdbId = imdbid?.takeIf { it.isNotBlank() } != null
        val hasQ = q?.takeIf { it.isNotBlank() } != null
        
        // Priority 1: Check IMDB ID (and potentially other IDs in the future)
        val imdbId = imdbid?.takeIf { it.isNotBlank() }
        if (imdbId != null) {
            logger.debug("Found IMDB ID: $imdbId, attempting to fetch metadata")
            val metadata = runBlocking {
                metadataService.getMetadataByImdbId(imdbId)
            }
            
            if (metadata != null) {
                logger.info("Resolved IMDb ID '{}' to: '{}' (year: {}{})", 
                    imdbId, metadata.title, metadata.startYear, 
                    metadata.endYear?.let { "-$it" } ?: "")
                // Return title and year separately - we'll search by title only and filter by year
                // Don't use article variations when metadata is provided (useArticleVariations = false)
                return SearchQuery(
                    title = metadata.title,
                    year = metadata.startYear, // Use start year for backward compatibility
                    startYear = metadata.startYear,
                    endYear = metadata.endYear,
                    useArticleVariations = false
                )
            } else {
                logger.warn("Failed to resolve IMDB ID '$imdbId' to metadata. Not using qTest as fallback since imdbid was provided.")
                // If imdbid was provided but failed, don't fall back to qTest
                // Return null to indicate no valid search query
                return null
            }
        }
        
        // Priority 2: Use 'q' parameter
        val qParam = q?.takeIf { it.isNotBlank() }
        if (qParam != null) {
            if (!isTestRequest) {
                logger.info("IPTV search request: query='{}'", qParam)
            } else {
                logger.debug("Using 'q' parameter: '$qParam'")
            }
            return extractTitleAndYear(qParam, useArticleVariations = true)
        }
        
        // Priority 3: Use 'qTest' parameter (testing data) - ONLY if q and imdbid are NOT provided
        // Can be either a text string or an IMDb ID
        // IMPORTANT: When only qTest is provided (isTestRequest = true), this is a connectivity test.
        // For connectivity tests:
        // - If qTest is an IMDb ID, we still need to resolve it to a title (using OMDB API) so we can search IPTV content
        // - Once we find IPTV content, we skip fetching other metadata (resolution, codec, file size, etc.) for that content
        // - If qTest is text, use it directly to search IPTV content
        if (!hasImdbId && !hasQ) {
            val qTestParam = qTest?.takeIf { it.isNotBlank() }
            if (qTestParam != null) {
                // Check if qTest looks like an IMDb ID (starts with "tt" followed by digits)
                // For test requests, we still need to resolve IMDb IDs to titles so we can search IPTV content
                if (isImdbId(qTestParam)) {
                    logger.debug("qTest parameter detected as IMDb ID: '$qTestParam', resolving to title for connectivity test")
                    val metadata = runBlocking {
                        metadataService.getMetadataByImdbId(qTestParam)
                    }
                    
                    if (metadata != null) {
                        logger.debug("Successfully resolved qTest IMDb ID '$qTestParam' to title: '${metadata.title}' (startYear: ${metadata.startYear}, endYear: ${metadata.endYear}) for connectivity test")
                        return SearchQuery(
                            title = metadata.title,
                            year = metadata.startYear,
                            startYear = metadata.startYear,
                            endYear = metadata.endYear,
                            useArticleVariations = false
                        )
                    } else {
                        logger.warn("Failed to resolve qTest IMDb ID '$qTestParam' to metadata, treating as text")
                        // Fall through to treat as text
                    }
                }
                
                // Treat as text string (either not an IMDb ID, or IMDb ID resolution failed)
                if (isTestRequest) {
                    logger.debug("qTest parameter provided for connectivity test: '$qTestParam' (treating as text)")
                } else {
                    logger.debug("Using 'qTest' parameter as text (testing): '$qTestParam'")
                }
                return extractTitleAndYear(qTestParam, useArticleVariations = true)
            }
        } else {
            // q or imdbid were provided, so skip qTest even if it exists
            if (qTest?.takeIf { it.isNotBlank() } != null) {
                logger.debug("qTest parameter provided but ignored since q or imdbid are present")
            }
        }
        
        // Priority 4: Fallback to legacy 'query' parameter
        val queryParam = query?.takeIf { it.isNotBlank() }
        if (queryParam != null) {
            logger.debug("Using legacy 'query' parameter: '$queryParam'")
            return extractTitleAndYear(queryParam, useArticleVariations = true)
        }
        
        return null
    }
    
    /**
     * Checks if a string looks like an IMDb ID.
     * IMDb IDs typically start with "tt" followed by 7-8 digits (e.g., "tt0111161", "tt12345678")
     * 
     * @param value The string to check
     * @return true if it looks like an IMDb ID, false otherwise
     */
    private fun isImdbId(value: String): Boolean {
        // IMDb ID pattern: "tt" followed by 7-8 digits
        val imdbIdPattern = Regex("^tt\\d{7,8}$", RegexOption.IGNORE_CASE)
        return imdbIdPattern.matches(value.trim())
    }
    
    /**
     * Determines the reason why a test request failed to return results.
     * 
     * @param qTest The qTest parameter value (raw input, may be null for non-test requests)
     * @param searchQuery The resolved search query (if any)
     * @param contentType The content type being searched (MOVIE or SERIES)
     * @return A human-readable reason for the failure
     */
    private fun determineFailureReason(qTest: String?, searchQuery: SearchQuery?, contentType: ContentType?): String {
        // If we have a resolved searchQuery, use it to determine the failure reason
        // This handles both test and non-test requests
        if (searchQuery != null) {
            val contentTypeStr = contentType?.name ?: "content"
            val hasAnyContent = iptvContentRepository.count() > 0
            val hasContentByType = contentType?.let { 
                iptvContentRepository.findByContentTypeAndIsActive(it, true).isNotEmpty()
            } ?: hasAnyContent
            
            // Check sync status
            val configuredProviders = iptvConfigurationService.getProviderConfigurations()
            val hasConfiguredProviders = configuredProviders.isNotEmpty()
            val hasSyncHashes = iptvSyncHashRepository.count() > 0
            val mostRecentSync = iptvSyncHashRepository.findMostRecentLastChecked()
            
            // Build sync status message
            val syncStatusMessage = when {
                !hasConfiguredProviders -> "No IPTV providers are configured."
                !hasSyncHashes -> "IPTV sync has not been run yet. Please trigger a sync manually or wait for the scheduled sync."
                mostRecentSync != null -> {
                    val timeSinceSync = Duration.between(mostRecentSync, Instant.now())
                    val timeAgo = when {
                        timeSinceSync.toDays() > 0 -> "${timeSinceSync.toDays()} day(s) ago"
                        timeSinceSync.toHours() > 0 -> "${timeSinceSync.toHours()} hour(s) ago"
                        timeSinceSync.toMinutes() > 0 -> "${timeSinceSync.toMinutes()} minute(s) ago"
                        else -> "just now"
                    }
                    if (!hasContentByType) {
                        "IPTV sync was last run $timeAgo, but no ${contentType?.name ?: "content"} data was synced. The provider may not have ${contentType?.name?.lowercase() ?: "content"} available, or the sync may have failed."
                    } else {
                        "IPTV sync was last run $timeAgo."
                    }
                }
                else -> "IPTV sync status is unknown."
            }
            
            if (!hasContentByType) {
                return "No IPTV ${contentTypeStr} data available to search. $syncStatusMessage"
            } else {
                // Check if the searchQuery title is an IMDb ID (meaning metadata resolution failed)
                if (isImdbId(searchQuery.title)) {
                    val originalId = qTest?.takeIf { isImdbId(it) } ?: searchQuery.title
                    return "Failed to resolve IMDb ID '$originalId' to metadata. The OMDB API may be unavailable, the IMDb ID may be invalid, or there was an error calling the API. Please check OMDB API status and verify the IMDb ID is correct."
                } else {
                    return "IPTV content not found for title '${searchQuery.title}'. IPTV ${contentTypeStr} data exists, but this specific title was not found. The content may not have been synced/imported from IPTV providers yet, or the title may not match exactly. $syncStatusMessage"
                }
            }
        }
        
        // No searchQuery available - check if qTest was provided
        if (qTest == null) {
            return "No search query provided"
        }
        
        // searchQuery is null but qTest is provided - this means the query failed to parse/resolve
        // Check if qTest is an IMDb ID
        if (isImdbId(qTest)) {
            // Check if OMDB API key is configured
            if (metadataConfigurationProperties.omdbApiKey.isBlank()) {
                return "OMDB API key not configured - cannot resolve IMDb ID '$qTest' to title. Please configure 'iptv.metadata.omdb-api-key' in application.properties"
            } else {
                return "Failed to resolve IMDb ID '$qTest' to metadata. The OMDB API may be unavailable or the IMDb ID may be invalid."
            }
        } else {
            // qTest is a text query that failed to parse
            return "Failed to parse search query from qTest='$qTest'"
        }
    }
    
    /**
     * Extracts title and year from a query string.
     * Handles formats like:
     * - "Title (1996)"
     * - "Title 1996"
     * - "Title"
     * 
     * @param query The query string to parse
     * @param useArticleVariations Whether to use article variations (The, A, An) in search
     */
    private fun extractTitleAndYear(query: String, useArticleVariations: Boolean = false): SearchQuery {
        // Try to extract year from patterns like "Title (1996)" or "Title 1996"
        val yearPattern = Regex("""\s*\((\d{4})\)\s*$|\s+(\d{4})\s*$""")
        val match = yearPattern.find(query)
        
        if (match != null) {
            val yearStr = match.groupValues[1].takeIf { it.isNotBlank() } 
                ?: match.groupValues[2].takeIf { it.isNotBlank() }
            val year = yearStr?.toIntOrNull()
            val title = query.substring(0, match.range.first).trim()
            
            logger.debug("Extracted title='$title', year=$year from query='$query'")
            return SearchQuery(title = title, year = year, useArticleVariations = useArticleVariations)
        }
        
        // No year found, return title as-is
        logger.debug("No year found in query='$query', using entire query as title")
        return SearchQuery(title = query.trim(), year = null, useArticleVariations = useArticleVariations)
    }

    @PostMapping("/add")
    fun add(
        @RequestBody request: AddIptvContentRequest
    ): ResponseEntity<String> {
        logger.info("IPTV add request: $request")
        
        val success = iptvRequestService.addIptvContent(
            contentId = request.contentId,
            providerName = request.providerName,
            category = request.category,
            season = request.season,
            episode = request.episode
        )
        
        return if (success) {
            ResponseEntity.ok("ok")
        } else {
            ResponseEntity.unprocessableEntity().body("Failed to add IPTV content")
        }
    }

    @GetMapping("/status")
    fun status(): ResponseEntity<Map<String, Any>> {
        // Return status for compatibility with Prowlarr
        return ResponseEntity.ok(mapOf(
            "status" to "active",
            "version" to "1.0.0"
        ))
    }

    @GetMapping("/list")
    fun list(): ResponseEntity<List<Map<String, Any>>> {
        // Return empty list for now - could be enhanced to list active downloads
        return ResponseEntity.ok(emptyList())
    }

    @PostMapping("/delete")
    fun delete(
        @RequestParam contentId: String,
        @RequestParam providerName: String
    ): ResponseEntity<String> {
        // TODO: Implement deletion if needed
        logger.info("IPTV delete request: iptvProvider=$providerName, contentId=$contentId")
        return ResponseEntity.ok("ok")
    }

    data class AddIptvContentRequest(
        val contentId: String,
        val providerName: String,
        val category: String,
        val season: Int? = null, // Season number for series (e.g., 8)
        val episode: Int? = null // Episode number within season (optional)
    )
    
    /**
     * Parses a boolean parameter from a string value.
     * Handles various formats that Prowlarr might send:
     * - ".True", ".False" (with dot prefix)
     * - "true", "false" (standard boolean strings)
     * - "1", "0" (numeric)
     * - null (parameter missing) -> returns default
     * - Empty string (parameter present but empty, e.g., when disabled in Prowlarr) -> returns false
     * 
     * @param value The string value to parse
     * @param defaultValue The default value to return if parameter is missing (null) or parsing fails
     * @return The parsed boolean value
     */
    private fun parseBooleanParameter(value: String?, defaultValue: Boolean): Boolean {
        // If parameter is completely missing (null), use default
        if (value == null) {
            return defaultValue
        }
        
        // If parameter is present but empty/blank, treat as false (disabled)
        // This handles the case when Prowlarr sends fetchFileSize= (empty) when the option is disabled
        if (value.isBlank()) {
            return false
        }
        
        // Remove any leading/trailing dots or whitespace
        val cleaned = value.trim().removePrefix(".").removeSuffix(".").lowercase()
        
        return when (cleaned) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> {
                logger.warn("Unable to parse boolean parameter value '{}', using default: {}", value, defaultValue)
                defaultValue
            }
        }
    }
    
    /**
     * Parses a limit parameter from a string value.
     * Handles various formats that Prowlarr might send:
     * - null (parameter missing) -> returns null (process all)
     * - Empty string -> returns null (process all)
     * - "0" -> returns null (process all)
     * - Positive integer string -> returns the integer value
     * 
     * @param value The string value to parse
     * @return The parsed integer limit, or null if all results should be processed
     */
    private fun parseLimitParameter(value: String?): Int? {
        // If parameter is completely missing (null), process all
        if (value == null) {
            return null
        }
        
        // If parameter is present but empty/blank, process all
        if (value.isBlank()) {
            return null
        }
        
        // Try to parse as integer
        val parsed = value.trim().toIntOrNull()
        
        // If parsing failed or value is 0, process all
        if (parsed == null || parsed == 0) {
            return null
        }
        
        // If negative, treat as "process all" (invalid limit)
        if (parsed < 0) {
            logger.warn("Invalid limit parameter value '{}' (negative), processing all results", value)
            return null
        }
        
        return parsed
    }
}

