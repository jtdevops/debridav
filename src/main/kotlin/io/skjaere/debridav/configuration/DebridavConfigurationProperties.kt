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
    }

    fun getMaxCacheSizeInBytes(): Long {
        return (cacheMaxSizeGb * ONE_K * ONE_K * ONE_K).toLong()
    }

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
     * Returns a limited range for rclone/arrs requests, or the original range if not applicable.
     * Only limits the range if the requested range is larger than the configured maximum.
     */
    fun getLimitedRangeForRcloneArrs(originalRange: io.milton.http.Range, httpRequestInfo: io.skjaere.debridav.stream.HttpRequestInfo): io.milton.http.Range {
        if (!shouldLimitDataForRcloneArrs(httpRequestInfo)) {
            return originalRange
        }

        val maxBytes = rcloneArrsMaxDataKb * 1024L
        val requestedSize = originalRange.finish - originalRange.start + 1

        // If the requested range is already smaller than or equal to our limit, don't modify it
        if (requestedSize <= maxBytes) {
            return originalRange
        }

        // Otherwise, limit it to our maximum
        val limitedEnd = originalRange.start + maxBytes - 1
        return io.milton.http.Range(originalRange.start, limitedEnd)
    }
}
