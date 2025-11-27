package io.skjaere.debridav.iptv.util

import io.skjaere.debridav.iptv.IptvProvider
import io.skjaere.debridav.iptv.IptvSyncHashRepository
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

@Service
class IptvResponseFileService(
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvSyncHashRepository: IptvSyncHashRepository
) {
    private val logger = LoggerFactory.getLogger(IptvResponseFileService::class.java)
    
    // Threshold for detecting empty responses: if new response is less than 10% of cached size, skip overwrite
    private val EMPTY_RESPONSE_SIZE_THRESHOLD = 0.1

    /**
     * Saves a response to a file for the given provider and response type.
     * Files are named: {provider-name}_{response-type}.{extension}
     * Checks if the new response is significantly smaller than cached (empty array detection) and skips overwrite if so.
     * 
     * @param providerConfig The provider configuration
     * @param responseType The type of response (e.g., "vod_streams", "vod_categories", "m3u")
     * @param content The content to save
     * @param extension File extension (default: "json" for Xtream Codes, "m3u" for M3U)
     * @return true if file was saved, false if skipped due to empty response detection
     */
    fun saveResponse(
        providerConfig: IptvProviderConfiguration,
        responseType: String,
        content: String,
        extension: String = when (providerConfig.type) {
            IptvProvider.M3U -> "m3u"
            IptvProvider.XTREAM_CODES -> "json"
        }
    ): Boolean {
        val saveFolder = iptvConfigurationProperties.responseSaveFolder ?: return false
        
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
            
            // Check for empty response detection: if cached file exists and new content is significantly smaller
            if (file.exists() && file.length() > 0) {
                val cachedSize = file.length()
                val newSize = content.length.toLong()
                
                // Check if new response is empty array or significantly smaller (less than 10% of cached size)
                val isEmptyArray = content.trim() == "[]" || content.trim() == "{}"
                val isSignificantlySmaller = cachedSize > 0 && newSize < (cachedSize * EMPTY_RESPONSE_SIZE_THRESHOLD)
                
                if (isEmptyArray || isSignificantlySmaller) {
                    logger.warn("Skipping save of ${providerConfig.type} response for provider ${providerConfig.name} (type: $responseType) - " +
                            "new response appears empty or significantly smaller (cached: $cachedSize bytes, new: $newSize bytes). " +
                            "This may indicate a temporary API issue. Keeping cached file.")
                    return false
                }
            }
            
            file.writeText(content)
            val fileSize = file.length()
            
            // Update file size in sync hash table if it exists
            val syncHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, responseType)
            if (syncHash != null) {
                syncHash.fileSize = fileSize
                iptvSyncHashRepository.save(syncHash)
            }
            
            logger.debug("Saved ${providerConfig.type} response for provider ${providerConfig.name} to: ${file.absolutePath} (size: $fileSize bytes)")
            return true
        } catch (e: Exception) {
            logger.warn("Failed to save IPTV response for provider ${providerConfig.name}: ${e.message}", e)
            return false
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

