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
    val enableReactiveLinkRefresh: Boolean = false, // Enable reactive link refresh instead of proactive refresh
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
}
