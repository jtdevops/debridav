package io.skjaere.debridav.maintenance

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Holds a single-use, in-memory API key for maintenance DELETE operations.
 * Key is generated only when needed (on first DELETE without valid key), not at startup.
 * Keys are single-use: after successful validation, the key is consumed and invalidated.
 */
@Component
class MaintenanceApiKeyHolder {
    private val logger = LoggerFactory.getLogger(MaintenanceApiKeyHolder::class.java)
    
    @Volatile
    private var currentKey: String? = null
    
    /**
     * Generates a new API key, stores it as the current key, logs it at WARN level,
     * and returns it. This is called when a DELETE request is received without a valid key.
     * 
     * @return The newly generated API key
     */
    fun generateAndLogKey(): String {
        val newKey = UUID.randomUUID().toString()
        currentKey = newKey
        logger.error("=== MAINTENANCE API KEY GENERATED ===")
        logger.error("A new maintenance API key has been generated for DELETE operations.")
        logger.error("Key: $newKey")
        logger.error("Use this key in the X-Api-Key header or apiKey query parameter for your next DELETE request.")
        logger.error("This key is single-use and will be invalidated after successful use.")
        logger.error("=== END MAINTENANCE API KEY ===")
        return newKey
    }
    
    /**
     * Validates the supplied key against the current key and consumes it if valid.
     * If the key matches, it is invalidated (set to null) and true is returned.
     * Otherwise, false is returned and the key remains unchanged.
     * 
     * @param key The API key to validate
     * @return true if the key is valid and was consumed, false otherwise
     */
    fun validateAndConsume(key: String?): Boolean {
        if (key.isNullOrBlank()) {
            return false
        }
        
        val current = currentKey
        return if (current != null && current == key) {
            // Consume the key by invalidating it
            currentKey = null
            true
        } else {
            false
        }
    }
}
