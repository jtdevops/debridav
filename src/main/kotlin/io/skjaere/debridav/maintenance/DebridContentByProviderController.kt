package io.skjaere.debridav.maintenance

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.skjaere.debridav.debrid.DebridProvider
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Controller for managing debrid content by provider.
 * Provides GET endpoints to list content (all providers or specific provider),
 * and DELETE endpoints to delete all files for a provider (protected by API key).
 */
@RestController
@RequestMapping("/api/maintenance/content")
class DebridContentByProviderController(
    private val debridContentByProviderService: DebridContentByProviderService,
    private val maintenanceApiKeyHolder: MaintenanceApiKeyHolder,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(DebridContentByProviderController::class.java)
    
    /**
     * Creates a JSON writer configured for pretty-printed output.
     * Both verbose and non-verbose modes use the same writer since the service
     * already handles the different return types (List<FileEntry> vs List<String>).
     */
    private fun createJsonWriter(): com.fasterxml.jackson.databind.ObjectWriter {
        return objectMapper.writerWithDefaultPrettyPrinter()
    }
    
    /**
     * Lists all debrid content grouped by provider.
     * Returns pretty-printed JSON.
     * 
     * @param verbose If true, returns full file details. If false (default), returns only path.
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listAllContentByProvider(
        @RequestParam(name = "verbose", defaultValue = "false") verbose: Boolean
    ): ResponseEntity<String> {
        logger.info("Listing all content by provider (verbose=$verbose)")
        val content = debridContentByProviderService.listContentByProvider(verbose)
        
        return try {
            val json = createJsonWriter().writeValueAsString(content)
            ResponseEntity.ok(json)
        } catch (e: Exception) {
            logger.error("Error serializing content to JSON", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("""{"error": "Failed to serialize content"}""")
        }
    }
    
    /**
     * Lists all IPTV content grouped by iptvProvider.
     * Returns pretty-printed JSON.
     * This endpoint must be defined before the generic /{provider} endpoint to ensure correct routing.
     * 
     * @param verbose If true, returns full file details. If false (default), returns only path.
     */
    @GetMapping("/IPTV", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listAllIptvContent(
        @RequestParam(name = "verbose", defaultValue = "false") verbose: Boolean
    ): ResponseEntity<String> {
        logger.info("Listing all IPTV content (verbose=$verbose)")
        val content = debridContentByProviderService.listAllIptvContent(verbose)
        
        return try {
            val json = createJsonWriter().writeValueAsString(content)
            ResponseEntity.ok(json)
        } catch (e: Exception) {
            logger.error("Error serializing IPTV content to JSON", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("""{"error": "Failed to serialize content"}""")
        }
    }
    
    /**
     * Lists content for a specific IPTV provider.
     * Returns pretty-printed JSON.
     * This endpoint must be defined before the generic /{provider} endpoint to ensure correct routing.
     * 
     * @param verbose If true, returns full file details. If false (default), returns only path.
     */
    @GetMapping("/IPTV/{iptvProvider}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listContentForIptvProvider(
        @PathVariable iptvProvider: String,
        @RequestParam(name = "verbose", defaultValue = "false") verbose: Boolean
    ): ResponseEntity<String> {
        logger.info("Listing content for IPTV provider: $iptvProvider (verbose=$verbose)")
        val content = debridContentByProviderService.listContentByProvider(DebridProvider.IPTV.name, iptvProvider, verbose)
        
        return if (content == null) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    mapOf("error" to "IPTV provider not found: $iptvProvider")
                )
            )
        } else {
            try {
                val json = createJsonWriter().writeValueAsString(content)
                ResponseEntity.ok(json)
            } catch (e: Exception) {
                logger.error("Error serializing content to JSON", e)
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("""{"error": "Failed to serialize content"}""")
            }
        }
    }
    
    /**
     * Lists content for a specific non-IPTV provider (e.g. PREMIUMIZE, REAL_DEBRID).
     * Returns pretty-printed JSON.
     * 
     * @param verbose If true, returns full file details. If false (default), returns only path.
     */
    @GetMapping("/{provider}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listContentForProvider(
        @PathVariable provider: String,
        @RequestParam(name = "verbose", defaultValue = "false") verbose: Boolean
    ): ResponseEntity<String> {
        // Check if this is an IPTV provider without iptvProvider specified
        if (provider.uppercase() == DebridProvider.IPTV.name) {
            return ResponseEntity.badRequest().body(
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    mapOf("error" to "IPTV provider requires iptvProvider path segment. Use: GET /api/maintenance/content/IPTV/{iptvProvider} or GET /api/maintenance/content/IPTV for all IPTV content")
                )
            )
        }
        
        logger.info("Listing content for provider: $provider (verbose=$verbose)")
        val content = debridContentByProviderService.listContentByProvider(provider, null, verbose)
        
        return if (content == null) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    mapOf("error" to "Provider not found: $provider")
                )
            )
        } else {
            try {
                val json = createJsonWriter().writeValueAsString(content)
                ResponseEntity.ok(json)
            } catch (e: Exception) {
                logger.error("Error serializing content to JSON", e)
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("""{"error": "Failed to serialize content"}""")
            }
        }
    }
    
    /**
     * Deletes all files for a non-IPTV provider (e.g. PREMIUMIZE, REAL_DEBRID).
     * Requires a valid API key via header or query parameter.
     */
    @DeleteMapping("/{provider}")
    fun deleteProvider(
        @PathVariable provider: String,
        @RequestHeader(name = "X-Api-Key", required = false) apiKeyHeader: String?,
        @RequestParam(name = "apiKey", required = false) apiKeyQuery: String?
    ): ResponseEntity<Map<String, Any>> {
        // Check if this is an IPTV provider without iptvProvider specified
        if (provider.uppercase() == DebridProvider.IPTV.name) {
            return ResponseEntity.badRequest().body(mapOf(
                "error" to "IPTV provider requires iptvProvider path segment. Use: DELETE /api/maintenance/content/IPTV/{iptvProvider}"
            ))
        }
        
        return handleDelete(provider, null, apiKeyHeader, apiKeyQuery)
    }
    
    /**
     * Deletes all files for an IPTV provider.
     * Requires iptvProvider path segment and a valid API key.
     */
    @DeleteMapping("/IPTV/{iptvProvider}")
    fun deleteIptvProvider(
        @PathVariable iptvProvider: String,
        @RequestHeader(name = "X-Api-Key", required = false) apiKeyHeader: String?,
        @RequestParam(name = "apiKey", required = false) apiKeyQuery: String?
    ): ResponseEntity<Map<String, Any>> {
        return handleDelete(DebridProvider.IPTV.name, iptvProvider, apiKeyHeader, apiKeyQuery)
    }
    
    /**
     * Handles the DELETE request with API key validation.
     */
    private fun handleDelete(
        provider: String,
        iptvProvider: String?,
        apiKeyHeader: String?,
        apiKeyQuery: String?
    ): ResponseEntity<Map<String, Any>> {
        // Get API key from header or query parameter (header takes precedence)
        val apiKey = apiKeyHeader ?: apiKeyQuery
        
        // Validate API key
        val isValid = maintenanceApiKeyHolder.validateAndConsume(apiKey)
        
        if (!isValid) {
            // Generate a new key and log it
            maintenanceApiKeyHolder.generateAndLogKey()
            
            val message = if (apiKey == null) {
                "API key required. A new key has been generated and is visible in the application logs. Use it in the X-Api-Key header or apiKey query parameter for your next DELETE request."
            } else {
                "API key is invalid or has already been used. A new key has been generated and is visible in the application logs. Keys are single-use."
            }
            
            logger.warn("DELETE request rejected for provider $provider${iptvProvider?.let { "/$it" } ?: ""}: $message")
            
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf(
                "error" to message
            ))
        }
        
        // Key is valid, proceed with deletion
        logger.info("API key validated, proceeding with deletion for provider $provider${iptvProvider?.let { "/$it" } ?: ""}")
        
        try {
            val deletedCount = debridContentByProviderService.deleteByProvider(provider, iptvProvider)
            
            return ResponseEntity.ok(mapOf(
                "status" to "deleted",
                "provider" to provider,
                "iptvProvider" to (iptvProvider ?: "N/A"),
                "deletedCount" to deletedCount
            ))
        } catch (e: Exception) {
            logger.error("Error deleting files for provider $provider${iptvProvider?.let { "/$it" } ?: ""}", e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf(
                "error" to "Failed to delete files: ${e.message}"
            ))
        }
    }
}
