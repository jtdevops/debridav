package io.skjaere.debridav.configuration

import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration


private const val ONE_K = 1024 * 1024
private const val DEFAULT_LINK_LIVENESS_CACHE_MINUTES = 15L
private const val DEFAULT_CACHED_FILE_CACHE_MINUTES = 30L
private const val DEFAULT_STREAMING_DELAY_MILLIS = 50L
private const val DEFAULT_STREAMING_RETRIES = 2L
private const val DEFAULT_STREAMING_NETWORK_ERROR_WAIT_MILLIS = 100L
private const val DEFAULT_STREAMING_CLIENT_ERROR_WAIT_MILLIS = 100L
private const val DEFAULT_STREAMING_PROVIDER_ERROR_WAIT_MINUTES = 1L

@ConfigurationProperties(prefix = "debridav")
data class DebridavConfigurationProperties(
    val rootPath: String,
    val downloadPath: String,
    val mountPath: String,
    var debridClients: List<DebridProvider>,
    val waitAfterMissing: Duration,
    val waitAfterProviderError: Duration,
    val waitAfterNetworkError: Duration,
    val waitAfterClientError: Duration,
    val retriesOnProviderError: Long,
    val delayBetweenRetries: Duration,
    val connectTimeoutMilliseconds: Long,
    val readTimeoutMilliseconds: Long,
    val shouldDeleteNonWorkingFiles: Boolean,
    val torrentLifetime: Duration,
    val enableFileImportOnStartup: Boolean,
    val chunkCachingSizeThreshold: Long,
    val chunkCachingGracePeriod: Duration,
    val defaultCategories: List<String>,
    val localEntityMaxSizeMb: Int,
    val cacheMaxSizeGb: Double,
    val linkLivenessCacheDuration: Duration = Duration.ofMinutes(DEFAULT_LINK_LIVENESS_CACHE_MINUTES),
    val cachedFileCacheDuration: Duration = Duration.ofMinutes(DEFAULT_CACHED_FILE_CACHE_MINUTES),
    val streamingDelayBetweenRetries: Duration = Duration.ofMillis(DEFAULT_STREAMING_DELAY_MILLIS),
    val streamingRetriesOnProviderError: Long = DEFAULT_STREAMING_RETRIES,
    val streamingWaitAfterNetworkError: Duration = Duration.ofMillis(DEFAULT_STREAMING_NETWORK_ERROR_WAIT_MILLIS),
    val streamingWaitAfterProviderError: Duration = Duration.ofMinutes(DEFAULT_STREAMING_PROVIDER_ERROR_WAIT_MINUTES),
    val streamingWaitAfterClientError: Duration = Duration.ofMillis(DEFAULT_STREAMING_CLIENT_ERROR_WAIT_MILLIS),
    val enableChunkCaching: Boolean = true,
    val enableInMemoryBuffering: Boolean = true,
    val disableByteRangeRequestChunking: Boolean = false,
    val enableStreamingDownloadTracking: Boolean = false,
    val enableReactiveLinkRefresh: Boolean = false, // Enable reactive link refresh instead of proactive refresh
    // Local video file approach for ARR projects
    val enableRcloneArrsLocalVideo: Boolean = false, // Enable serving local video files for ARR requests
    val rcloneArrsLocalVideoFilePaths: String? = null, // Video file mapping as comma-separated key=value pairs
    val rcloneArrsLocalVideoPathRegex: String? = null, // Regex pattern to match file paths for local video serving
    val rcloneArrsUserAgentPattern: String?, // User agent pattern for ARR detection
    val rcloneArrsHostnamePattern: String? // Hostname pattern for ARR detection
) {
    init {
        require(debridClients.isNotEmpty()) {
            "No debrid providers defined"
        }
        if (enableChunkCaching) {
            require((cacheMaxSizeGb * ONE_K * ONE_K) > localEntityMaxSizeMb) {
                "debridav.cache-max-size-gb must be greater than debridav.chunk-caching-size-threshold in Gb"
            }
        }
        if (enableRcloneArrsLocalVideo) {
            require(!rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
                "rcloneArrsLocalVideoFilePaths must be specified when enableRcloneArrsLocalVideo is true"
            }
        }
    }

    fun getMaxCacheSizeInBytes(): Long {
        return (cacheMaxSizeGb * ONE_K * ONE_K * ONE_K).toLong()
    }


    /**
     * Checks if we should serve a local video file for ARR requests instead of the actual media file.
     * This helps reduce bandwidth usage by serving a small local file for metadata analysis.
     */
    fun shouldServeLocalVideoForArrs(httpRequestInfo: io.skjaere.debridav.stream.HttpRequestInfo): Boolean {
        if (!enableRcloneArrsLocalVideo || rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
            return false
        }
        
        // Check user agent
        val userAgent = httpRequestInfo.headers["user-agent"]
        if (userAgent != null && rcloneArrsUserAgentPattern != null && userAgent.contains(rcloneArrsUserAgentPattern)) {
            return true
        }

        // Check hostname
        val sourceInfo = httpRequestInfo.sourceInfo
        if (sourceInfo != null && rcloneArrsHostnamePattern != null && sourceInfo.contains(rcloneArrsHostnamePattern)) {
            return true
        }

        return false
    }

    /**
     * Checks if the given file path matches the configured regex pattern for local video serving.
     * Returns true if no regex is configured (matches all paths) or if the path matches the regex.
     */
    fun shouldServeLocalVideoForPath(filePath: String): Boolean {
        if (rcloneArrsLocalVideoPathRegex == null) {
            return true // No regex configured, serve for all paths
        }
        
        return try {
            filePath.matches(Regex(rcloneArrsLocalVideoPathRegex))
        } catch (e: Exception) {
            false // Invalid regex, don't serve
        }
    }

    /**
     * Detects the resolution from a file name (case-insensitive).
     * Returns the resolution key if found, null otherwise.
     */
    fun detectResolutionFromFileName(fileName: String): String? {
        val fileNameLower = fileName.lowercase()
        
        // Common resolution patterns
        val resolutionPatterns = listOf(
            "2160p", "4k", "uhd",
            "1080p", "1080i",
            "720p", "720i", 
            "480p", "480i",
            "360p", "360i",
            "240p", "240i"
        )
        
        for (pattern in resolutionPatterns) {
            if (fileNameLower.contains(pattern)) {
                return pattern
            }
        }
        
        return null
    }

    /**
     * Parses the rcloneArrsLocalVideoFilePaths string into a map.
     * Format: "key1=value1,key2=value2" or just "value" for default
     */
    fun parseLocalVideoFilePaths(): Map<String, String> {
        if (rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
            return emptyMap()
        }
        
        return rcloneArrsLocalVideoFilePaths.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                if (entry.contains("=")) {
                    val parts = entry.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else null
                } else {
                    // No key, treat as default
                    "" to entry
                }
            }.toMap()
    }

    /**
     * Gets the appropriate local video file path based on resolution detection and configuration.
     * Returns the file path to serve, or null if no suitable file is found.
     */
    fun getLocalVideoFilePath(fileName: String): String? {
        val filePaths = parseLocalVideoFilePaths()
        
        // First, try to detect resolution from filename
        val detectedResolution = detectResolutionFromFileName(fileName)
        
        if (detectedResolution != null) {
            // Look for exact match first
            filePaths[detectedResolution]?.let { return it }
            
            // Look for case-insensitive match
            filePaths.entries.find { 
                it.key.lowercase() == detectedResolution 
            }?.value?.let { return it }
            
            // Look for pipe-separated keys (e.g., "2160p|1080p", "720p|")
            filePaths.entries.forEach { (key, value) ->
                val resolutionKeys = key.split("|").map { it.trim() }
                if (resolutionKeys.contains(detectedResolution) || 
                    resolutionKeys.any { it.lowercase() == detectedResolution.lowercase() }) {
                    return value
                }
            }
        }
        
        // Look for default entries (keys ending with "|", starting with "=", or empty key)
        filePaths.entries.find { 
            it.key.endsWith("|") || it.key.startsWith("=") || it.key.isEmpty() 
        }?.value?.let { return it }
        
        return null
    }
}
