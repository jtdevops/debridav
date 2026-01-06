package io.skjaere.debridav.debrid.client


import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.fs.CachedFile
import kotlinx.coroutines.delay
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

class DefaultStreamableLinkPreparer(
    override val httpClient: HttpClient,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val rateLimiter: RateLimiter,
    private val userAgent: String?
) : StreamableLinkPreparable {
    private val logger = LoggerFactory.getLogger(DefaultStreamableLinkPreparer::class.java)

    init {
        // Log the effective logger level at startup for debugging
        logger.debug("DefaultStreamableLinkPreparer logger initialized: loggerName={}, effectiveLevel={}", 
            DefaultStreamableLinkPreparer::class.java.name,
            if (logger.isTraceEnabled) "TRACE" else if (logger.isDebugEnabled) "DEBUG" else if (logger.isInfoEnabled) "INFO" else if (logger.isWarnEnabled) "WARN" else "ERROR")
    }

    constructor(
        httpClient: HttpClient,
        debridavConfigurationProperties: DebridavConfigurationProperties,
        rateLimiter: RateLimiter
    ) : this(httpClient, debridavConfigurationProperties, rateLimiter, null)

    /**
     * Calculates dynamic socket timeout based on chunk size.
     * Larger chunks need more time to download, especially from slower IPTV providers.
     * 
     * Formula: base timeout (10s) + 1 second per 10 MB of chunk size, capped at 300 seconds (5 minutes)
     * 
     * @param chunkSizeBytes The size of the chunk in bytes
     * @return Socket timeout in milliseconds
     */
    private fun calculateSocketTimeout(chunkSizeBytes: Long): Long {
        val baseTimeoutMs = 10_000L // 10 seconds base timeout
        val chunkSizeMB = chunkSizeBytes / (1024 * 1024) // Convert bytes to MB
        val additionalTimeoutMs = chunkSizeMB * 1000L // 1 second per MB
        val maxTimeoutMs = 300_000L // 5 minutes maximum timeout
        
        val calculatedTimeout = baseTimeoutMs + additionalTimeoutMs
        return minOf(calculatedTimeout, maxTimeoutMs)
    }

    /**
     * Detects if a URL is likely an IPTV content URL.
     * IPTV URLs typically come from Xtream Codes providers and have patterns like:
     * - {baseUrl}/movie/{username}/{password}/{id}.{ext}
     * - {baseUrl}/series/{username}/{password}/{id}.{ext}
     * - {baseUrl}/live/{username}/{password}/{id}.{ext}
     * - Or M3U playlist URLs
     */
    private fun isIptvUrl(url: String): Boolean {
        if (url.isBlank()) {
            return false
        }
        
        // Check for Xtream Codes patterns (most common IPTV format)
        // Pattern: /movie/ or /series/ or /live/ followed by username/password/id.ext
        val xtreamPattern = Regex(".*/(movie|series|live)/[^/]+/[^/]+/[^/]+\\.(mp4|mkv|avi|ts|mov|m4v|m2ts|mts|vob|flv|webm|m3u8)$", RegexOption.IGNORE_CASE)
        if (xtreamPattern.matches(url)) {
            return true
        }
        
        // Check for M3U playlist URLs
        if (url.contains(".m3u", ignoreCase = true)) {
            return true
        }
        
        // Check if provider is not a known debrid provider (heuristic)
        // This is detected at the StreamingService level, but we can also check URL patterns
        // If URL doesn't match known debrid patterns, it might be IPTV
        val debridPatterns = listOf(
            "real-debrid.com",
            "premiumize.me",
            "easynews.com",
            "torbox.app"
        )
        val isDebridUrl = debridPatterns.any { url.contains(it, ignoreCase = true) }
        
        // If it's not a debrid URL and matches video file patterns, assume IPTV
        if (!isDebridUrl && url.matches(Regex(".*\\.(mp4|mkv|avi|ts|mov|m4v|m2ts|mts|vob|flv|webm|m3u8)$", RegexOption.IGNORE_CASE))) {
            return true
        }
        
        return false
    }

    @Suppress("MagicNumber")
    override suspend fun prepareStreamUrl(debridLink: CachedFile, range: Range?): HttpStatement {
        val isIptv = isIptvUrl(debridLink.link ?: "")
        val maxRetries = debridavConfigurationProperties.streamingRetriesOnProviderError.toInt()
        val delayBetweenRetries = debridavConfigurationProperties.streamingDelayBetweenRetries
        val waitAfterNetworkError = debridavConfigurationProperties.streamingWaitAfterNetworkError
        
        for (attempt in 0..maxRetries) {
            try {
                return rateLimiter.executeSuspendFunction {
                    httpClient.prepareGet(debridLink.link!!) {
                    headers {
                        // Apply Range headers to the original URL (including IPTV URLs)
                        // Range headers will be re-applied to redirect URLs in StreamingService to ensure providers honor the requested range
                        range?.let { range ->
                            getByteRange(range, debridLink.size!!)?.let { byteRange ->
                                // Only apply byte range if chunking is not disabled
                                if (!debridavConfigurationProperties.disableByteRangeRequestChunking) {
                                    logger.debug(
                                        "Applying byteRange $byteRange " +
                                                "for ${debridLink.link}" +
                                                " (${FileUtils.byteCountToDisplaySize(byteRange.getSize())}) " +
                                                if (isIptv) "(IPTV - Range header will be re-applied on redirect URLs)" else ""
                                    )

                                    if (!(range.start == 0L && range.finish == debridLink.size)) {
                                        append(HttpHeaders.Range, "bytes=${byteRange.start}-${byteRange.end}")
                                    }
                                } else {
                                    logger.debug("Byte range chunking disabled - using exact user range" +
                                            if (isIptv) " (IPTV - Range header will be re-applied on redirect URLs)" else "")
                                    // When chunking is disabled, use the exact range requested by user
                                    if (!(range.start == 0L && range.finish == debridLink.size)) {
                                        append(HttpHeaders.Range, "bytes=${range.start}-${range.finish}")
                                    }
                                }
                            }
                        }

                        // Use user agent from constructor if provided
                        userAgent?.let {
                            append(HttpHeaders.UserAgent, it)
                        }
                    }
                    
                    if (isIptv) {
                        logger.debug("Detected IPTV URL - Range headers applied to original URL, will be re-applied on redirect URLs: ${debridLink.link?.take(100)}")
                    }
                    
                    // Calculate dynamic socket timeout based on chunk size
                    val chunkSizeBytes = range?.let { 
                        // Calculate chunk size: finish - start + 1 (both are inclusive)
                        it.finish - it.start + 1
                    } ?: debridLink.size!!
                    val dynamicSocketTimeout = calculateSocketTimeout(chunkSizeBytes)
                    
                    logger.debug("Dynamic socket timeout calculated: chunkSize={} bytes ({}), timeout={} ms", 
                        chunkSizeBytes, FileUtils.byteCountToDisplaySize(chunkSizeBytes), dynamicSocketTimeout)
                    
                    timeout {
                        requestTimeoutMillis = 20_000_000
                        socketTimeoutMillis = dynamicSocketTimeout
                        connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds
                    }
                }
                }
            } catch (e: Exception) {
                // Check if this is a network/connection error that should be retried
                val isNetworkError = e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("network", ignoreCase = true) == true ||
                        e.message?.contains("connect", ignoreCase = true) == true
                
                // Only retry on network/connection errors, not on HTTP errors (404, 403, etc.)
                // HTTP errors are typically not retryable and should fail immediately
                val shouldRetry = isNetworkError && attempt < maxRetries
                
                if (shouldRetry) {
                    val waitTime = if (isNetworkError) waitAfterNetworkError else delayBetweenRetries
                    logger.debug("Failed to prepare stream URL (attempt ${attempt + 1}/${maxRetries + 1}), retrying after ${waitTime.toMillis()}ms: path={}, provider={}, error={}", 
                        debridLink.path, debridLink.provider, e.message)
                    delay(waitTime.toMillis())
                } else {
                    // TRACE level logging for HTTP request exceptions with full stack trace
                    logger.trace("HTTP_REQUEST_EXCEPTION: Exception preparing HTTP request: path={}, link={}, provider={}, exceptionClass={}, attempt=${attempt + 1}/${maxRetries + 1}", 
                        debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, attempt + 1, maxRetries + 1)
                    // Explicitly log stack trace to ensure it appears
                    logger.trace("HTTP_REQUEST_EXCEPTION_STACK_TRACE", e)
                    throw e
                }
            }
        }
        
        // Should never reach here, but throw exception as fallback
        throw RuntimeException("Failed to prepare stream URL after ${maxRetries + 1} attempts: path=${debridLink.path}")
    }

    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean {
        val maxRetries = debridavConfigurationProperties.streamingRetriesOnProviderError.toInt()
        val delayBetweenRetries = debridavConfigurationProperties.streamingDelayBetweenRetries
        val waitAfterNetworkError = debridavConfigurationProperties.streamingWaitAfterNetworkError
        
        for (attempt in 0..maxRetries) {
            try {
                logger.debug("LINK_ALIVE_HTTP_CHECK: file={}, provider={}, link={}, size={} bytes", 
                    debridLink.path, debridLink.provider, debridLink.link?.take(50) + "...", debridLink.size)
                val isIptv = isIptvUrl(debridLink.link ?: "")
                
                return rateLimiter.executeSuspendFunction {
                    // Use GET with Range header (bytes=0-0) instead of HEAD
                    // HEAD requests are not supported by all servers, but byte range requests are more universally supported
                    val result = httpClient.get(debridLink.link!!) {
                        headers {
                            append(io.ktor.http.HttpHeaders.Range, "bytes=0-0")
                        }
                        timeout {
                            requestTimeoutMillis = 5000 // 5 second timeout - fail fast
                            connectTimeoutMillis = 2000 // 2 second connect timeout
                        }
                    }.status.isSuccess()
                    logger.debug("LINK_ALIVE_HTTP_RESULT: file={}, provider={}, isAlive={}", 
                        debridLink.path, debridLink.provider, result)
                    result
                }
            } catch (e: Exception) {
                val isNetworkError = e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("network", ignoreCase = true) == true
                
                if (attempt < maxRetries) {
                    val waitTime = if (isNetworkError) waitAfterNetworkError else delayBetweenRetries
                    logger.debug("Failed to check if link is alive (attempt ${attempt + 1}/${maxRetries + 1}), retrying after ${waitTime.toMillis()}ms: path={}, provider={}, error={}", 
                        debridLink.path, debridLink.provider, e.message)
                    delay(waitTime.toMillis())
                } else {
                    // TRACE level logging for HTTP request exceptions with full stack trace
                    logger.trace("HTTP_RANGE_REQUEST_EXCEPTION: Exception checking link alive after ${maxRetries + 1} attempts: path={}, link={}, provider={}, exceptionClass={}", 
                        debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
                    // Explicitly log stack trace to ensure it appears
                    logger.trace("HTTP_RANGE_REQUEST_EXCEPTION_STACK_TRACE", e)
                    throw e
                }
            }
        }
        
        // Should never reach here, but return false as fallback
        return false
    }
}
