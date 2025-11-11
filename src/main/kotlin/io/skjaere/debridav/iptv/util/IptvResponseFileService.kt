package io.skjaere.debridav.iptv.util

import io.skjaere.debridav.iptv.IptvProvider
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

@Service
class IptvResponseFileService(
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(IptvResponseFileService::class.java)

    /**
     * Saves a response to a file for the given provider and response type.
     * Files are named: {provider-name}_{response-type}.{extension}
     * 
     * @param providerConfig The provider configuration
     * @param responseType The type of response (e.g., "vod_streams", "vod_categories", "m3u")
     * @param content The content to save
     * @param extension File extension (default: "json" for Xtream Codes, "m3u" for M3U)
     */
    fun saveResponse(
        providerConfig: IptvProviderConfiguration,
        responseType: String,
        content: String,
        extension: String = when (providerConfig.type) {
            IptvProvider.M3U -> "m3u"
            IptvProvider.XTREAM_CODES -> "json"
        }
    ) {
        val saveFolder = iptvConfigurationProperties.responseSaveFolder ?: return
        
        try {
            val folder = File(saveFolder)
            if (!folder.exists()) {
                folder.mkdirs()
                logger.debug("Created IPTV response save folder: $saveFolder")
            }
            
            // Sanitize provider name for filename (remove invalid characters)
            val sanitizedProviderName = providerConfig.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filename = "${sanitizedProviderName}_${responseType}.$extension"
            val file = File(folder, filename)
            
            file.writeText(content)
            logger.debug("Saved ${providerConfig.type} response for provider ${providerConfig.name} to: ${file.absolutePath}")
        } catch (e: Exception) {
            logger.warn("Failed to save IPTV response for provider ${providerConfig.name}: ${e.message}", e)
        }
    }

    /**
     * Loads a response from a file for the given provider and response type.
     * 
     * @param providerConfig The provider configuration
     * @param responseType The type of response (e.g., "vod_streams", "vod_categories", "m3u")
     * @param extension File extension (default: "json" for Xtream Codes, "m3u" for M3U)
     * @param logNotFound Whether to log when file is not found (default: true). Set to false for hash checking to avoid duplicate logs.
     * @return The file content, or null if file doesn't exist or can't be read
     */
    fun loadResponse(
        providerConfig: IptvProviderConfiguration,
        responseType: String,
        extension: String = when (providerConfig.type) {
            IptvProvider.M3U -> "m3u"
            IptvProvider.XTREAM_CODES -> "json"
        },
        logNotFound: Boolean = true
    ): String? {
        val saveFolder = iptvConfigurationProperties.responseSaveFolder ?: return null
        
        try {
            // Sanitize provider name for filename (remove invalid characters)
            val sanitizedProviderName = providerConfig.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filename = "${sanitizedProviderName}_${responseType}.$extension"
            val file = File(saveFolder, filename)
            
            if (!file.exists()) {
                if (logNotFound) {
                    logger.debug("Local response file not found for provider ${providerConfig.name}: ${file.absolutePath}")
                }
                return null
            }
            
            val content = file.readText()
            logger.debug("Loaded ${providerConfig.type} response for provider ${providerConfig.name} from: ${file.absolutePath}")
            return content
        } catch (e: Exception) {
            logger.warn("Failed to load IPTV response for provider ${providerConfig.name}: ${e.message}", e)
            return null
        }
    }

    /**
     * Checks if local responses should be used for the given provider.
     * Per-provider setting takes precedence over global setting.
     */
    fun shouldUseLocalResponses(providerConfig: IptvProviderConfiguration): Boolean {
        return providerConfig.useLocalResponses ?: iptvConfigurationProperties.useLocalResponses
    }

    /**
     * Checks if responses should be saved for the given provider.
     */
    fun shouldSaveResponses(): Boolean {
        return iptvConfigurationProperties.responseSaveFolder != null
    }
}

