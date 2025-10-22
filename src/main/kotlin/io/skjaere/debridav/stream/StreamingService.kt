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
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import java.util.concurrent.ExecutionException
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlin.math.min


private const val DEFAULT_BUFFER_SIZE = 65536L //64kb
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds
private const val BYTE_CHANNEL_CAPACITY = 2000
private const val MAX_COMPLETED_DOWNLOADS_HISTORY = 1000
private const val CACHE_DOWNLOAD_TIMEOUT_MS = 60000L // 60 seconds timeout for cache downloads

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
    // Rclone/Arrs byte duplication metrics (grouped with bytes data)
    var actualBytesSent: Long? = null,  // Total bytes actually sent (after duplication)
    var cachedChunkSize: Long? = null,  // Size of the cached chunk used
    var wasCacheHit: Boolean = false,   // Whether this request used cached data
    var usedByteDuplication: Boolean = false,  // Whether byte duplication was used
    // Completion metadata
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
    
    // Map to track in-flight downloads to prevent duplicate fetches
    // Key is cache key (filepath-start-finish), Value is Mutex for that specific download
    private val inFlightDownloads = ConcurrentHashMap<String, Mutex>()
    
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
        val limitedRangeResult = if (range != null) {
            debridavConfigProperties.getLimitedRangeForRcloneArrs(originalRange, httpRequestInfo)
        } else {
            DebridavConfigurationProperties.LimitedRangeResult(originalRange, false)
        }
        val appliedRange = limitedRangeResult.range

        // Log if byte duplication should be used for rclone/arrs
        if (limitedRangeResult.shouldUseByteDuplication && debridavConfigProperties.enableRcloneArrsDataLimiting) {
            logger.info("RCLONE_ARRS_BYTE_DUPLICATION: file={}, requested_range={}-{}, max_kb={}",
                remotelyCachedEntity.name ?: "unknown",
                appliedRange.start, appliedRange.finish,
                debridavConfigProperties.rcloneArrsMaxDataKb)
        }

        // Log if range was limited for rclone/arrs (old behavior)
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

            val trackingId = initializeDownloadTracking(debridLink, range, remotelyCachedEntity, httpRequestInfo)
            
            // If byte duplication is needed, use duplicateBytesToRange instead of direct write
            val result = if (limitedRangeResult.shouldUseByteDuplication) {
                // Mark as byte duplication in tracking
                trackingId?.let { id ->
                    activeDownloads[id]?.apply {
                        usedByteDuplication = true
                        cachedChunkSize = cachedData.size.toLong()
                        wasCacheHit = true
                    }
                }
                // Determine the chunking strategy that was used to create this cached data
                // This is independent of whether data came from cache or fresh download
                val cachedDownloadRange = if (appliedRange.start == 0L) {
                    Range(0L, debridavConfigProperties.rcloneArrsMaxDataKb * 1024L - 1)
                } else {
                    val maxBytes = debridavConfigProperties.rcloneArrsMaxDataKb * 1024L
                    val downloadStart = maxOf(0L, appliedRange.finish - maxBytes + 1)
                    Range(downloadStart, appliedRange.finish)
                }
                duplicateBytesToRange(cachedData, appliedRange, cachedDownloadRange, outputStream, remotelyCachedEntity, trackingId)
            } else {
                // Write cached data directly to output stream (old behavior for non-duplication cases)
                withContext(Dispatchers.IO) {
                    outputStream.write(cachedData)
                    outputStream.flush()
                }
                StreamResult.OK
            }

            trackingId?.let { id -> completeDownloadTracking(id, result) }
            logger.info("done streaming ${debridLink.path} from cache: $result")
            return@coroutineScope result
        }

        val trackingId = initializeDownloadTracking(debridLink, range, remotelyCachedEntity, httpRequestInfo)
        
        var result: StreamResult = StreamResult.OK
        try {
            // Handle rclone/arrs requests with byte duplication if needed
            if (limitedRangeResult.shouldUseByteDuplication) {
                // Mark as byte duplication in tracking
                trackingId?.let { id ->
                    activeDownloads[id]?.usedByteDuplication = true
                }
                result = streamWithByteDuplication(debridLink, appliedRange, remotelyCachedEntity, outputStream, httpRequestInfo, trackingId)
            }
            // For rclone/arrs requests with caching enabled, try to serve from cache first
            // This is only executed when: cache enabled AND data limiting enabled AND request matches rclone/arrs patterns
            // This ensures cached data is NEVER served to non-rclone/arrs clients
            else if (debridavConfigProperties.rcloneArrsCacheEnabled &&
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

    /**
     * Streams content for rclone/arrs requests using byte duplication to fulfill the requested range
     * while only downloading the configured maximum amount from external sources.
     * Uses thread-safe cache loading to ensure only one download happens per range.
     */
    private suspend fun streamWithByteDuplication(
        debridLink: CachedFile,
        range: Range,
        remotelyCachedEntity: RemotelyCachedEntity,
        outputStream: OutputStream,
        httpRequestInfo: HttpRequestInfo,
        trackingId: String?
    ): StreamResult = coroutineScope {
        val maxBytes = debridavConfigProperties.rcloneArrsMaxDataKb * 1024L
        val requestedSize = range.finish - range.start + 1
        
        // Create a limited range for the actual download
        // If start is 0, download from start; otherwise, download ending at range end
        val downloadRange = if (range.start == 0L) {
            // Start from beginning: download from 0 to maxBytes-1
            Range(0L, maxBytes - 1)
        } else {
            // Start from end: download ending at range.finish
            val downloadStart = maxOf(0L, range.finish - maxBytes + 1)
            Range(downloadStart, range.finish)
        }
        
        logger.info("RCLONE_ARRS_DUPLICATION_START: file={}, requested_range={}-{}, download_chunk={}-{}, chunk_strategy={}, max_kb={}",
            remotelyCachedEntity.name ?: "unknown",
            range.start, range.finish,
            downloadRange.start, downloadRange.finish,
            if (range.start == 0L) "start-from-beginning" else "end-at-request-end",
            debridavConfigProperties.rcloneArrsMaxDataKb)
        
        // Use thread-safe cache loading - only one thread will download, others will wait
        // Check for cache hit using the same cache key strategy as the actual cache operations
        val cacheKey = debridavConfigProperties.generateCacheKey(debridLink.path, downloadRange)
        val wasCacheHit = cacheKey?.let { rcloneArrsCache.getIfPresent(it) != null } ?: false
        val downloadedData = getOrLoadCachedRcloneArrsData(
            debridLink, 
            downloadRange, 
            remotelyCachedEntity, 
            httpRequestInfo
        )
        
        if (downloadedData != null) {
            // Update tracking context with cache metrics
            trackingId?.let { id ->
                activeDownloads[id]?.apply {
                    cachedChunkSize = downloadedData.size.toLong()
                    this.wasCacheHit = wasCacheHit
                    // Update bytesDownloaded with actual bytes downloaded from external sources
                    if (!wasCacheHit) {
                        bytesDownloaded.addAndGet(downloadedData.size.toLong())
                    }
                }
            }
            
            return@coroutineScope duplicateBytesToRange(downloadedData, range, downloadRange, outputStream, remotelyCachedEntity, trackingId)
        }
        
        logger.error("RCLONE_ARRS_DUPLICATION_FAILED: file={}, range={}-{}",
            remotelyCachedEntity.name ?: "unknown",
            range.start, range.finish)
        
        StreamResult.IO_ERROR
    }
    
    /**
     * Duplicates the provided bytes to fulfill the requested range, streaming dynamically
     * without preallocating the entire content in memory.
     * Mimics normal streaming behavior by writing in 64KB chunks without explicit flushes.
     */
    private suspend fun duplicateBytesToRange(
        sourceBytes: ByteArray,
        targetRange: Range,
        downloadRange: Range,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        trackingId: String?
    ): StreamResult = coroutineScope {
        val sourceSize = sourceBytes.size
        val targetSize = targetRange.finish - targetRange.start + 1
        val chunkSize = DEFAULT_BUFFER_SIZE.toInt() // Use same 64KB chunk size as normal streaming
        
        // Calculate the offset within source bytes where the target range starts
        val sourceOffset = targetRange.start - downloadRange.start
        
        // Handle different scenarios for byte duplication
        val actualSourceOffset = when {
            sourceOffset < 0 -> {
                // Target range starts before download range - start from beginning of source bytes
                logger.info("RCLONE_ARRS_DUPLICATION_PREPEND: file={}, target_start={}, download_start={}, using_prepend_strategy",
                    remotelyCachedEntity.name ?: "unknown", targetRange.start, downloadRange.start)
                0
            }
            sourceOffset >= sourceSize -> {
                // Target range starts after download range - this shouldn't happen with our chunking strategy
                logger.error("RCLONE_ARRS_DUPLICATION_RANGE_ERROR: file={}, target_range={}-{}, download_range={}-{}, source_offset={}, source_size={}",
                    remotelyCachedEntity.name ?: "unknown",
                    targetRange.start, targetRange.finish, downloadRange.start, downloadRange.finish, sourceOffset, sourceSize)
                return@coroutineScope StreamResult.IO_ERROR
            }
            else -> {
                // Target range starts within download range - use calculated offset
                sourceOffset.toInt()
            }
        }
        
        logger.info("RCLONE_ARRS_DUPLICATION_PROCESSING: file={}, source_size={}, target_size={}, download_range={}-{}, source_offset={}",
            remotelyCachedEntity.name ?: "unknown",
            sourceSize, targetSize, downloadRange.start, downloadRange.finish, sourceOffset)
        
        try {
            var bytesSent = 0L
            var sourcePosition = actualSourceOffset // Start from the calculated offset
            
            while (bytesSent < targetSize) {
                // Calculate how many bytes to send in this iteration (up to 64KB chunk size)
                val remainingBytes = targetSize - bytesSent
                val remainingInSource = sourceSize - sourcePosition
                val bytesToSend = Math.min(Math.min(remainingBytes, chunkSize.toLong()), remainingInSource.toLong()).toInt()
                
                if (bytesToSend <= 0) {
                    // Reset position to the correct offset for duplication
                    sourcePosition = actualSourceOffset
                    continue
                }
                
                // Send the bytes - matches normal streaming behavior (write without explicit flush)
                try {
                    withContext(Dispatchers.IO) {
                        outputStream.write(sourceBytes, sourcePosition, bytesToSend)
                    }
                } catch (e: java.io.IOException) {
                    // Check if it's a client disconnect (connection reset)
                    if (e.message?.contains("Connection reset") == true || 
                        e.message?.contains("Broken pipe") == true ||
                        e.message?.contains("ClientAbortException") == true) {
                        logger.info("RCLONE_ARRS_DUPLICATION_CLIENT_DISCONNECTED: file={}, bytes_sent={}",
                            remotelyCachedEntity.name ?: "unknown", bytesSent)
                        
                        // Update tracking context with actual bytes sent before returning
                        trackingId?.let { id ->
                            activeDownloads[id]?.actualBytesSent = bytesSent
                        }
                        
                        return@coroutineScope StreamResult.OK // Client got what it needed or cancelled
                    }
                    throw e
                }
                
                bytesSent += bytesToSend
                sourcePosition += bytesToSend
                
                // If we've reached the end of source bytes, reset position for duplication
                if (sourcePosition >= sourceSize) {
                    sourcePosition = actualSourceOffset
                }
            }
            
            // Final flush to ensure all data is sent (matches non-duplication cache behavior)
            withContext(Dispatchers.IO) {
                outputStream.flush()
            }
            
            logger.info("RCLONE_ARRS_DUPLICATION_COMPLETED: file={}, bytes_sent={}",
                remotelyCachedEntity.name ?: "unknown", bytesSent)
            
            // Update tracking context with actual bytes sent
            trackingId?.let { id ->
                activeDownloads[id]?.actualBytesSent = bytesSent
            }
            
            StreamResult.OK
        } catch (e: java.io.IOException) {
            logger.error("RCLONE_ARRS_DUPLICATION_ERROR: file={}, error={}",
                remotelyCachedEntity.name ?: "unknown", e.toString())
            StreamResult.IO_ERROR
        } catch (e: Exception) {
            logger.error("RCLONE_ARRS_DUPLICATION_ERROR: file={}, error={}",
                remotelyCachedEntity.name ?: "unknown", e.toString())
            StreamResult.IO_ERROR
        }
    }

    /**
     * Thread-safe method to get cached data or load it if not present.
     * Ensures only one download happens per cache key, with other threads waiting for the result.
     * Includes timeout protection to prevent indefinite waits on stalled downloads.
     * 
     * Note: Cache key strategy is configurable - can be file-path only or include range information
     * based on the rcloneArrsCacheKeyStrategy configuration.
     */
    private suspend fun getOrLoadCachedRcloneArrsData(
        debridLink: CachedFile,
        range: Range,
        remotelyCachedEntity: RemotelyCachedEntity,
        httpRequestInfo: HttpRequestInfo
    ): ByteArray? {
        if (!debridavConfigProperties.rcloneArrsCacheEnabled ||
            !debridavConfigProperties.enableRcloneArrsDataLimiting ||
            !debridavConfigProperties.shouldLimitDataForRcloneArrs(httpRequestInfo)) {
            // Caching disabled, download directly without coordination
            return fetchRcloneArrsData(debridLink, range, remotelyCachedEntity)
        }
        
        // Generate cache key based on configured strategy
        val cacheKey = debridavConfigProperties.generateCacheKey(debridLink.path, range) ?: return null
        
        // Fast path: check if already in cache
        val cachedData = rcloneArrsCache.getIfPresent(cacheKey)
        if (cachedData != null) {
            logger.info("RCLONE_ARRS_DUPLICATION_CACHE_HIT: file={}, requested_range={}-{}, cached_size={} bytes (serving cached chunk for metadata)",
                remotelyCachedEntity.name ?: "unknown",
                range.start, range.finish,
                cachedData.size)
            return cachedData
        }
        
        // Get or create a mutex for this specific cache key
        val mutex = inFlightDownloads.computeIfAbsent(cacheKey) { Mutex() }
        
        try {
            // Lock the mutex with timeout to prevent indefinite waits on stalled downloads
            return withTimeout(CACHE_DOWNLOAD_TIMEOUT_MS) {
                mutex.withLock {
                    // Double-check: another thread might have loaded it while we were waiting
                    val cachedDataAfterWait = rcloneArrsCache.getIfPresent(cacheKey)
                    if (cachedDataAfterWait != null) {
                        logger.info("RCLONE_ARRS_DUPLICATION_CACHE_HIT_AFTER_WAIT: file={}, requested_range={}-{}, cached_size={} bytes (another thread cached it)",
                            remotelyCachedEntity.name ?: "unknown",
                            range.start, range.finish,
                            cachedDataAfterWait.size)
                        return@withTimeout cachedDataAfterWait
                    }
                    
                    // Still not in cache, we need to download
                    // Note: We download the requested range, but it will be cached and reused for any range request
                    logger.info("RCLONE_ARRS_DUPLICATION_DOWNLOADING: file={}, requested_range={}-{}, thread={}",
                        remotelyCachedEntity.name ?: "unknown",
                        range.start, range.finish,
                        Thread.currentThread().name)
                    
                    val downloadedData = fetchRcloneArrsData(debridLink, range, remotelyCachedEntity)
                    
                    if (downloadedData != null) {
                        logger.info("RCLONE_ARRS_DUPLICATION_DOWNLOADED: file={}, requested_range={}-{}, downloaded_size={} bytes (cached for all ranges)",
                            remotelyCachedEntity.name ?: "unknown",
                            range.start, range.finish,
                            downloadedData.size)
                        
                        // Cache the downloaded data - will be reused for ANY range request on this file
                        rcloneArrsCache.put(cacheKey, downloadedData)
                        
                        logger.debug("RCLONE_ARRS_FILE_CACHE_STORE: file={}, size={} bytes (cached chunk for metadata probing)",
                            debridLink.path, downloadedData.size)
                    }
                    
                    return@withTimeout downloadedData
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("RCLONE_ARRS_DUPLICATION_TIMEOUT: file={}, requested_range={}-{}, timeout={}ms, thread={}",
                remotelyCachedEntity.name ?: "unknown",
                range.start, range.finish,
                CACHE_DOWNLOAD_TIMEOUT_MS,
                Thread.currentThread().name)
            
            // On timeout, try to download independently without coordination
            // This allows the request to continue even if another thread's download stalled
            logger.info("RCLONE_ARRS_DUPLICATION_FALLBACK_DOWNLOAD: file={}, requested_range={}-{}, thread={}",
                remotelyCachedEntity.name ?: "unknown",
                range.start, range.finish,
                Thread.currentThread().name)
            
            return fetchRcloneArrsData(debridLink, range, remotelyCachedEntity)
        } finally {
            // Clean up the mutex from the map after download completes
            // Only remove if it's the same mutex instance (to avoid race with new requests)
            inFlightDownloads.remove(cacheKey, mutex)
        }
    }
    
    /**
     * Fetches data for rclone/arrs requests without caching
     */
    private suspend fun fetchRcloneArrsData(
        debridLink: CachedFile,
        range: Range,
        remotelyCachedEntity: RemotelyCachedEntity
    ): ByteArray? = coroutineScope {
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()

            // Stream the requested range to capture the bytes
            streamBytes(remotelyCachedEntity, range, debridLink, byteArrayOutputStream)
            
            val data = byteArrayOutputStream.toByteArray()

            if (data.isNotEmpty()) {
                return@coroutineScope data
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch rclone/arrs data for ${debridLink.path}", e)
        }
        
        null
    }
    
    fun getCompletedDownloads(): List<DownloadTrackingContext> = completedDownloads.toList()

    fun getCachedRcloneArrsData(debridLink: CachedFile, range: Range, httpRequestInfo: HttpRequestInfo): ByteArray? {
        if (!debridavConfigProperties.rcloneArrsCacheEnabled ||
            !debridavConfigProperties.enableRcloneArrsDataLimiting ||
            !debridavConfigProperties.shouldLimitDataForRcloneArrs(httpRequestInfo)) {
            return null
        }
        // Generate cache key based on configured strategy
        val cacheKey = debridavConfigProperties.generateCacheKey(debridLink.path, range) ?: return null
        return rcloneArrsCache.getIfPresent(cacheKey)
    }

    fun initializeDownloadTracking(debridLink: CachedFile, range: Range?, remotelyCachedEntity: RemotelyCachedEntity, httpRequestInfo: HttpRequestInfo): String? {
        if (!debridavConfigProperties.enableStreamingDownloadTracking) return null
        
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
        
        completedDownloads.add(context)
        while (completedDownloads.size > MAX_COMPLETED_DOWNLOADS_HISTORY) {
            completedDownloads.poll()
        }
    }

    suspend fun fetchAndCacheRcloneArrsData(debridLink: CachedFile, range: Range, remotelyCachedEntity: RemotelyCachedEntity, outputStream: OutputStream): ByteArray? {
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val teeOutputStream = TeeOutputStream(outputStream, byteArrayOutputStream)
            
            streamBytes(remotelyCachedEntity, range, debridLink, teeOutputStream)
            
            val data = byteArrayOutputStream.toByteArray()
            if (data.isNotEmpty()) {
                // Cache the data for future use using configured strategy
                val cacheKey = debridavConfigProperties.generateCacheKey(debridLink.path, range)
                if (cacheKey != null) {
                    rcloneArrsCache.put(cacheKey, data)
                }
                return data
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch and cache rclone/arrs data for ${debridLink.path}", e)
        }
        
        return null
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
    @Suppress("TooGenericExceptionCaught")
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
}
