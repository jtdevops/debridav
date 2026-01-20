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
    private val torBoxConfig: TorBoxConfigurationProperties?
) {
    private val logger = LoggerFactory.getLogger(WebDavProviderConfigurationService::class.java)
    
    private val builtInConfigs: Map<String, WebDavProviderConfiguration> by lazy {
        buildBuiltInConfigs()
    }
    
    private val customConfigs: Map<String, WebDavProviderConfiguration> by lazy {
        buildCustomConfigs()
    }
    
    /**
     * Get configuration for a provider by name
     * Built-in providers take precedence over custom providers with the same name
     */
    fun getConfiguration(providerName: String): WebDavProviderConfiguration? {
        val normalizedName = normalizeProviderName(providerName)
        
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
        
        // Premiumize
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
            }
        }
        
        // Real-Debrid
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
            }
        }
        
        // TorBox - uses Basic auth with WebDAV username/password
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
            }
        }
        
        return configs
    }
    
    private fun buildCustomConfigs(): Map<String, WebDavProviderConfiguration> {
        val configs = mutableMapOf<String, WebDavProviderConfiguration>()
        
        // Find all custom provider names from environment variables
        // Pattern: DEBRIDAV_WEBDAV_PROVIDER_{NAME}_URL
        val providerNames = mutableSetOf<String>()
        
        environment.activeProfiles.forEach { profile ->
            // This is a simplified approach - in practice, we'd need to scan all properties
            // For now, we'll rely on the providers list from WebDavFolderMappingProperties
        }
        
        // We'll discover providers by checking for URL properties
        // This is called after providers are configured in WebDavFolderMappingProperties
        // So we can get the list from there or scan environment
        
        // For now, we'll parse from environment properties directly
        // Pattern: debridav.webdav.provider.{name}.url
        val propertySources = (environment as org.springframework.core.env.AbstractEnvironment).propertySources
        propertySources.forEach { propertySource ->
            if (propertySource.source is java.util.Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val source = propertySource.source as Map<String, Any>
                source.keys.forEach { key ->
                    val prefix = "debridav.webdav.provider."
                    if (key.startsWith(prefix) && key.endsWith(".url")) {
                        val providerName = key.removePrefix(prefix).removeSuffix(".url")
                        if (providerName.isNotBlank() && normalizeProviderName(providerName) !in BUILT_IN_PROVIDERS) {
                            providerNames.add(providerName)
                        }
                    }
                }
            }
        }
        
        // Also check environment variables directly
        System.getenv().keys.forEach { envKey ->
            val prefix = "DEBRIDAV_WEBDAV_PROVIDER_"
            val suffix = "_URL"
            if (envKey.startsWith(prefix) && envKey.endsWith(suffix)) {
                val providerName = envKey.removePrefix(prefix).removeSuffix(suffix)
                if (providerName.isNotBlank()) {
                    providerNames.add(providerName.lowercase())
                }
            }
        }
        
        // Build configurations for each discovered provider
        providerNames.forEach { providerName ->
            val normalizedName = normalizeProviderName(providerName)
            
            // Skip if it's a built-in provider (handled separately)
            if (normalizedName in BUILT_IN_PROVIDERS) {
                return@forEach
            }
            
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
