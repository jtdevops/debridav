package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.cache.BytesToCache
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.cache.StreamPlanningService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import org.apache.commons.io.FileUtils
import java.net.InetAddress
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.CachedFile
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.ktor.client.HttpClient
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.util.VideoFileExtensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.EOFException
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit


private const val DEFAULT_BUFFER_SIZE = 65536L //64kb
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds
private const val BYTE_CHANNEL_CAPACITY = 2000
private const val MAX_COMPLETED_DOWNLOADS_HISTORY = 1000


data class DownloadTrackingContext(
    val filePath: String,
    val fileName: String,
    val requestedRange: Range?,
    val requestedSize: Long,
    val downloadStartTime: Instant = Instant.now(),
    val bytesDownloaded: AtomicLong = AtomicLong(0),
    // Completion metadata
    var actualBytesSent: Long? = null,  // Total bytes actually sent
    var downloadEndTime: Instant? = null,
    var completionStatus: String = "in_progress",
    val httpHeaders: Map<String, String> = emptyMap(),
    val sourceIpAddress: String? = null
)

data class HttpRequestInfo(
    val headers: Map<String, String> = emptyMap(),
    val sourceIpAddress: String? = null,
    val sourceHostname: String? = null
) {
    val sourceInfo: String? get() {
        val ip = sourceIpAddress ?: return null
        val hostname = sourceHostname ?: run {
            try {
                InetAddress.getByName(ip).hostName
            } catch (e: Exception) {
                null
            }
        }
        return if (hostname != null && hostname != ip) "$ip/$hostname" else ip
    }
}

/**
 * Represents a cache fetch request with context needed for downloading
 */
private data class CacheFetchRequest(
    val debridLink: CachedFile,
    val range: Range,
    val remotelyCachedEntity: RemotelyCachedEntity
)

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigProperties: DebridavConfigurationProperties,
    private val streamPlanningService: StreamPlanningService,
    private val debridLinkService: DebridLinkService,
    private val localVideoService: LocalVideoService,
    private val httpClient: HttpClient,
    private val iptvConfigurationProperties: io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties?,
    private val iptvConfigurationService: io.skjaere.debridav.iptv.configuration.IptvConfigurationService?,
    private val iptvResponseFileService: io.skjaere.debridav.iptv.util.IptvResponseFileService?,
    prometheusRegistry: PrometheusRegistry
) {
    
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    
    init {
        // Log the effective logger level at startup for debugging
        logger.info("StreamingService logger initialized: loggerName={}, effectiveLevel={}", 
            StreamingService::class.java.name, 
            if (logger.isTraceEnabled) "TRACE" else if (logger.isDebugEnabled) "DEBUG" else if (logger.isInfoEnabled) "INFO" else if (logger.isWarnEnabled) "WARN" else "ERROR")
    }
    
    private val outputGauge =
        Gauge.builder().name("debridav.output.stream.bitrate").labelNames("provider", "file").labelNames("file")
            .register(prometheusRegistry)
    private val inputGauge = Gauge.builder().name("debridav.input.stream.bitrate").labelNames("provider", "file")
        .register(prometheusRegistry)
    private val timeToFirstByteHistogram =
        Histogram.builder().help("Time duration between sending request and receiving first byte")
            .name("debridav.streaming.time.to.first.byte").labelNames("provider").register(prometheusRegistry)
    private val activeOutputStream = ConcurrentLinkedQueue<OutputStreamingContext>()
    private val activeInputStreams = ConcurrentLinkedQueue<InputStreamingContext>()
    private val activeDownloads = ConcurrentHashMap<String, DownloadTrackingContext>()
    private val completedDownloads = ConcurrentLinkedQueue<DownloadTrackingContext>()
    
    // Rate limiting for IPTV provider login calls: max 1 call per minute per provider
    private val iptvLoginCallTimestamps = ConcurrentHashMap<String, Long>()
    private val IPTV_LOGIN_RATE_LIMIT_MS = 60_000L // 1 minute


    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun streamContents(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        httpRequestInfo: HttpRequestInfo = HttpRequestInfo(),
    ): StreamResult = coroutineScope {
        // For IPTV content, make an initial login/test call to the provider before streaming
        // Skip login call if this is an ARR request AND provider is not in bypass list (will use local video files, no external calls needed)
        if (remotelyCachedEntity.contents is io.skjaere.debridav.fs.DebridIptvContent) {
            val iptvContent = remotelyCachedEntity.contents as io.skjaere.debridav.fs.DebridIptvContent
            val providerName = iptvContent.iptvProviderName
            val isArrRequest = debridavConfigProperties.shouldServeLocalVideoForArrs(httpRequestInfo)
            val shouldBypass = providerName != null && debridavConfigProperties.shouldBypassLocalVideoForIptvProvider(providerName)
            
            if (isArrRequest && !shouldBypass) {
                logger.debug("Skipping IPTV provider login call for ARR request (will use local video file, provider=$providerName)")
            } else if (providerName != null && iptvConfigurationProperties != null && iptvConfigurationService != null && iptvResponseFileService != null) {
                try {
                    // Rate limiting: max 1 call per minute per provider
                    val now = System.currentTimeMillis()
                    val lastCallTime = iptvLoginCallTimestamps[providerName] ?: 0L
                    val timeSinceLastCall = now - lastCallTime
                    
                    if (timeSinceLastCall < IPTV_LOGIN_RATE_LIMIT_MS) {
                        logger.debug("Skipping IPTV provider login call for $providerName (rate limited, last call was ${timeSinceLastCall}ms ago)")
                    } else {
                        val providerConfigs = iptvConfigurationService.getProviderConfigurations()
                        val providerConfig = providerConfigs.find { it.name == providerName }
                        if (providerConfig != null && providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                            logger.debug("Making initial login call to IPTV provider $providerName before streaming")
                            val xtreamCodesClient = io.skjaere.debridav.iptv.client.XtreamCodesClient(httpClient, iptvResponseFileService)
                            val loginSuccess = xtreamCodesClient.verifyAccount(providerConfig)
                            // Update timestamp after successful call
                            iptvLoginCallTimestamps[providerName] = now
                            if (!loginSuccess) {
                                logger.warn("IPTV provider login verification failed for $providerName, but continuing with stream attempt")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to make initial login call to IPTV provider $providerName: ${e.message}, continuing with stream attempt", e)
                }
            }
        }
        val originalRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))

        // Check if we should serve local video file for ARR requests
        if (debridavConfigProperties.shouldServeLocalVideoForArrs(httpRequestInfo)) {
            val fileName = remotelyCachedEntity.name ?: "unknown"
            val fullPath = remotelyCachedEntity.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
            
            // Check if IPTV provider should bypass local video serving
            val iptvProviderName = if (remotelyCachedEntity.contents is io.skjaere.debridav.fs.DebridIptvContent) {
                (remotelyCachedEntity.contents as io.skjaere.debridav.fs.DebridIptvContent).iptvProviderName
            } else {
                null
            }
            
            val shouldBypass = debridavConfigProperties.shouldBypassLocalVideoForIptvProvider(iptvProviderName)
            if (shouldBypass) {
                logger.debug("LOCAL_VIDEO_BYPASS: file={}, iptvProvider={}, bypassing local video serving, will serve from IPTV provider", 
                    fileName, iptvProviderName)
            } else {
                logger.debug("LOCAL_VIDEO_CHECK: file={}, fullPath={}, shouldServeLocalVideoForArrs=true", fileName, fullPath)
                
                // Check if the file path matches the configured regex pattern
                if (!debridavConfigProperties.shouldServeLocalVideoForPath(fullPath)) {
                    logger.debug("LOCAL_VIDEO_PATH_NOT_MATCHED: file={}, fullPath={}, regex={}, will serve external file", 
                        fileName, fullPath, debridavConfigProperties.rcloneArrsLocalVideoPathRegex)
                } else {
                    // Only apply local video serving to media files, not subtitles or other files
                    if (isMediaFile(fileName)) {
                        // Get the external file size to check against the minimum size threshold
                        val externalFileSize = debridLink.size ?: 0L
                        
                        // Check if the file is large enough to use local video serving
                        if (debridavConfigProperties.shouldUseLocalVideoForSize(externalFileSize)) {
                            logger.debug("LOCAL_VIDEO_SERVING_REQUEST: file={}, range={}-{}, source={}, isMediaFile=true, externalSize={} bytes, minSizeKb={}",
                                fileName, originalRange.start, originalRange.finish, httpRequestInfo.sourceInfo, 
                                externalFileSize, debridavConfigProperties.rcloneArrsLocalVideoMinSizeKb)
                            
                            val success = localVideoService.serveLocalVideoFile(outputStream, range, httpRequestInfo, fileName)
                            return@coroutineScope if (success) StreamResult.OK else StreamResult.IO_ERROR
                        } else {
                            logger.debug("LOCAL_VIDEO_PATH_MATCHED_BUT_TOO_SMALL: file={}, fullPath={}, regex={}, isMediaFile=true, externalSize={} bytes, minSizeKb={}, will serve external file", 
                                fileName, fullPath, debridavConfigProperties.rcloneArrsLocalVideoPathRegex, 
                                externalFileSize, debridavConfigProperties.rcloneArrsLocalVideoMinSizeKb)
                        }
                    } else {
                        logger.debug("LOCAL_VIDEO_PATH_MATCHED_BUT_NOT_MEDIA: file={}, fullPath={}, regex={}, isMediaFile=false, will serve external file", 
                            fileName, fullPath, debridavConfigProperties.rcloneArrsLocalVideoPathRegex)
                    }
                }
            }
        } else {
            logger.debug("LOCAL_VIDEO_CHECK: file={}, shouldServeLocalVideoForArrs=false, will serve external file", 
                remotelyCachedEntity.name ?: "unknown")
        }

        // Use the original range for normal streaming
        val appliedRange = originalRange
        
        logger.debug("EXTERNAL_FILE_STREAMING: file={}, range={}-{}, size={} bytes, provider={}, source={}", 
            remotelyCachedEntity.name ?: "unknown", appliedRange.start, appliedRange.finish, 
            appliedRange.finish - appliedRange.start + 1, debridLink.provider, httpRequestInfo.sourceInfo)
        
        val trackingId = initializeDownloadTracking(debridLink, range, remotelyCachedEntity, httpRequestInfo)
        
        var result: StreamResult = StreamResult.OK
        try {
            // Normal streaming
            streamBytes(remotelyCachedEntity, appliedRange, debridLink, outputStream, trackingId)
            result = StreamResult.OK
        } catch (e: LinkNotFoundException) {
            result = handleLinkNotFound(debridLink, remotelyCachedEntity, appliedRange, outputStream)
        } catch (e: EOFException) {
            result = handleEOFException(debridLink, remotelyCachedEntity, appliedRange, outputStream)
        } catch (_: DebridProviderException) {
            result = StreamResult.PROVIDER_ERROR
        } catch (_: StreamToClientException) {
            result = StreamResult.IO_ERROR
        } catch (_: ReadFromHttpStreamException) {
            result = StreamResult.IO_ERROR
        } catch (_: ClientErrorException) {
            result = StreamResult.CLIENT_ERROR
        } catch (_: ClientAbortException) {
            result = StreamResult.OK
        } catch (e: kotlinx.io.IOException) {
            // TRACE level logging for streaming IO exceptions with full stack trace
            logger.trace("STREAMING_IO_EXCEPTION: IOException during streaming: path={}, link={}, provider={}, exceptionClass={}", 
                debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
            // Explicitly log stack trace to ensure it appears
            logger.trace("STREAMING_IO_EXCEPTION_STACK_TRACE", e)
            logger.error("IOError occurred during streaming", e)
            result = StreamResult.IO_ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // TRACE level logging for streaming runtime exceptions with full stack trace
            logger.trace("STREAMING_RUNTIME_EXCEPTION: RuntimeException during streaming: path={}, link={}, provider={}, exceptionClass={}", 
                debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
            // Explicitly log stack trace to ensure it appears
            logger.trace("STREAMING_RUNTIME_EXCEPTION_STACK_TRACE", e)
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            result = StreamResult.UNKNOWN_ERROR
        }
        
        // Fallback to local video files if IPTV provider streaming failed and bypass was configured
        if (result != StreamResult.OK && debridavConfigProperties.shouldServeLocalVideoForArrs(httpRequestInfo)) {
            val fileName = remotelyCachedEntity.name ?: "unknown"
            val fullPath = remotelyCachedEntity.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
            
            // Check if this is IPTV content that was configured to bypass local video
            val iptvProviderName = if (remotelyCachedEntity.contents is io.skjaere.debridav.fs.DebridIptvContent) {
                (remotelyCachedEntity.contents as io.skjaere.debridav.fs.DebridIptvContent).iptvProviderName
            } else {
                null
            }
            val shouldBypass = debridavConfigProperties.shouldBypassLocalVideoForIptvProvider(iptvProviderName)
            
            if (shouldBypass && VideoFileExtensions.isVideoFile(fileName)) {
                // IPTV provider failed, try to fallback to local video files
                logger.warn("IPTV provider streaming failed (result={}) for file={}, provider={}, attempting fallback to local video file", 
                    result, fileName, iptvProviderName)
                
                // Check if the file path matches the configured regex pattern
                if (debridavConfigProperties.shouldServeLocalVideoForPath(fullPath)) {
                    // Get the external file size to check against the minimum size threshold
                    val externalFileSize = debridLink.size ?: 0L
                    
                    // Check if the file is large enough to use local video serving
                    if (debridavConfigProperties.shouldUseLocalVideoForSize(externalFileSize)) {
                        logger.info("FALLBACK_TO_LOCAL_VIDEO: file={}, iptvProvider={}, falling back to local video file after IPTV provider failure", 
                            fileName, iptvProviderName)
                        
                        try {
                            val success = localVideoService.serveLocalVideoFile(outputStream, range, httpRequestInfo, fileName)
                            if (success) {
                                logger.info("FALLBACK_TO_LOCAL_VIDEO_SUCCESS: file={}, iptvProvider={}, successfully served local video file", 
                                    fileName, iptvProviderName)
                                this.coroutineContext.cancelChildren()
                                trackingId?.let { id -> completeDownloadTracking(id, StreamResult.OK) }
                                return@coroutineScope StreamResult.OK
                            } else {
                                logger.warn("FALLBACK_TO_LOCAL_VIDEO_FAILED: file={}, iptvProvider={}, local video file serving failed", 
                                    fileName, iptvProviderName)
                            }
                        } catch (e: Exception) {
                            logger.error("FALLBACK_TO_LOCAL_VIDEO_ERROR: file={}, iptvProvider={}, error serving local video file", 
                                fileName, iptvProviderName, e)
                        }
                    } else {
                        logger.debug("FALLBACK_TO_LOCAL_VIDEO_SKIPPED_SIZE: file={}, iptvProvider={}, externalSize={} bytes, minSizeKb={}, file too small for local video", 
                            fileName, iptvProviderName, externalFileSize, debridavConfigProperties.rcloneArrsLocalVideoMinSizeKb)
                    }
                } else {
                    logger.debug("FALLBACK_TO_LOCAL_VIDEO_SKIPPED_PATH: file={}, iptvProvider={}, fullPath={}, path does not match regex", 
                        fileName, iptvProviderName, fullPath)
                }
            }
        }
        
        try {
            // Cleanup
        } finally {
            this.coroutineContext.cancelChildren()
            trackingId?.let { id -> completeDownloadTracking(id, result) }
        }
        logger.info("done streaming ${debridLink.path}: $result")
        result
    }
    

    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, debridLink: CachedFile, outputStream: OutputStream, trackingId: String?
    ) = coroutineScope {
        launch {
            val streamingPlan = streamPlanningService.generatePlan(
                fileChunkCachingService.getAllCachedChunksForEntity(remotelyCachedEntity),
                LongRange(range.start, range.finish),
                debridLink
            )
            val sources = getSources(streamingPlan)
            val byteArrays = getByteArrays(sources)
            sendContent(byteArrays, outputStream, remotelyCachedEntity, trackingId)
        }
    }

    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.file)
        this.remove(ctx)
    }

    fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        val providerLabel = ctx.provider?.toString() ?: "IPTV"
        inputGauge.remove(providerLabel, ctx.file)
        if (this.contains(ctx)) {
            this.remove(ctx)
        } else {
            logger.warn("context $ctx not found in queue")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getSources(
        streamPlan: StreamPlanningService.StreamPlan
    ): ReceiveChannel<StreamPlanningService.StreamSource> = this.produce(this.coroutineContext, 2) {
        streamPlan.sources.forEach {
            send(it)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getByteArrays(
        streamPlan: ReceiveChannel<StreamPlanningService.StreamSource>
    ): ReceiveChannel<ByteArrayContext> = this.produce(this.coroutineContext, BYTE_CHANNEL_CAPACITY) {
        streamPlan.consumeEach { sourceContext ->
            when (sourceContext) {
                is StreamPlanningService.StreamSource.Cached -> sendCachedBytes(sourceContext)
                is StreamPlanningService.StreamSource.Remote -> sendBytesFromHttpStreamWithKtor(sourceContext)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<ByteArrayContext>.sendBytesFromHttpStreamWithKtor(
        source: StreamPlanningService.StreamSource.Remote
    ) {
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val started = Instant.now()
        
        // Handle IPTV files - check if we can find a matching debrid client
        // If not found, it's likely an IPTV file (using IPTV provider)
        val httpStatement = try {
            val debridClient = debridClients.firstOrNull { it.getProvider() == source.cachedFile.provider }
            if (debridClient != null) {
                // Debrid file - use debrid client
                debridClient.prepareStreamUrl(source.cachedFile, range)
            } else {
                // IPTV file or unknown - use direct streaming
                val rateLimiter = RateLimiter.of("iptv", RateLimiterConfig.custom()
                    .limitForPeriod(100)
                    .limitRefreshPeriod(java.time.Duration.ofSeconds(1))
                    .build())
                val iptvUserAgent = iptvConfigurationProperties?.userAgent
                val linkPreparer = DefaultStreamableLinkPreparer(
                    httpClient,
                    debridavConfigProperties,
                    rateLimiter,
                    iptvUserAgent
                )
                linkPreparer.prepareStreamUrl(source.cachedFile, range)
            }
        } catch (e: NoSuchElementException) {
            // No matching debrid client - treat as IPTV
            val rateLimiter = RateLimiter.of("iptv", RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(java.time.Duration.ofSeconds(1))
                .build())
            val iptvUserAgent = iptvConfigurationProperties?.userAgent
            val linkPreparer = DefaultStreamableLinkPreparer(
                httpClient,
                debridavConfigProperties,
                rateLimiter,
                iptvUserAgent
            )
            linkPreparer.prepareStreamUrl(source.cachedFile, range)
        } catch (e: Exception) {
            // TRACE level logging for streaming exceptions with full stack trace
            logger.trace("STREAMING_EXCEPTION: Error preparing stream URL: path={}, link={}, provider={}, exceptionClass={}", 
                source.cachedFile.path, source.cachedFile.link?.take(100), source.cachedFile.provider, e::class.simpleName, e)
            // Explicitly log stack trace to ensure it appears
            logger.trace("STREAMING_EXCEPTION_STACK_TRACE", e)
            throw e
        }
        
        httpStatement.execute { response ->
            val isIptv = isIptvUrl(source.cachedFile.link ?: "")
            val expectedBytes = byteRangeInfo!!.length()
            val requestedRange = "${source.range.start}-${source.range.last}"
            
            // Log HTTP response details at TRACE level
            // Try to get TLS version information from the response
            val tlsVersion = try {
                // Ktor CIO doesn't directly expose SSL session, but we can check if it's HTTPS
                val url = source.cachedFile.link
                if (url != null && url.startsWith("https://")) {
                    // Check JVM TLS configuration
                    val clientProtocols = System.getProperty("jdk.tls.client.protocols", "default")
                    val supportedProtocols = try {
                        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                        sslContext.init(null, null, null)
                        sslContext.defaultSSLParameters.protocols.contentToString()
                    } catch (e: Exception) {
                        "unknown"
                    }
                    "clientProtocols=$clientProtocols, supported=$supportedProtocols"
                } else {
                    "not-https"
                }
            } catch (e: Exception) {
                "error-checking-tls: ${e.message}"
            }
            
            val storedFileSize = source.cachedFile.size ?: -1L
            val httpContentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val redirectLocation = response.headers["Location"]
            // For byte range requests, httpContentLength is the range size, not the full file size
            // This mismatch is expected and normal for range requests
            val sizeMismatch = if (storedFileSize > 0 && httpContentLength > 0 && storedFileSize != httpContentLength) {
                if (httpContentLength < storedFileSize) {
                    "EXPECTED_RANGE: storedSize=$storedFileSize != httpContentLength=$httpContentLength (range request)"
                } else {
                    "MISMATCH: storedSize=$storedFileSize != httpContentLength=$httpContentLength"
                }
            } else {
                "OK"
            }
            
            logger.trace("HTTP_RESPONSE: path={}, link={}, status={}, requestedRange={}, expectedBytes={}, storedFileSize={}, httpContentLength={}, sizeMismatch={}, redirectLocation={}, tlsInfo={}", 
                source.cachedFile.path, source.cachedFile.link?.take(100), response.status.value, 
                requestedRange, expectedBytes, storedFileSize, httpContentLength, sizeMismatch, redirectLocation ?: "none", tlsVersion)
            
            // Check if this is a redirect response - redirects don't have content bodies
            // Check configuration to determine redirect handling mode
            val shouldHandleRedirectManually = iptvConfigurationProperties?.redirectHandlingMode == 
                io.skjaere.debridav.iptv.configuration.RedirectHandlingMode.MANUAL
            
            if (response.status.value in 300..399) {
                val redirectLocation = response.headers["Location"]
                if (redirectLocation != null) {
                    if (shouldHandleRedirectManually) {
                        logger.info("REDIRECT_RESPONSE: Following redirect manually to preserve Range header: path={}, originalLink={}, redirectLocation={}, requestedRange={}, isIptv={}", 
                            source.cachedFile.path, source.cachedFile.link?.take(100), redirectLocation, requestedRange, isIptv)
                    
                    // Manually follow redirect while preserving Range header
                    // Close the current response first
                    response.cancel()
                    
                    // Make new request to redirect location with Range header preserved
                    val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                        redirectLocation
                    } else {
                        // Relative redirect - construct absolute URL using URI
                        val originalUri = java.net.URI(source.cachedFile.link!!)
                        originalUri.resolve(redirectLocation).toString()
                    }
                    
                    // Create new request to redirect URL with Range header preserved
                    val rangeHeader = "bytes=${source.range.start}-${source.range.last}"
                    logger.trace("REDIRECT_REQUEST: Making request to redirect URL: redirectUrl={}, rangeHeader={}", redirectUrl, rangeHeader)
                    
                    val redirectResponse = try {
                        httpClient.get(redirectUrl) {
                            headers {
                                append(HttpHeaders.Range, rangeHeader)
                                // Use IPTV user agent if available, otherwise use default
                                iptvConfigurationProperties?.userAgent?.let {
                                    append(HttpHeaders.UserAgent, it)
                                }
                            }
                            timeout {
                                requestTimeoutMillis = 20_000_000
                                socketTimeoutMillis = debridavConfigProperties.readTimeoutMilliseconds
                                connectTimeoutMillis = debridavConfigProperties.connectTimeoutMilliseconds
                            }
                        }
                    } catch (e: Exception) {
                        logger.trace("REDIRECT_REQUEST_EXCEPTION: Exception making request to redirect URL: redirectUrl={}, rangeHeader={}, exceptionClass={}", 
                            redirectUrl, rangeHeader, e::class.simpleName, e)
                        logger.trace("REDIRECT_REQUEST_EXCEPTION_STACK_TRACE", e)
                        throw ReadFromHttpStreamException("Failed to make request to redirect URL: $redirectUrl", e)
                    }
                    
                    logger.trace("REDIRECT_RESPONSE_RECEIVED: Received response from redirect URL: redirectUrl={}, status={}, contentLength={}, contentType={}", 
                        redirectUrl, redirectResponse.status.value, redirectResponse.headers["Content-Length"], redirectResponse.headers["Content-Type"])
                    
                    // Recursively process the redirect response (but only once to avoid infinite loops)
                    if (redirectResponse.status.value in 300..399) {
                        logger.warn("REDIRECT_CHAIN: Redirect chain detected, stopping at second redirect: path={}, redirectLocation={}, status={}", 
                            source.cachedFile.path, redirectLocation, redirectResponse.status.value)
                        redirectResponse.cancel()
                        throw ReadFromHttpStreamException("Redirect chain detected - multiple redirects not supported. Last location: ${redirectResponse.headers["Location"] ?: redirectLocation}", 
                            RuntimeException("Multiple redirects detected"))
                    }
                    
                    // Process the redirect response as if it was the original response
                    logger.trace("REDIRECT_PROCESSING: Starting to process redirect response body: redirectUrl={}, status={}", redirectUrl, redirectResponse.status.value)
                    
                    return@execute try {
                        redirectResponse.body<ByteReadChannel>().toInputStream().use { inputStream ->
                            logger.trace("REDIRECT_INPUT_STREAM_CREATED: Successfully created input stream from redirect response: redirectUrl={}", redirectUrl)
                            
                            val actualProvider = if (debridClients.none { it.getProvider() == source.cachedFile.provider }) {
                                null
                            } else {
                                source.cachedFile.provider
                            }
                            val streamingContext = InputStreamingContext(
                                ResettableCountingInputStream(inputStream), 
                                actualProvider, 
                                source.cachedFile.path!!
                            )
                            activeInputStreams.add(streamingContext)
                            try {
                                logger.trace("REDIRECT_STREAMING_START: Starting to stream from redirect URL: redirectUrl={}, expectedBytes={}", redirectUrl, expectedBytes)
                                withContext(Dispatchers.IO) {
                                    pipeHttpInputStreamToOutputChannel(
                                        streamingContext, byteRangeInfo, source, started, isIptv, expectedBytes
                                    )
                                }
                                logger.trace("REDIRECT_STREAMING_COMPLETE: Successfully completed streaming from redirect URL: redirectUrl={}", redirectUrl)
                            } catch (e: CancellationException) {
                                logger.trace("REDIRECT_STREAMING_CANCELLED: Streaming cancelled: redirectUrl={}", redirectUrl)
                                close(e)
                                throw e
                            } catch (e: EOFException) {
                            val actualBytesRead = streamingContext.inputStream.getTotalCount()
                            val storedFileSize = source.cachedFile.size ?: -1L
                            val httpContentLength = redirectResponse.headers["Content-Length"]?.toLongOrNull() ?: -1L
                            val fullFileSize = if (storedFileSize > 0) storedFileSize else httpContentLength
                            // For byte range requests, httpContentLength is the range size, not the full file size
                            // This mismatch is expected and normal for range requests
                            val sizeMismatch = if (storedFileSize > 0 && httpContentLength > 0 && storedFileSize != httpContentLength) {
                                if (httpContentLength < storedFileSize) {
                                    "EXPECTED_RANGE: storedSize=$storedFileSize != httpContentLength=$httpContentLength (range request)"
                                } else {
                                    "MISMATCH: storedSize=$storedFileSize != httpContentLength=$httpContentLength"
                                }
                            } else {
                                "OK"
                            }
                            
                            logger.trace("STREAMING_EOF: EOFException during stream read (after redirect): path={}, redirectUrl={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, storedFileSize={}, httpContentLength={}, fullFileSize={}, sizeMismatch={}, httpStatus={}, exceptionClass={}", 
                                source.cachedFile.path, redirectUrl.take(100), requestedRange, expectedBytes, actualBytesRead, storedFileSize, httpContentLength, fullFileSize, sizeMismatch, redirectResponse.status.value, e::class.simpleName)
                            throw e
                        } catch (e: Exception) {
                            val actualBytesRead = streamingContext.inputStream.getTotalCount()
                            logger.trace("STREAMING_EXCEPTION: Exception during stream read (after redirect): path={}, redirectUrl={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, httpStatus={}, exceptionClass={}", 
                                source.cachedFile.path, redirectUrl.take(100), requestedRange, expectedBytes, actualBytesRead, redirectResponse.status.value, e::class.simpleName, e)
                            // Explicitly log stack trace to ensure it appears
                            logger.trace("STREAMING_EXCEPTION_STACK_TRACE (after redirect)", e)
                            logger.error("An error occurred during reading from stream after redirect", e)
                            throw ReadFromHttpStreamException("An error occurred during reading from stream after redirect", e)
                        } finally {
                            logger.trace("REDIRECT_CLEANUP: Cleaning up redirect response: redirectUrl={}", redirectUrl)
                            redirectResponse.cancel()
                            activeInputStreams.removeStream(streamingContext)
                        }
                            }  // closes use block
                        } catch (e: Exception) {
                            // Check if this is a client abort (expected when client disconnects)
                            if (isClientAbortException(e)) {
                                logger.debug("REDIRECT_BODY_EXCEPTION: Client disconnected (expected): redirectUrl={}, exceptionClass={}", 
                                    redirectUrl, e::class.simpleName)
                            } else {
                                logger.trace("REDIRECT_BODY_EXCEPTION: Exception creating input stream from redirect response body: redirectUrl={}, exceptionClass={}", 
                                    redirectUrl, e::class.simpleName, e)
                                logger.trace("REDIRECT_BODY_EXCEPTION_STACK_TRACE", e)
                            }
                            redirectResponse.cancel()
                            throw ReadFromHttpStreamException("Failed to create input stream from redirect response: $redirectUrl", e)
                        }
                    } else {
                        // Automatic redirect handling: HttpRedirect plugin should have handled it
                        // However, if we see a redirect response here, it means the plugin didn't follow it
                        // (likely because Range headers prevent automatic redirect following)
                        // Fall back to manual handling - this is expected behavior, so log at DEBUG level
                        logger.debug("REDIRECT_RESPONSE: Automatic redirect handling skipped (HttpRedirect plugin doesn't follow redirects with Range headers), using manual handling: path={}, originalLink={}, redirectLocation={}, requestedRange={}, isIptv={}", 
                            source.cachedFile.path, source.cachedFile.link?.take(100), redirectLocation, requestedRange, isIptv)
                        
                        // Manually follow redirect while preserving Range header
                        // Close the current response first
                        response.cancel()
                        
                        // Make new request to redirect location with Range header preserved
                        val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                            redirectLocation
                        } else {
                            // Relative redirect - construct absolute URL using URI
                            val originalUri = java.net.URI(source.cachedFile.link!!)
                            originalUri.resolve(redirectLocation).toString()
                        }
                        
                        // Create new request to redirect URL with Range header preserved
                        val rangeHeader = "bytes=${source.range.start}-${source.range.last}"
                        logger.trace("REDIRECT_REQUEST: Making request to redirect URL (fallback from automatic): redirectUrl={}, rangeHeader={}", redirectUrl, rangeHeader)
                        
                        val redirectResponse = try {
                            httpClient.get(redirectUrl) {
                                headers {
                                    append(HttpHeaders.Range, rangeHeader)
                                    // Use IPTV user agent if available, otherwise use default
                                    iptvConfigurationProperties?.userAgent?.let {
                                        append(HttpHeaders.UserAgent, it)
                                    }
                                }
                                timeout {
                                    requestTimeoutMillis = 20_000_000
                                    socketTimeoutMillis = debridavConfigProperties.readTimeoutMilliseconds
                                    connectTimeoutMillis = debridavConfigProperties.connectTimeoutMilliseconds
                                }
                            }
                        } catch (e: Exception) {
                            logger.trace("REDIRECT_REQUEST_EXCEPTION: Exception making request to redirect URL: redirectUrl={}, rangeHeader={}, exceptionClass={}", 
                                redirectUrl, rangeHeader, e::class.simpleName, e)
                            logger.trace("REDIRECT_REQUEST_EXCEPTION_STACK_TRACE", e)
                            throw ReadFromHttpStreamException("Failed to make request to redirect URL: $redirectUrl", e)
                        }
                        
                        logger.trace("REDIRECT_RESPONSE_RECEIVED: Received response from redirect URL (fallback): redirectUrl={}, status={}, contentLength={}, contentType={}", 
                            redirectUrl, redirectResponse.status.value, redirectResponse.headers["Content-Length"], redirectResponse.headers["Content-Type"])
                        
                        // Recursively process the redirect response (but only once to avoid infinite loops)
                        if (redirectResponse.status.value in 300..399) {
                            logger.warn("REDIRECT_CHAIN: Redirect chain detected, stopping at second redirect: path={}, redirectLocation={}, status={}", 
                                source.cachedFile.path, redirectLocation, redirectResponse.status.value)
                            redirectResponse.cancel()
                            throw ReadFromHttpStreamException("Redirect chain detected - multiple redirects not supported. Last location: ${redirectResponse.headers["Location"] ?: redirectLocation}", 
                                RuntimeException("Multiple redirects detected"))
                        }
                        
                        // Process the redirect response as if it was the original response
                        logger.trace("REDIRECT_PROCESSING: Starting to process redirect response body (fallback): redirectUrl={}, status={}", redirectUrl, redirectResponse.status.value)
                        
                        return@execute try {
                            redirectResponse.body<ByteReadChannel>().toInputStream().use { inputStream ->
                                logger.trace("REDIRECT_INPUT_STREAM_CREATED: Successfully created input stream from redirect response (fallback): redirectUrl={}", redirectUrl)
                                
                                val actualProvider = if (debridClients.none { it.getProvider() == source.cachedFile.provider }) {
                                    null
                                } else {
                                    source.cachedFile.provider
                                }
                                val streamingContext = InputStreamingContext(
                                    ResettableCountingInputStream(inputStream), 
                                    actualProvider, 
                                    source.cachedFile.path!!
                                )
                                activeInputStreams.add(streamingContext)
                                try {
                                    logger.trace("REDIRECT_STREAMING_START: Starting to stream from redirect URL (fallback): redirectUrl={}, expectedBytes={}", redirectUrl, expectedBytes)
                                    withContext(Dispatchers.IO) {
                                        pipeHttpInputStreamToOutputChannel(
                                            streamingContext, byteRangeInfo, source, started, isIptv, expectedBytes
                                        )
                                    }
                                    logger.trace("REDIRECT_STREAMING_COMPLETE: Successfully completed streaming from redirect URL (fallback): redirectUrl={}", redirectUrl)
                                } catch (e: CancellationException) {
                                    logger.trace("REDIRECT_STREAMING_CANCELLED: Streaming cancelled (fallback): redirectUrl={}", redirectUrl)
                                    close(e)
                                    throw e
                                } catch (e: EOFException) {
                                    val actualBytesRead = streamingContext.inputStream.getTotalCount()
                                    val storedFileSize = source.cachedFile.size ?: -1L
                                    val httpContentLength = redirectResponse.headers["Content-Length"]?.toLongOrNull() ?: -1L
                                    val fullFileSize = if (storedFileSize > 0) storedFileSize else httpContentLength
                                    // For byte range requests, httpContentLength is the range size, not the full file size
                                    // This mismatch is expected and normal for range requests
                                    val sizeMismatch = if (storedFileSize > 0 && httpContentLength > 0 && storedFileSize != httpContentLength) {
                                        if (httpContentLength < storedFileSize) {
                                            "EXPECTED_RANGE: storedSize=$storedFileSize != httpContentLength=$httpContentLength (range request)"
                                        } else {
                                            "MISMATCH: storedSize=$storedFileSize != httpContentLength=$httpContentLength"
                                        }
                                    } else {
                                        "OK"
                                    }
                                    
                                    logger.trace("STREAMING_EOF: EOFException during stream read (after redirect fallback): path={}, redirectUrl={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, storedFileSize={}, httpContentLength={}, fullFileSize={}, sizeMismatch={}, httpStatus={}, exceptionClass={}", 
                                        source.cachedFile.path, redirectUrl.take(100), requestedRange, expectedBytes, actualBytesRead, storedFileSize, httpContentLength, fullFileSize, sizeMismatch, redirectResponse.status.value, e::class.simpleName)
                                    throw e
                                } catch (e: Exception) {
                                    // Check if this is a client abort (expected when client disconnects)
                                    if (isClientAbortException(e)) {
                                        val actualBytesRead = streamingContext.inputStream.getTotalCount()
                                        logger.debug("STREAMING_EXCEPTION: Client disconnected (expected, after redirect fallback): path={}, redirectUrl={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, httpStatus={}, exceptionClass={}", 
                                            source.cachedFile.path, redirectUrl.take(100), requestedRange, expectedBytes, actualBytesRead, redirectResponse.status.value, e::class.simpleName)
                                    } else {
                                        val actualBytesRead = streamingContext.inputStream.getTotalCount()
                                        logger.trace("STREAMING_EXCEPTION: Exception during stream read (after redirect fallback): path={}, redirectUrl={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, httpStatus={}, exceptionClass={}", 
                                            source.cachedFile.path, redirectUrl.take(100), requestedRange, expectedBytes, actualBytesRead, redirectResponse.status.value, e::class.simpleName, e)
                                        // Explicitly log stack trace to ensure it appears
                                        logger.trace("STREAMING_EXCEPTION_STACK_TRACE (after redirect fallback)", e)
                                        logger.error("An error occurred during reading from stream after redirect fallback", e)
                                    }
                                    throw ReadFromHttpStreamException("An error occurred during reading from stream after redirect fallback", e)
                                } finally {
                                    logger.trace("REDIRECT_CLEANUP: Cleaning up redirect response (fallback): redirectUrl={}", redirectUrl)
                                    redirectResponse.cancel()
                                    activeInputStreams.removeStream(streamingContext)
                                }
                                }  // closes use block
                            } catch (e: Exception) {
                                // Check if this is a client abort (expected when client disconnects)
                                if (isClientAbortException(e)) {
                                    logger.debug("REDIRECT_BODY_EXCEPTION: Client disconnected (expected): redirectUrl={}, exceptionClass={}", 
                                        redirectUrl, e::class.simpleName)
                                } else {
                                    logger.trace("REDIRECT_BODY_EXCEPTION: Exception creating input stream from redirect response body (fallback): redirectUrl={}, exceptionClass={}", 
                                        redirectUrl, e::class.simpleName, e)
                                    logger.trace("REDIRECT_BODY_EXCEPTION_STACK_TRACE", e)
                                }
                                redirectResponse.cancel()
                                throw ReadFromHttpStreamException("Failed to create input stream from redirect response: $redirectUrl", e)
                            }
                    }
                } else {
                    // No redirect location - throw error
                    logger.warn("REDIRECT_RESPONSE: Received redirect status {} but no Location header: path={}, originalLink={}, requestedRange={}", 
                        response.status.value, source.cachedFile.path, source.cachedFile.link?.take(100), requestedRange)
                    throw ReadFromHttpStreamException("Received redirect response (${response.status.value}) but no Location header found", 
                        RuntimeException("Redirect response without Location header"))
                }
            }
            
            try {
                response.body<ByteReadChannel>().toInputStream().use { inputStream ->
                    // For IPTV files, provider is IPTV (not a debrid client) - set to null for metrics
                    val actualProvider = if (debridClients.none { it.getProvider() == source.cachedFile.provider }) {
                        null
                    } else {
                        source.cachedFile.provider
                    }
                    val streamingContext = InputStreamingContext(
                        ResettableCountingInputStream(inputStream), 
                        actualProvider, 
                        source.cachedFile.path!!
                    )
                    activeInputStreams.add(streamingContext)
                    try {
                        withContext(Dispatchers.IO) {
                            pipeHttpInputStreamToOutputChannel(
                                streamingContext, byteRangeInfo, source, started, isIptv, expectedBytes
                            )
                        }
                    } catch (e: CancellationException) {
                        close(e)
                        throw e
                    } catch (e: EOFException) {
                        // EOFException is expected when stream ends early
                        // TRACE level logging for streaming EOF exceptions
                        val actualBytesRead = streamingContext.inputStream.getTotalCount()
                        val storedFileSize = source.cachedFile.size ?: -1L
                        val httpContentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                        val fullFileSize = if (storedFileSize > 0) storedFileSize else httpContentLength
                        // For byte range requests, httpContentLength is the range size, not the full file size
                        // This mismatch is expected and normal for range requests
                        val sizeMismatch = if (storedFileSize > 0 && httpContentLength > 0 && storedFileSize != httpContentLength) {
                            if (httpContentLength < storedFileSize) {
                                "EXPECTED_RANGE: storedSize=$storedFileSize != httpContentLength=$httpContentLength (range request)"
                            } else {
                                "MISMATCH: storedSize=$storedFileSize != httpContentLength=$httpContentLength"
                            }
                        } else {
                            "OK"
                        }
                        
                        logger.trace("STREAMING_EOF: EOFException during stream read: path={}, link={}, provider={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, storedFileSize={}, httpContentLength={}, fullFileSize={}, sizeMismatch={}, httpStatus={}, exceptionClass={}", 
                            source.cachedFile.path, source.cachedFile.link?.take(100), source.cachedFile.provider,
                            requestedRange, expectedBytes, actualBytesRead, storedFileSize, httpContentLength, fullFileSize, sizeMismatch, response.status.value, e::class.simpleName)
                        // Let it propagate to outer handler for proper handling
                        throw e
                    } catch (e: Exception) {
                        // Check if this is a client abort (expected when client disconnects)
                        if (isClientAbortException(e)) {
                            val actualBytesRead = streamingContext.inputStream.getTotalCount()
                            logger.debug("STREAMING_EXCEPTION: Client disconnected (expected): path={}, link={}, provider={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, httpStatus={}, exceptionClass={}", 
                                source.cachedFile.path, source.cachedFile.link?.take(100), source.cachedFile.provider,
                                requestedRange, expectedBytes, actualBytesRead, response.status.value, e::class.simpleName)
                        } else {
                            // TRACE level logging for streaming exceptions with full stack trace
                            val actualBytesRead = streamingContext.inputStream.getTotalCount()
                            logger.trace("STREAMING_EXCEPTION: Exception during stream read: path={}, link={}, provider={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, httpStatus={}, exceptionClass={}", 
                                source.cachedFile.path, source.cachedFile.link?.take(100), source.cachedFile.provider,
                                requestedRange, expectedBytes, actualBytesRead, response.status.value, e::class.simpleName, e)
                            // Explicitly log stack trace to ensure it appears
                            logger.trace("STREAMING_EXCEPTION_STACK_TRACE", e)
                            logger.error("An error occurred during reading from stream", e)
                        }
                        throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                    } finally {
                        response.cancel()
                        activeInputStreams.removeStream(streamingContext)
                    }
                }
            } catch (e: Exception) {
                // Skip logging for CancellationException and EOFException as they're expected during normal streaming
                if (e !is CancellationException && e !is EOFException) {
                    logger.trace("NON_REDIRECT_BODY_EXCEPTION: Exception creating input stream from response body: status={}, exceptionClass={}", 
                        response.status.value, e::class.simpleName, e)
                    logger.trace("NON_REDIRECT_BODY_EXCEPTION_STACK_TRACE", e)
                }
                response.cancel()
                throw ReadFromHttpStreamException("Failed to create input stream from response", e)
            }
        }
    }

    private suspend fun ProducerScope<ByteArrayContext>.pipeHttpInputStreamToOutputChannel(
        streamingContext: InputStreamingContext,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo?,
        source: StreamPlanningService.StreamSource.Remote,
        started: Instant,
        isIptv: Boolean = false,
        expectedTotalBytes: Long = 0L
    ) {
        logger.trace("PIPE_STREAM_START: Starting to pipe stream: path={}, expectedTotalBytes={}, remaining={}", 
            source.cachedFile.path, expectedTotalBytes, byteRangeInfo!!.length())
        
        var hasReadFirstByte = false
        var timeToFirstByte: Double
        var remaining = byteRangeInfo!!.length()
        var firstByte = source.range.start
        var readBytes = 0L
        var readIterations = 0
        
        while (remaining > 0) {
            readIterations++
            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()

            val bytes = try {
                streamingContext.inputStream.readNBytes(size.toInt())
            } catch (e: Exception) {
                // Skip logging for CancellationException as it's expected during normal streaming
                if (e !is CancellationException) {
                    logger.trace("PIPE_STREAM_READ_EXCEPTION: Exception during readNBytes: iteration={}, remaining={}, size={}, readBytes={}, exceptionClass={}", 
                        readIterations, remaining, size, readBytes, e::class.simpleName, e)
                    logger.trace("PIPE_STREAM_READ_EXCEPTION_STACK_TRACE", e)
                }
                throw e
            }
            
            readBytes += bytes.size
            if (!hasReadFirstByte) {
                hasReadFirstByte = true
                timeToFirstByte = Duration.between(started, Instant.now()).toMillis().toDouble()
                val providerLabel = if (debridClients.none { it.getProvider() == source.cachedFile.provider }) {
                    "IPTV"
                } else {
                    source.cachedFile.provider.toString()
                }
                timeToFirstByteHistogram.labelValues(providerLabel).observe(timeToFirstByte)
            }
            if (bytes.isNotEmpty()) {
                send(
                    ByteArrayContext(
                        bytes, Range(firstByte, firstByte + bytes.size - 1), ByteArraySource.REMOTE
                    )
                )
                firstByte = firstByte + bytes.size
                remaining -= bytes.size
            } else {
                // Enhanced EOF logging
                val storedFileSize = source.cachedFile.size ?: -1L
                val fullFileSize = storedFileSize
                val readProgress = if (expectedTotalBytes > 0) (readBytes * 100 / expectedTotalBytes) else 0
                val rangeEnd = source.range.last
                val rangeStart = source.range.start
                val requestedRangeSize = rangeEnd - rangeStart + 1
                
                logger.trace("STREAMING_EOF_DETAILS: Stream ended prematurely: path={}, storedFileSize={}, requestedRange={}-{}, requestedRangeSize={}, expectedBytes={}, actualBytesRead={}, remainingBytes={}, readProgress={}%", 
                    source.cachedFile.path, storedFileSize, rangeStart, rangeEnd, requestedRangeSize, expectedTotalBytes, readBytes, remaining, readProgress)
                throw EOFException()
            }
        }
        
        logger.trace("PIPE_STREAM_END: Completed piping stream: path={}, totalBytesRead={}, iterations={}", 
            source.cachedFile.path, readBytes, readIterations)
    }

    private suspend fun ProducerScope<ByteArrayContext>.sendCachedBytes(
        source: StreamPlanningService.StreamSource.Cached
    ) {
        val bytes = fileChunkCachingService.getBytesFromChunk(
            source.fileChunk, source.range
        )

        this.send(
            ByteArrayContext(
                bytes, Range(source.range.start, source.range.last), ByteArraySource.CACHED
            )
        )
        logger.debug("sending cached bytes complete.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    suspend fun CoroutineScope.sendContent(
        byteArrayChannel: ReceiveChannel<ByteArrayContext>,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        trackingId: String?
    ) {
        val shouldBufferInMemory = debridavConfigProperties.enableInMemoryBuffering
        val shouldCacheToDatabase = debridavConfigProperties.enableChunkCaching
        
        // Only initialize cache variables if both buffering and caching are enabled
        var bytesToCache = if (shouldBufferInMemory && shouldCacheToDatabase) mutableListOf<BytesToCache>() else null
        var bytesToCacheSize = 0L
        var bytesSent = 0L
        val gaugeContext = OutputStreamingContext(
            ResettableCountingOutputStream(outputStream), remotelyCachedEntity.name!!
        )
        activeOutputStream.add(gaugeContext)
        try {
            byteArrayChannel.consumeEach { context ->
                if (shouldBufferInMemory && bytesToCache != null && context.source == ByteArraySource.REMOTE) {
                    bytesToCacheSize += context.byteArray.size
                    if (bytesToCacheSize > debridavConfigProperties.chunkCachingSizeThreshold) {
                        // Stop buffering if over threshold, but keep streaming
                        bytesToCache = null
                    } else {
                        bytesToCache?.add(
                            BytesToCache(
                                context.byteArray, context.range.start, context.range.finish
                            )
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    gaugeContext.outputStream.write(context.byteArray)
                }
                bytesSent += context.byteArray.size
                
                // Update download tracking with bytes sent
                trackingId?.let { id ->
                    activeDownloads[id]?.bytesDownloaded?.addAndGet(context.byteArray.size.toLong())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ClientAbortException) {
            cancel()
        } catch (e: Exception) {
            logger.error("An error occurred during streaming", e)
            throw StreamToClientException("An error occurred during streaming", e)
        } finally {
            gaugeContext.outputStream.close()
            activeOutputStream.removeStream(gaugeContext)
            
            // Only save to database if we have bytes to cache
            if (bytesToCache != null && bytesToCache.isNotEmpty()) {
                fileChunkCachingService.cacheBytes(remotelyCachedEntity, bytesToCache)
            }
        }
    }

    @Scheduled(fixedRate = STREAMING_METRICS_POLLING_RATE_S, timeUnit = TimeUnit.SECONDS)
    fun recordMetrics() {
        activeOutputStream.forEach {
            outputGauge.labelValues(it.file)
                .set(it.outputStream.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S))
        }
        activeInputStreams.forEach {
            inputGauge.labelValues(it.provider.toString(), it.file)
                .set(it.inputStream.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S))
        }
    }

    fun handleLinkNotFound(debridLink: CachedFile, remotelyCachedEntity: RemotelyCachedEntity, range: Range, outputStream: OutputStream): StreamResult {
        logger.warn("Link not found for ${debridLink.path}")
        return StreamResult.DEAD_LINK
    }

    fun handleEOFException(debridLink: CachedFile, remotelyCachedEntity: RemotelyCachedEntity, range: Range, outputStream: OutputStream): StreamResult {
        // Check if this is IPTV content to provide more informative logging
        val isIptvContent = remotelyCachedEntity.contents is io.skjaere.debridav.fs.DebridIptvContent
        val iptvProviderName = if (isIptvContent) {
            (remotelyCachedEntity.contents as io.skjaere.debridav.fs.DebridIptvContent).iptvProviderName
        } else {
            null
        }
        
        if (isIptvContent && iptvProviderName != null) {
            logger.warn("EOF reached while streaming IPTV content ${debridLink.path} from provider $iptvProviderName - provider may be unavailable or stream ended early")
        } else {
            logger.info("EOF reached while streaming ${debridLink.path}")
        }
        return StreamResult.OK
    }

    fun getCompletedDownloads(): List<DownloadTrackingContext> {
        return completedDownloads.toList()
    }

    fun initializeDownloadTracking(debridLink: CachedFile, range: Range?, remotelyCachedEntity: RemotelyCachedEntity, httpRequestInfo: HttpRequestInfo): String? {
        if (!debridavConfigProperties.enableStreamingDownloadTracking) return null
        
        cleanupExpiredDownloadTracking()
        
        val trackingId = "${System.currentTimeMillis()}-${debridLink.path.hashCode()}"
        val requestedSize = (range?.finish ?: debridLink.size!! - 1) - (range?.start ?: 0) + 1
        val fileName = remotelyCachedEntity.name ?: "unknown"

        val context = DownloadTrackingContext(
            filePath = debridLink.path ?: "unknown_path",
            fileName = fileName,
            requestedRange = range,
            requestedSize = requestedSize,
            httpHeaders = httpRequestInfo.headers,
            sourceIpAddress = httpRequestInfo.sourceIpAddress
        )
        
        activeDownloads[trackingId] = context
        
        logger.debug("DOWNLOAD_TRACKING_STARTED: file={}, requestedSize={} bytes, trackingId={}", 
            fileName, requestedSize, trackingId)
        
        return trackingId
    }

    fun completeDownloadTracking(trackingId: String, result: StreamResult) {
        if (!debridavConfigProperties.enableStreamingDownloadTracking) return
        
        val context = activeDownloads.remove(trackingId) ?: return
        context.downloadEndTime = Instant.now()
        context.completionStatus = when (result) {
            StreamResult.OK -> "completed"
            StreamResult.IO_ERROR -> "io_error"
            StreamResult.PROVIDER_ERROR -> "provider_error"
            StreamResult.CLIENT_ERROR -> "client_error"
            else -> "unknown_error"
        }
        
        // Set actual bytes sent to the final downloaded count
        context.actualBytesSent = context.bytesDownloaded.get()
        
        logger.debug("DOWNLOAD_TRACKING_COMPLETED: file={}, bytesDownloaded={}, actualBytesSent={}", 
            context.fileName, context.bytesDownloaded.get(), context.actualBytesSent)
        
        completedDownloads.add(context)
        while (completedDownloads.size > MAX_COMPLETED_DOWNLOADS_HISTORY) {
            completedDownloads.poll()
        }
    }

    private fun cleanupExpiredDownloadTracking() {
        if (!debridavConfigProperties.enableStreamingDownloadTracking) return
        
        val now = Instant.now()
        val expirationDuration = debridavConfigProperties.streamingDownloadTrackingCacheExpirationHours
        
        val iterator = completedDownloads.iterator()
        var removedCount = 0
        
        while (iterator.hasNext()) {
            val context = iterator.next()
            val downloadEndTime = context.downloadEndTime
            
            if (downloadEndTime != null) {
                val age = Duration.between(downloadEndTime, now)
                if (age >= expirationDuration) {
                    iterator.remove()
                    removedCount++
                }
            }
        }
        
        if (removedCount > 0) {
            logger.debug("Cleaned up $removedCount expired download tracking entries")
        }
    }
    
    /**
     * Checks if the given filename is a media file based on its extension.
     * Only media files should use local video serving, not subtitles or other files.
     */
    private fun isMediaFile(fileName: String): Boolean {
        return VideoFileExtensions.isVideoFile(fileName)
    }
    
    /**
     * Checks if an exception is caused by a client abort (client disconnecting).
     * This is expected behavior and shouldn't be logged as an error.
     */
    private fun isClientAbortException(e: Exception): Boolean {
        if (e is ClientAbortException) {
            return true
        }
        // Check if CancellationException is caused by ClientAbortException
        if (e is CancellationException) {
            var cause: Throwable? = e.cause
            while (cause != null) {
                if (cause is ClientAbortException) {
                    return true
                }
                cause = cause.cause
            }
        }
        // Check if any exception in the chain is ClientAbortException
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is ClientAbortException) {
                return true
            }
            cause = cause.cause
        }
        return false
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
}
