package io.skjaere.debridav.configuration

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.stereotype.Component

/**
 * Actuator endpoint for runtime configuration changes.
 * 
 * Usage examples:
 * - Set debug suffix: POST /actuator/configuration {"debugArrTorrentInfoContentPathSuffix": "__DEBUG_TESTING"}
 * - Clear debug suffix: POST /actuator/configuration {"debugArrTorrentInfoContentPathSuffix": ""}
 * - Get current overrides: GET /actuator/configuration
 * - Clear all overrides: DELETE /actuator/configuration
 */
@Component
@Endpoint(id = "configuration")
class ConfigurationActuatorEndpoint(
    private val runtimeConfigurationService: RuntimeConfigurationService
) {
    private val logger = LoggerFactory.getLogger(ConfigurationActuatorEndpoint::class.java)
    
    @WriteOperation
    fun setConfiguration(request: ConfigurationRequest): Map<String, Any> {
        logger.info("Configuration change request received: {}", request)
        
        val results = mutableMapOf<String, Any>()
        
        // Handle debugArrTorrentInfoContentPathSuffix
        if (request.debugArrTorrentInfoContentPathSuffix != null) {
            val value = if (request.debugArrTorrentInfoContentPathSuffix.isBlank()) {
                null
            } else {
                request.debugArrTorrentInfoContentPathSuffix
            }
            runtimeConfigurationService.setOverride("debugArrTorrentInfoContentPathSuffix", value)
            results["debugArrTorrentInfoContentPathSuffix"] = value ?: "cleared"
            logger.info("Set debugArrTorrentInfoContentPathSuffix to: {}", value ?: "null (cleared)")
        }
        
        return mapOf(
            "status" to "success",
            "overrides" to results,
            "message" to "Configuration updated successfully"
        )
    }
    
    @ReadOperation
    fun getConfiguration(): Map<String, Any> {
        val overrides = runtimeConfigurationService.getAllOverrides()
        return mapOf(
            "status" to "success",
            "overrides" to overrides,
            "message" to "Current runtime configuration overrides"
        )
    }
    
    @DeleteOperation
    fun clearConfiguration(): Map<String, Any> {
        runtimeConfigurationService.clearAll()
        logger.info("All runtime configuration overrides cleared")
        return mapOf(
            "status" to "success",
            "message" to "All runtime configuration overrides cleared"
        )
    }
    
    data class ConfigurationRequest(
        val debugArrTorrentInfoContentPathSuffix: String? = null
    )
}

