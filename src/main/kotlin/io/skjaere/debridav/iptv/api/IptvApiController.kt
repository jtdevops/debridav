package io.skjaere.debridav.iptv.api

import io.skjaere.debridav.iptv.IptvRequestService
import io.skjaere.debridav.iptv.model.ContentType
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/iptv")
class IptvApiController(
    private val iptvRequestService: IptvRequestService
) {
    private val logger = LoggerFactory.getLogger(IptvApiController::class.java)

    @PostMapping("/search")
    fun search(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) category: String?,
        request: HttpServletRequest
    ): ResponseEntity<List<IptvRequestService.IptvSearchResult>> {
        logger.info("IPTV search request received - query='{}', type='{}', category='{}', fullQueryString='{}'", 
            query, type, category, request.queryString)
        logger.debug("Request URI: {}, Method: {}, RemoteAddr: {}", request.requestURI, request.method, request.remoteAddr)
        
        // Log all request parameters for debugging
        request.parameterMap.forEach { (key, values) ->
            logger.debug("Request parameter: {} = {}", key, values.joinToString(", "))
        }
        
        // Handle empty or missing query (e.g., from Prowlarr connection tests)
        if (query.isNullOrBlank()) {
            logger.warn("Query is null or blank - this might indicate Prowlarr is not sending the search query correctly")
            return ResponseEntity.ok(emptyList())
        }
        
        // Check if query looks like an IMDB ID (starts with "tt" followed by digits)
        val isImdbId = query.matches(Regex("^tt\\d+$", RegexOption.IGNORE_CASE))
        if (isImdbId) {
            logger.warn("Received IMDB ID query '{}' but IPTV API only supports title-based searches. " +
                    "This suggests Prowlarr is sending IMDB ID instead of title. " +
                    "Consider checking Prowlarr indexer configuration.", query)
            // Return empty results for IMDB ID queries since we can't search by IMDB ID
            return ResponseEntity.ok(emptyList())
        }
        
        val contentType = type?.let {
            try {
                ContentType.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid content type '{}', ignoring", it)
                null
            }
        }
        
        logger.info("Searching IPTV content with query='{}', contentType={}", query, contentType)
        val results = iptvRequestService.searchIptvContent(query, contentType)
        logger.info("Search returned {} results", results.size)
        if (results.isNotEmpty()) {
            logger.debug("First result sample: {}", results.first())
        }
        return ResponseEntity.ok(results)
    }

    @PostMapping("/add")
    fun add(
        @RequestBody request: AddIptvContentRequest
    ): ResponseEntity<String> {
        logger.info("IPTV add request: $request")
        
        val success = iptvRequestService.addIptvContent(
            contentId = request.contentId,
            providerName = request.providerName,
            category = request.category
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
        logger.info("IPTV delete request: provider=$providerName, contentId=$contentId")
        return ResponseEntity.ok("ok")
    }

    data class AddIptvContentRequest(
        val contentId: String,
        val providerName: String,
        val category: String
    )
}

