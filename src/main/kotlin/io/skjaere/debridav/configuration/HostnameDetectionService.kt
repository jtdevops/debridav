package io.skjaere.debridav.configuration

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.net.InetAddress

/**
 * Service that detects the container/host hostname at startup.
 * First checks the HOSTNAME environment variable, then falls back to network detection.
 */
@Service
class HostnameDetectionService(
    private val environment: Environment
) {
    private val logger = LoggerFactory.getLogger(HostnameDetectionService::class.java)
    
    private var detectedHostname: String? = null
    
    /**
     * Detects the hostname at startup.
     * Priority: 1) HOSTNAME environment variable, 2) Network detection
     */
    @PostConstruct
    fun detectHostname() {
        // First, check HOSTNAME environment variable
        val envHostname = environment.getProperty("HOSTNAME")
        if (!envHostname.isNullOrBlank()) {
            detectedHostname = envHostname
            logger.info("Using hostname from HOSTNAME environment variable: $envHostname")
            return
        }
        
        // Fall back to network detection
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
            logger.info("Detected container hostname via network detection: $finalHostname (from ${localHost.hostAddress})")
        } catch (e: Exception) {
            logger.warn("Failed to detect hostname using network detection: ${e.message}. Hostname detection failed and HOSTNAME environment variable is not available.", e)
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

