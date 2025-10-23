package io.skjaere.debridav.stream

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for serving local video files to ARR projects to reduce bandwidth usage.
 * Instead of serving the actual media files, this service serves small, locally-hosted
 * video files that contain enough metadata for ARR projects to analyze.
 */
@Service
class LocalVideoService(
    private val debridavConfigProperties: DebridavConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(LocalVideoService::class.java)

    /**
     * Serves the local video file for ARR requests.
     * Returns true if the file was served successfully, false otherwise.
     */
    suspend fun serveLocalVideoFile(
        outputStream: OutputStream,
        range: io.milton.http.Range?,
        httpRequestInfo: HttpRequestInfo,
        fileName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get the appropriate local video file path based on resolution detection
            val localVideoPath = if (fileName != null) {
                debridavConfigProperties.getLocalVideoFilePath(fileName)
            } else {
                // Fallback to any available path when no fileName is provided
                debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull()
            } ?: return@withContext false

            val localVideoFile = File(localVideoPath)
            if (!localVideoFile.exists() || !localVideoFile.isFile) {
                logger.error("LOCAL_VIDEO_FILE_NOT_FOUND: path={}, fileName={}", localVideoPath, fileName)
                return@withContext false
            }

            val fileSize = localVideoFile.length()
            val actualRange = range ?: io.milton.http.Range(0, fileSize - 1)
            
            // Ensure range doesn't exceed file size
            val start = maxOf(0L, actualRange.start)
            val end = minOf(fileSize - 1, actualRange.finish)
            
            if (start > end) {
                logger.warn("LOCAL_VIDEO_INVALID_RANGE: start={}, end={}, file_size={}", start, end, fileSize)
                return@withContext false
            }

            val detectedResolution = if (fileName != null) {
                debridavConfigProperties.detectResolutionFromFileName(fileName)
            } else null
            
            logger.info("LOCAL_VIDEO_SERVING: file={}, range={}-{}, size={} bytes, source={}, originalFileName={}, detectedResolution={}",
                localVideoFile.name, start, end, end - start + 1, httpRequestInfo.sourceInfo, fileName, detectedResolution)

            // Stream the requested range from the local file
            FileInputStream(localVideoFile).use { inputStream ->
                inputStream.skip(start)
                val buffer = ByteArray(65536) // 64KB buffer
                var remaining = end - start + 1
                var position = start

                while (remaining > 0) {
                    val bytesToRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val bytesRead = inputStream.read(buffer, 0, bytesToRead)
                    
                    if (bytesRead == -1) {
                        logger.warn("LOCAL_VIDEO_EOF_REACHED: position={}, expected_end={}", position, end)
                        break
                    }

                    try {
                        outputStream.write(buffer, 0, bytesRead)
                        remaining -= bytesRead
                        position += bytesRead
                    } catch (e: ClientAbortException) {
                        logger.info("LOCAL_VIDEO_CLIENT_ABORTED: client disconnected during streaming, position={}, remaining={}", 
                            position, remaining)
                        return@withContext true // Client disconnected, but this is normal behavior
                    } catch (e: IOException) {
                        if (e.message?.contains("Connection reset") == true || 
                            e.message?.contains("Broken pipe") == true) {
                            logger.info("LOCAL_VIDEO_CONNECTION_RESET: client connection lost during streaming, position={}, remaining={}", 
                                position, remaining)
                            return@withContext true // Connection reset, but this is normal behavior
                        }
                        throw e // Re-throw if it's not a connection-related issue
                    }
                }
                
                try {
                    outputStream.flush()
                } catch (e: ClientAbortException) {
                    logger.info("LOCAL_VIDEO_CLIENT_ABORTED_FLUSH: client disconnected during flush")
                    return@withContext true
                } catch (e: IOException) {
                    if (e.message?.contains("Connection reset") == true || 
                        e.message?.contains("Broken pipe") == true) {
                        logger.info("LOCAL_VIDEO_CONNECTION_RESET_FLUSH: client connection lost during flush")
                        return@withContext true
                    }
                    throw e
                }
            }

            logger.info("LOCAL_VIDEO_SERVED_SUCCESSFULLY: file={}, range={}-{}, bytes_served={}",
                localVideoFile.name, start, end, end - start + 1)

            true
        } catch (e: ClientAbortException) {
            logger.info("LOCAL_VIDEO_CLIENT_ABORTED: client disconnected, path={}", 
                debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull())
            true // Client disconnected, but this is normal behavior
        } catch (e: IOException) {
            if (e.message?.contains("Connection reset") == true || 
                e.message?.contains("Broken pipe") == true) {
                logger.info("LOCAL_VIDEO_CONNECTION_RESET: client connection lost, path={}", 
                    debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull())
                true // Connection reset, but this is normal behavior
            } else {
                logger.error("LOCAL_VIDEO_IO_ERROR: path={}, error={}", 
                    debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull(), e.message, e)
                false
            }
        } catch (e: Exception) {
            logger.error("LOCAL_VIDEO_SERVE_ERROR: path={}, error={}", 
                debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull(), e.message, e)
            false
        }
    }

    /**
     * Gets the size of the local video file for content-length headers.
     * Reads the actual file size from the filesystem.
     */
    fun getLocalVideoFileSize(fileName: String? = null): Long {
        val localVideoPath = if (fileName != null) {
            debridavConfigProperties.getLocalVideoFilePath(fileName)
        } else {
            debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull()
        } ?: return 0L
        
        val localVideoFile = File(localVideoPath)
        return if (localVideoFile.exists() && localVideoFile.isFile) {
            localVideoFile.length()
        } else {
            0L
        }
    }

    /**
     * Gets the MIME type of the local video file.
     * Attempts to detect the MIME type from the file extension.
     */
    fun getLocalVideoFileMimeType(fileName: String? = null): String {
        val localVideoPath = if (fileName != null) {
            debridavConfigProperties.getLocalVideoFilePath(fileName)
        } else {
            debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull()
        } ?: return "video/mp4"
        
        val localVideoFile = File(localVideoPath)
        
        if (!localVideoFile.exists() || !localVideoFile.isFile) {
            return "video/mp4" // fallback
        }
        
        // Detect MIME type from file extension
        val fileName = localVideoFile.name.lowercase()
        return when {
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".avi") -> "video/x-msvideo"
            fileName.endsWith(".mkv") -> "video/x-matroska"
            fileName.endsWith(".mov") -> "video/quicktime"
            fileName.endsWith(".wmv") -> "video/x-ms-wmv"
            fileName.endsWith(".flv") -> "video/x-flv"
            fileName.endsWith(".webm") -> "video/webm"
            fileName.endsWith(".m4v") -> "video/x-m4v"
            else -> "video/mp4" // fallback
        }
    }

    /**
     * Checks if the local video file exists and is accessible.
     */
    fun isLocalVideoFileAvailable(fileName: String? = null): Boolean {
        val localVideoPath = if (fileName != null) {
            debridavConfigProperties.getLocalVideoFilePath(fileName)
        } else {
            debridavConfigProperties.parseLocalVideoFilePaths().values.firstOrNull()
        } ?: return false
        
        val localVideoFile = File(localVideoPath)
        return localVideoFile.exists() && localVideoFile.isFile && localVideoFile.canRead()
    }
}
