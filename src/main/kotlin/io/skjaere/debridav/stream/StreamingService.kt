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
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import org.apache.commons.io.FileUtils
import java.net.InetAddress
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
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
import kotlinx.io.EOFException
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


private const val DEFAULT_BUFFER_SIZE = 65536L //64kb
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds
private const val BYTE_CHANNEL_CAPACITY = 2000
private const val MAX_COMPLETED_DOWNLOADS_HISTORY = 1000

/**
 * OutputStream that writes to two output streams simultaneously.
 */
private class TeeOutputStream(
    private val stream1: OutputStream,
    private val stream2: OutputStream
) : OutputStream() {

    override fun write(b: Int) {
        stream1.write(b)
        stream2.write(b)
    }

    override fun write(b: ByteArray) {
        stream1.write(b)
        stream2.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        stream1.write(b, off, len)
        stream2.write(b, off, len)
    }

    override fun flush() {
        stream1.flush()
        stream2.flush()
    }

    override fun close() {
        try {
            stream1.close()
        } finally {
            stream2.close()
        }
    }
}

data class DownloadTrackingContext(
    val filePath: String,
    val fileName: String,
    val requestedRange: Range?,
    val requestedSize: Long,
    val downloadStartTime: Instant = Instant.now(),
    val bytesDownloaded: AtomicLong = AtomicLong(0),
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

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigProperties: DebridavConfigurationProperties,
    private val streamPlanningService: StreamPlanningService,
    private val debridLinkService: DebridLinkService,
    prometheusRegistry: PrometheusRegistry
) {
    // Cache for rclone/arrs limited data - key is just "filePath" since we normalize ranges
    private val rcloneArrsCache: Cache<String, ByteArray> = if (debridavConfigProperties.rcloneArrsCacheEnabled) {
        CacheBuilder.newBuilder()
            .maximumSize((debridavConfigProperties.rcloneArrsCacheSizeMb * 1024 * 1024 / (debridavConfigProperties.rcloneArrsMaxDataKb * 1024)).toLong()) // Estimate based on max data size
            .expireAfterAccess(debridavConfigProperties.rcloneArrsCacheExpiryMinutes, TimeUnit.MINUTES) // Configurable expiry time
            .build()
    } else {
        CacheBuilder.newBuilder().maximumSize(0).build() // Disabled cache
    }
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

        // Apply range limiting for rclone/arrs requests if enabled
        val appliedRange = if (range != null) {
            debridavConfigProperties.getLimitedRangeForRcloneArrs(originalRange, httpRequestInfo)
        } else {
            originalRange
        }

        // Log if range was limited for rclone/arrs
        if (appliedRange != originalRange && debridavConfigProperties.enableRcloneArrsDataLimiting) {
            logger.info("RCLONE_ARRS_RANGE_LIMITED: file={}, original_range={}-{}, limited_range={}-{}, max_kb={}",
                remotelyCachedEntity.name ?: "unknown",
                originalRange.start, originalRange.finish,
                appliedRange.start, appliedRange.finish,
                debridavConfigProperties.rcloneArrsMaxDataKb)
        }

        // Check for cached data for rclone/arrs requests
        val cachedData = getCachedRcloneArrsData(debridLink, appliedRange, httpRequestInfo)
        if (cachedData != null) {
            logger.info("RCLONE_ARRS_CACHE_HIT: file={}, range={}-{}, size={} bytes",
                remotelyCachedEntity.name ?: "unknown",
                appliedRange.start, appliedRange.finish,
                cachedData.size)

            // Write cached data directly to output stream
            withContext(Dispatchers.IO) {
                outputStream.write(cachedData)
                outputStream.flush()
            }

            val trackingId = initializeDownloadTracking(debridLink, range, remotelyCachedEntity, httpRequestInfo)
            trackingId?.let { id -> completeDownloadTracking(id, StreamResult.OK) }
            logger.info("done streaming ${debridLink.path} from cache: OK")
            return@coroutineScope StreamResult.OK
        }

        val trackingId = initializeDownloadTracking(debridLink, range, remotelyCachedEntity, httpRequestInfo)

        var result: StreamResult = StreamResult.OK
        try {
            // For rclone/arrs requests with caching enabled, try to serve from cache first
            // This is only executed when: cache enabled AND data limiting enabled AND request matches rclone/arrs patterns
            // This ensures cached data is NEVER served to non-rclone/arrs clients
            if (debridavConfigProperties.rcloneArrsCacheEnabled &&
                debridavConfigProperties.enableRcloneArrsDataLimiting &&
                debridavConfigProperties.shouldLimitDataForRcloneArrs(httpRequestInfo)) {

                val cachedData = getCachedRcloneArrsData(debridLink, appliedRange, httpRequestInfo)
                if (cachedData != null) {
                    logger.info("RCLONE_ARRS_CACHE_HIT: file={}, requested_range={}-{}, cached_size={} bytes",
                        remotelyCachedEntity.name ?: "unknown",
                        appliedRange.start, appliedRange.finish,
                        cachedData.size)

                    withContext(Dispatchers.IO) {
                        outputStream.write(cachedData)
                        outputStream.flush()
                    }
                    result = StreamResult.OK
                } else {
                    // Fetch and cache the data
                    val fetchedData = fetchAndCacheRcloneArrsData(debridLink, appliedRange, remotelyCachedEntity, outputStream)
                    if (fetchedData != null) {
                        result = StreamResult.OK
                    } else {
                        result = StreamResult.IO_ERROR
                    }
                }
            } else {
                // Normal streaming for non-rclone/arrs requests
                streamBytes(remotelyCachedEntity, appliedRange, debridLink, outputStream)
                result = StreamResult.OK
            }
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

    private fun initializeDownloadTracking(
        debridLink: CachedFile,
        range: Range?,
        remotelyCachedEntity: RemotelyCachedEntity,
        httpRequestInfo: HttpRequestInfo = HttpRequestInfo()
    ): String? {
        return if (debridavConfigProperties.enableStreamingDownloadTracking) {
            val trackingId = "${remotelyCachedEntity.id}-${System.currentTimeMillis()}"
            val requestedSize = if (range != null) (range.finish - range.start + 1) else debridLink.size!!
            val trackingContext = DownloadTrackingContext(
                filePath = debridLink.path ?: "unknown",
                fileName = remotelyCachedEntity.name ?: "unknown",
                requestedRange = range,
                requestedSize = requestedSize,
                httpHeaders = httpRequestInfo.headers,
                sourceIpAddress = httpRequestInfo.sourceIpAddress
            )
            activeDownloads[trackingId] = trackingContext
            trackingId
        } else {
            null
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private suspend fun handleLinkNotFound(
        debridLink: CachedFile,
        remotelyCachedEntity: RemotelyCachedEntity,
        appliedRange: Range,
        outputStream: OutputStream
    ): StreamResult {
        logger.info("Link not found, attempting immediate retry for ${debridLink.path}")
        return try {
            val freshLink = debridLinkService.getCachedFile(remotelyCachedEntity)
            if (freshLink != null) {
                streamBytes(remotelyCachedEntity, appliedRange, freshLink, outputStream)
                StreamResult.OK
            } else {
                StreamResult.DEAD_LINK
            }
        } catch (retryException: RuntimeException) {
            logger.warn("Immediate retry failed for ${debridLink.path}: ${retryException.message}")
            StreamResult.DEAD_LINK
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private suspend fun handleEOFException(
        debridLink: CachedFile,
        remotelyCachedEntity: RemotelyCachedEntity,
        appliedRange: Range,
        outputStream: OutputStream
    ): StreamResult {
        logger.info("EOF encountered (likely expired content), attempting immediate retry for ${debridLink.path}")
        return try {
            val freshLink = debridLinkService.getCachedFile(remotelyCachedEntity)
            if (freshLink != null) {
                streamBytes(remotelyCachedEntity, appliedRange, freshLink, outputStream)
                StreamResult.OK
            } else {
                StreamResult.DEAD_LINK
            }
        } catch (retryException: RuntimeException) {
            logger.warn("Immediate retry failed for ${debridLink.path}: ${retryException.message}")
            StreamResult.DEAD_LINK
        }
    }

    private fun completeDownloadTracking(trackingId: String, result: StreamResult) {
        activeDownloads[trackingId]?.let { context ->
            context.downloadEndTime = Instant.now()
            context.completionStatus = when (result) {
                StreamResult.OK -> "completed"
                StreamResult.DEAD_LINK -> "dead_link"
                StreamResult.IO_ERROR -> "io_error"
                StreamResult.PROVIDER_ERROR -> "provider_error"
                StreamResult.CLIENT_ERROR -> "client_error"
                StreamResult.UNKNOWN_ERROR -> "unknown_error"
                else -> "unknown"
            }

            val duration = Duration.between(context.downloadStartTime, context.downloadEndTime)
            logger.info(
                "DOWNLOAD_COMPLETED: file={}, range={}-{}, requested_size={} ({}), " +
                        "downloaded={} ({}), status={}, duration={}ms",
                context.fileName, context.requestedRange?.start, context.requestedRange?.finish,
                FileUtils.byteCountToDisplaySize(context.requestedSize), context.requestedSize,
                FileUtils.byteCountToDisplaySize(context.bytesDownloaded.get()), context.bytesDownloaded.get(),
                context.completionStatus, duration.toMillis()
            )

            // Add completed context to historical storage for actuator endpoint access
            completedDownloads.offer(context)

            // Keep only the last MAX_COMPLETED_DOWNLOADS_HISTORY completed downloads to prevent memory issues
            while (completedDownloads.size > MAX_COMPLETED_DOWNLOADS_HISTORY) {
                completedDownloads.poll()
            }

            activeDownloads.remove(trackingId)
        }
    }


    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, debridLink: CachedFile, outputStream: OutputStream
    ) = coroutineScope {
        launch {
            val streamingPlan = streamPlanningService.generatePlan(
                fileChunkCachingService.getAllCachedChunksForEntity(remotelyCachedEntity),
                LongRange(range.start, range.finish),
                debridLink
            )
            val sources = getSources(streamingPlan)
            val byteArrays = getByteArrays(sources)
            sendContent(byteArrays, outputStream, remotelyCachedEntity)
        }
    }

    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.file)
        this.remove(ctx)
    }

    fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        inputGauge.remove(ctx.provider.toString(), ctx.file)
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
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun ProducerScope<ByteArrayContext>.sendBytesFromHttpStreamWithKtor(
        source: StreamPlanningService.StreamSource.Remote
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val started = Instant.now()
        debridClient.prepareStreamUrl(source.cachedFile, range).execute { response ->
            response.body<ByteReadChannel>().toInputStream().use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream), source.cachedFile.provider!!, source.cachedFile.path!!
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
                } catch (e: EOFException) {
                    throw e
                } catch (e: RuntimeException) {
                    logger.error("An error occurred during reading from stream", e)
                    throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                } finally {
                    response.cancel()
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }

    /*@OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    private fun ProducerScope<ByteArrayContext>.sendBytesFromHttpStreamWithApache(
        source: StreamPlanningService.StreamSource.Remote
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val httpStreamingParams: StreamHttpParams = debridClient.getStreamParams(source.cachedFile, range)
        val request = generateRequestFromSource(source, httpStreamingParams)

        val started = Instant.now()
        HttpClients.createDefault().let { httpClient ->
            httpClient.executeOpen(null, request, null).entity.content.let { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream),
                    source.cachedFile.provider!!,
                    source.cachedFile.path!!
                )
                activeInputStreams.add(streamingContext)
                try {
                    runBlocking(Dispatchers.IO) {
                        try {
                            pipeHttpInputStreamToOutputChannel(
                                streamingContext, byteRangeInfo, source, started
                            )
                        } finally {
                            httpClient.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("An error occurred during reading from stream", e)
                    throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                } finally {
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }*/

    /*private fun generateRequestFromSource(
        source: StreamPlanningService.StreamSource.Remote, httpStreamingParams: StreamHttpParams
    ): HttpGet {
        val request = HttpGet(source.cachedFile.link)
        request.config = RequestConfig.custom()
            .setConnectionRequestTimeout(
                Timeout.ofMilliseconds(httpStreamingParams.timeouts.connectTimeoutMillis)
            )
            .setResponseTimeout(
                Timeout.ofMilliseconds(httpStreamingParams.timeouts.requestTimeoutMillis)
            )
            .build()
        httpStreamingParams.headers.forEach { (key, value) -> request.addHeader(key, value) }
        return request
    }*/

    private suspend fun ProducerScope<ByteArrayContext>.pipeHttpInputStreamToOutputChannel(
        streamingContext: InputStreamingContext,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo?,
        source: StreamPlanningService.StreamSource.Remote,
        started: Instant
    ) {
        var hasReadFirstByte = false;
        var timeToFirstByte: Double
        var remaining = byteRangeInfo!!.length()
        var firstByte = source.range.start
        var readBytes = 0L
        
        // Find tracking context for this download if enabled
        val trackingContext = if (debridavConfigProperties.enableStreamingDownloadTracking) {
            activeDownloads.values.find { it.filePath == source.cachedFile.path }
        } else {
            null
        }
        
        while (remaining > 0) {
            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()

            val bytes = streamingContext.inputStream.readNBytes(size.toInt())
            readBytes += bytes.size
            
            // Update download tracking if enabled
            trackingContext?.bytesDownloaded?.addAndGet(bytes.size.toLong())
            
            if (!hasReadFirstByte) {
                hasReadFirstByte = true
                timeToFirstByte = Duration.between(started, Instant.now()).toMillis().toDouble()
                timeToFirstByteHistogram.labelValues(source.cachedFile.provider.toString()).observe(timeToFirstByte)
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
        remotelyCachedEntity: RemotelyCachedEntity
    ) {
        val shouldBufferInMemory = debridavConfigProperties.enableInMemoryBuffering
        val shouldCacheToDatabase = debridavConfigProperties.enableChunkCaching
        
        // Only initialize cache variables if both buffering and caching are enabled
        // AND byte range request chunking is not disabled (to prevent wasted caching)
        var bytesToCache = if (shouldBufferInMemory && shouldCacheToDatabase && 
                              !debridavConfigProperties.disableByteRangeRequestChunking) 
            mutableListOf<BytesToCache>() else null
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
            
            // Only save to database if we have bytes to cache AND byte range request chunking is not disabled
            if (bytesToCache != null && bytesToCache.isNotEmpty() && 
                !debridavConfigProperties.disableByteRangeRequestChunking) {
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

    /**
     * Returns a copy of completed download tracking contexts for actuator endpoint access.
     * This method is package-private for access by the actuator endpoint.
     */
    fun getCompletedDownloads(): List<DownloadTrackingContext> {
        return completedDownloads.toList()
    }

    /**
     * Gets cached data for rclone/arrs requests, or null if not cached or not a rclone/arrs request.
     * Returns normalized cached data for the file (always starts from byte 0, limited size).
     *
     * SAFETY: This method has triple protection to ensure cached data is NEVER served to non-rclone/arrs clients:
     * 1. Cache must be enabled
     * 2. Data limiting must be enabled
     * 3. Request must match rclone/arrs patterns (user agent or hostname)
     */
    private fun getCachedRcloneArrsData(debridLink: CachedFile, range: Range, httpRequestInfo: HttpRequestInfo): ByteArray? {
        if (!debridavConfigProperties.rcloneArrsCacheEnabled ||
            !debridavConfigProperties.enableRcloneArrsDataLimiting ||
            !debridavConfigProperties.shouldLimitDataForRcloneArrs(httpRequestInfo)) {
            return null
        }

        val cacheKey = debridLink.path // Just use filepath as cache key
        return rcloneArrsCache.getIfPresent(cacheKey)
    }

    /**
     * Fetches data for rclone/arrs requests and caches it.
     * Always fetches the normalized range (starting from 0, limited size) and caches it for the filepath.
     */
    private suspend fun fetchAndCacheRcloneArrsData(
        debridLink: CachedFile,
        range: Range,
        remotelyCachedEntity: RemotelyCachedEntity,
        outputStream: OutputStream
    ): ByteArray? {
        return try {
            // Always fetch the normalized range (0 to maxBytes-1) for caching
            val maxBytes = debridavConfigProperties.rcloneArrsMaxDataKb * 1024L
            val normalizedRange = Range(0, maxBytes - 1)

            val byteArrayOutputStream = ByteArrayOutputStream()

            // Create a tee output stream that writes to both the original output and our buffer
            val teeOutputStream = TeeOutputStream(outputStream, byteArrayOutputStream)

            // Stream the normalized range to the tee output stream
            streamBytes(remotelyCachedEntity, normalizedRange, debridLink, teeOutputStream)

            // Get the captured data
            val data = byteArrayOutputStream.toByteArray()

            // Cache the normalized data for future requests using just filepath as key
            cacheRcloneArrsData(debridLink, data)

            logger.info("RCLONE_ARRS_CACHE_MISS_FETCHED: file={}, normalized_range=0-{}, size={} bytes",
                remotelyCachedEntity.name ?: "unknown",
                maxBytes - 1, data.size)

            data
        } catch (e: Exception) {
            logger.error("Failed to fetch and cache rclone/arrs data for ${debridLink.path}", e)
            null
        }
    }

    /**
     * Caches normalized data for rclone/arrs requests.
     */
    private fun cacheRcloneArrsData(debridLink: CachedFile, data: ByteArray) {
        if (!debridavConfigProperties.rcloneArrsCacheEnabled ||
            !debridavConfigProperties.enableRcloneArrsDataLimiting) {
            return
        }

        val cacheKey = debridLink.path // Just use filepath as cache key
        rcloneArrsCache.put(cacheKey, data)

        logger.debug("RCLONE_ARRS_CACHE_STORE: file={}, size={} bytes",
            debridLink.path, data.size)
    }
}
