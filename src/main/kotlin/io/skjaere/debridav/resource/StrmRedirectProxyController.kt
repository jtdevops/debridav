package io.skjaere.debridav.resource

import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.stream.HttpRequestInfo
import io.skjaere.debridav.stream.StreamResult
import io.skjaere.debridav.stream.StreamingService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.OutputStream
import java.net.InetAddress

/**
 * Redirect proxy controller for STRM external URLs.
 * This controller handles redirect requests for external URLs in STRM files,
 * checking if URLs are expired and refreshing them before redirecting.
 */
@RestController
@RequestMapping("/strm-proxy")
class StrmRedirectProxyController(
    private val fileService: DatabaseFileService,
    private val debridLinkService: DebridLinkService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val debridFileRepository: DebridFileContentsRepository,
    private val streamingService: StreamingService
) {
    private val logger = LoggerFactory.getLogger(StrmRedirectProxyController::class.java)

    /**
     * Handles redirect requests for STRM external URLs.
     * The path should contain the file ID and filename encoded in the URL.
     * Format: /strm-proxy/{fileId}/{filename}
     * 
     * @param fileId The ID of the file to redirect to
     * @param filename The filename (for compatibility, may include extension)
     * @param request The HTTP request
     * @return Redirect response to the active external URL
     */
    @GetMapping("/{fileId}/{filename}")
    fun redirectToExternalUrl(
        @PathVariable fileId: Long,
        @PathVariable filename: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Void> {
        val proxyUrl = "${request.scheme}://${request.serverName}:${request.serverPort}${request.requestURI}"
        val requestMethod = request.method
        val rangeHeader = request.getHeader("Range")
        logger.info("STRM proxy: Received request - Method: $requestMethod, Range: ${rangeHeader ?: "none"}, fileId: $fileId, filename: $filename")
        
        return try {
            // Load the file entity by ID
            val dbEntity = debridFileRepository.findById(fileId).orElse(null)
            val file = dbEntity as? RemotelyCachedEntity
            if (file == null) {
                logger.warn("STRM proxy: File not found for ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Void>()
            }

            // Reload to ensure contents are loaded
            val reloadedFile = fileService.reloadRemotelyCachedEntity(file)
            if (reloadedFile == null) {
                logger.warn("STRM proxy: Could not reload file for ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Void>()
            }

            val contents = reloadedFile.contents ?: run {
                logger.warn("STRM proxy: File has no contents for ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Void>()
            }

            // Determine the provider
            val provider = determineProvider(reloadedFile, contents)
            if (provider == null) {
                logger.warn("STRM proxy: Could not determine provider for file ID: $fileId")
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build<Void>()
            }

            // Check if proxy URLs are enabled for this provider (proxy URLs always refresh)
            val shouldUseProxy = debridavConfigurationProperties.shouldUseProxyUrlForStrm(provider)
            
            // Get the external URL (with refresh check if proxy is enabled)
            val externalUrl = if (shouldUseProxy && provider != DebridProvider.IPTV) {
                getExternalUrlWithRefresh(reloadedFile, contents, provider)
            } else {
                getExternalUrl(reloadedFile, contents)
            }

            if (externalUrl == null) {
                logger.warn("STRM proxy: No external URL available for file ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Void>()
            }

            logger.info("STRM proxy: Final URL returned: $externalUrl (fileId: $fileId, provider: $provider, file: ${reloadedFile.name})")
            
            // Check if streaming mode is enabled
            if (debridavConfigurationProperties.strmProxyStreamMode) {
                streamContent(reloadedFile, contents, provider, rangeHeader, request, response)
                return ResponseEntity.ok().build<Void>()
            } else {
                // Redirect to the external URL
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header("Location", externalUrl)
                    .build<Void>()
            }
        } catch (e: Exception) {
            logger.error("STRM proxy: Error processing request for file ID: $fileId", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build<Void>()
        }
    }
    
    /**
     * Streams content directly through the proxy instead of redirecting.
     */
    private fun streamContent(
        file: RemotelyCachedEntity,
        contents: io.skjaere.debridav.fs.DebridFileContents,
        provider: DebridProvider,
        rangeHeader: String?,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) = runBlocking {
        try {
            val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
            if (cachedFile == null) {
                logger.warn("STRM proxy: No CachedFile found for streaming, file ID: ${file.id}")
                response.sendError(HttpServletResponse.SC_NOT_FOUND)
                return@runBlocking
            }
            
            // Parse Range header
            val range = parseRangeHeader(rangeHeader, cachedFile.size ?: 0L)
            
            // Extract HTTP request info
            val httpRequestInfo = extractHttpRequestInfo(request, file, range)
            
            logger.info("STRM proxy: Streaming content directly (fileId: ${file.id}, provider: $provider, range: ${range?.let { "${it.start}-${it.finish}" } ?: "full"})")
            
            // Set response headers
            response.contentType = file.mimeType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
            
            // Set Content-Length and Content-Range headers for range requests
            if (range != null && cachedFile.size != null) {
                val contentLength = range.finish - range.start + 1
                response.setHeader(HttpHeaders.CONTENT_LENGTH, contentLength.toString())
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes ${range.start}-${range.finish}/${cachedFile.size}")
                response.status = HttpServletResponse.SC_PARTIAL_CONTENT
            } else {
                cachedFile.size?.let { response.setHeader(HttpHeaders.CONTENT_LENGTH, it.toString()) }
            }
            
            // Stream the content
            val result = streamingService.streamContents(
                cachedFile,
                range,
                response.outputStream,
                file,
                httpRequestInfo
            )
            
            when (result) {
                StreamResult.OK -> {
                    try {
                        response.flushBuffer()
                    } catch (e: Exception) {
                        // Handle expected exceptions when response is already committed/closed
                        if (isExpectedResponseException(e)) {
                            logger.debug("STRM proxy: Response already committed/closed (expected): fileId=${file.id}, exceptionClass=${e::class.simpleName}")
                        } else {
                            throw e
                        }
                    }
                }
                StreamResult.PROVIDER_ERROR -> {
                    if (!response.isCommitted) {
                        response.sendError(HttpServletResponse.SC_BAD_GATEWAY)
                    }
                }
                StreamResult.IO_ERROR -> {
                    if (!response.isCommitted) {
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                    }
                }
                StreamResult.CLIENT_ERROR -> {
                    if (!response.isCommitted) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST)
                    }
                }
                else -> {
                    if (!response.isCommitted) {
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                    }
                }
            }
        } catch (e: Exception) {
            // Check if this is an expected exception (client disconnect, response already committed, etc.)
            if (isExpectedResponseException(e)) {
                logger.debug("STRM proxy: Expected exception during streaming (client disconnect/response committed): fileId=${file.id}, exceptionClass=${e::class.simpleName}")
            } else {
                logger.error("STRM proxy: Error streaming content for file ID: ${file.id}", e)
                if (!response.isCommitted) {
                    try {
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                    } catch (_: Exception) {
                        // Response may already be committed
                    }
                }
            }
        }
    }
    
    /**
     * Parses Range header from HTTP request.
     * Format: "bytes=start-end" or "bytes=start-"
     */
    private fun parseRangeHeader(rangeHeader: String?, fileSize: Long): Range? {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return null
        }
        
        try {
            val rangeValue = rangeHeader.substring(6) // Remove "bytes=" prefix
            val parts = rangeValue.split("-")
            
            if (parts.size != 2) {
                return null
            }
            
            val start = parts[0].toLongOrNull() ?: return null
            val end = if (parts[1].isEmpty()) {
                fileSize - 1
            } else {
                parts[1].toLongOrNull() ?: return null
            }
            
            return Range(start, end)
        } catch (e: Exception) {
            logger.warn("STRM proxy: Failed to parse Range header: $rangeHeader", e)
            return null
        }
    }
    
    /**
     * Extracts HTTP request info for streaming service.
     */
    private fun extractHttpRequestInfo(
        request: HttpServletRequest,
        file: RemotelyCachedEntity,
        range: Range?
    ): HttpRequestInfo {
        val httpHeaders = mutableMapOf<String, String>()
        
        // Extract HTTP headers
        request.headerNames?.toList()?.forEach { headerName ->
            request.getHeaders(headerName)?.toList()?.forEach { headerValue ->
                httpHeaders[headerName] = headerValue
            }
        }
        
        // Get source IP address
        val sourceIpAddress = request.remoteAddr
            ?: request.getHeader("X-Forwarded-For")?.split(",")?.first()?.trim()
            ?: request.getHeader("X-Real-IP")
            ?: "unknown"
        
        // Try to resolve hostname from IP address
        var sourceHostname: String? = null
        if (sourceIpAddress != "unknown") {
            try {
                sourceHostname = InetAddress.getByName(sourceIpAddress).hostName
            } catch (e: Exception) {
                // If hostname resolution fails, leave it null
            }
        }
        
        return HttpRequestInfo(httpHeaders, sourceIpAddress, sourceHostname)
    }

    /**
     * Determines the provider for a RemotelyCachedEntity.
     */
    private fun determineProvider(file: RemotelyCachedEntity, contents: io.skjaere.debridav.fs.DebridFileContents): DebridProvider? {
        // Check if it's IPTV content
        if (contents is DebridIptvContent) {
            return DebridProvider.IPTV
        }
        
        // Try to get provider from debridLinks
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        return cachedFile?.provider
    }

    /**
     * Gets the external URL from a RemotelyCachedEntity with refresh check.
     */
    private fun getExternalUrlWithRefresh(
        file: RemotelyCachedEntity,
        contents: io.skjaere.debridav.fs.DebridFileContents,
        provider: DebridProvider
    ): String? = runBlocking {
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        val originalUrl = cachedFile?.link
        if (originalUrl == null) {
            return@runBlocking null
        }

        logger.info("STRM proxy: Initial URL from database: $originalUrl (fileId: ${file.id}, provider: $provider)")

        try {
            // Check if link is alive using the cache
            val cacheKey = io.skjaere.debridav.debrid.DebridLinkService.LinkLivenessCacheKey(
                provider.toString(),
                cachedFile.path,
                cachedFile.size,
                file.id, // Use RemotelyCachedEntity.id as entity identifier
                cachedFile
            )
            
            // Check if value is already cached (without triggering a load)
            val cachedValue = debridLinkService.isLinkAliveCache.getIfPresent(cacheKey)
            val isAlive = if (cachedValue != null) {
                logger.info("STRM proxy: URL status from cache (cached): $originalUrl")
                cachedValue
            } else {
                logger.info("STRM proxy: Verifying URL is active (cache miss): $originalUrl")
                debridLinkService.isLinkAliveCache.get(cacheKey)
            }
            
            val finalUrl = if (!isAlive) {
                logger.info("STRM proxy: External URL expired for ${file.name} from $provider, refreshing...")
                // Refresh the link
                val refreshedLink = debridLinkService.refreshLinkOnError(file, cachedFile)
                if (refreshedLink != null) {
                    logger.info("STRM proxy: Successfully refreshed external URL for ${file.name} from $provider")
                    logger.info("STRM proxy: Refreshed URL returned: ${refreshedLink.link}")
                    refreshedLink.link
                } else {
                    logger.warn("STRM proxy: Failed to refresh external URL for ${file.name} from $provider, using original URL")
                    logger.info("STRM proxy: Using original URL (refresh failed): $originalUrl")
                    originalUrl
                }
            } else {
                // URL is still alive, use it
                logger.info("STRM proxy: URL verified active, using original URL: $originalUrl")
                originalUrl
            }
            
            return@runBlocking finalUrl
        } catch (e: Exception) {
            logger.warn("STRM proxy: Error checking/refreshing external URL for ${file.name} from $provider: ${e.message}, using original URL", e)
            logger.info("STRM proxy: Using original URL (error occurred): $originalUrl")
            originalUrl
        }
    }

    /**
     * Gets the external URL from a RemotelyCachedEntity without refresh check.
     */
    private fun getExternalUrl(
        file: RemotelyCachedEntity,
        contents: io.skjaere.debridav.fs.DebridFileContents
    ): String? {
        // Try to get a CachedFile link first (for debrid providers)
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        if (cachedFile?.link != null) {
            return cachedFile.link
        }
        
        // Try to get an IptvFile link (for IPTV content)
        if (contents is DebridIptvContent) {
            val iptvFile = contents.debridLinks.firstOrNull { it is io.skjaere.debridav.fs.IptvFile } as? io.skjaere.debridav.fs.IptvFile
            val tokenizedUrl = iptvFile?.link
            if (tokenizedUrl != null) {
                // Resolve IPTV template URL if needed
                return if (tokenizedUrl.startsWith("{IPTV_TEMPLATE_URL}")) {
                    try {
                        val template = contents.iptvUrlTemplate
                        if (template != null) {
                            tokenizedUrl.replace("{IPTV_TEMPLATE_URL}", template.baseUrl)
                        } else {
                            null
                        }
                    } catch (e: org.hibernate.LazyInitializationException) {
                        null
                    }
                } else {
                    tokenizedUrl
                }
            }
        }
        
        return null
    }
    
    /**
     * Checks if an exception is an expected response exception (client disconnect, response already committed, etc.).
     * These exceptions should be logged at DEBUG level without stacktraces.
     */
    private fun isExpectedResponseException(e: Exception): Boolean {
        // Check for AsyncRequestNotUsableException (Spring wrapper for client disconnects and response errors)
        if (e.javaClass.simpleName == "AsyncRequestNotUsableException") {
            return true
        }
        // Check for messages indicating response is already committed/closed
        if (e.message?.contains("Response not usable") == true ||
            e.message?.contains("Response already committed") == true ||
            e.message?.contains("Response has been closed") == true) {
            return true
        }
        // Check for connection reset messages
        if (e.message?.contains("Connection reset") == true ||
            e.message?.contains("Connection reset by peer") == true ||
            e.message?.contains("Broken pipe") == true) {
            return true
        }
        // Check cause chain
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause.javaClass.simpleName == "AsyncRequestNotUsableException" ||
                cause.message?.contains("Response not usable") == true ||
                cause.message?.contains("Response already committed") == true ||
                cause.message?.contains("Response has been closed") == true ||
                cause.message?.contains("Connection reset") == true ||
                cause.message?.contains("Connection reset by peer") == true ||
                cause.message?.contains("Broken pipe") == true) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}

