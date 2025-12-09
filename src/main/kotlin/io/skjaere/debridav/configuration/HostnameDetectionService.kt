package io.skjaere.debridav.configuration

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.InetAddress

/**
 * Service that detects the container/host hostname at startup using network detection.
 * This is more reliable than relying on environment variables.
 */
@Service
class HostnameDetectionService {
    private val logger = LoggerFactory.getLogger(HostnameDetectionService::class.java)
    
    private var detectedHostname: String? = null
    
    /**
     * Detects the hostname at startup using network detection.
     */
    @PostConstruct
    fun detectHostname() {
        try {
            val localHost = InetAddress.getLocalHost()
            // Try canonical hostname first (usually the FQDN)
            val hostname = localHost.canonicalHostName
            
            // If canonical hostname is an IP address, try hostname instead
            val finalHostname = if (isIpAddress(hostname)) {
                localHost.hostName.takeIf { !isIpAddress(it) } ?: hostname
            } else {
                hostname
            }
            
            detectedHostname = finalHostname
            logger.info("Detected container hostname: $finalHostname (from ${localHost.hostAddress})")
        } catch (e: Exception) {
            logger.warn("Failed to detect hostname using network detection: ${e.message}. Will fall back to environment variable or require explicit configuration.", e)
            detectedHostname = null
        }
    }
    
    /**
     * Gets the detected hostname.
     * @return The detected hostname, or null if detection failed
     */
    fun getHostname(): String? = detectedHostname
    
    /**
     * Checks if a string is an IP address.
     */
    private fun isIpAddress(str: String): Boolean {
        return try {
            // Try to parse as IP address
            val parts = str.split(".")
            if (parts.size == 4) {
                parts.all { it.toIntOrNull() in 0..255 }
            } else if (str.contains(":")) {
                // IPv6 address
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

