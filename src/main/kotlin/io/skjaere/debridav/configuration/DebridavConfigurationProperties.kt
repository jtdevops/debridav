package io.skjaere.debridav.configuration

import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration


private const val ONE_K = 1024 * 1024

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
    val linkLivenessCacheDuration: Duration = Duration.ofMinutes(15),
    val cachedFileCacheDuration: Duration = Duration.ofMinutes(30),
    val streamingDelayBetweenRetries: Duration = Duration.ofMillis(50),
    val streamingRetriesOnProviderError: Long = 2,
    val streamingWaitAfterNetworkError: Duration = Duration.ofMillis(100),
    val streamingWaitAfterProviderError: Duration = Duration.ofMinutes(1),
    val streamingWaitAfterClientError: Duration = Duration.ofMillis(100),
) {
    init {
        require(debridClients.isNotEmpty()) {
            "No debrid providers defined"
        }
        require((cacheMaxSizeGb * ONE_K * ONE_K) > localEntityMaxSizeMb) {
            "debridav.cache-max-size-gb must be greater than debridav.chunk-caching-size-threshold in Gb"
        }
    }

    fun getMaxCacheSizeInBytes(): Long {
        return (cacheMaxSizeGb * ONE_K * ONE_K * ONE_K).toLong()
    }
}
