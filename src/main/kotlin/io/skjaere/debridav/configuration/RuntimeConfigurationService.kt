package io.skjaere.debridav.configuration

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Service to hold runtime configuration overrides.
 * These values take precedence over the static configuration properties.
 */
@Service
class RuntimeConfigurationService {
    private val runtimeOverrides = ConcurrentHashMap<String, String?>()
    
    /**
     * Sets a runtime configuration override.
     * @param key The configuration key (e.g., "debugArrTorrentInfoContentPathSuffix")
     * @param value The value to set (null to clear the override)
     */
    fun setOverride(key: String, value: String?) {
        if (value == null || value.isBlank()) {
            runtimeOverrides.remove(key)
        } else {
            runtimeOverrides[key] = value
        }
    }
    
    /**
     * Gets a runtime configuration override.
     * @param key The configuration key
     * @return The override value, or null if not set
     */
    fun getOverride(key: String): String? {
        return runtimeOverrides[key]
    }
    
    /**
     * Gets the effective value for a configuration property.
     * Returns the runtime override if set, otherwise returns the default value.
     * @param key The configuration key
     * @param defaultValue The default value from static configuration
     * @return The effective value (override or default)
     */
    fun getEffectiveValue(key: String, defaultValue: String?): String? {
        return runtimeOverrides[key] ?: defaultValue
    }
    
    /**
     * Clears all runtime overrides.
     */
    fun clearAll() {
        runtimeOverrides.clear()
    }
    
    /**
     * Gets all current runtime overrides.
     */
    fun getAllOverrides(): Map<String, String?> {
        return runtimeOverrides.toMap()
    }
}

