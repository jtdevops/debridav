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
    // Configuration for limiting data served to rclone/arrs requests (for metadata analysis)
    val enableRcloneArrsDataLimiting: Boolean = false,
    val rcloneArrsUserAgentPattern: String?,
    val rcloneArrsHostnamePattern: String?,
    val rcloneArrsMaxDataKb: Long = 256, // Maximum data to serve in KB
    val rcloneArrsCacheEnabled: Boolean = true, // Enable local caching of limited data
    val rcloneArrsCacheSizeMb: Long = 100, // Maximum cache size in MB
    val rcloneArrsCacheExpiryMinutes: Long = 30, // Cache expiry time in minutes
    val rcloneArrsCacheKeyStrategy: String = "filepath-only", // Cache key strategy: "filepath-only" or "filepath-range"
    val rcloneArrsDirectDownloadThresholdKb: Long? = null, // Direct download threshold in KB (null = disabled)
    val enableReactiveLinkRefresh: Boolean = false, // Enable reactive link refresh instead of proactive refresh
    // Local video file approach for ARR projects
    val enableRcloneArrsLocalVideo: Boolean = false, // Enable serving local video files for ARR requests
    val rcloneArrsLocalVideoFilePaths: String? = null, // Video file mapping as comma-separated key=value pairs
    val rcloneArrsLocalVideoPathRegex: String? = null, // Regex pattern to match file paths for local video serving
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
        require(rcloneArrsCacheKeyStrategy in listOf("filepath-only", "filepath-range")) {
            "rcloneArrsCacheKeyStrategy must be either 'filepath-only' or 'filepath-range'"
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
     * Generates a consistent cache key based on the configured strategy
     */
    fun generateCacheKey(filePath: String?, range: io.milton.http.Range?): String? {
        if (filePath == null) return null
        
        return when (rcloneArrsCacheKeyStrategy) {
            "filepath-only" -> filePath
            "filepath-range" -> {
                if (range != null) {
                    "${filePath}-${range.start}-${range.finish}"
                } else {
                    filePath
                }
            }
            else -> filePath // fallback to filepath-only
        }
    }

    /**
     * Data class representing the result of range limiting for rclone/arrs requests
     */
    data class LimitedRangeResult(
        val range: io.milton.http.Range,
        val shouldUseByteDuplication: Boolean = false
    )

    /**
     * Checks if the given HTTP request info matches rclone/arrs patterns for data limiting.
     * Returns true if EITHER the user agent matches OR the hostname matches.
     */
    fun shouldLimitDataForRcloneArrs(httpRequestInfo: io.skjaere.debridav.stream.HttpRequestInfo): Boolean {
        // If feature is not enabled, return false
        if (!enableRcloneArrsDataLimiting) return false

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
     * Returns a limited range result for rclone/arrs requests, or the original range if not applicable.
     * Returns the original range with a flag indicating if byte duplication should be used
     * instead of actually limiting the range.
     */
    fun getLimitedRangeForRcloneArrs(originalRange: io.milton.http.Range, httpRequestInfo: io.skjaere.debridav.stream.HttpRequestInfo): LimitedRangeResult {
        if (!shouldLimitDataForRcloneArrs(httpRequestInfo)) {
            return LimitedRangeResult(originalRange, false)
        }

        val maxBytes = rcloneArrsMaxDataKb * 1024L
        val requestedSize = originalRange.finish - originalRange.start + 1

        // If the requested range is already smaller than or equal to our limit, don't modify it
        if (requestedSize <= maxBytes) {
            return LimitedRangeResult(originalRange, false)
        }

        // Return the original range but with byte duplication flag set to true
        return LimitedRangeResult(originalRange, true)
    }

    /**
     * Checks if we should serve a local video file for ARR requests instead of the actual media file.
     * This helps reduce bandwidth usage by serving a small local file for metadata analysis.
     */
    fun shouldServeLocalVideoForArrs(httpRequestInfo: io.skjaere.debridav.stream.HttpRequestInfo): Boolean {
        if (!enableRcloneArrsLocalVideo || rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
            return false
        }
        
        // Use the same detection logic as rclone/arrs data limiting
        return shouldLimitDataForRcloneArrs(httpRequestInfo)
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
