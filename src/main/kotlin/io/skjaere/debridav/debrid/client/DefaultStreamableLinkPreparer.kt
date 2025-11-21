package io.skjaere.debridav.debrid.client


import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.fs.CachedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

const val RETRIES = 3L

class DefaultStreamableLinkPreparer(
    override val httpClient: HttpClient,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val rateLimiter: RateLimiter,
    private val userAgent: String?
) : StreamableLinkPreparable {
    private val logger = LoggerFactory.getLogger(DefaultStreamableLinkPreparer::class.java)

    init {
        // Log the effective logger level at startup for debugging
        logger.info("DefaultStreamableLinkPreparer logger initialized: loggerName={}, effectiveLevel={}", 
            DefaultStreamableLinkPreparer::class.java.name,
            if (logger.isTraceEnabled) "TRACE" else if (logger.isDebugEnabled) "DEBUG" else if (logger.isInfoEnabled) "INFO" else if (logger.isWarnEnabled) "WARN" else "ERROR")
    }

    constructor(
        httpClient: HttpClient,
        debridavConfigurationProperties: DebridavConfigurationProperties,
        rateLimiter: RateLimiter
    ) : this(httpClient, debridavConfigurationProperties, rateLimiter, null)

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
        
        // Check if IPTV content has default file size (2GB for movies, 1GB for episodes)
        // If so, skip Range header to allow automatic redirect following
        val hasDefaultIptvSize = isIptv && debridLink.size != null && 
            (debridLink.size == 2_000_000_000L || debridLink.size == 1_000_000_000L)
        
        return try {
            rateLimiter.executeSuspendFunction {
                httpClient.prepareGet(debridLink.link!!) {
                headers {
                    // Skip Range header for IPTV content with default file size to allow automatic redirect following
                    if (!hasDefaultIptvSize) {
                        // Always handle byte range requests - the chunking control is internal
                        range?.let { range ->
                            getByteRange(range, debridLink.size!!)?.let { byteRange ->
                                // Only apply byte range if chunking is not disabled
                                if (!debridavConfigurationProperties.disableByteRangeRequestChunking) {
                                    logger.info(
                                        "Applying byteRange $byteRange " +
                                                "for ${debridLink.link}" +
                                                " (${FileUtils.byteCountToDisplaySize(byteRange.getSize())}) "
                                    )

                                    if (!(range.start == 0L && range.finish == debridLink.size)) {
                                        append(HttpHeaders.Range, "bytes=${byteRange.start}-${byteRange.end}")
                                    }
                                } else {
                                    logger.info("Byte range chunking disabled - using exact user range")
                                    // When chunking is disabled, use the exact range requested by user
                                    if (!(range.start == 0L && range.finish == debridLink.size)) {
                                        append(HttpHeaders.Range, "bytes=${range.start}-${range.finish}")
                                    }
                                }
                            }
                        }
                    } else {
                        logger.debug("Skipping Range header for IPTV content with default file size to enable automatic redirect following: path={}, size={}", 
                            debridLink.path, debridLink.size)
                    }

                    // Use user agent from constructor if provided
                    userAgent?.let {
                        append(HttpHeaders.UserAgent, it)
                    }
                }
                
                // For IPTV URLs, log that insecure SSL and redirect following are enabled
                // (SSL trust-all and redirect following are configured at the HTTP client engine level)
                // When no Range header is sent, HttpRedirect plugin can automatically follow redirects
                if (isIptv) {
                    if (hasDefaultIptvSize) {
                        logger.debug("Detected IPTV URL with default file size, using automatic redirect following (no Range header): ${debridLink.link?.take(100)}")
                    } else {
                        logger.debug("Detected IPTV URL, using insecure SSL and redirect following: ${debridLink.link?.take(100)}")
                    }
                }
                
                timeout {
                    requestTimeoutMillis = 20_000_000
                    socketTimeoutMillis = 10_000
                    connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds
                }
            }
        }
        } catch (e: Exception) {
            // TRACE level logging for HTTP request exceptions with full stack trace
            logger.trace("HTTP_REQUEST_EXCEPTION: Exception preparing HTTP request: path={}, link={}, provider={}, exceptionClass={}", 
                debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
            // Explicitly log stack trace to ensure it appears
            logger.trace("HTTP_REQUEST_EXCEPTION_STACK_TRACE", e)
            throw e
        }
    }

    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean = flow {
        logger.debug("LINK_ALIVE_HTTP_CHECK: file={}, provider={}, link={}, size={} bytes", 
            debridLink.path, debridLink.provider, debridLink.link?.take(50) + "...", debridLink.size)
        val isIptv = isIptvUrl(debridLink.link ?: "")
        try {
            rateLimiter.executeSuspendFunction {
                val result = httpClient.head(debridLink.link!!).status.isSuccess()
                logger.debug("LINK_ALIVE_HTTP_RESULT: file={}, provider={}, isAlive={}", 
                    debridLink.path, debridLink.provider, result)
                emit(result)
            }
        } catch (e: Exception) {
            // TRACE level logging for HTTP HEAD request exceptions with full stack trace
            logger.trace("HTTP_HEAD_EXCEPTION: Exception checking link alive: path={}, link={}, provider={}, exceptionClass={}", 
                debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
            // Explicitly log stack trace to ensure it appears
            logger.trace("HTTP_HEAD_EXCEPTION_STACK_TRACE", e)
            throw e
        }
    }.retry(RETRIES)
        .first()
}
