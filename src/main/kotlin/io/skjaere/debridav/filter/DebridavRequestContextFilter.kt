package io.skjaere.debridav.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that ensures request context is always bound for downstream components that use RequestContextHolder.
 * This is essential for components like DebridFileResource that need to extract HTTP headers and source info.
 * 
 * Runs early in the filter chain to ensure request context is available before other filters process the request.
 * 
 * Note: This filter complements Spring Boot's built-in RequestContextFilter by ensuring context is set
 * very early in the filter chain, before filters like MediaStreamingOptimizationFilter that may wrap the response.
 */
@Component
@ConditionalOnProperty(name = ["debridav.enable-vfs-optimization-headers"], havingValue = "true", matchIfMissing = false)
@Order(Ordered.HIGHEST_PRECEDENCE + 40) // Run very early, before MediaStreamingOptimizationFilter
class DebridavRequestContextFilter : OncePerRequestFilter() {
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Ensure request context is bound for downstream components that use RequestContextHolder
        // This is critical for DebridFileResource.extractHttpRequestInfo() to work correctly
        val previousAttributes = RequestContextHolder.getRequestAttributes()
        val requestAttributes = ServletRequestAttributes(request, response)
        RequestContextHolder.setRequestAttributes(requestAttributes, true)
        
        try {
            filterChain.doFilter(request, response)
        } finally {
            // Only restore previous request attributes if they existed
            // If previousAttributes was null, leave our context in place for downstream use
            if (previousAttributes != null) {
                RequestContextHolder.setRequestAttributes(previousAttributes, true)
            }
        }
    }
}

