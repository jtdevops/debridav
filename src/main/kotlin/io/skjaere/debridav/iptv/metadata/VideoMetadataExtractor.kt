package io.skjaere.debridav.iptv.metadata

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.URI
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
     * Uses FFprobe to read directly from the URL instead of downloading chunks.
     * Falls back to HTTP-based file size extraction if FFprobe fails.
     * Always attempts to extract file size, even if other metadata extraction fails.
     * Returns null only if both FFprobe and HTTP file size extraction fail.
     */
    suspend fun extractVideoMetadata(url: String): VideoMetadata? {
        if (!iptvConfigurationProperties.metadataEnhancementEnabled) {
            return null
        }
        
        // Resolve redirects first (needed for both FFprobe and HTTP file size extraction)
        val redirectUrl = resolveRedirectUrl(url)
        val finalUrl = redirectUrl ?: url
        
        if (redirectUrl != null) {
            logger.debug("Using resolved redirect URL: originalUrl={}, redirectUrl={}", 
                url.take(100), redirectUrl.take(100))
        } else {
            logger.debug("No redirect found, using original URL: {}", url.take(100))
        }
        
        // Try FFprobe first if available
        var metadata: VideoMetadata? = null
        if (ffprobeAvailable) {
            try {
                metadata = probeUrlWithFfprobe(finalUrl)
                if (metadata != null && metadata.hasVideoInfo()) {
                    logger.debug("Successfully extracted video metadata from URL using FFprobe")
                    // If FFprobe succeeded but didn't get file size, try HTTP fallback
                    if (metadata.fileSize == null) {
                        val fileSize = extractFileSizeViaHttp(finalUrl)
                        if (fileSize != null) {
                            logger.debug("Extracted file size via HTTP fallback: $fileSize bytes")
                            metadata = metadata.copy(fileSize = fileSize)
                        }
                    }
                    return metadata
                }
            } catch (e: Exception) {
                logger.debug("FFprobe extraction failed: ${e.message}")
            }
        } else {
            logger.debug("FFprobe not available, will try HTTP-based file size extraction")
        }
        
        // FFprobe failed or not available - try to extract at least file size via HTTP
        val fileSize = extractFileSizeViaHttp(finalUrl)
        if (fileSize != null) {
            logger.debug("Extracted file size via HTTP: $fileSize bytes (video metadata extraction failed)")
            // Return metadata with only file size
            return VideoMetadata(
                width = null,
                height = null,
                codecName = null,
                fileSize = fileSize
            )
        }
        
        logger.debug("Could not extract video metadata or file size from URL")
        return null
    }
    
    /**
     * Extracts file size from a URL using HTTP HEAD or GET with Range header.
     * Tries HEAD first, then falls back to GET with Range: bytes=0-0.
     * Extracts file size from Content-Range or Content-Length headers.
     * Returns null if file size cannot be determined.
     */
    private suspend fun extractFileSizeViaHttp(url: String): Long? {
        return try {
            // Try HEAD request first (more efficient)
            try {
                val headResponse = httpClient.head(url) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
                
                if (headResponse.status.isSuccess()) {
                    val contentLength = headResponse.headers["Content-Length"]?.toLongOrNull()
                    if (contentLength != null && contentLength > 0) {
                        logger.debug("Extracted file size via HEAD request: $contentLength bytes")
                        return contentLength
                    }
                }
            } catch (e: Exception) {
                logger.debug("HEAD request failed: ${e.message}, falling back to GET")
            }
            
            // Fallback to GET with Range header
            val getResponse = httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    append(HttpHeaders.Range, "bytes=0-0")
                }
                timeout {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 2000
                }
            }
            
            if (getResponse.status.isSuccess()) {
                // Try to extract file size from Content-Range header first (e.g., "bytes 0-0/1882075726")
                val contentRange = getResponse.headers["Content-Range"]
                if (contentRange != null) {
                    val rangeRegex = Regex("bytes\\s+\\d+-\\d+/(\\d+)")
                    val matchResult = rangeRegex.find(contentRange)
                    val totalSize = matchResult?.groupValues?.get(1)?.toLongOrNull()
                    if (totalSize != null && totalSize > 0) {
                        logger.debug("Extracted file size via GET Content-Range: $totalSize bytes")
                        // Consume response body
                        try {
                            getResponse.body<ByteReadChannel>()
                        } catch (e: Exception) {
                            // Ignore errors when consuming response body
                        }
                        return totalSize
                    }
                }
                
                // Fallback to Content-Length header
                val contentLength = getResponse.headers["Content-Length"]?.toLongOrNull()
                // Consume response body
                try {
                    getResponse.body<ByteReadChannel>()
                } catch (e: Exception) {
                    // Ignore errors when consuming response body
                }
                if (contentLength != null && contentLength > 0) {
                    logger.debug("Extracted file size via GET Content-Length: $contentLength bytes")
                    return contentLength
                }
            }
            
            null
        } catch (e: Exception) {
            logger.debug("Failed to extract file size via HTTP: ${e.message}")
            null
        }
    }
    
    /**
     * Resolves redirects for a URL by following the complete redirect chain.
     * Uses GET with Range bytes=0-0 to check for redirects without downloading data.
     * Returns the final URL after following all redirects, or the original URL if no redirects.
     * 
     * @param maxRedirects Maximum number of redirects to follow (default: 10) to prevent infinite loops
     */
    private suspend fun resolveRedirectUrl(url: String, maxRedirects: Int = 10): String? {
        var currentUrl = url
        var redirectCount = 0
        
        while (redirectCount < maxRedirects) {
            try {
                val response = httpClient.get(currentUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0") // Minimal request to check for redirects
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
                
                // Consume response body to ensure proper cleanup
                try {
                    response.body<ByteReadChannel>()
                } catch (e: Exception) {
                    // Ignore errors when consuming response body
                }
                
                if (response.status.value in 300..399) {
                    val redirectLocation = response.headers["Location"]
                    if (redirectLocation != null) {
                        redirectCount++
                        
                        // Resolve redirect URL (handle relative redirects)
                        val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                            redirectLocation
                        } else {
                            // Relative redirect - construct absolute URL
                            val currentUri = URI(currentUrl)
                            currentUri.resolve(redirectLocation).toString()
                        }
                        
                        logger.debug("Following redirect $redirectCount: from={}, to={}", currentUrl.take(100), redirectUrl.take(100))
                        currentUrl = redirectUrl
                        continue // Follow the next redirect
                    } else {
                        logger.debug("Redirect response (status ${response.status.value}) but no Location header for URL: {}", currentUrl.take(100))
                        return null
                    }
                } else if (!response.status.isSuccess()) {
                    // Non-success status (like 551) - if we've followed redirects, return the final URL anyway
                    // so FFprobe can try it; otherwise return null to use original URL
                    if (redirectCount > 0) {
                        logger.debug("Final URL in redirect chain returned non-success status ${response.status.value}, but will use it for FFprobe: {}", currentUrl.take(100))
                        return currentUrl
                    } else {
                        logger.debug("URL returned non-success status ${response.status.value}, will try original URL with FFprobe: {}", currentUrl.take(100))
                        return null
                    }
                } else {
                    // Success status (200, 206, etc.) - this is the final URL
                    if (redirectCount > 0) {
                        logger.debug("Resolved redirect chain (${redirectCount} redirects): finalUrl={}", currentUrl.take(100))
                    }
                    return currentUrl
                }
            } catch (e: Exception) {
                logger.debug("Failed to resolve redirect URL at step $redirectCount: ${e.message}")
                return null
            }
        }
        
        // Too many redirects
        logger.warn("Maximum redirect limit ($maxRedirects) reached for URL: {}", url.take(100))
        return null
    }
    
    /**
     * Downloads a chunk of the file using HTTP Range request.
     * Handles redirects manually to preserve Range headers (HttpRedirect plugin may not preserve them).
     */
    private suspend fun downloadChunk(url: String, start: Long, size: Long): ByteArray? {
        return try {
            val response = httpClient.get(url) {
                headers {
                    append(HttpHeaders.Range, "bytes=$start-${start + size - 1}")
                    append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                }
                timeout {
                    requestTimeoutMillis = 10000 // 10 second timeout
                    connectTimeoutMillis = 5000
                }
            }
            
            // Handle redirects manually (HttpRedirect plugin may not preserve Range headers)
            if (response.status.value in 300..399) {
                val redirectLocation = response.headers["Location"]
                if (redirectLocation != null) {
                    // Consume redirect response body to ensure proper cleanup
                    try {
                        response.body<ByteReadChannel>()
                    } catch (e: Exception) {
                        // Ignore errors when consuming redirect body
                    }
                    
                    // Resolve redirect URL (handle relative redirects)
                    val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                        redirectLocation
                    } else {
                        // Relative redirect - construct absolute URL
                        val originalUri = java.net.URI(url)
                        originalUri.resolve(redirectLocation).toString()
                    }
                    
                    logger.debug("Following redirect for range request: originalUrl={}, redirectUrl={}, range=bytes=$start-${start + size - 1}", 
                        url.take(100), redirectUrl.take(100))
                    
                    // Make new request to redirect URL with Range header preserved
                    val redirectResponse = httpClient.get(redirectUrl) {
                        headers {
                            append(HttpHeaders.Range, "bytes=$start-${start + size - 1}")
                            append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        }
                        timeout {
                            requestTimeoutMillis = 10000
                            connectTimeoutMillis = 5000
                        }
                    }
                    
                    // Accept both 200 (OK) and 206 (Partial Content) as success
                    if (redirectResponse.status.value == 200 || redirectResponse.status.value == 206) {
                        redirectResponse.body<ByteArray>()
                    } else {
                        logger.debug("HTTP redirect request failed with status ${redirectResponse.status.value} for range bytes=$start-${start + size - 1}")
                        null
                    }
                } else {
                    logger.debug("HTTP redirect response missing Location header, status ${response.status.value}")
                    null
                }
            } else if (response.status.value == 200 || response.status.value == 206) {
                // Accept both 200 (OK) and 206 (Partial Content) as success
                // Some servers return 200 even for Range requests
                response.body<ByteArray>()
            } else {
                logger.debug("HTTP request failed with status ${response.status.value} for range bytes=$start-${start + size - 1}")
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to download chunk: ${e.message}")
            null
        }
    }
    
    /**
     * Downloads the last chunk of the file (for MP4 with moov at end).
     * Tries HEAD request first, falls back to GET with Range header if HEAD fails.
     */
    private suspend fun downloadLastChunk(url: String): ByteArray? {
        return try {
            // Try to get file size using HEAD request first (more efficient)
            var contentLength: Long? = null
            
            try {
                val headResponse = httpClient.head(url) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                    timeout {
                        requestTimeoutMillis = 5000
                        connectTimeoutMillis = 2000
                    }
                }
                
                if (headResponse.status.isSuccess()) {
                    contentLength = headResponse.headers["Content-Length"]?.toLongOrNull()
                } else {
                    logger.debug("HEAD request failed with status ${headResponse.status.value}, falling back to GET")
                }
            } catch (e: Exception) {
                logger.debug("HEAD request failed: ${e.message}, falling back to GET")
            }
            
            // If HEAD failed or didn't provide Content-Length, try GET with Range header for first byte
            // Uses same approach as IptvRequestService.fetchActualFileSize()
            if (contentLength == null) {
                try {
                    val getResponse = httpClient.get(url) {
                        headers {
                            append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                            append(HttpHeaders.Range, "bytes=0-0")
                        }
                        timeout {
                            requestTimeoutMillis = 5000
                            connectTimeoutMillis = 2000
                        }
                    }
                    
                    if (getResponse.status.isSuccess()) {
                        // Try to extract file size from Content-Range header first (e.g., "bytes 0-0/1882075726")
                        val contentRange = getResponse.headers["Content-Range"]
                        if (contentRange != null) {
                            // Parse Content-Range: bytes 0-0/1882075726
                            val rangeRegex = Regex("bytes\\s+\\d+-\\d+/(\\d+)")
                            val matchResult = rangeRegex.find(contentRange)
                            val totalSize = matchResult?.groupValues?.get(1)?.toLongOrNull()
                            if (totalSize != null && totalSize > 0) {
                                contentLength = totalSize
                            }
                        }
                        
                        // Fallback to Content-Length header if Content-Range is not available
                        if (contentLength == null) {
                            contentLength = getResponse.headers["Content-Length"]?.toLongOrNull()
                        }
                        
                        // Consume response body to ensure proper cleanup
                        try {
                            getResponse.body<ByteReadChannel>()
                        } catch (e: Exception) {
                            // Ignore errors when consuming response body
                        }
                    } else {
                        logger.debug("GET request failed with status ${getResponse.status.value}, cannot determine file size")
                        // Consume response body even on failure
                        try {
                            getResponse.body<ByteReadChannel>()
                        } catch (e: Exception) {
                            // Ignore errors when consuming response body
                        }
                        return null
                    }
                } catch (e: Exception) {
                    logger.debug("GET request failed: ${e.message}, cannot determine file size")
                    return null
                }
            }
            
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
     * Probes video metadata using FFprobe by reading directly from a URL.
     * FFprobe will read only the necessary metadata without downloading the entire file.
     * Thread-safe: Each call creates a new process.
     */
    private suspend fun probeUrlWithFfprobe(url: String): VideoMetadata? {
        if (!ffprobeAvailable) {
            return null
        }
        
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                // FFprobe can read directly from URLs with custom headers
                // Using -v error to show errors while keeping output minimal
                // FFprobe is smart about reading only necessary metadata without downloading the entire file
                process = ProcessBuilder(
                    iptvConfigurationProperties.ffprobePath,
                    "-v", "error",
                    "-show_format",
                    "-show_streams",
                    "-print_format", "json",
                    "-headers", "User-Agent: ${iptvConfigurationProperties.userAgent}",
                    url
                ).redirectErrorStream(true).start()
                
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
                    // Log error output if available (redirectErrorStream(true) sends stderr to stdout)
                    val errorOutput = output.takeIf { it.isNotBlank() } ?: "no error output"
                    logger.debug("FFprobe exited with code $exitCode. Error output: ${errorOutput.take(200)}")
                    return@withContext null
                }
                
                // Parse JSON output
                val ffprobeOutput = json.decodeFromString<FfprobeOutput>(output)
                return@withContext extractVideoMetadata(ffprobeOutput)
            } catch (e: Exception) {
                logger.debug("FFprobe execution failed for URL: ${e.message}")
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
     * Probes video metadata using FFprobe with data from stdin.
     * Thread-safe: Each call creates a new process.
     * @deprecated Use probeUrlWithFfprobe instead for better efficiency
     */
    @Deprecated("Use probeUrlWithFfprobe instead", ReplaceWith("probeUrlWithFfprobe(url)"))
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
                // Note: FFprobe may close stdin early if it encounters an error, causing a broken pipe
                // This is expected behavior and should be handled gracefully
                try {
                    process.outputStream.use { outputStream ->
                        outputStream.write(data)
                        outputStream.flush()
                    }
                } catch (e: java.io.IOException) {
                    // Broken pipe is expected if FFprobe closes stdin early (e.g., invalid data)
                    if (e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                        logger.debug("FFprobe closed stdin early (broken pipe), likely invalid or unparseable data")
                    } else {
                        throw e // Re-throw other IO exceptions
                    }
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
                    // Log error output if available (redirectErrorStream(true) sends stderr to stdout)
                    val errorOutput = output.takeIf { it.isNotBlank() } ?: "no error output"
                    logger.debug("FFprobe exited with code $exitCode. Error output: ${errorOutput.take(200)}")
                    return@withContext null
                }
                
                // Parse JSON output
                val ffprobeOutput = json.decodeFromString<FfprobeOutput>(output)
                return@withContext extractVideoMetadata(ffprobeOutput)
            } catch (e: java.io.IOException) {
                // Handle broken pipe and other IO exceptions gracefully
                if (e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                    logger.debug("FFprobe broken pipe - data may be invalid or unparseable")
                } else {
                    logger.debug("FFprobe IO error: ${e.message}")
                }
                return@withContext null
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
        
        fun hasFileSize(): Boolean {
            return fileSize != null && fileSize > 0
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

