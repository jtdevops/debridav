package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
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
import java.net.URI
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
    val sourceHostname: String? = null,
    val isInternal: Boolean = false
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
    private val iptvRequestService: io.skjaere.debridav.iptv.IptvRequestService?,
    private val iptvLoginRateLimitService: io.skjaere.debridav.iptv.IptvLoginRateLimitService?,
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
            
            // Check if file size is a default value and refetch if needed
            // This is important for byte range headers even when using local video files
            if (iptvRequestService != null && providerName != null) {
                try {
                    // Determine content type to check if file size is default
                    val contentType = iptvRequestService.getContentTypeForRefId(iptvContent.iptvContentRefId)
                        ?: io.skjaere.debridav.iptv.model.ContentType.MOVIE // Default to MOVIE if we can't determine
                    
                    val currentFileSize = iptvContent.size
                    if (iptvRequestService.isDefaultFileSize(currentFileSize, contentType)) {
                        logger.info("Detected default file size for IPTV content, attempting to refetch actual size: currentSize={}, contentType={}, iptvProvider={}", 
                            currentFileSize, contentType, providerName)
                        
                        // Refetch file size from IPTV provider
                        val newFileSize = iptvRequestService.refetchAndUpdateFileSize(iptvContent, remotelyCachedEntity)
                        if (newFileSize != null && newFileSize != currentFileSize) {
                            logger.info("Successfully refetched file size: oldSize={}, newSize={}", currentFileSize, newFileSize)
                            // Update debridLink size as well
                            debridLink.size = newFileSize
                        } else {
                            logger.debug("File size refetch did not change size or failed: currentSize={}, newSize={}", currentFileSize, newFileSize)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to check/refetch default file size for IPTV content: ${e.message}", e)
                }
            }
            
            if (isArrRequest && !shouldBypass) {
                logger.debug("Skipping IPTV provider login call for ARR request (will use local video file, iptvProvider=$providerName)")
            } else if (providerName != null && iptvConfigurationProperties != null && iptvConfigurationService != null && iptvResponseFileService != null && iptvLoginRateLimitService != null) {
                try {
                    // Rate limiting: shared across all services per provider
                    if (iptvLoginRateLimitService.shouldRateLimit(providerName)) {
                        val timeSinceLastCall = iptvLoginRateLimitService.getTimeSinceLastCall(providerName)
                        logger.debug("Skipping IPTV provider login call for $providerName (rate limited, last call was ${timeSinceLastCall}ms ago)")
                    } else {
                        val providerConfigs = iptvConfigurationService.getProviderConfigurations()
                        val providerConfig = providerConfigs.find { it.name == providerName }
                        if (providerConfig != null && providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                            logger.debug("Making initial login call to IPTV provider $providerName before streaming")
                            val userAgent = iptvConfigurationProperties?.userAgent
                            val xtreamCodesClient = io.skjaere.debridav.iptv.client.XtreamCodesClient(httpClient, iptvResponseFileService, userAgent)
                            val loginSuccess = xtreamCodesClient.verifyAccount(providerConfig)
                            // Update timestamp after successful call
                            iptvLoginRateLimitService.recordLoginCall(providerName)
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
        val requestedChunkSize = appliedRange.finish - appliedRange.start + 1
        val requestedChunkSizeMB = requestedChunkSize / (1024 * 1024)
        
        // Warn if chunk size exceeds configured threshold (may cause buffer underruns)
        if (requestedChunkSizeMB > debridavConfigProperties.streamingMaxChunkSizeWarningMb) {
            logger.warn("LARGE_CHUNK_REQUEST: file={}, range={}-{}, chunkSize={} bytes ({} MB), exceeds warning threshold of {} MB. Large chunks may cause video playback pauses due to buffer underruns.", 
                remotelyCachedEntity.name ?: "unknown", appliedRange.start, appliedRange.finish, 
                requestedChunkSize, requestedChunkSizeMB, debridavConfigProperties.streamingMaxChunkSizeWarningMb)
        }
        
        // Determine provider label for logging - use DebridProvider.IPTV for IPTV content, otherwise use debrid provider
        val providerLabel = if (remotelyCachedEntity.contents is io.skjaere.debridav.fs.DebridIptvContent || debridLink.provider == io.skjaere.debridav.debrid.DebridProvider.IPTV) {
            io.skjaere.debridav.debrid.DebridProvider.IPTV.toString()
        } else {
            debridLink.provider?.toString() ?: "null"
        }
        
        logger.debug("EXTERNAL_FILE_STREAMING: file={}, range={}-{}, size={} bytes ({} MB), provider={}, source={}", 
            remotelyCachedEntity.name ?: "unknown", appliedRange.start, appliedRange.finish, 
            requestedChunkSize, requestedChunkSizeMB, providerLabel, httpRequestInfo.sourceInfo)
        
        val trackingId = initializeDownloadTracking(debridLink, range, remotelyCachedEntity, httpRequestInfo)
        
        // Create a simple log ID for linking start/stop logs
        // Use only timestamp for simplicity - it's unique enough to link start/stop pairs
        // This is independent of trackingId (which is used for /actuator/streaming-download-tracking)
        val logId = System.currentTimeMillis().toString()
        
        // Log video download start at INFO level (only for external requests, not internal operations)
        // Also suppress logs for very small ranges (< 1KB) which are likely metadata requests
        val fileName = remotelyCachedEntity.name ?: "unknown"
        val requestedSize = appliedRange.finish - appliedRange.start + 1
        val requestedSizeMB = String.format("%.2f", requestedSize / 1_000_000.0)
        val isSmallRange = requestedSize < 1024 // Less than 1KB is likely a metadata request
        val shouldLog = !httpRequestInfo.isInternal && !isSmallRange
        
        if (shouldLog) {
            logger.info("Video download started [id={}]: file={}, range={}-{}, size={} bytes ({} MB), provider={}", 
                logId, fileName, appliedRange.start, appliedRange.finish, requestedSize, requestedSizeMB, providerLabel)
        } else {
            logger.debug("Video download started (internal/small) [id={}]: file={}, range={}-{}, size={} bytes ({} MB), provider={}, isInternal={}, isSmallRange={}", 
                logId, fileName, appliedRange.start, appliedRange.finish, requestedSize, requestedSizeMB, providerLabel, httpRequestInfo.isInternal, isSmallRange)
        }
        
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
            // Check if this is a ConnectTimeoutException (handled gracefully with fallback)
            val isConnectTimeout = e.javaClass.simpleName == "ConnectTimeoutException" || 
                                   e.message?.contains("Connect timeout") == true
            
            // Check if this is a client abort exception (expected when client disconnects)
            val isClientAbort = isClientAbortException(e) ||
                               e.message?.contains("Connection reset") == true ||
                               e.message?.contains("Connection reset by peer") == true ||
                               e.message?.contains("Broken pipe") == true ||
                               e.cause?.message?.contains("Connection reset") == true ||
                               e.cause?.message?.contains("Connection reset by peer") == true ||
                               e.cause?.message?.contains("Broken pipe") == true
            
            if (isConnectTimeout) {
                // Reduced logging for handled ConnectTimeoutException - fallback will handle it
                logger.debug("STREAMING_IO_EXCEPTION: Connect timeout during streaming: path={}, link={}, provider={}, exceptionClass={}", 
                    debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName)
                result = StreamResult.IO_ERROR
            } else if (isClientAbort) {
                // Client disconnect is expected behavior, log at DEBUG level
                logger.debug("Client disconnected during streaming (expected): path={}, exceptionClass={}", 
                    debridLink.path, e::class.simpleName)
                result = StreamResult.OK
            } else {
                // TRACE level logging for other streaming IO exceptions with full stack trace
                logger.trace("STREAMING_IO_EXCEPTION: IOException during streaming: path={}, link={}, provider={}, exceptionClass={}", 
                    debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
                // Explicitly log stack trace to ensure it appears
                logger.trace("STREAMING_IO_EXCEPTION_STACK_TRACE", e)
                logger.error("IOError occurred during streaming", e)
                result = StreamResult.IO_ERROR
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Check if this is a client abort exception (expected when client disconnects)
            if (isClientAbortException(e) || 
                e.javaClass.simpleName == "AsyncRequestNotUsableException" ||
                e.message?.contains("Connection reset") == true ||
                e.message?.contains("Connection reset by peer") == true ||
                e.message?.contains("Broken pipe") == true ||
                e.cause?.message?.contains("Connection reset") == true ||
                e.cause?.message?.contains("Connection reset by peer") == true ||
                e.cause?.message?.contains("Broken pipe") == true) {
                logger.debug("Client disconnected during streaming (expected): path={}, exceptionClass={}", 
                    debridLink.path, e::class.simpleName)
                result = StreamResult.OK
            } else {
                // TRACE level logging for streaming runtime exceptions with full stack trace
                logger.trace("STREAMING_RUNTIME_EXCEPTION: RuntimeException during streaming: path={}, link={}, provider={}, exceptionClass={}", 
                    debridLink.path, debridLink.link?.take(100), debridLink.provider, e::class.simpleName, e)
                // Explicitly log stack trace to ensure it appears
                logger.trace("STREAMING_RUNTIME_EXCEPTION_STACK_TRACE", e)
                logger.error("An error occurred during streaming ${debridLink.path}", e)
                result = StreamResult.UNKNOWN_ERROR
            }
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
        
        val fileNameForCompletion = remotelyCachedEntity.name ?: "unknown"
        try {
            // Cleanup
        } finally {
            this.coroutineContext.cancelChildren()
            
            // Get bytes downloaded before completing tracking (which removes the context)
            val actualBytesDownloaded = trackingId?.let { id -> 
                activeDownloads[id]?.bytesDownloaded?.get() ?: 0L
            } ?: 0L
            val actualBytesDownloadedMB = String.format("%.2f", actualBytesDownloaded / 1_000_000.0)
            
            trackingId?.let { id -> completeDownloadTracking(id, result) }
            
            // Log video download completion at INFO level (only for external requests, not internal operations)
            // Also suppress logs for very small ranges (< 1KB) which are likely metadata requests
            // Reuse the same logId from start to link the logs together
            val status = when (result) {
                StreamResult.OK -> "completed"
                StreamResult.IO_ERROR -> "io_error"
                StreamResult.PROVIDER_ERROR -> "provider_error"
                StreamResult.CLIENT_ERROR -> "client_error"
                else -> "error"
            }
            val isSmallRange = requestedSize < 1024 // Less than 1KB is likely a metadata request
            val shouldLog = !httpRequestInfo.isInternal && !isSmallRange
            
            if (shouldLog) {
                logger.info("Video download stopped [id={}]: file={}, size={} bytes ({} MB), downloaded={} bytes ({} MB), status={}", 
                    logId, fileNameForCompletion, requestedSize, requestedSizeMB, actualBytesDownloaded, actualBytesDownloadedMB, status)
            } else {
                logger.debug("Video download stopped (internal/small) [id={}]: file={}, size={} bytes ({} MB), downloaded={} bytes ({} MB), status={}, isInternal={}, isSmallRange={}", 
                    logId, fileNameForCompletion, requestedSize, requestedSizeMB, actualBytesDownloaded, actualBytesDownloadedMB, status, httpRequestInfo.isInternal, isSmallRange)
            }
        }
        logger.debug("done streaming ${debridLink.path}: $result")
        result
    }
    

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

    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, debridLink: CachedFile, outputStream: OutputStream, trackingId: String?
    ) = coroutineScope {
        val streamingPlan = streamPlanningService.generatePlan(
            fileChunkCachingService.getAllCachedChunksForEntity(remotelyCachedEntity),
            LongRange(range.start, range.finish),
            debridLink
        )
        
        // Optimize for direct streaming when there are no cached chunks and both caching and buffering are disabled
        // This ensures data flows directly from external provider to client without server-side buffering
        val shouldUseDirectStreaming = !debridavConfigProperties.enableChunkCaching && 
                                      !debridavConfigProperties.enableInMemoryBuffering &&
                                      streamingPlan.sources.size == 1 && 
                                      streamingPlan.sources.first() is StreamPlanningService.StreamSource.Remote
        
        if (shouldUseDirectStreaming) {
            // Direct streaming: bypass channel buffering, stream directly from HTTP to client
            val remoteSource = streamingPlan.sources.first() as StreamPlanningService.StreamSource.Remote
            logger.debug("DIRECT_STREAMING: Using direct streaming path (no caching, single remote source): file={}, range={}-{}", 
                remotelyCachedEntity.name, remoteSource.range.first, remoteSource.range.last)
            streamDirectlyFromHttp(remoteSource, outputStream, trackingId)
        } else {
            // Use channel-based streaming for cached chunks or when caching is enabled
            launch {
                val sources = getSources(streamingPlan)
                val byteArrays = getByteArrays(sources)
                sendContent(byteArrays, outputStream, remotelyCachedEntity, trackingId)
            }
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

    /**
     * Direct streaming: streams data directly from HTTP response to client output stream
     * without buffering through channels. Used when caching is disabled and there are no cached chunks.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun streamDirectlyFromHttp(
        source: StreamPlanningService.StreamSource.Remote,
        outputStream: OutputStream,
        trackingId: String?
    ) {
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val started = Instant.now()
        val isIptv = isIptvUrl(source.cachedFile.link ?: "")
        val expectedBytes = byteRangeInfo!!.length()
        
        // Prepare HTTP request (same logic as sendBytesFromHttpStreamWithKtor)
        val httpStatement = try {
            val debridClient = debridClients.firstOrNull { it.getProvider() == source.cachedFile.provider }
            if (debridClient != null) {
                debridClient.prepareStreamUrl(source.cachedFile, range)
            } else {
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
            logger.trace("STREAMING_EXCEPTION: Error preparing stream URL: path={}, link={}, provider={}, exceptionClass={}", 
                source.cachedFile.path, source.cachedFile.link?.take(100), source.cachedFile.provider, e::class.simpleName, e)
            logger.trace("STREAMING_EXCEPTION_STACK_TRACE", e)
            throw e
        }
        
        // Execute HTTP request and stream directly to output
        httpStatement.execute { response ->
            // Handle redirects (same logic as sendBytesFromHttpStreamWithKtor)
            if (response.status.value in 300..399) {
                val redirectLocationHeader = response.headers["Location"]
                if (redirectLocationHeader != null) {
                    logger.debug("DIRECT_STREAMING_REDIRECT: Following redirect: path={}, redirectLocation={}", 
                        source.cachedFile.path, redirectLocationHeader.take(100))
                    
                    try {
                        response.body<ByteReadChannel>()
                    } catch (e: Exception) {
                        // Ignore errors when consuming redirect body
                    }
                    response.cancel()
                    
                    val redirectUrl = if (redirectLocationHeader.startsWith("http://") || redirectLocationHeader.startsWith("https://")) {
                        redirectLocationHeader
                    } else {
                        val originalUri = URI(source.cachedFile.link!!)
                        originalUri.resolve(redirectLocationHeader).toString()
                    }
                    
                    // Calculate dynamic timeout for redirect
                    val chunkSizeBytes = source.range.last - source.range.first + 1
                    val dynamicSocketTimeout = calculateSocketTimeout(chunkSizeBytes)
                    
                    // Use shorter connect timeout for redirects (2 seconds) since redirects are typically fast
                    // This reduces latency when following redirect chains
                    val redirectConnectTimeout = minOf(2000L, debridavConfigProperties.connectTimeoutMilliseconds)
                    
                    val redirectStatement = httpClient.prepareGet(redirectUrl) {
                        headers {
                            val rangeHeader = "bytes=${source.range.start}-${source.range.last}"
                            append(HttpHeaders.Range, rangeHeader)
                            iptvConfigurationProperties?.userAgent?.let {
                                append(HttpHeaders.UserAgent, it)
                            }
                        }
                        timeout {
                            requestTimeoutMillis = 20_000_000
                            socketTimeoutMillis = dynamicSocketTimeout
                            connectTimeoutMillis = redirectConnectTimeout
                        }
                    }
                    
                    redirectStatement.execute { redirectResponse ->
                        if (redirectResponse.status.value in 300..399) {
                            redirectResponse.cancel()
                            throw ReadFromHttpStreamException("Redirect chain detected", RuntimeException("Multiple redirects"))
                        }
                        
                        // Stream redirect response directly to output
                        redirectResponse.body<ByteReadChannel>().toInputStream().use { inputStream ->
                            streamDirectlyToOutput(inputStream, outputStream, expectedBytes, trackingId, source.cachedFile.path!!)
                        }
                        redirectResponse.cancel()
                    }
                } else {
                    response.cancel()
                    throw ReadFromHttpStreamException("Redirect without Location header", RuntimeException("No Location header"))
                }
            } else {
                // Stream response directly to output (no redirect)
                response.body<ByteReadChannel>().toInputStream().use { inputStream ->
                    streamDirectlyToOutput(inputStream, outputStream, expectedBytes, trackingId, source.cachedFile.path!!)
                }
            }
        }
    }
    
    /**
     * Streams data directly from input stream to output stream with minimal buffering.
     * Uses a 64KB buffer for efficient streaming without excessive memory usage.
     */
    private suspend fun streamDirectlyToOutput(
        inputStream: java.io.InputStream,
        outputStream: OutputStream,
        expectedBytes: Long,
        trackingId: String?,
        filePath: String
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE.toInt())
        val flushInterval = DEFAULT_BUFFER_SIZE * 10L // Flush every 640KB
        var totalBytesRead = 0L
        var bytesRead: Int
        var bytesSinceLastFlush = 0L
        
        try {
            while (totalBytesRead < expectedBytes) {
                val bytesToRead = minOf(buffer.size.toLong(), expectedBytes - totalBytesRead).toInt()
                bytesRead = inputStream.read(buffer, 0, bytesToRead)
                
                if (bytesRead == -1) {
                    logger.trace("DIRECT_STREAMING_EOF: Stream ended early: path={}, expectedBytes={}, actualBytesRead={}", 
                        filePath, expectedBytes, totalBytesRead)
                    break
                }
                
                // Write directly to output stream
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                bytesSinceLastFlush += bytesRead
                
                // Update tracking
                trackingId?.let { id ->
                    activeDownloads[id]?.bytesDownloaded?.addAndGet(bytesRead.toLong())
                }
                
                // Flush periodically to ensure data flows to client
                if (bytesSinceLastFlush >= flushInterval) {
                    outputStream.flush()
                    bytesSinceLastFlush = 0L
                }
            }
            
            // Final flush
            outputStream.flush()
            
            logger.trace("DIRECT_STREAMING_COMPLETE: path={}, expectedBytes={}, actualBytesRead={}", 
                filePath, expectedBytes, totalBytesRead)
        } catch (e: Exception) {
            logger.trace("DIRECT_STREAMING_EXCEPTION: path={}, bytesRead={}, exceptionClass={}", 
                filePath, totalBytesRead, e::class.simpleName, e)
            throw e
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
            
            // HttpRedirect plugin doesn't follow redirects when Range headers are present
            // Manually follow redirects and re-apply Range headers to ensure the provider honors the requested range
            if (response.status.value in 300..399) {
                val redirectLocationHeader = response.headers["Location"]
                if (redirectLocationHeader != null) {
                    logger.debug("REDIRECT_RESPONSE: Following redirect manually and re-applying Range headers: path={}, originalLink={}, redirectLocation={}, requestedRange={}, isIptv={}", 
                        source.cachedFile.path, source.cachedFile.link?.take(100), redirectLocationHeader, requestedRange, isIptv)
                    
                    // Consume and cancel the redirect response
                    try {
                        response.body<ByteReadChannel>()
                    } catch (e: Exception) {
                        // Ignore errors when consuming redirect body
                    }
                    response.cancel()
                    
                    // Resolve redirect URL (handle relative redirects)
                    val redirectUrl = if (redirectLocationHeader.startsWith("http://") || redirectLocationHeader.startsWith("https://")) {
                        redirectLocationHeader
                    } else {
                        // Relative redirect - construct absolute URL
                        val originalUri = URI(source.cachedFile.link!!)
                        originalUri.resolve(redirectLocationHeader).toString()
                    }
                    
                    // Apply Range headers to redirect URLs to ensure the provider honors the requested range
                    // Some providers don't preserve Range headers through redirects, so we must re-apply them
                    // Use prepareGet().execute() to stream the response instead of buffering it
                    
                    // Calculate dynamic socket timeout based on chunk size
                    val chunkSizeBytes = source.range.last - source.range.first + 1 // Both are inclusive
                    val dynamicSocketTimeout = calculateSocketTimeout(chunkSizeBytes)
                    
                    // Use shorter connect timeout for redirects (2 seconds) since redirects are typically fast
                    // This reduces latency when following redirect chains
                    val redirectConnectTimeout = minOf(2000L, debridavConfigProperties.connectTimeoutMilliseconds)
                    
                    val redirectStatement = httpClient.prepareGet(redirectUrl) {
                        headers {
                            // Apply Range headers to redirect URLs to preserve range requests (e.g., when skipping in video)
                            val rangeHeader = "bytes=${source.range.start}-${source.range.last}"
                            append(HttpHeaders.Range, rangeHeader)
                            logger.debug("REDIRECT_REQUEST: Making request to redirect URL with Range header: redirectUrl={}, rangeHeader={}, isIptv={}, chunkSize={} bytes ({}), timeout={} ms", 
                                redirectUrl.take(100), rangeHeader, isIptv, chunkSizeBytes, 
                                org.apache.commons.io.FileUtils.byteCountToDisplaySize(chunkSizeBytes), dynamicSocketTimeout)
                            iptvConfigurationProperties?.userAgent?.let {
                                append(HttpHeaders.UserAgent, it)
                            }
                        }
                        timeout {
                            requestTimeoutMillis = 20_000_000
                            socketTimeoutMillis = dynamicSocketTimeout
                            connectTimeoutMillis = redirectConnectTimeout
                        }
                    }
                    
                    // Execute the redirect request and stream the response
                    return@execute try {
                        redirectStatement.execute { redirectResponse ->
                            // Check for redirect chain (shouldn't happen, but handle it)
                            if (redirectResponse.status.value in 300..399) {
                                logger.warn("REDIRECT_CHAIN: Redirect chain detected: path={}, redirectLocation={}, status={}", 
                                    source.cachedFile.path, redirectResponse.headers["Location"], redirectResponse.status.value)
                                redirectResponse.cancel()
                                throw ReadFromHttpStreamException("Redirect chain detected - multiple redirects not supported", 
                                    RuntimeException("Multiple redirects detected"))
                            }
                            
                            logger.trace("REDIRECT_RESPONSE_RECEIVED: Received response from redirect URL: redirectUrl={}, status={}, contentLength={}", 
                                redirectUrl.take(100), redirectResponse.status.value, redirectResponse.headers["Content-Length"])
                            
                            // Stream the redirect response body directly
                            try {
                                redirectResponse.body<ByteReadChannel>().toInputStream().use { inputStream ->
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
                                        val actualBytesRead = streamingContext.inputStream.getTotalCount()
                                        val storedFileSize = source.cachedFile.size ?: -1L
                                        val httpContentLength = redirectResponse.headers["Content-Length"]?.toLongOrNull() ?: -1L
                                        val fullFileSize = if (storedFileSize > 0) storedFileSize else httpContentLength
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
                                        logger.trace("STREAMING_EXCEPTION_STACK_TRACE (after redirect)", e)
                                        logger.error("An error occurred during reading from stream after redirect", e)
                                        throw ReadFromHttpStreamException("An error occurred during reading from stream after redirect", e)
                                    } finally {
                                        redirectResponse.cancel()
                                        activeInputStreams.removeStream(streamingContext)
                                    }
                                }
                            } catch (e: Exception) {
                                // Suppress stacktraces for ClientAbortException, CancellationException, and EOFException (normal client disconnects)
                                if (isClientAbortException(e) || e is CancellationException || e is EOFException) {
                                    logger.debug("REDIRECT_BODY_EXCEPTION: Client disconnected (expected): redirectUrl={}, exceptionClass={}", 
                                        redirectUrl.take(100), e::class.simpleName)
                                } else {
                                    logger.trace("REDIRECT_BODY_EXCEPTION: Exception creating input stream from redirect response body: redirectUrl={}, exceptionClass={}", 
                                        redirectUrl.take(100), e::class.simpleName, e)
                                    logger.trace("REDIRECT_BODY_EXCEPTION_STACK_TRACE", e)
                                }
                                throw ReadFromHttpStreamException("Failed to create input stream from redirect response: $redirectUrl", e)
                            }
                        }
                    } catch (e: Exception) {
                        // Suppress stacktraces for ClientAbortException, CancellationException, and EOFException (normal client disconnect)
                        if (isClientAbortException(e) || e is CancellationException || e is EOFException) {
                            logger.debug("REDIRECT_REQUEST_EXCEPTION: Client disconnected (expected): redirectUrl={}, exceptionClass={}", 
                                redirectUrl.take(100), e::class.simpleName)
                        } else {
                            val rangeInfo = if (isIptv) "no-range-header" else "bytes=${source.range.start}-${source.range.last}"
                            logger.trace("REDIRECT_REQUEST_EXCEPTION: Exception making request to redirect URL: redirectUrl={}, rangeInfo={}, exceptionClass={}", 
                                redirectUrl.take(100), rangeInfo, e::class.simpleName, e)
                            logger.trace("REDIRECT_REQUEST_EXCEPTION_STACK_TRACE", e)
                        }
                        throw ReadFromHttpStreamException("Failed to make request to redirect URL: $redirectUrl", e)
                    }
                } else {
                    logger.warn("REDIRECT_RESPONSE: Received redirect status {} but no Location header: path={}, originalLink={}, requestedRange={}", 
                        response.status.value, source.cachedFile.path, source.cachedFile.link?.take(100), requestedRange)
                    response.cancel()
                    throw ReadFromHttpStreamException("Received redirect response (${response.status.value}) but no Location header found", 
                        RuntimeException("Redirect response without Location header"))
                }
            }
            
            // Process the response normally (no redirect)
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
                        
                        logger.trace("STREAMING_EOF: EOFException during stream read: path={}, link={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, storedFileSize={}, httpContentLength={}, fullFileSize={}, sizeMismatch={}, httpStatus={}, exceptionClass={}", 
                            source.cachedFile.path, source.cachedFile.link?.take(100), requestedRange, expectedBytes, actualBytesRead, storedFileSize, httpContentLength, fullFileSize, sizeMismatch, response.status.value, e::class.simpleName)
                        throw e
                    } catch (e: Exception) {
                        val actualBytesRead = streamingContext.inputStream.getTotalCount()
                        logger.trace("STREAMING_EXCEPTION: Exception during stream read: path={}, link={}, requestedRange={}, expectedBytes={}, actualBytesRead={}, httpStatus={}, exceptionClass={}", 
                            source.cachedFile.path, source.cachedFile.link?.take(100), requestedRange, expectedBytes, actualBytesRead, response.status.value, e::class.simpleName, e)
                        // Explicitly log stack trace to ensure it appears
                        logger.trace("STREAMING_EXCEPTION_STACK_TRACE", e)
                        logger.error("An error occurred during reading from stream", e)
                        throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                    } finally {
                        activeInputStreams.removeStream(streamingContext)
                    }
                }
            } catch (e: Exception) {
                // Check if this is a client abort or cancellation (expected when client disconnects)
                if (isClientAbortException(e) || e is CancellationException || e is EOFException) {
                    logger.debug("BODY_EXCEPTION: Client disconnected (expected): link={}, exceptionClass={}", 
                        source.cachedFile.link?.take(100), e::class.simpleName)
                } else {
                    logger.trace("BODY_EXCEPTION: Exception creating input stream from response body: link={}, exceptionClass={}", 
                        source.cachedFile.link?.take(100), e::class.simpleName, e)
                    logger.trace("BODY_EXCEPTION_STACK_TRACE", e)
                }
                response.cancel()
                throw ReadFromHttpStreamException("Failed to create input stream from response: ${source.cachedFile.link}", e)
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
            // Handle the case where consumeEach calls cancelConsumed but the consumer has already failed
            // This is expected when the consumer fails before the producer completes
            if (e.message?.contains("Channel was consumed, consumer had failed") == true) {
                logger.debug("Channel cancellation during consumeEach (expected when consumer fails): path={}", 
                    remotelyCachedEntity.name)
                // Don't rethrow - this is expected behavior when consumer fails
                return
            }
            throw e
        } catch (_: ClientAbortException) {
            cancel()
        } catch (e: Exception) {
            // Check if this is a client abort exception (expected when client disconnects)
            if (isClientAbortException(e) || 
                e.javaClass.simpleName == "AsyncRequestNotUsableException" ||
                e.cause?.message?.contains("Connection reset") == true ||
                e.cause?.message?.contains("Connection reset by peer") == true ||
                e.message?.contains("Connection reset") == true ||
                e.message?.contains("Connection reset by peer") == true) {
                logger.debug("Client disconnected during streaming (expected): path={}, exceptionClass={}", 
                    remotelyCachedEntity.name, e::class.simpleName)
                cancel()
            } else {
                logger.error("An error occurred during streaming", e)
                throw StreamToClientException("An error occurred during streaming", e)
            }
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

    fun clearCompletedDownloads() {
        completedDownloads.clear()
    }

    fun initializeDownloadTracking(debridLink: CachedFile, range: Range?, remotelyCachedEntity: RemotelyCachedEntity, httpRequestInfo: HttpRequestInfo): String? {
        if (!debridavConfigProperties.enableStreamingDownloadTracking) return null
        
        cleanupExpiredDownloadTracking()
        
        val trackingId = "${System.currentTimeMillis()}-${debridLink.path.hashCode()}"
        val requestedSize = (range?.finish ?: debridLink.size!! - 1) - (range?.start ?: 0) + 1
        val fileName = remotelyCachedEntity.name ?: "unknown"
        
        // Get the actual realized file path including parent folders
        // This is the actual file path in the filesystem, not the original magnet path
        val actualFilePath = remotelyCachedEntity.directory?.fileSystemPath()?.let { 
            "$it/$fileName" 
        } ?: fileName

        val context = DownloadTrackingContext(
            filePath = actualFilePath,
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
        // Check for AsyncRequestNotUsableException (Spring wrapper for client disconnects)
        // This includes both "Connection reset" and "Response not usable after response errors" cases
        if (e.javaClass.simpleName == "AsyncRequestNotUsableException") {
            return true
        }
        // Check for connection reset messages in exception message
        if (e.message?.contains("Connection reset") == true || 
            e.message?.contains("Connection reset by peer") == true ||
            e.message?.contains("Broken pipe") == true) {
            return true
        }
        // Check if CancellationException is caused by ClientAbortException
        if (e is CancellationException) {
            var cause: Throwable? = e.cause
            while (cause != null) {
                if (cause is ClientAbortException || 
                    cause.javaClass.simpleName == "AsyncRequestNotUsableException" ||
                    cause.message?.contains("Connection reset") == true ||
                    cause.message?.contains("Connection reset by peer") == true ||
                    cause.message?.contains("Broken pipe") == true) {
                    return true
                }
                cause = cause.cause
            }
        }
        // Check if any exception in the chain is ClientAbortException or has connection reset message
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is ClientAbortException || 
                cause.javaClass.simpleName == "AsyncRequestNotUsableException" ||
                cause.message?.contains("Connection reset") == true ||
                cause.message?.contains("Connection reset by peer") == true ||
                cause.message?.contains("Broken pipe") == true) {
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
