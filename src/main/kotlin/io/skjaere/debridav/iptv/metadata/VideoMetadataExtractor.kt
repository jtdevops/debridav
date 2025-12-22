package io.skjaere.debridav.iptv.metadata

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.concurrent.TimeUnit
import jakarta.annotation.PostConstruct

@Service
class VideoMetadataExtractor(
    private val httpClient: HttpClient,
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(VideoMetadataExtractor::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private var ffprobeAvailable: Boolean = false
    private var ffprobeVersion: String? = null
    
    companion object {
        private const val FIRST_CHUNK_SIZE: Long = 2 * 1024 * 1024L // 2MB
        private const val LAST_CHUNK_SIZE: Long = 2 * 1024 * 1024L // 2MB
    }
    
    @PostConstruct
    fun checkFfprobeAvailability() {
        if (!iptvConfigurationProperties.metadataEnhancementEnabled) {
            logger.info("Metadata enhancement is disabled in configuration")
            return
        }
        
        try {
            val process = ProcessBuilder(
                iptvConfigurationProperties.ffprobePath,
                "-version"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            
            if (completed && process.exitValue() == 0) {
                // Extract version from output (first line typically contains version info)
                val versionLine = output.lines().firstOrNull { it.contains("ffprobe version", ignoreCase = true) }
                ffprobeVersion = versionLine?.substringBefore("\n")?.trim() ?: "unknown"
                ffprobeAvailable = true
                logger.info("FFprobe is available: $ffprobeVersion")
            } else {
                ffprobeAvailable = false
                val exitCode = if (completed) process.exitValue() else -1
                logger.warn("FFprobe check failed with exit code $exitCode. Metadata enhancement (resolution/codec extraction) will be skipped. File size extraction will still work.")
            }
        } catch (e: Exception) {
            ffprobeAvailable = false
            logger.warn("FFprobe is not available on this system (path: '${iptvConfigurationProperties.ffprobePath}'). Metadata enhancement (resolution/codec extraction) will be skipped. File size extraction will still work. Error: ${e.message}")
        }
    }
    
    /**
     * Extracts video metadata (resolution, codec, file size) from a media file URL.
     * Returns null if extraction fails or FFprobe is not available.
     */
    suspend fun extractVideoMetadata(url: String): VideoMetadata? {
        if (!iptvConfigurationProperties.metadataEnhancementEnabled) {
            return null
        }
        
        if (!ffprobeAvailable) {
            logger.debug("Skipping video metadata extraction - FFprobe not available")
            return null
        }
        
        return try {
            // Try first 2MB
            val firstChunk = downloadChunk(url, 0, FIRST_CHUNK_SIZE)
            if (firstChunk != null) {
                val metadata = probeWithFfprobe(firstChunk)
                if (metadata != null && metadata.hasVideoInfo()) {
                    logger.debug("Successfully extracted video metadata from first chunk")
                    return metadata
                }
            }
            
            // If first chunk didn't work, try last 2MB (for MP4 with moov at end)
            logger.debug("First chunk did not contain video metadata, trying last chunk")
            val lastChunk = downloadLastChunk(url)
            if (lastChunk != null) {
                val metadata = probeWithFfprobe(lastChunk)
                if (metadata != null && metadata.hasVideoInfo()) {
                    logger.debug("Successfully extracted video metadata from last chunk")
                    return metadata
                }
            }
            
            logger.debug("Could not extract video metadata from either chunk")
            null
        } catch (e: Exception) {
            logger.warn("Failed to extract video metadata from URL: ${e.message}", e)
            null
        }
    }
    
    /**
     * Downloads a chunk of the file using HTTP Range request.
     */
    private suspend fun downloadChunk(url: String, start: Long, size: Long): ByteArray? {
        return try {
            val response = httpClient.get(url) {
                headers {
                    append(HttpHeaders.Range, "bytes=$start-${start + size - 1}")
                }
                timeout {
                    requestTimeoutMillis = 10000 // 10 second timeout
                    connectTimeoutMillis = 5000
                }
            }
            
            if (response.status.isSuccess()) {
                response.body<ByteArray>()
            } else {
                logger.debug("HTTP request failed with status ${response.status.value}")
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to download chunk: ${e.message}")
            null
        }
    }
    
    /**
     * Downloads the last chunk of the file (for MP4 with moov at end).
     */
    private suspend fun downloadLastChunk(url: String): ByteArray? {
        return try {
            // First, get file size using HEAD request
            val headResponse = httpClient.head(url) {
                timeout {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 2000
                }
            }
            
            val contentLength = headResponse.headers["Content-Length"]?.toLongOrNull()
            if (contentLength == null || contentLength <= LAST_CHUNK_SIZE) {
                // File is too small or size unknown, skip
                return null
            }
            
            val start = contentLength - LAST_CHUNK_SIZE
            downloadChunk(url, start, LAST_CHUNK_SIZE)
        } catch (e: Exception) {
            logger.debug("Failed to download last chunk: ${e.message}")
            null
        }
    }
    
    /**
     * Probes video metadata using FFprobe with data from stdin.
     * Thread-safe: Each call creates a new process.
     */
    private suspend fun probeWithFfprobe(data: ByteArray): VideoMetadata? {
        if (!ffprobeAvailable) {
            return null
        }
        
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                process = ProcessBuilder(
                    iptvConfigurationProperties.ffprobePath,
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    "-"
                ).redirectErrorStream(true).start()
                
                // Write data to stdin
                process.outputStream.use { outputStream ->
                    outputStream.write(data)
                    outputStream.flush()
                }
                
                // Read output with timeout
                val output = withTimeout(iptvConfigurationProperties.ffprobeTimeout.toMillis()) {
                    process.inputStream.bufferedReader().readText()
                }
                
                // Wait for process to complete (with timeout)
                val completed = process.waitFor(iptvConfigurationProperties.ffprobeTimeout.toMillis(), TimeUnit.MILLISECONDS)
                
                if (!completed) {
                    logger.warn("FFprobe process timed out after ${iptvConfigurationProperties.ffprobeTimeout}")
                    process.destroyForcibly()
                    return@withContext null
                }
                
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    logger.debug("FFprobe exited with code $exitCode")
                    return@withContext null
                }
                
                // Parse JSON output
                val ffprobeOutput = json.decodeFromString<FfprobeOutput>(output)
                return@withContext extractVideoMetadata(ffprobeOutput)
            } catch (e: Exception) {
                logger.warn("FFprobe execution failed: ${e.message}", e)
                return@withContext null
            } finally {
                // Ensure process is cleaned up
                process?.let {
                    try {
                        if (it.isAlive) {
                            it.destroyForcibly()
                            it.waitFor(1, TimeUnit.SECONDS)
                        }
                    } catch (e: Exception) {
                        // Ignore cleanup errors
                    }
                }
            }
        }
    }
    
    /**
     * Extracts video metadata from FFprobe JSON output.
     */
    private fun extractVideoMetadata(ffprobeOutput: FfprobeOutput): VideoMetadata? {
        // Find video stream
        val videoStream = ffprobeOutput.streams?.firstOrNull { it.codec_type == "video" }
        
        if (videoStream == null) {
            logger.debug("No video stream found in FFprobe output")
            return null
        }
        
        val width = videoStream.width
        val height = videoStream.height
        val codecName = videoStream.codec_name
        
        // Extract file size from format tags
        val fileSize = ffprobeOutput.format?.tags?.get("NUMBER_OF_BYTES")
            ?.toLongOrNull()
            ?: ffprobeOutput.format?.tags?.get("NUMBER_OF_BYTES-eng")?.toLongOrNull()
            ?: ffprobeOutput.format?.tags?.get("NUMBER_OF_BYTES-ENG")?.toLongOrNull()
            ?: ffprobeOutput.format?.size?.toLongOrNull()
        
        return VideoMetadata(
            width = width,
            height = height,
            codecName = codecName,
            fileSize = fileSize
        )
    }
    
    /**
     * Data class for video metadata extracted from media files.
     */
    data class VideoMetadata(
        val width: Int?,
        val height: Int?,
        val codecName: String?,
        val fileSize: Long?
    ) {
        fun hasVideoInfo(): Boolean {
            return width != null && height != null
        }
    }
    
    /**
     * FFprobe JSON output structure.
     */
    @Serializable
    private data class FfprobeOutput(
        val streams: List<FfprobeStream>? = null,
        val format: FfprobeFormat? = null
    )
    
    @Serializable
    private data class FfprobeStream(
        val codec_type: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val codec_name: String? = null
    )
    
    @Serializable
    private data class FfprobeFormat(
        val size: String? = null,
        val tags: Map<String, String>? = null
    )
}

