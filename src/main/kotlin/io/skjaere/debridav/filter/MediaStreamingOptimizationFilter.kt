package io.skjaere.debridav.filter

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that adds optimization headers for VFS path (Milton) streaming responses.
 * Only applies when direct streaming is enabled (chunk caching and in-memory buffering disabled).
 * 
 * Headers added:
 * - Cache-Control: no-store (prevents caching delays)
 * - Content-Encoding: identity (explicitly disable compression)
 * - X-Accel-Buffering: no (nginx-compatible, disables proxy buffering)
 * - Accept-Ranges: bytes (make explicit)
 */
@Component
@ConditionalOnProperty(name = ["debridav.enable-vfs-optimization-headers"], havingValue = "true", matchIfMissing = false)
@Order(Ordered.HIGHEST_PRECEDENCE + 50) // Run BEFORE Milton filter so we can wrap the response
class MediaStreamingOptimizationFilter(
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) : OncePerRequestFilter() {
    
    private val logger = LoggerFactory.getLogger(MediaStreamingOptimizationFilter::class.java)
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Only process GET requests
        if (request.method != "GET") {
            filterChain.doFilter(request, response)
            return
        }
        
        // Skip endpoints that are excluded from Milton processing to reduce log noise
        // These match the paths excluded in Milton filter configuration
        val requestUri = request.requestURI
        val excludedPaths = listOf("/actuator", "/api", "/files", "/version", "/sabnzbd", "/strm-proxy")
        if (excludedPaths.any { requestUri.startsWith(it) }) {
            filterChain.doFilter(request, response)
            return
        }
        
        // Check if direct streaming is enabled
        val isDirectStreaming = !debridavConfigurationProperties.enableChunkCaching && 
                               !debridavConfigurationProperties.enableInMemoryBuffering
        
        if (!isDirectStreaming) {
            filterChain.doFilter(request, response)
            return
        }
        
        // Check if we should process headers (optimization headers or header logging enabled)
        val shouldProcessHeaders = debridavConfigurationProperties.enableVfsOptimizationHeaders || 
                                  debridavConfigurationProperties.logVfsHeaders
        
        // Always wrap response to ensure request context flows properly, even if we don't process headers
        // This ensures RequestContextHolder works correctly for downstream components
        val wrappedResponse = if (shouldProcessHeaders) {
            // Log at TRACE level when optimization headers are enabled
            if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                logger.trace("VFS_FILTER_ACTIVE: Processing request URI={}, directStreaming={}", 
                    requestUri, isDirectStreaming)
            }
            
            // Wrap response BEFORE Milton processes it, so we can intercept headers when Milton sets them
            object : HttpServletResponseWrapper(response) {
                private var headersProcessed = false
                private var contentTypeSet: String? = null
                private var mediaCheckDone = false
                private var isMediaResponse = false
                
                override fun setHeader(name: String, value: String) {
                    super.setHeader(name, value)
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        logger.trace("VFS_FILTER_SET_HEADER: name={}, value={}, URI={}", name, value, request.requestURI)
                    }
                    if (name.equals("Content-Type", ignoreCase = true)) {
                        contentTypeSet = value
                        mediaCheckDone = false // Reset check when content-type changes
                        if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                            logger.trace("VFS_FILTER_CONTENT_TYPE_SET: contentType={}, URI={}", value, request.requestURI)
                        }
                    }
                    processHeadersIfNeeded()
                }
                
                override fun addHeader(name: String, value: String) {
                    super.addHeader(name, value)
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        logger.trace("VFS_FILTER_ADD_HEADER: name={}, value={}, URI={}", name, value, request.requestURI)
                    }
                    if (name.equals("Content-Type", ignoreCase = true)) {
                        contentTypeSet = value
                        mediaCheckDone = false // Reset check when content-type changes
                        if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                            logger.trace("VFS_FILTER_CONTENT_TYPE_ADDED: contentType={}, URI={}", value, request.requestURI)
                        }
                    }
                    processHeadersIfNeeded()
                }
                
                override fun setContentType(type: String) {
                    super.setContentType(type)
                    contentTypeSet = type
                    mediaCheckDone = false // Reset check when content-type changes
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        logger.trace("VFS_FILTER_SET_CONTENT_TYPE: contentType={}, URI={}", type, request.requestURI)
                    }
                    processHeadersIfNeeded()
                }
                
                override fun flushBuffer() {
                    processHeadersIfNeeded()
                    super.flushBuffer()
                }
                
                override fun getOutputStream() = super.getOutputStream().also {
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        logger.trace("VFS_FILTER_GET_OUTPUT_STREAM: URI={}, contentType={}", request.requestURI, response.contentType)
                    }
                    processHeadersIfNeeded()
                }
                
                override fun getWriter() = super.getWriter().also {
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        logger.trace("VFS_FILTER_GET_WRITER: URI={}, contentType={}", request.requestURI, response.contentType)
                    }
                    processHeadersIfNeeded()
                }
                
                fun processHeadersIfNeeded() {
                    if (headersProcessed || isCommitted) return
                    
                    // Check if content-type is set and is a media type
                    // Keep checking until content-type is actually set (Milton may set it later)
                    var contentType = contentTypeSet ?: response.contentType
                    
                    // If content-type is still null, try to infer from file extension as fallback
                    // This handles cases where Milton doesn't set Content-Type header explicitly
                    if (contentType == null) {
                        contentType = this@MediaStreamingOptimizationFilter.inferContentTypeFromUri(request.requestURI)
                        if (contentType != null && debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                            logger.trace("VFS_FILTER_INFERRED_CONTENT_TYPE: URI={}, inferredContentType={}", request.requestURI, contentType)
                        }
                    }
                    
                    // Only mark check as done if we have a content-type (even if it's not media)
                    // This prevents infinite checking for non-media responses
                    if (!mediaCheckDone && contentType != null) {
                        isMediaResponse = contentType.startsWith("video/") || contentType.startsWith("audio/")
                        mediaCheckDone = true
                        
                        if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                            logger.trace("VFS_FILTER_CHECK: URI={}, contentTypeSet={}, responseContentType={}, inferredContentType={}, isMediaResponse={}, headersProcessed={}, committed={}", 
                                request.requestURI, contentTypeSet, response.contentType, contentType, isMediaResponse, headersProcessed, isCommitted)
                        }
                    } else if (!mediaCheckDone && contentType == null) {
                        // Content-type not set yet, keep checking (no logging unless optimization is enabled)
                        if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                            logger.trace("VFS_FILTER_CHECK: URI={}, contentType not set yet, will check again", request.requestURI)
                        }
                        return
                    }
                    
                    if (!isMediaResponse) {
                        // Not a media response, don't process
                        return
                    }
                    
                    // Only process headers once for media responses
                    if (headersProcessed) return
                    headersProcessed = true
                    
                    val finalContentType = contentTypeSet ?: response.contentType ?: this@MediaStreamingOptimizationFilter.inferContentTypeFromUri(request.requestURI) ?: "unknown"
                    
                    // Log at TRACE level when optimization headers are enabled
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        logger.trace("VFS_FILTER_PROCESSING_HEADERS: URI={}, finalContentType={}, logVfsHeaders={}, enableVfsOptimizationHeaders={}", 
                            request.requestURI, finalContentType, 
                            debridavConfigurationProperties.logVfsHeaders, 
                            debridavConfigurationProperties.enableVfsOptimizationHeaders)
                    }
                    
                    // Log existing headers if enabled (DEBUG level)
                    if (debridavConfigurationProperties.logVfsHeaders) {
                        this@MediaStreamingOptimizationFilter.logExistingHeaders(request, response, finalContentType)
                    }
                    
                    // Add optimization headers if enabled
                    if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
                        this@MediaStreamingOptimizationFilter.addOptimizationHeaders(request, response, finalContentType)
                    }
                }
            }
        } else {
            // When headers processing is disabled, use a pass-through wrapper that doesn't intercept anything
            // This ensures request context flows properly without any processing overhead
            object : HttpServletResponseWrapper(response) {
                // No overrides - just pass through all calls
            }
        }
        
        filterChain.doFilter(request, wrappedResponse)
    }
    
    /**
     * Infers content type from file extension in URI as a fallback when Milton doesn't set Content-Type header.
     */
    private fun inferContentTypeFromUri(uri: String): String? {
        val lowerUri = uri.lowercase()
        return when {
            lowerUri.endsWith(".mp4") -> "video/mp4"
            lowerUri.endsWith(".mkv") -> "video/x-matroska"
            lowerUri.endsWith(".avi") -> "video/x-msvideo"
            lowerUri.endsWith(".mov") -> "video/quicktime"
            lowerUri.endsWith(".webm") -> "video/webm"
            lowerUri.endsWith(".flv") -> "video/x-flv"
            lowerUri.endsWith(".wmv") -> "video/x-ms-wmv"
            lowerUri.endsWith(".m4v") -> "video/x-m4v"
            lowerUri.endsWith(".mp3") -> "audio/mpeg"
            lowerUri.endsWith(".m4a") -> "audio/mp4"
            lowerUri.endsWith(".flac") -> "audio/flac"
            lowerUri.endsWith(".ogg") -> "audio/ogg"
            lowerUri.endsWith(".wav") -> "audio/wav"
            lowerUri.endsWith(".aac") -> "audio/aac"
            else -> null
        }
    }
    
    private fun logExistingHeaders(request: HttpServletRequest, response: HttpServletResponse, contentType: String) {
        val headerNames = response.headerNames
        val headers = mutableMapOf<String, MutableList<String>>()
        
        headerNames.forEach { headerName ->
            val values = response.getHeaders(headerName)?.toList() ?: emptyList()
            headers[headerName] = values.toMutableList()
        }
        
        logger.debug("MILTON_HEADERS: Request URI={}, Content-Type={}, Headers={}", 
            request.requestURI, contentType, headers)
    }
    
    private fun addOptimizationHeaders(request: HttpServletRequest, response: HttpServletResponse, contentType: String) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Content-Encoding", "identity")
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Accept-Ranges", "bytes")
        
        logger.trace("VFS_OPTIMIZATION_HEADERS: Added optimization headers to response for URI={}, Content-Type={}", 
            request.requestURI, contentType)
    }
}

