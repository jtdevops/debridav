package io.skjaere.debridav.resource

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.stream.HttpRequestInfo
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress

/**
 * Helper component to detect ARR requests from HTTP context.
 * This is used to determine if we should serve local video files instead of actual media files.
 */
@Component
class ArrRequestDetector(
    private val debridavConfigProperties: DebridavConfigurationProperties
) {
    
    /**
     * Detects if the current HTTP request is from an ARR project.
     * Returns true if we should serve a local video file instead of the actual media file.
     */
    fun isArrRequest(): Boolean {
        if (!debridavConfigProperties.enableRcloneArrsLocalVideo) {
            return false
        }
        
        try {
            val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            val httpRequest = requestAttributes?.request ?: return false
            
            val httpRequestInfo = extractHttpRequestInfo(httpRequest)
            return debridavConfigProperties.shouldServeLocalVideoForArrs(httpRequestInfo)
        } catch (e: Exception) {
            // If we can't extract request info, assume it's not an ARR request
            return false
        }
    }
    
    /**
     * Extracts HTTP request information from the current request context.
     */
    private fun extractHttpRequestInfo(httpRequest: HttpServletRequest): HttpRequestInfo {
        val httpHeaders = mutableMapOf<String, String>()
        var sourceIpAddress: String? = null
        var sourceHostname: String? = null

        // Extract HTTP headers as Map
        httpRequest.headerNames?.toList()?.forEach { headerName ->
            httpRequest.getHeaders(headerName)?.toList()?.forEach { headerValue ->
                httpHeaders[headerName] = headerValue
            }
        }

        // Get source IP address
        sourceIpAddress = httpRequest.remoteAddr
            ?: httpRequest.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: httpRequest.getHeader("X-Real-IP")
        
        // Try to resolve hostname from IP address
        if (sourceIpAddress != null && sourceIpAddress != "unknown") {
            try {
                sourceHostname = InetAddress.getByName(sourceIpAddress).hostName
            } catch (e: Exception) {
                // If hostname resolution fails, leave it null
                // The HttpRequestInfo.sourceInfo getter will try to resolve it again
            }
        }

        return HttpRequestInfo(httpHeaders, sourceIpAddress, sourceHostname)
    }
}
