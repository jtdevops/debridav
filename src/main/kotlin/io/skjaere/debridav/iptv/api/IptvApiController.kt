package io.skjaere.debridav.iptv.api

import io.skjaere.debridav.iptv.IptvRequestService
import io.skjaere.debridav.iptv.metadata.MetadataService
import io.skjaere.debridav.iptv.model.ContentType
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
    private val metadataService: MetadataService
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
        request: HttpServletRequest
    ): ResponseEntity<List<IptvRequestService.IptvSearchResult>> {
        logger.info("IPTV search request received - query='{}', type='{}', category='{}', fullQueryString='{}'", 
            query, type, category, request.queryString)
        
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
        // 3. Fallback to 'qTest' parameter (testing data)
        // 4. Fallback to legacy 'query' parameter
        
        val searchQuery = determineSearchQuery(
            imdbid = imdbid,
            tmdbid = tmdbid,
            traktid = traktid,
            doubanid = doubanid,
            q = q,
            qTest = qTest,
            query = query,
            contentType = contentType
        )
        
        if (searchQuery == null) {
            logger.warn("No valid search query found in request parameters")
            return ResponseEntity.ok(emptyList())
        }
        
        logger.info("Searching IPTV content with title='{}', year={}, startYear={}, endYear={}, contentType={}, season={}, episode={}, useArticleVariations={}", 
            searchQuery.title, searchQuery.year, searchQuery.startYear, searchQuery.endYear, contentType, season, episode, searchQuery.useArticleVariations)
        // Use episode parameter (e.g., "S08" or "S08E01") for magnet title
        val results = iptvRequestService.searchIptvContent(searchQuery.title, searchQuery.year, contentType, searchQuery.useArticleVariations, episode, searchQuery.startYear, searchQuery.endYear)
        logger.info("Search returned {} results", results.size)
        if (results.isNotEmpty()) {
            logger.debug("First result sample: {}", results.first())
        }
        return ResponseEntity.ok(results)
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
     * 3. Fallback to 'qTest' parameter
     * 4. Fallback to legacy 'query' parameter
     * 
     * Returns SearchQuery with title and optional year extracted separately
     */
    private fun determineSearchQuery(
        imdbid: String?,
        tmdbid: String?,
        traktid: String?,
        doubanid: String?,
        q: String?,
        qTest: String?,
        query: String?,
        contentType: ContentType?
    ): SearchQuery? {
        // Priority 1: Check IMDB ID (and potentially other IDs in the future)
        val imdbId = imdbid?.takeIf { it.isNotBlank() }
        if (imdbId != null) {
            logger.debug("Found IMDB ID: $imdbId, attempting to fetch metadata")
            val metadata = runBlocking {
                metadataService.getMetadataByImdbId(imdbId)
            }
            
            if (metadata != null) {
                logger.info("Successfully resolved IMDB ID '$imdbId' to title: '${metadata.title}' (startYear: ${metadata.startYear}, endYear: ${metadata.endYear})")
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
                logger.warn("Failed to resolve IMDB ID '$imdbId' to metadata, falling back to next priority")
            }
        }
        
        // Priority 2: Use 'q' parameter
        val qParam = q?.takeIf { it.isNotBlank() }
        if (qParam != null) {
            logger.debug("Using 'q' parameter: '$qParam'")
            return extractTitleAndYear(qParam, useArticleVariations = true)
        }
        
        // Priority 3: Use 'qTest' parameter (testing data)
        val qTestParam = qTest?.takeIf { it.isNotBlank() }
        if (qTestParam != null) {
            logger.debug("Using 'qTest' parameter (testing): '$qTestParam'")
            return extractTitleAndYear(qTestParam, useArticleVariations = true)
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
}

