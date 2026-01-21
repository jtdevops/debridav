package io.skjaere.debridav.webdav.folder

import io.skjaere.debridav.debrid.client.premiumize.PremiumizeConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import io.skjaere.debridav.debrid.client.torbox.TorBoxConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Built-in WebDAV provider names that are reserved
 */
private val BUILT_IN_PROVIDERS = setOf("premiumize", "real_debrid", "realdebrid", "torbox")

@Service
@ConditionalOnProperty(
    prefix = "debridav.webdav-folder-mapping",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class WebDavProviderConfigurationService(
    private val environment: Environment,
    private val premiumizeConfig: PremiumizeConfigurationProperties?,
    private val realDebridConfig: RealDebridConfigurationProperties?,
    private val torBoxConfig: TorBoxConfigurationProperties?,
    private val folderMappingProperties: WebDavFolderMappingProperties
) {
    private val logger = LoggerFactory.getLogger(WebDavProviderConfigurationService::class.java)
    
    // Cache the enabled providers list (normalized to lowercase)
    private val enabledProviders: Set<String> by lazy {
        folderMappingProperties.getProvidersList().map { it.lowercase().trim() }.toSet()
    }
    
    private val builtInConfigs: Map<String, WebDavProviderConfiguration> by lazy {
        buildBuiltInConfigs()
    }
    
    private val customConfigs: Map<String, WebDavProviderConfiguration> by lazy {
        buildCustomConfigs()
    }
    
    init {
        // Check for collisions between enabled built-in providers and custom provider configs
        checkForBuiltInProviderCollisions()
    }
    
    /**
     * Check if any enabled built-in provider has custom provider config environment variables defined.
     * This warns users who may have accidentally configured both.
     */
    private fun checkForBuiltInProviderCollisions() {
        val enabledBuiltInProviders = enabledProviders.filter { it in BUILT_IN_PROVIDERS }
        
        enabledBuiltInProviders.forEach { providerName ->
            val hasCustomUrl = hasCustomProviderConfig(providerName, "URL")
            val hasCustomUsername = hasCustomProviderConfig(providerName, "USERNAME")
            val hasCustomPassword = hasCustomProviderConfig(providerName, "PASSWORD")
            
            if (hasCustomUrl || hasCustomUsername || hasCustomPassword) {
                logger.warn(
                    "Custom WebDAV provider config detected for built-in provider '$providerName' - " +
                    "custom config (DEBRIDAV_WEBDAV_PROVIDER_${providerName.uppercase()}_*) will be ignored. " +
                    "Use the built-in provider config instead (e.g., PREMIUMIZE_WEBDAV_USERNAME)."
                )
            }
        }
    }
    
    /**
     * Check if a custom provider config environment variable exists for a given provider and property.
     */
    private fun hasCustomProviderConfig(providerName: String, property: String): Boolean {
        val envVar = "DEBRIDAV_WEBDAV_PROVIDER_${providerName.uppercase()}_$property"
        val propKey = "debridav.webdav.provider.$providerName.${property.lowercase()}"
        
        return !environment.getProperty(propKey).isNullOrBlank() ||
               !System.getenv(envVar).isNullOrBlank()
    }
    
    /**
     * Check if a provider is in the enabled providers list.
     * If the providers list is empty, no providers are allowed.
     */
    fun isProviderEnabled(providerName: String): Boolean {
        return normalizeProviderName(providerName) in enabledProviders
    }
    
    /**
     * Get configuration for a provider by name.
     * Returns null if the provider is not in the enabled providers list.
     * Built-in providers take precedence over custom providers with the same name.
     */
    fun getConfiguration(providerName: String): WebDavProviderConfiguration? {
        val normalizedName = normalizeProviderName(providerName)
        
        // Provider must be in the enabled list
        if (normalizedName !in enabledProviders) {
            logger.debug("Provider '$normalizedName' is not in the enabled providers list")
            return null
        }
        
        // Check built-in providers first
        builtInConfigs[normalizedName]?.let {
            // Check if there's a custom config with the same name (collision)
            if (customConfigs.containsKey(normalizedName)) {
                logger.warn(
                    "Custom WebDAV provider '$normalizedName' conflicts with built-in provider - using built-in"
                )
            }
            return it
        }
        
        // Check custom providers
        return customConfigs[normalizedName]
    }
    
    /**
     * Get all configured providers (built-in and custom)
     */
    fun getAllConfigurations(): Map<String, WebDavProviderConfiguration> {
        val all = mutableMapOf<String, WebDavProviderConfiguration>()
        all.putAll(builtInConfigs)
        // Custom configs override built-in only if they don't conflict
        customConfigs.forEach { (name, config) ->
            if (!builtInConfigs.containsKey(name)) {
                all[name] = config
            }
        }
        return all
    }
    
    /**
     * Check if a provider name is a built-in provider
     */
    fun isBuiltInProvider(providerName: String): Boolean {
        return normalizeProviderName(providerName) in BUILT_IN_PROVIDERS
    }
    
    private fun normalizeProviderName(name: String): String {
        return name.lowercase().trim()
    }
    
    private fun buildBuiltInConfigs(): Map<String, WebDavProviderConfiguration> {
        val configs = mutableMapOf<String, WebDavProviderConfiguration>()
        
        // Only configure built-in providers that are in the enabled providers list
        
        // Premiumize
        if ("premiumize" in enabledProviders) {
            premiumizeConfig?.let { config ->
                val username = config.webdavUsername
                val password = config.webdavPassword
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    configs["premiumize"] = WebDavProviderConfiguration(
                        name = "premiumize",
                        url = "https://webdav.premiumize.me",
                        username = username,
                        password = password,
                        isBuiltIn = true
                    )
                    logger.info("Configured built-in WebDAV provider: premiumize")
                } else {
                    logger.warn("Provider 'premiumize' is enabled but WebDAV credentials are not configured")
                }
            }
        }
        
        // Real-Debrid
        if ("real_debrid" in enabledProviders || "realdebrid" in enabledProviders) {
            realDebridConfig?.let { config ->
                val username = config.webdavUsername
                val password = config.webdavPassword
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    configs["real_debrid"] = WebDavProviderConfiguration(
                        name = "real_debrid",
                        url = "https://dav.real-debrid.com",
                        username = username,
                        password = password,
                        isBuiltIn = true
                    )
                    // Also support "realdebrid" variant
                    configs["realdebrid"] = configs["real_debrid"]!!
                    logger.info("Configured built-in WebDAV provider: real_debrid")
                } else {
                    logger.warn("Provider 'real_debrid' is enabled but WebDAV credentials are not configured")
                }
            }
        }
        
        // TorBox - uses Basic auth with WebDAV username/password
        if ("torbox" in enabledProviders) {
            torBoxConfig?.let { config ->
                val webdavUrl = "https://webdav.torbox.app"
                val username = config.webdavUsername
                val password = config.webdavPassword
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    configs["torbox"] = WebDavProviderConfiguration(
                        name = "torbox",
                        url = webdavUrl,
                        username = username,
                        password = password,
                        authType = WebDavAuthType.BASIC,
                        isBuiltIn = true
                    )
                    logger.info("Configured built-in WebDAV provider: torbox")
                } else {
                    logger.warn("Provider 'torbox' is enabled but WebDAV credentials are not configured")
                }
            }
        }
        
        return configs
    }
    
    private fun buildCustomConfigs(): Map<String, WebDavProviderConfiguration> {
        val configs = mutableMapOf<String, WebDavProviderConfiguration>()
        
        // Only process providers that are explicitly listed in DEBRIDAV_WEBDAV_FOLDER_MAPPING_PROVIDERS
        // and are not built-in providers
        val customProviderNames = enabledProviders.filter { it !in BUILT_IN_PROVIDERS }
        
        customProviderNames.forEach { providerName ->
            val normalizedName = normalizeProviderName(providerName)
            
            val url = environment.getProperty("debridav.webdav.provider.$providerName.url")
                ?: System.getenv("DEBRIDAV_WEBDAV_PROVIDER_${providerName.uppercase()}_URL")
            
            if (url.isNullOrBlank()) {
                logger.debug("Skipping provider $providerName - no URL configured")
                return@forEach
            }
            
            val username = environment.getProperty("debridav.webdav.provider.$providerName.username")
                ?: System.getenv("DEBRIDAV_WEBDAV_PROVIDER_${providerName.uppercase()}_USERNAME")
            
            val password = environment.getProperty("debridav.webdav.provider.$providerName.password")
                ?: System.getenv("DEBRIDAV_WEBDAV_PROVIDER_${providerName.uppercase()}_PASSWORD")
            
            val syncIntervalStr = environment.getProperty("debridav.webdav.provider.$providerName.sync-interval")
                ?: System.getenv("DEBRIDAV_WEBDAV_PROVIDER_${providerName.uppercase()}_SYNC_INTERVAL")
            
            val syncInterval = syncIntervalStr?.let {
                try {
                    Duration.parse(it)
                } catch (e: Exception) {
                    logger.warn("Invalid sync interval format for provider $providerName: $it", e)
                    null
                }
            }
            
            try {
                configs[normalizedName] = WebDavProviderConfiguration(
                    name = normalizedName,
                    url = url,
                    username = username,
                    password = password,
                    syncInterval = syncInterval,
                    isBuiltIn = false
                )
                logger.info("Configured custom WebDAV provider: $normalizedName")
            } catch (e: Exception) {
                logger.error("Error configuring custom WebDAV provider $providerName", e)
            }
        }
        
        return configs
    }
}
