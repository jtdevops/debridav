package io.skjaere.debridav.filter

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that disables ETag generation for streaming endpoints.
 * ETag calculation can delay first byte, so we disable it for:
 * - /strm-proxy/... paths (always)
 * - WebDAV paths serving media files (only if enableVfsOptimizationHeaders is true)
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 50) // Run after other filters
class StreamingEtagDisableFilter(
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) : OncePerRequestFilter() {
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Check if we should disable ETag for this request
        val shouldDisableEtag = shouldDisableEtag(request, response)
        
        if (shouldDisableEtag) {
            // Wrap response to remove ETag header
            val wrappedResponse = object : HttpServletResponseWrapper(response) {
                override fun setHeader(name: String, value: String) {
                    if (name.equals("ETag", ignoreCase = true)) {
                        // Skip ETag header for streaming endpoints
                        return
                    }
                    super.setHeader(name, value)
                }
                
                override fun addHeader(name: String, value: String) {
                    if (name.equals("ETag", ignoreCase = true)) {
                        // Skip ETag header for streaming endpoints
                        return
                    }
                    super.addHeader(name, value)
                }
                
                override fun containsHeader(name: String): Boolean {
                    if (name.equals("ETag", ignoreCase = true)) {
                        // Pretend ETag doesn't exist
                        return false
                    }
                    return super.containsHeader(name)
                }
            }
            filterChain.doFilter(request, wrappedResponse)
        } else {
            filterChain.doFilter(request, response)
        }
    }
    
    private fun shouldDisableEtag(request: HttpServletRequest, response: HttpServletResponse): Boolean {
        val requestUri = request.requestURI
        
        // Always disable ETag for STRM proxy paths
        if (requestUri.startsWith("/strm-proxy/")) {
            return true
        }
        
        // For VFS paths, disable ETag if optimization headers are enabled
        // We'll check content type in the wrapper when headers are set
        if (debridavConfigurationProperties.enableVfsOptimizationHeaders) {
            // Disable for all VFS paths when optimization is enabled
            return true
        }
        
        return false
    }
}
