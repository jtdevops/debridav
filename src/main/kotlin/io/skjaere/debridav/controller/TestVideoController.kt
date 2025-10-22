package io.skjaere.debridav.controller

import io.skjaere.debridav.util.TestVideoGenerator
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for generating test video files for ARR projects.
 */
@RestController
@RequestMapping("/api/test-video")
class TestVideoController(
    private val testVideoGenerator: TestVideoGenerator
) {

    /**
     * Generates a test video file at the specified path with a specific preset.
     * 
     * @param path The path where to generate the test video file
     * @param preset The video preset to use (optional, defaults to SMALL_720P)
     * @return Success or error response
     */
    @PostMapping("/generate")
    fun generateTestVideo(
        @RequestParam path: String,
        @RequestParam(required = false, defaultValue = "SMALL_720P") preset: String
    ): ResponseEntity<Map<String, Any>> {
        return try {
            val videoPreset = try {
                TestVideoGenerator.VideoPreset.valueOf(preset.uppercase())
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "message" to "Invalid preset: $preset. Available presets: ${TestVideoGenerator.VideoPreset.values().joinToString { it.name }}",
                    "path" to path
                ))
            }
            
            val success = testVideoGenerator.generateTestVideo(path, videoPreset)
            if (success) {
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "message" to "Test video generated successfully",
                    "path" to path,
                    "preset" to preset,
                    "resolution" to videoPreset.resolution,
                    "targetSizeMB" to videoPreset.targetSizeMB
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "message" to "Failed to generate test video",
                    "path" to path,
                    "preset" to preset
                ))
            }
        } catch (e: Exception) {
            ResponseEntity.internalServerError().body(mapOf(
                "success" to false,
                "message" to "Error generating test video: ${e.message}",
                "path" to path,
                "preset" to preset
            ))
        }
    }

    /**
     * Lists all available video presets.
     * 
     * @return List of available presets with their properties
     */
    @GetMapping("/presets")
    fun getAvailablePresets(): ResponseEntity<Map<String, Any>> {
        val presets = TestVideoGenerator.VideoPreset.values().map { preset ->
            mapOf(
                "name" to preset.name,
                "size" to preset.size,
                "resolution" to preset.resolution,
                "targetSizeMB" to preset.targetSizeMB,
                "description" to "${preset.size} ${preset.resolution} (${preset.targetSizeMB}MB)"
            )
        }
        
        return ResponseEntity.ok(mapOf(
            "presets" to presets,
            "default" to "SMALL_720P"
        ))
    }

    /**
     * Generates a test video file in the default location.
     * 
     * @return Success or error response with the generated file path
     */
    @PostMapping("/generate-default")
    fun generateDefaultTestVideo(): ResponseEntity<Map<String, Any>> {
        val defaultPath = "/tmp/debridav-test-video.mp4"
        return try {
            val success = testVideoGenerator.generateTestVideo(defaultPath)
            if (success) {
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "message" to "Test video generated successfully",
                    "path" to defaultPath,
                    "note" to "Update your configuration to use this path: debridav.rclone-arrs-local-video-file-path=$defaultPath"
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "message" to "Failed to generate test video",
                    "path" to defaultPath
                ))
            }
        } catch (e: Exception) {
            ResponseEntity.internalServerError().body(mapOf(
                "success" to false,
                "message" to "Error generating test video: ${e.message}",
                "path" to defaultPath
            ))
        }
    }
}
