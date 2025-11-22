package io.skjaere.debridav.iptv.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.skjaere.debridav.iptv.IptvRequestService
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant

/**
 * Diagnostic service for testing IPTV redirect and streaming performance.
 * Enabled via debridav.iptv.enable-redirect-diagnostics configuration property.
 */
@Service
class IptvRedirectDiagnosticsService(
    private val httpClient: HttpClient,
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvRequestService: IptvRequestService
) {
    private val logger = LoggerFactory.getLogger(IptvRedirectDiagnosticsService::class.java)
    
    companion object {
        private const val TEST_DOWNLOAD_SIZE_BYTES = 5 * 1024 * 1024 // 5MB
        private const val SLOW_THRESHOLD_MS = 5000 // 5 seconds
    }
    
    /**
     * Test results for a single test scenario
     */
    data class TestResult(
        val testName: String,
        val success: Boolean,
        val durationMs: Long,
        val errorMessage: String? = null,
        val details: Map<String, Any> = emptyMap()
    )
    
    /**
     * Complete diagnostic report for an IPTV URL
     */
    data class DiagnosticReport(
        val originalUrl: String,
        val timestamp: Instant,
        val tests: List<TestResult>,
        val summary: String
    )
    
    /**
     * Runs comprehensive diagnostics on an IPTV URL.
     * Tests include:
     * - Automatic redirect following
     * - Manual redirect handling
     * - Download 5MB test
     * - HEAD vs GET comparison
     * - Cached vs fresh redirect comparison
     * - Timeout behavior
     */
    fun runDiagnostics(originalUrl: String): DiagnosticReport {
        logger.info("IPTV_REDIRECT_DIAGNOSTICS: Starting diagnostics for URL: {}", originalUrl.take(100))
        
        val tests = mutableListOf<TestResult>()
        
        // Test 1: Simple GET request with automatic redirect following (no Range header)
        tests.add(testSimpleGetWithAutoRedirect(originalUrl))
        
        // Test 2: Automatic redirect following with Range header (let HttpClient handle redirects)
        tests.add(testAutomaticRedirect(originalUrl))
        
        // Test 3: HEAD request to resolve redirect
        tests.add(testHeadRequest(originalUrl))
        
        // Test 4: GET request with Range header (bytes=0-0) to resolve redirect
        tests.add(testGetRequestWithRange(originalUrl))
        
        // Test 5: Manual redirect following
        val manualRedirectResult = testManualRedirect(originalUrl)
        tests.add(manualRedirectResult)
        
        // Test 6: Download 5MB from original URL (handle redirect if needed)
        tests.add(testDownload5MB(originalUrl, "original"))
        
        // Test 7: Download 5MB from redirect URL (if redirect was found)
        val redirectUrl = manualRedirectResult.details["redirectUrl"] as? String
        if (redirectUrl != null) {
            tests.add(testDownload5MB(redirectUrl, "redirect"))
            
            // Test 6b: Test redirect URL expiration (try again after a short delay)
            tests.add(testRedirectUrlExpiration(redirectUrl))
            
            // Test 6c: Test redirect URL Range request support
            tests.add(testRedirectUrlRangeSupport(redirectUrl))
        }
        
        // Test 8: Cached redirect URL test
        tests.add(testCachedRedirectUrl(originalUrl))
        
        // Test 9: Compare cached vs fresh redirect
        if (redirectUrl != null) {
            tests.add(testCachedVsFreshRedirect(originalUrl, redirectUrl))
        }
        
        // Test 10: Timeout behavior test
        tests.add(testTimeoutBehavior(originalUrl))
        
        // Test 11: OkHttp client redirect test
        tests.add(testOkHttpRedirect(originalUrl))
        
        // Test 12: Ktor Java engine redirect test
        tests.add(testKtorJavaEngineRedirect(originalUrl))
        
        // Generate summary
        val successfulTests = tests.count { it.success }
        val failedTests = tests.count { !it.success }
        val slowTests = tests.count { it.durationMs > SLOW_THRESHOLD_MS }
        val averageDuration = tests.map { it.durationMs }.average()
        
        val summary = buildString {
            appendLine("Diagnostics Summary:")
            appendLine("  Total tests: ${tests.size}")
            appendLine("  Successful: $successfulTests")
            appendLine("  Failed: $failedTests")
            appendLine("  Slow (>${SLOW_THRESHOLD_MS}ms): $slowTests")
            appendLine("  Average duration: ${averageDuration.toLong()}ms")
            appendLine()
            appendLine("Test Details:")
            tests.forEach { test ->
                val status = if (test.success) "✓" else "✗"
                val speed = if (test.durationMs > SLOW_THRESHOLD_MS) " (SLOW)" else ""
                appendLine("  $status ${test.testName}: ${test.durationMs}ms$speed")
                if (!test.success && test.errorMessage != null) {
                    appendLine("    Error: ${test.errorMessage}")
                }
                test.details.forEach { (key, value) ->
                    appendLine("    $key: $value")
                }
            }
        }
        
        logger.info("IPTV_REDIRECT_DIAGNOSTICS: Completed diagnostics\n{}", summary)
        
        return DiagnosticReport(
            originalUrl = originalUrl,
            timestamp = Instant.now(),
            tests = tests,
            summary = summary
        )
    }
    
    /**
     * Test 1: Simple GET request with automatic redirect following (no Range header)
     * This tests if Range headers are causing issues with redirects
     * Also checks if HttpRedirect plugin actually follows redirects
     */
    private fun testSimpleGetWithAutoRedirect(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        var connectTime: Long = 0
        var firstByteTime: Long = 0
        
        return try {
            val connectStartTime = System.currentTimeMillis()
            val response = runBlocking {
                httpClient.get(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        // NO Range header - simple GET request
                    }
                    timeout {
                        requestTimeoutMillis = 10000 // 10 second timeout
                        connectTimeoutMillis = 2000
                    }
                }
            }
            connectTime = System.currentTimeMillis() - connectStartTime
            
            val firstByteStartTime = System.currentTimeMillis()
            val wasRedirected = response.status.value in 300..399
            val finalStatusCode = response.status.value
            val redirectLocation = response.headers["Location"]
            
            // Try to read first byte to measure time to first byte
            try {
                runBlocking {
                    val channel = response.body<ByteReadChannel>()
                    channel.toInputStream().use { inputStream ->
                        inputStream.readNBytes(1)
                    }
                }
                firstByteTime = System.currentTimeMillis() - firstByteStartTime
            } catch (e: Exception) {
                firstByteTime = System.currentTimeMillis() - firstByteStartTime
                // Ignore errors when consuming response body
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            // Check if HttpRedirect actually followed the redirect
            // If status is 200/206, redirect was followed; if 302, redirect was not followed
            val redirectWasFollowed = finalStatusCode in 200..299
            
            TestResult(
                testName = "Simple GET with Auto Redirect (No Range)",
                success = response.status.isSuccess() || response.status.value in 300..399,
                durationMs = duration,
                details = mapOf(
                    "originalUrl" to originalUrl.take(100),
                    "wasRedirected" to wasRedirected,
                    "redirectWasFollowed" to redirectWasFollowed,
                    "statusCode" to finalStatusCode,
                    "redirectLocation" to (redirectLocation?.take(100) ?: "none"),
                    "contentType" to (response.headers["Content-Type"] ?: "none"),
                    "contentLength" to (response.headers["Content-Length"] ?: "none"),
                    "connectTimeMs" to connectTime,
                    "firstByteTimeMs" to firstByteTime,
                    "totalDurationMs" to duration
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Simple GET with Auto Redirect (No Range)",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error",
                details = mapOf(
                    "connectTimeMs" to connectTime,
                    "firstByteTimeMs" to firstByteTime
                )
            )
        }
    }
    
    /**
     * Test 2: Automatic redirect following with Range header (let HttpClient handle redirects automatically)
     */
    private fun testAutomaticRedirect(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val response = runBlocking {
                httpClient.get(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 10000 // 10 second timeout
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            // Note: HttpClient may follow redirects automatically, so final URL might differ
            val wasRedirected = response.status.value in 300..399
            
            TestResult(
                testName = "Automatic Redirect Following",
                success = response.status.isSuccess() || response.status.value in 300..399,
                durationMs = duration,
                details = mapOf(
                    "originalUrl" to originalUrl.take(100),
                    "wasRedirected" to wasRedirected,
                    "statusCode" to response.status.value,
                    "redirectLocation" to (response.headers["Location"] ?: "none")
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Automatic Redirect Following",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 2: HEAD request to resolve redirect
     */
    private fun testHeadRequest(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val response = runBlocking {
                httpClient.head(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            val redirectLocation = response.headers["Location"]
            val wasRedirect = response.status.value in 300..399
            
            TestResult(
                testName = "HEAD Request Redirect Resolution",
                success = true,
                durationMs = duration,
                details = mapOf(
                    "statusCode" to response.status.value,
                    "wasRedirect" to wasRedirect,
                    "redirectLocation" to (redirectLocation?.take(100) ?: "none")
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "HEAD Request Redirect Resolution",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 3: GET request with Range header (bytes=0-0) to resolve redirect
     */
    private fun testGetRequestWithRange(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val response = runBlocking {
                httpClient.get(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            val redirectLocation = response.headers["Location"]
            val wasRedirect = response.status.value in 300..399
            
            // Consume response body to ensure proper cleanup
            try {
                runBlocking {
                    response.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            TestResult(
                testName = "GET Request with Range Header",
                success = true,
                durationMs = duration,
                details = mapOf(
                    "statusCode" to response.status.value,
                    "wasRedirect" to wasRedirect,
                    "redirectLocation" to (redirectLocation?.take(100) ?: "none")
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "GET Request with Range Header",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 4: Manual redirect following
     */
    private fun testManualRedirect(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            // Step 1: Get redirect location
            val initialResponse = runBlocking {
                httpClient.get(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val initialDuration = System.currentTimeMillis() - startTime
            
            if (initialResponse.status.value !in 300..399) {
                return TestResult(
                    testName = "Manual Redirect Following",
                    success = true,
                    durationMs = initialDuration,
                    details = mapOf(
                        "statusCode" to initialResponse.status.value,
                        "wasRedirect" to false,
                        "redirectUrl" to "none"
                    )
                )
            }
            
            val redirectLocation = initialResponse.headers["Location"]
            if (redirectLocation == null) {
                return TestResult(
                    testName = "Manual Redirect Following",
                    success = false,
                    durationMs = initialDuration,
                    errorMessage = "Redirect response but no Location header"
                )
            }
            
            // Consume initial response body
            try {
                runBlocking {
                    initialResponse.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            // Step 2: Build absolute redirect URL
            val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                redirectLocation
            } else {
                val originalUri = java.net.URI(originalUrl)
                originalUri.resolve(redirectLocation).toString()
            }
            
            // Step 3: Make request to redirect URL
            val redirectStartTime = System.currentTimeMillis()
            val redirectResponse = runBlocking {
                httpClient.get(redirectUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 10000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val redirectDuration = System.currentTimeMillis() - redirectStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            
            // Consume redirect response body
            try {
                runBlocking {
                    redirectResponse.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            TestResult(
                testName = "Manual Redirect Following",
                success = redirectResponse.status.isSuccess() || redirectResponse.status.value == 206,
                durationMs = totalDuration,
                details = mapOf(
                    "initialStatusCode" to initialResponse.status.value,
                    "redirectUrl" to redirectUrl.take(100),
                    "redirectStatusCode" to redirectResponse.status.value,
                    "initialDurationMs" to initialDuration,
                    "redirectDurationMs" to redirectDuration
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Manual Redirect Following",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 5 & 6: Download 5MB from URL (handles redirects for original URL)
     */
    private fun testDownload5MB(url: String, urlType: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            // Step 1: Make initial request
            val initialResponse = runBlocking {
                httpClient.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-${TEST_DOWNLOAD_SIZE_BYTES - 1}")
                    }
                    timeout {
                        requestTimeoutMillis = 30000 // 30 second timeout for download
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            // Step 2: Handle redirect if needed (for original URL)
            val finalResponse = if (initialResponse.status.value in 300..399 && urlType == "original") {
                val redirectLocation = initialResponse.headers["Location"]
                if (redirectLocation != null) {
                    // Consume initial response
                    try {
                        runBlocking {
                            initialResponse.body<ByteReadChannel>()
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                    
                    // Build absolute redirect URL
                    val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                        redirectLocation
                    } else {
                        val originalUri = java.net.URI(url)
                        originalUri.resolve(redirectLocation).toString()
                    }
                    
                    // Make request to redirect URL
                    runBlocking {
                        httpClient.get(redirectUrl) {
                            headers {
                                append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                                append(HttpHeaders.Range, "bytes=0-${TEST_DOWNLOAD_SIZE_BYTES - 1}")
                            }
                            timeout {
                                requestTimeoutMillis = 30000
                                connectTimeoutMillis = 2000
                            }
                        }
                    }
                } else {
                    initialResponse
                }
            } else {
                initialResponse
            }
            
            if (!finalResponse.status.isSuccess() && finalResponse.status.value != 206) {
                val redirectLocation = finalResponse.headers["Location"]
                return TestResult(
                    testName = "Download 5MB from $urlType URL",
                    success = false,
                    durationMs = System.currentTimeMillis() - startTime,
                    errorMessage = "HTTP ${finalResponse.status.value}",
                    details = mapOf(
                        "statusCode" to finalResponse.status.value,
                        "redirectLocation" to (redirectLocation?.take(100) ?: "none"),
                        "contentType" to (finalResponse.headers["Content-Type"] ?: "none")
                    )
                )
            }
            
            val body: ByteArray = runBlocking {
                val channel: ByteReadChannel = finalResponse.body()
                channel.toInputStream().use { inputStream ->
                    inputStream.readNBytes(TEST_DOWNLOAD_SIZE_BYTES)
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            val bytesDownloaded = body.size
            val speedMbps = if (duration > 0) {
                (bytesDownloaded.toDouble() * 8.0) / (duration.toDouble() / 1000.0) / 1_000_000.0
            } else {
                0.0
            }
            
            TestResult(
                testName = "Download 5MB from $urlType URL",
                success = bytesDownloaded > 0,
                durationMs = duration,
                details = mapOf(
                    "bytesDownloaded" to bytesDownloaded,
                    "speedMbps" to String.format("%.2f", speedMbps),
                    "statusCode" to finalResponse.status.value,
                    "contentType" to (finalResponse.headers["Content-Type"] ?: "none"),
                    "contentLength" to (finalResponse.headers["Content-Length"] ?: "none"),
                    "acceptRanges" to (finalResponse.headers["Accept-Ranges"] ?: "none")
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Download 5MB from $urlType URL",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error",
                details = mapOf(
                    "exceptionType" to (e::class.simpleName ?: "Unknown")
                )
            )
        }
    }
    
    /**
     * Test 6b: Test redirect URL expiration (try again after a short delay)
     */
    private fun testRedirectUrlExpiration(redirectUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            // First request
            val firstResponse = runBlocking {
                httpClient.get(redirectUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val firstStatusCode = firstResponse.status.value
            val firstDuration = System.currentTimeMillis() - startTime
            
            // Consume first response
            try {
                runBlocking {
                    firstResponse.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            // Wait 2 seconds
            Thread.sleep(2000)
            
            // Second request
            val secondStartTime = System.currentTimeMillis()
            val secondResponse = runBlocking {
                httpClient.get(redirectUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val secondStatusCode = secondResponse.status.value
            val secondDuration = System.currentTimeMillis() - secondStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            
            // Consume second response
            try {
                runBlocking {
                    secondResponse.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            val stillValid = firstStatusCode == secondStatusCode && firstStatusCode in 200..299
            
            TestResult(
                testName = "Redirect URL Expiration Test",
                success = stillValid,
                durationMs = totalDuration,
                details = mapOf(
                    "firstStatusCode" to firstStatusCode,
                    "secondStatusCode" to secondStatusCode,
                    "firstDurationMs" to firstDuration,
                    "secondDurationMs" to secondDuration,
                    "stillValid" to stillValid,
                    "delayBetweenRequestsMs" to 2000
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Redirect URL Expiration Test",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 6c: Test redirect URL Range request support
     */
    private fun testRedirectUrlRangeSupport(redirectUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            // Test 1: Request without Range header
            val noRangeResponse = runBlocking {
                httpClient.get(redirectUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val noRangeStatusCode = noRangeResponse.status.value
            val noRangeContentLength = noRangeResponse.headers["Content-Length"]
            
            // Consume response
            try {
                runBlocking {
                    noRangeResponse.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            // Test 2: Request with Range header (first 1KB)
            val rangeResponse = runBlocking {
                httpClient.get(redirectUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-1023")
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val rangeStatusCode = rangeResponse.status.value
            val rangeContentLength = rangeResponse.headers["Content-Length"]
            val rangeContentRange = rangeResponse.headers["Content-Range"]
            val acceptRanges = rangeResponse.headers["Accept-Ranges"]
            
            // Consume response
            try {
                runBlocking {
                    rangeResponse.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            val supportsRange = rangeStatusCode == 206 || (rangeStatusCode == 200 && rangeContentRange != null)
            
            TestResult(
                testName = "Redirect URL Range Support Test",
                success = supportsRange,
                durationMs = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "noRangeStatusCode" to noRangeStatusCode,
                    "noRangeContentLength" to (noRangeContentLength ?: "none"),
                    "rangeStatusCode" to rangeStatusCode,
                    "rangeContentLength" to (rangeContentLength ?: "none"),
                    "rangeContentRange" to (rangeContentRange ?: "none"),
                    "acceptRanges" to (acceptRanges ?: "none"),
                    "supportsRange" to supportsRange
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Redirect URL Range Support Test",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 7: Cached redirect URL test
     */
    private fun testCachedRedirectUrl(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val cachedRedirectUrl = runBlocking {
                iptvRequestService.getCachedRedirectUrl(originalUrl)
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            if (cachedRedirectUrl == null) {
                return TestResult(
                    testName = "Cached Redirect URL Test",
                    success = true,
                    durationMs = duration,
                    details = mapOf(
                        "cachedRedirectUrl" to "none (not cached)",
                        "cacheHit" to false
                    )
                )
            }
            
            // Test accessing cached redirect URL
            val redirectStartTime = System.currentTimeMillis()
            val response = runBlocking {
                httpClient.get(cachedRedirectUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 10000
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val redirectDuration = System.currentTimeMillis() - redirectStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            
            // Consume response body
            try {
                runBlocking {
                    response.body<ByteReadChannel>()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            TestResult(
                testName = "Cached Redirect URL Test",
                success = response.status.isSuccess() || response.status.value == 206,
                durationMs = totalDuration,
                details = mapOf(
                    "cachedRedirectUrl" to cachedRedirectUrl.take(100),
                    "cacheHit" to true,
                    "redirectStatusCode" to response.status.value,
                    "redirectDurationMs" to redirectDuration
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Cached Redirect URL Test",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 8: Compare cached vs fresh redirect
     */
    private fun testCachedVsFreshRedirect(originalUrl: String, freshRedirectUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val cachedRedirectUrl = runBlocking {
                iptvRequestService.getCachedRedirectUrl(originalUrl)
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            val areSame = cachedRedirectUrl == freshRedirectUrl
            
            TestResult(
                testName = "Cached vs Fresh Redirect Comparison",
                success = true,
                durationMs = duration,
                details = mapOf(
                    "cachedRedirectUrl" to (cachedRedirectUrl?.take(100) ?: "none"),
                    "freshRedirectUrl" to freshRedirectUrl.take(100),
                    "areSame" to areSame,
                    "cacheHit" to (cachedRedirectUrl != null)
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Cached vs Fresh Redirect Comparison",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 9: Timeout behavior test
     */
    private fun testTimeoutBehavior(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            // Test with very short timeout to see timeout behavior
            val response = runBlocking {
                httpClient.get(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 1000 // 1 second timeout
                        connectTimeoutMillis = 500 // 500ms connect timeout
                    }
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            TestResult(
                testName = "Timeout Behavior Test",
                success = duration < 2000, // Should complete quickly or timeout
                durationMs = duration,
                details = mapOf(
                    "statusCode" to response.status.value,
                    "completedWithinTimeout" to (duration < 2000)
                )
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val wasTimeout = e.message?.contains("timeout", ignoreCase = true) == true ||
                             e.message?.contains("timed out", ignoreCase = true) == true
            
            TestResult(
                testName = "Timeout Behavior Test",
                success = wasTimeout, // Timeout is expected with short timeout
                durationMs = duration,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error",
                details = mapOf(
                    "wasTimeout" to wasTimeout,
                    "exceptionType" to (e::class.simpleName ?: "Unknown")
                )
            )
        }
    }
    
    /**
     * Test 11: OkHttp client redirect test
     * Tests redirect following using OkHttp client (no headers except User-Agent)
     * If successful, downloads 5MB to prove video streaming works
     */
    private fun testOkHttpRedirect(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)          // follow 3xx redirects
                .followSslRedirects(true)       // follow https<->http redirects
                .build()
            
            val request = Request.Builder()
                .url(originalUrl)
                .header("User-Agent", iptvConfigurationProperties.userAgent)
                .build()
            
            val response = client.newCall(request).execute()
            val redirectDuration = System.currentTimeMillis() - startTime
            
            val finalUrl = response.request.url.toString()
            val wasRedirected = finalUrl != originalUrl
            
            // If successful, download 5MB to prove video streaming works
            var bytesDownloaded = 0
            var downloadDuration = 0L
            var speedMbps = 0.0
            
            if (response.isSuccessful) {
                val downloadStartTime = System.currentTimeMillis()
                response.body?.use { body ->
                    val source = body.source()
                    val buffer = Buffer()
                    var totalBytesRead = 0L
                    
                    while (totalBytesRead < TEST_DOWNLOAD_SIZE_BYTES) {
                        val bytesToRead = minOf(
                            TEST_DOWNLOAD_SIZE_BYTES - totalBytesRead,
                            8192L // Read in 8KB chunks
                        )
                        val bytesRead = source.read(buffer, bytesToRead)
                        if (bytesRead == -1L) {
                            break // End of stream
                        }
                        totalBytesRead += bytesRead
                        // Clear buffer after reading to avoid memory buildup
                        buffer.clear()
                    }
                    
                    bytesDownloaded = totalBytesRead.toInt()
                }
                downloadDuration = System.currentTimeMillis() - downloadStartTime
                speedMbps = if (downloadDuration > 0) {
                    (bytesDownloaded.toDouble() * 8.0) / (downloadDuration.toDouble() / 1000.0) / 1_000_000.0
                } else {
                    0.0
                }
            }
            
            val totalDuration = System.currentTimeMillis() - startTime
            
            TestResult(
                testName = "OkHttp Redirect Test",
                success = response.isSuccessful || response.code in 300..399,
                durationMs = totalDuration,
                details = mapOf(
                    "originalUrl" to originalUrl.take(100),
                    "finalUrl" to finalUrl.take(100),
                    "wasRedirected" to wasRedirected,
                    "statusCode" to response.code,
                    "redirectDurationMs" to redirectDuration,
                    "contentType" to (response.header("Content-Type") ?: "none"),
                    "contentLength" to (response.header("Content-Length") ?: "none"),
                    "bytesDownloaded" to bytesDownloaded,
                    "downloadDurationMs" to downloadDuration,
                    "speedMbps" to String.format("%.2f", speedMbps)
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "OkHttp Redirect Test",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
    
    /**
     * Test 12: Ktor Java engine redirect test
     * Tests redirect following using Ktor client with Java engine (no headers except User-Agent)
     * If successful, downloads 5MB to prove video streaming works
     */
    private fun testKtorJavaEngineRedirect(originalUrl: String): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val client = HttpClient(Java) {
                install(HttpRedirect) {
                    checkHttpMethod = true       // redirect on POST -> GET, etc.
                    allowHttpsDowngrade = true   // allow redirects from HTTPS → HTTP (needed for IPTV)
                }
            }
            
            val response = runBlocking {
                client.get(originalUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        // NO Range header - simple GET request
                    }
                    timeout {
                        requestTimeoutMillis = 10000 // 10 second timeout
                        connectTimeoutMillis = 2000
                    }
                }
            }
            
            val redirectDuration = System.currentTimeMillis() - startTime
            val wasRedirected = response.status.value in 300..399
            val redirectWasFollowed = response.status.value in 200..299
            
            // If successful, download 5MB to prove video streaming works
            var bytesDownloaded = 0
            var downloadDuration = 0L
            var speedMbps = 0.0
            
            if (response.status.isSuccess()) {
                val downloadStartTime = System.currentTimeMillis()
                try {
                    runBlocking {
                        val channel = response.body<ByteReadChannel>()
                        val bytes = channel.toInputStream().use { inputStream ->
                            inputStream.readNBytes(TEST_DOWNLOAD_SIZE_BYTES)
                        }
                        bytesDownloaded = bytes.size
                    }
                    downloadDuration = System.currentTimeMillis() - downloadStartTime
                    speedMbps = if (downloadDuration > 0) {
                        (bytesDownloaded.toDouble() * 8.0) / (downloadDuration.toDouble() / 1000.0) / 1_000_000.0
                    } else {
                        0.0
                    }
                } catch (e: Exception) {
                    // If download fails, still report redirect success
                }
            }
            
            client.close()
            
            val totalDuration = System.currentTimeMillis() - startTime
            
            TestResult(
                testName = "Ktor Java Engine Redirect Test",
                success = response.status.isSuccess() || response.status.value in 300..399,
                durationMs = totalDuration,
                details = mapOf(
                    "originalUrl" to originalUrl.take(100),
                    "wasRedirected" to wasRedirected,
                    "redirectWasFollowed" to redirectWasFollowed,
                    "statusCode" to response.status.value,
                    "redirectDurationMs" to redirectDuration,
                    "redirectLocation" to (response.headers["Location"]?.take(100) ?: "none"),
                    "contentType" to (response.headers["Content-Type"] ?: "none"),
                    "contentLength" to (response.headers["Content-Length"] ?: "none"),
                    "bytesDownloaded" to bytesDownloaded,
                    "downloadDurationMs" to downloadDuration,
                    "speedMbps" to String.format("%.2f", speedMbps)
                )
            )
        } catch (e: Exception) {
            TestResult(
                testName = "Ktor Java Engine Redirect Test",
                success = false,
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            )
        }
    }
}

