package io.skjaere.debridav.stream

import io.ktor.client.call.body
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
    prometheusRegistry: PrometheusRegistry
) {
    
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
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
        val originalRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))

        // Check if we should serve local video file for ARR requests
        if (debridavConfigProperties.shouldServeLocalVideoForArrs(httpRequestInfo)) {
            val fileName = remotelyCachedEntity.name ?: "unknown"
            val fullPath = remotelyCachedEntity.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
            
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
            logger.error("IOError occurred during streaming", e)
            result = StreamResult.IO_ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            result = StreamResult.UNKNOWN_ERROR
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
        // If not found, it's likely an IPTV file (using dummy provider)
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
        }
        
        httpStatement.execute { response ->
            response.body<ByteReadChannel>().toInputStream().use { inputStream ->
                // For IPTV files, provider might be a dummy value - set to null for metrics
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
                            streamingContext, byteRangeInfo, source, started
                        )
                    }
                } catch (e: CancellationException) {
                    close(e)
                    throw e
                } catch (e: Exception) {
                    logger.error("An error occurred during reading from stream", e)
                    throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                } finally {
                    response.cancel()
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }

    private suspend fun ProducerScope<ByteArrayContext>.pipeHttpInputStreamToOutputChannel(
        streamingContext: InputStreamingContext,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo?,
        source: StreamPlanningService.StreamSource.Remote,
        started: Instant
    ) {
        var hasReadFirstByte = false
        var timeToFirstByte: Double
        var remaining = byteRangeInfo!!.length()
        var firstByte = source.range.start
        var readBytes = 0L
        while (remaining > 0) {
            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()

            val bytes = streamingContext.inputStream.readNBytes(size.toInt())
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
                throw EOFException()
            }
        }
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
        logger.info("EOF reached while streaming ${debridLink.path}")
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
}
