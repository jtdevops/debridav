package io.skjaere.debridav.util

/**
 * Shared constants for video file extensions used across the codebase.
 * This ensures consistency in ARR detection and media file identification.
 */
object VideoFileExtensions {
    /**
     * List of known video file extensions (case-insensitive).
     * This list is used for:
     * - ARR local video serving detection (isMediaFile)
     * - Torrent file selection
     * - Video file identification
     */
    val KNOWN_VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".avi", ".ts", ".mov", ".wmv", ".flv", ".webm", ".m4v",
        ".m2ts", ".mts", ".vob", ".ogv", ".3gp", ".asf", ".rm", ".rmvb"
    )

    /**
     * Checks if a filename has a video extension (case-insensitive).
     */
    fun isVideoFile(fileName: String): Boolean {
        val lowerFileName = fileName.lowercase()
        return KNOWN_VIDEO_EXTENSIONS.any { lowerFileName.endsWith(it) }
    }
}

