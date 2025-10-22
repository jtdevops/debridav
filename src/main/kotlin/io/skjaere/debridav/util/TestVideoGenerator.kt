package io.skjaere.debridav.util

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Utility to generate test video files for ARR projects.
 * This creates MP4 files with configurable size and resolution for ARR analysis.
 */
@Component
class TestVideoGenerator {
    private val logger = LoggerFactory.getLogger(TestVideoGenerator::class.java)

    /**
     * Video generation presets for different use cases
     */
    enum class VideoPreset(val size: String, val resolution: String, val targetSizeMB: Int) {
        MINIMAL("minimal", "1x1", 1),           // ~1KB - minimal metadata only
        SMALL_720P("small", "1280x720", 1),     // 1MB 720p
        SMALL_1080P("small", "1920x1080", 1),   // 1MB 1080p  
        MEDIUM_720P("medium", "1280x720", 10),  // 10MB 720p
        MEDIUM_1080P("medium", "1920x1080", 10), // 10MB 1080p
        LARGE_720P("large", "1280x720", 50),    // 50MB 720p
        LARGE_1080P("large", "1920x1080", 50)   // 50MB 1080p
    }

    /**
     * Generates a test video file at the specified path with default settings.
     * Returns true if successful, false otherwise.
     */
    fun generateTestVideo(outputPath: String): Boolean {
        return generateTestVideo(outputPath, VideoPreset.SMALL_720P)
    }

    /**
     * Generates a test video file at the specified path with custom preset.
     * Returns true if successful, false otherwise.
     */
    fun generateTestVideo(outputPath: String, preset: VideoPreset): Boolean {
        try {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            logger.info("TEST_VIDEO_GENERATION_START: path={}, preset={}, resolution={}, targetSize={}MB", 
                outputPath, preset.size, preset.resolution, preset.targetSizeMB)

            val videoBytes = when (preset) {
                VideoPreset.MINIMAL -> createMinimalMp4()
                else -> createVideoWithFFmpeg(outputPath, preset)
            }
            
            if (videoBytes != null) {
                FileOutputStream(outputFile).use { fos ->
                    fos.write(videoBytes)
                }
                logger.info("TEST_VIDEO_GENERATED: path={}, size={} bytes, preset={}", 
                    outputPath, videoBytes.size, preset.size)
                return true
            } else {
                logger.error("TEST_VIDEO_GENERATION_FAILED: Could not generate video with preset {}", preset)
                return false
            }
        } catch (e: Exception) {
            logger.error("TEST_VIDEO_GENERATION_FAILED: path={}, preset={}, error={}", 
                outputPath, preset, e.message, e)
            return false
        }
    }

    /**
     * Creates a minimal MP4 file with basic video metadata.
     * This is a very small file (around 1KB) that contains enough structure
     * for ARR projects to recognize as a valid video file.
     */
    private fun createMinimalMp4(): ByteArray {
        // This creates a minimal MP4 file with ftyp, moov, and mdat boxes
        // It's not a playable video but contains enough metadata for ARR analysis
        return byteArrayOf(
            // ftyp box
            0x00, 0x00, 0x00, 0x20, // box size
            0x66, 0x74, 0x79, 0x70, // 'ftyp'
            0x69, 0x73, 0x6F, 0x6D, // major brand 'isom'
            0x00, 0x00, 0x00, 0x00, // minor version
            0x69, 0x73, 0x6F, 0x6D, // compatible brand 'isom'
            0x69, 0x73, 0x6F, 0x32, // compatible brand 'iso2'
            0x6D, 0x70, 0x34, 0x31, // compatible brand 'mp41'
            
            // moov box (minimal)
            0x00, 0x00, 0x00, 0x08, // box size
            0x6D, 0x6F, 0x6F, 0x76, // 'moov'
            
            // mdat box (minimal)
            0x00, 0x00, 0x00, 0x08, // box size
            0x6D, 0x64, 0x61, 0x74  // 'mdat'
        )
    }

    /**
     * Creates a video file using FFmpeg with the specified preset.
     * Returns null if FFmpeg is not available or generation fails.
     */
    private fun createVideoWithFFmpeg(outputPath: String, preset: VideoPreset): ByteArray? {
        return try {
            // Check if FFmpeg is available
            val processBuilder = ProcessBuilder("ffmpeg", "-version")
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                logger.warn("FFmpeg not available, falling back to minimal MP4")
                return createMinimalMp4()
            }

            // Calculate duration based on target size and resolution
            val duration = calculateDuration(preset)
            val resolution = preset.resolution.split("x")
            val width = resolution[0].toInt()
            val height = resolution[1].toInt()

            // Create temporary file for FFmpeg output
            val tempFile = File.createTempFile("debridav_temp_", ".mp4")
            tempFile.deleteOnExit()

            // Calculate bitrate for more accurate file size
            val bitrateKbps = when {
                height >= 1080 -> {
                    when {
                        preset.targetSizeMB <= 1 -> 2000    // 2 Mbps for 1MB files
                        preset.targetSizeMB <= 10 -> 4000   // 4 Mbps for 10MB files  
                        else -> 8000                       // 8 Mbps for larger files
                    }
                }
                height >= 720 -> {
                    when {
                        preset.targetSizeMB <= 1 -> 1000    // 1 Mbps for 1MB files
                        preset.targetSizeMB <= 10 -> 2000   // 2 Mbps for 10MB files
                        else -> 4000                       // 4 Mbps for larger files
                    }
                }
                else -> 500 // 0.5 Mbps for lower resolutions
            }

            // Build FFmpeg command with bitrate control
            val ffmpegCommand = listOf(
                "ffmpeg",
                "-f", "lavfi",
                "-i", "testsrc=duration=$duration:size=${width}x${height}:rate=25",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-b:v", "${bitrateKbps}k",
                "-maxrate", "${bitrateKbps}k",
                "-bufsize", "${bitrateKbps * 2}k",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                "-y", // Overwrite output file
                tempFile.absolutePath
            )

            logger.info("FFMPEG_COMMAND: {}", ffmpegCommand.joinToString(" "))

            val ffmpegProcess = ProcessBuilder(ffmpegCommand).start()
            val ffmpegExitCode = ffmpegProcess.waitFor()

            if (ffmpegExitCode == 0 && tempFile.exists()) {
                val videoBytes = tempFile.readBytes()
                tempFile.delete()
                videoBytes
            } else {
                logger.error("FFmpeg generation failed with exit code: {}", ffmpegExitCode)
                tempFile.delete()
                createMinimalMp4()
            }
        } catch (e: Exception) {
            logger.warn("FFmpeg generation failed: {}, falling back to minimal MP4", e.message)
            createMinimalMp4()
        }
    }

    /**
     * Calculates the duration needed to achieve the target file size.
     * This is a more accurate estimation based on resolution and target size.
     */
    private fun calculateDuration(preset: VideoPreset): Double {
        val targetSizeMB = preset.targetSizeMB
        val resolution = preset.resolution.split("x")
        val width = resolution[0].toInt()
        val height = resolution[1].toInt()
        
        // More accurate bitrate estimation based on resolution and target size
        val bitrateMbps = when {
            height >= 1080 -> {
                // For 1080p: estimate bitrate based on target size
                when {
                    targetSizeMB <= 1 -> 2.0    // 2 Mbps for 1MB files
                    targetSizeMB <= 10 -> 4.0   // 4 Mbps for 10MB files  
                    else -> 8.0                 // 8 Mbps for larger files
                }
            }
            height >= 720 -> {
                // For 720p: estimate bitrate based on target size
                when {
                    targetSizeMB <= 1 -> 1.0    // 1 Mbps for 1MB files
                    targetSizeMB <= 10 -> 2.0   // 2 Mbps for 10MB files
                    else -> 4.0                 // 4 Mbps for larger files
                }
            }
            else -> 0.5 // 0.5 Mbps for lower resolutions
        }
        
        // Calculate duration: targetSizeMB / (bitrateMbps / 8) * 8 (convert Mbps to MB/s)
        val duration = targetSizeMB / (bitrateMbps / 8.0)
        return maxOf(1.0, minOf(300.0, duration)) // Between 1 and 300 seconds
    }
}
