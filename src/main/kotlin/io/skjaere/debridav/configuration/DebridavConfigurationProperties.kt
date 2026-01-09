package io.skjaere.debridav.configuration

import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration


private const val ONE_K = 1024 * 1024
private const val DEFAULT_LINK_LIVENESS_CACHE_MINUTES = 15L
private const val DEFAULT_CACHED_FILE_CACHE_MINUTES = 30L
private const val DEFAULT_STREAMING_DELAY_MILLIS = 50L
private const val DEFAULT_STREAMING_RETRIES = 2L
private const val DEFAULT_STREAMING_NETWORK_ERROR_WAIT_MILLIS = 100L
private const val DEFAULT_STREAMING_CLIENT_ERROR_WAIT_MILLIS = 100L
private const val DEFAULT_STREAMING_PROVIDER_ERROR_WAIT_MINUTES = 1L
private const val DEFAULT_STREAMING_DOWNLOAD_TRACKING_CACHE_EXPIRATION_HOURS = 24L
private const val DEFAULT_DEBRID_DIRECT_DL_RESPONSE_CACHE_EXPIRATION_SECONDS = 30L
private const val DEFAULT_STREAMING_MAX_CHUNK_SIZE_WARNING_MB = 1024L // 1 GB - warn when chunks exceed this size
private const val DEFAULT_STREAMING_BUFFER_SIZE = 65536L // 64KB - buffer size for direct streaming
private const val DEFAULT_STREAMING_FLUSH_MULTIPLIER = 4L // Flush multiplier (buffer size * multiplier = flush interval)

@ConfigurationProperties(prefix = "debridav")
data class DebridavConfigurationProperties(
    val rootPath: String,
    val downloadPath: String,
    val mountPath: String,
    var debridClients: List<DebridProvider>,
    val waitAfterMissing: Duration,
    val waitAfterProviderError: Duration,
    val waitAfterNetworkError: Duration,
    val waitAfterClientError: Duration,
    val retriesOnProviderError: Long,
    val delayBetweenRetries: Duration,
    val connectTimeoutMilliseconds: Long,
    val readTimeoutMilliseconds: Long,
    val shouldDeleteNonWorkingFiles: Boolean,
    val torrentLifetime: Duration,
    val enableFileImportOnStartup: Boolean,
    val chunkCachingSizeThreshold: Long,
    val chunkCachingGracePeriod: Duration,
    val defaultCategories: List<String>,
    val localEntityMaxSizeMb: Int,
    val cacheMaxSizeGb: Double,
    val linkLivenessCacheDuration: Duration = Duration.ofMinutes(DEFAULT_LINK_LIVENESS_CACHE_MINUTES),
    val cachedFileCacheDuration: Duration = Duration.ofMinutes(DEFAULT_CACHED_FILE_CACHE_MINUTES),
    val streamingDelayBetweenRetries: Duration = Duration.ofMillis(DEFAULT_STREAMING_DELAY_MILLIS),
    val streamingRetriesOnProviderError: Long = DEFAULT_STREAMING_RETRIES,
    val streamingWaitAfterNetworkError: Duration = Duration.ofMillis(DEFAULT_STREAMING_NETWORK_ERROR_WAIT_MILLIS),
    val streamingWaitAfterProviderError: Duration = Duration.ofMinutes(DEFAULT_STREAMING_PROVIDER_ERROR_WAIT_MINUTES),
    val streamingWaitAfterClientError: Duration = Duration.ofMillis(DEFAULT_STREAMING_CLIENT_ERROR_WAIT_MILLIS),
    val enableChunkCaching: Boolean = true,
    val enableInMemoryBuffering: Boolean = true,
    val disableByteRangeRequestChunking: Boolean = false,
    val enableStreamingDownloadTracking: Boolean = false,
    val streamingDownloadTrackingCacheExpirationHours: Duration = Duration.ofHours(DEFAULT_STREAMING_DOWNLOAD_TRACKING_CACHE_EXPIRATION_HOURS),
    val debridDirectDlResponseCacheExpirationSeconds: Duration = Duration.ofSeconds(DEFAULT_DEBRID_DIRECT_DL_RESPONSE_CACHE_EXPIRATION_SECONDS),
    val streamingMaxChunkSizeWarningMb: Long = DEFAULT_STREAMING_MAX_CHUNK_SIZE_WARNING_MB, // Warn when chunk size exceeds this threshold (in MB)
    val streamingBufferSize: Long = DEFAULT_STREAMING_BUFFER_SIZE, // Buffer size in bytes for direct streaming (default: 64KB)
    val streamingFlushMultiplier: Long = DEFAULT_STREAMING_FLUSH_MULTIPLIER, // Flush multiplier - flush interval = bufferSize * multiplier (default: 4, so 64KB * 4 = 256KB flush interval)
    val enableVfsOptimizationHeaders: Boolean = false, // Enable optimization headers for VFS path (Milton) streaming (default: false for backwards compatibility)
    val logVfsHeaders: Boolean = false, // Log existing Milton headers before modification (default: false, independent of enableVfsOptimizationHeaders)
    // Local video file approach for ARR projects
    val enableRcloneArrsLocalVideo: Boolean = false, // Enable serving local video files for ARR requests
    val rcloneArrsLocalVideoFilePaths: String? = null, // Video file mapping as comma-separated key=value pairs
    val rcloneArrsLocalVideoPathRegex: String? = null, // Regex pattern to match file paths for local video serving
    val rcloneArrsLocalVideoMinSizeKb: Long? = null, // Minimum file size in KB to use local video (smaller files served externally)
    val rcloneArrsUserAgentPattern: String?, // User agent pattern for ARR detection
    val rcloneArrsHostnamePattern: String?, // Hostname pattern for ARR detection
    val rcloneArrsLocalVideoFileIptvBypassProviders: String? = null, // Comma-separated list of IPTV provider names to bypass local video serving (use "*" for all providers)
    val debugArrTorrentInfoContentPathSuffix: String? = null, // Optional suffix to append to contentPath in ARR torrent info API responses (qBittorrent emulation /api/v2/torrents/info endpoint used by Sonarr/Radarr) for debugging purposes (e.g., "__DEBUG_TESTING")
    // Downloads cleanup configuration
    val enableDownloadsCleanupTimeBased: Boolean = false, // If true, cleanup only removes files/torrents older than downloadsCleanupTimeBasedThresholdMinutes. If false (default), cleanup is immediate (no age check).
    val downloadsCleanupTimeBasedThresholdMinutes: Long = 10, // Time threshold in minutes for time-based cleanup (only used if enableDownloadsCleanupTimeBased is true)
    // STRM file configuration
    val strmEnabled: Boolean = false, // Enable/disable STRM feature
    val strmFolderMappings: String? = null, // Maps root folders to STRM folders (e.g., tv=tv_strm,movies=movies_strm)
    val strmRootPathPrefix: String? = null, // Optional prefix for paths written in STRM files (e.g., /media)
    val strmFileExtensionMode: String = "REPLACE", // How to handle file extensions: REPLACE (episode.mkv -> episode.strm) or APPEND (episode.mkv -> episode.mkv.strm)
    val strmFileFilterMode: String = "MEDIA_ONLY", // Which files to convert: ALL (all files), MEDIA_ONLY (only media extensions), NON_STRM (all except .strm)
    val strmMediaExtensions: List<String> = listOf("mkv", "mp4", "avi", "mov", "m4v", "mpg", "mpeg", "wmv", "flv", "webm", "ts", "m2ts"), // List of media file extensions when filter mode is MEDIA_ONLY
    val strmUseExternalUrlForProviders: String? = null, // Comma-separated list of provider names for which to use external URLs. Supports ALL/* for all providers and ! prefix for negation.
    val strmProxyExternalUrlForProviders: String? = null, // Comma-separated list of provider names for which external URLs should use proxy URLs instead of direct URLs. Supports ALL/* for all providers and ! prefix for negation.
    val strmProxyBaseUrl: String? = null, // Base URL for STRM redirect proxy (e.g., http://debridav:8080). If not set, defaults to http://{detected-hostname}:8080
    val strmProxyStreamMode: Boolean = false, // If true, stream content directly through proxy instead of redirecting. Provides more control over content delivery.
    val strmProviders: String? = "*", // Comma-separated list of provider names for which to create STRM files. Supports ALL/* for all providers and ! prefix for negation. Default: * (all providers)
    val strmExcludeFilenameRegex: String? = null, // Optional regex pattern to match filenames. Files matching this pattern will use original media files instead of STRM files.
    val localEntityAlwaysStoreExtensions: List<String> = emptyList() // File extensions that should always be stored as LocalEntity (bypass size checks). Default: empty (disabled). Set to enable (e.g., "srt,vtt,ass,ssa,sub,idx,sup,ttml,dfxp,usf")
) {
    init {
        require(debridClients.isNotEmpty()) {
            "No debrid providers defined"
        }
        if (enableChunkCaching) {
            require((cacheMaxSizeGb * ONE_K * ONE_K) > localEntityMaxSizeMb) {
                "debridav.cache-max-size-gb must be greater than debridav.chunk-caching-size-threshold in Gb"
            }
        }
        if (enableRcloneArrsLocalVideo) {
            require(!rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
                "rcloneArrsLocalVideoFilePaths must be specified when enableRcloneArrsLocalVideo is true"
            }
            // Log configuration for debugging
            val logger = org.slf4j.LoggerFactory.getLogger(DebridavConfigurationProperties::class.java)
            logger.debug("ARR_LOCAL_VIDEO_CONFIG: enabled=true, userAgentPattern={}, hostnamePattern={}, filePaths={}, pathRegex={}, minSizeKb={}",
                rcloneArrsUserAgentPattern ?: "null (not configured)",
                rcloneArrsHostnamePattern ?: "null (not configured)",
                rcloneArrsLocalVideoFilePaths,
                rcloneArrsLocalVideoPathRegex ?: "null (matches all paths)",
                rcloneArrsLocalVideoMinSizeKb ?: "null (no minimum size)")
        }
    }

    fun getMaxCacheSizeInBytes(): Long {
        return (cacheMaxSizeGb * ONE_K * ONE_K * ONE_K).toLong()
    }


    /**
     * Checks if we should serve a local video file for ARR requests instead of the actual media file.
     * This helps reduce bandwidth usage by serving a small local file for metadata analysis.
     */
    fun shouldServeLocalVideoForArrs(httpRequestInfo: io.skjaere.debridav.stream.HttpRequestInfo): Boolean {
        if (!enableRcloneArrsLocalVideo || rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
            return false
        }
        
        // Check user agent - use case-insensitive matching for flexibility
        // This handles cases where:
        // - Pattern "rclone" matches "rclone/", "rclone/arrs", "rclone/v1.2.3"
        // - Pattern "rclone/arrs" matches "rclone/arrs", "rclone/arrs/v1.2.3"
        // - Pattern "rclone/arrs" also matches "rclone/" (user-agent is prefix of pattern)
        val userAgent = httpRequestInfo.headers["user-agent"]
        if (userAgent != null && rcloneArrsUserAgentPattern != null) {
            val lowerUserAgent = userAgent.lowercase()
            val lowerPattern = rcloneArrsUserAgentPattern.lowercase()
            // Check if user-agent starts with pattern OR pattern starts with user-agent
            // This handles both "rclone" matching "rclone/arrs" and "rclone/arrs" matching "rclone/"
            val matches = lowerUserAgent.startsWith(lowerPattern) || lowerPattern.startsWith(lowerUserAgent)
            if (matches) {
                return true
            }
        }
        // Note: Removed TRACE logging for "not configured" case as it's expected when pattern is not configured

        // Check hostname
        val sourceInfo = httpRequestInfo.sourceInfo
        if (sourceInfo != null && rcloneArrsHostnamePattern != null) {
            val matches = sourceInfo.contains(rcloneArrsHostnamePattern)
            if (matches) {
                return true
            } else {
                // Log when hostname pattern doesn't match for debugging
                val logger = org.slf4j.LoggerFactory.getLogger(DebridavConfigurationProperties::class.java)
                logger.trace("ARR_LOCAL_VIDEO: sourceInfo={}, hostnamePattern={}, does not match", 
                    sourceInfo, rcloneArrsHostnamePattern)
            }
        } else if (rcloneArrsHostnamePattern != null) {
            // Log when sourceInfo is null but pattern is configured
            val logger = org.slf4j.LoggerFactory.getLogger(DebridavConfigurationProperties::class.java)
            logger.debug("ARR_LOCAL_VIDEO: sourceInfo is null, cannot match hostnamePattern={}", 
                rcloneArrsHostnamePattern)
        }

        return false
    }

    /**
     * Checks if the given file path matches the configured regex pattern for local video serving.
     * Returns true if no regex is configured (matches all paths) or if the path matches the regex.
     */
    fun shouldServeLocalVideoForPath(filePath: String): Boolean {
        if (rcloneArrsLocalVideoPathRegex == null) {
            return true // No regex configured, serve for all paths
        }
        
        return try {
            filePath.matches(Regex(rcloneArrsLocalVideoPathRegex))
        } catch (e: Exception) {
            false // Invalid regex, don't serve
        }
    }
    
    /**
     * Checks if an IPTV provider should bypass local video file serving for ARR requests.
     * Returns true if the provider should bypass (use direct IPTV provider instead of local files).
     * 
     * @param iptvProviderName The IPTV provider name to check
     * @return true if provider should bypass local video serving, false otherwise
     */
    fun shouldBypassLocalVideoForIptvProvider(iptvProviderName: String?): Boolean {
        if (iptvProviderName == null || rcloneArrsLocalVideoFileIptvBypassProviders.isNullOrBlank()) {
            return false
        }
        
        val bypassProviders = rcloneArrsLocalVideoFileIptvBypassProviders.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        // Check if "*" is in the list (all providers bypass)
        if (bypassProviders.contains("*")) {
            return true
        }
        
        // Check if the specific provider is in the list
        return bypassProviders.contains(iptvProviderName)
    }

    /**
     * Detects the resolution from a file name (case-insensitive).
     * Returns the resolution key if found, null otherwise.
     */
    fun detectResolutionFromFileName(fileName: String): String? {
        val fileNameLower = fileName.lowercase()
        
        // Common resolution patterns
        val resolutionPatterns = listOf(
            "2160p", "4k", "uhd",
            "1080p", "1080i",
            "720p", "720i", 
            "480p", "480i",
            "360p", "360i",
            "240p", "240i"
        )
        
        for (pattern in resolutionPatterns) {
            if (fileNameLower.contains(pattern)) {
                return pattern
            }
        }
        
        return null
    }

    /**
     * Parses the rcloneArrsLocalVideoFilePaths string into a map.
     * Format: "key1=value1,key2=value2" or just "value" for default
     */
    fun parseLocalVideoFilePaths(): Map<String, String> {
        if (rcloneArrsLocalVideoFilePaths.isNullOrBlank()) {
            return emptyMap()
        }
        
        return rcloneArrsLocalVideoFilePaths.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                if (entry.contains("=")) {
                    val parts = entry.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else null
                } else {
                    // No key, treat as default
                    "" to entry
                }
            }.toMap()
    }

    /**
     * Gets the appropriate local video file path based on resolution detection and configuration.
     * Returns the file path to serve, or null if no suitable file is found.
     */
    fun getLocalVideoFilePath(fileName: String): String? {
        val filePaths = parseLocalVideoFilePaths()
        
        // First, try to detect resolution from filename
        val detectedResolution = detectResolutionFromFileName(fileName)
        
        if (detectedResolution != null) {
            // Look for exact match first
            filePaths[detectedResolution]?.let { return it }
            
            // Look for case-insensitive match
            filePaths.entries.find { 
                it.key.lowercase() == detectedResolution 
            }?.value?.let { return it }
            
            // Look for pipe-separated keys (e.g., "2160p|1080p", "720p|")
            filePaths.entries.forEach { (key, value) ->
                val resolutionKeys = key.split("|").map { it.trim() }
                if (resolutionKeys.contains(detectedResolution) || 
                    resolutionKeys.any { it.lowercase() == detectedResolution.lowercase() }) {
                    return value
                }
            }
        }
        
        // Look for default entries (keys ending with "|", starting with "=", or empty key)
        filePaths.entries.find { 
            it.key.endsWith("|") || it.key.startsWith("=") || it.key.isEmpty() 
        }?.value?.let { return it }
        
        return null
    }
    
    /**
     * Checks if a file should use local video serving based on its size.
     * If rcloneArrsLocalVideoMinSizeKb is configured, files smaller than this threshold
     * will be served externally instead of using local video files.
     */
    fun shouldUseLocalVideoForSize(fileSizeBytes: Long): Boolean {
        if (rcloneArrsLocalVideoMinSizeKb == null) {
            return true // No size threshold configured, use local video for all files
        }
        
        val minSizeBytes = rcloneArrsLocalVideoMinSizeKb * 1024 // Convert KB to bytes
        return fileSizeBytes >= minSizeBytes
    }

    /**
     * Checks if STRM feature is enabled.
     * @return true if STRM is enabled, false otherwise
     */
    fun isStrmEnabled(): Boolean {
        return strmEnabled
    }

    /**
     * Parses the strmFolderMappings string into a map.
     * Format: "tv=tv_strm,movies=movies_strm"
     * Returns empty map if STRM is disabled or mappings are not configured.
     */
    fun parseStrmFolderMappings(): Map<String, String> {
        if (!strmEnabled || strmFolderMappings.isNullOrBlank()) {
            return emptyMap()
        }

        return strmFolderMappings.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                if (entry.contains("=")) {
                    val parts = entry.split("=", limit = 2)
                    if (parts.size == 2) {
                        val originalFolder = parts[0].trim().removePrefix("/")
                        val strmFolder = parts[1].trim().removePrefix("/")
                        if (originalFolder.isNotEmpty() && strmFolder.isNotEmpty()) {
                            originalFolder to strmFolder
                        } else null
                    } else null
                } else null
            }.toMap()
    }

    /**
     * Checks if a path is a STRM folder path.
     * @param path The path to check (e.g., "/tv_strm" or "/tv_strm/show")
     * @return true if the path is a STRM folder, false otherwise
     */
    fun isStrmPath(path: String): Boolean {
        if (!strmEnabled) {
            return false
        }

        val mappings = parseStrmFolderMappings()
        if (mappings.isEmpty()) {
            return false
        }

        val normalizedPath = path.removePrefix("/").removeSuffix("/")
        if (normalizedPath.isEmpty()) {
            return false
        }

        val firstSegment = normalizedPath.split("/").first()
        return mappings.values.contains(firstSegment)
    }

    /**
     * Gets the original folder path from a STRM folder path.
     * @param strmPath The STRM path (e.g., "/tv_strm" or "/tv_strm/show")
     * @return The original path (e.g., "/tv" or "/tv/show"), or null if not a STRM path
     */
    fun getOriginalPathFromStrm(strmPath: String): String? {
        if (!strmEnabled) {
            return null
        }

        val mappings = parseStrmFolderMappings()
        if (mappings.isEmpty()) {
            return null
        }

        val normalizedPath = strmPath.removePrefix("/").removeSuffix("/")
        if (normalizedPath.isEmpty()) {
            return null
        }

        val pathSegments = normalizedPath.split("/")
        val firstSegment = pathSegments.first()

        // Find the original folder name for this STRM folder
        val originalFolder = mappings.entries.find { it.value == firstSegment }?.key
            ?: return null

        // Reconstruct the original path
        val remainingSegments = pathSegments.drop(1)
        return if (remainingSegments.isEmpty()) {
            "/$originalFolder"
        } else {
            "/$originalFolder/${remainingSegments.joinToString("/")}"
        }
    }

    /**
     * Parses the comma-separated list of providers for which STRM files should be created.
     * Supports:
     * - `ALL` or `*` to represent all providers
     * - Negation with `!` prefix (e.g., `*,!IPTV` means all providers except IPTV)
     * @return Pair of (allowed providers set, denied providers set), or null if not configured
     */
    private fun parseStrmProviders(): Pair<Set<String>, Set<String>>? {
        val providers = strmProviders?.trim()
        if (providers.isNullOrBlank()) {
            return null
        }
        
        val allowedProviders = mutableSetOf<String>()
        val deniedProviders = mutableSetOf<String>()
        var allowAll = false
        
        providers.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .forEach { provider ->
                when {
                    provider == "ALL" || provider == "*" -> {
                        allowAll = true
                    }
                    provider.startsWith("!") -> {
                        val deniedProvider = provider.removePrefix("!")
                        if (deniedProvider.isNotEmpty()) {
                            deniedProviders.add(deniedProvider)
                        }
                    }
                    else -> {
                        allowedProviders.add(provider)
                    }
                }
            }
        
        return if (allowAll) {
            // If ALL/* is specified, return special marker
            Pair(setOf("*"), deniedProviders)
        } else if (allowedProviders.isNotEmpty() || deniedProviders.isNotEmpty()) {
            Pair(allowedProviders, deniedProviders)
        } else {
            null
        }
    }

    /**
     * Checks if STRM files should be created for a given provider.
     * @param provider Optional provider to check. If provided and strmProviders is set,
     *                 checks if this specific provider is allowed.
     * @return true if STRM files should be created for this provider, false otherwise
     */
    fun shouldUseStrmForProvider(provider: DebridProvider? = null): Boolean {
        // Check provider-specific configuration
        val providerConfig = parseStrmProviders()
        if (providerConfig != null) {
            val (allowedProviders, deniedProviders) = providerConfig
            
            return if (provider != null) {
                val providerName = provider.name.uppercase()
                
                // First check if provider is explicitly denied
                if (deniedProviders.contains(providerName)) {
                    return false
                }
                
                // Check if ALL/* is specified (all providers allowed)
                if (allowedProviders.contains("*")) {
                    return true
                }
                
                // Check if this specific provider is in the allowed list
                allowedProviders.contains(providerName)
            } else {
                // If no provider specified but provider-specific config exists, default to true if ALL/* is set
                allowedProviders.contains("*")
            }
        }
        
        // If no configuration is set, default to true (backward compatible - all providers use STRM)
        return true
    }

    /**
     * Checks if a filename should be excluded from STRM file creation based on regex pattern.
     * @param fileName The file name to check
     * @return true if the filename matches the exclusion regex pattern, false otherwise
     */
    fun shouldExcludeFilenameFromStrm(fileName: String): Boolean {
        if (strmExcludeFilenameRegex.isNullOrBlank()) {
            return false // No regex configured, no exclusion
        }
        
        return try {
            fileName.matches(Regex(strmExcludeFilenameRegex))
        } catch (e: Exception) {
            // Invalid regex, log warning and don't exclude
            val logger = org.slf4j.LoggerFactory.getLogger(DebridavConfigurationProperties::class.java)
            logger.warn("Invalid regex pattern in strmExcludeFilenameRegex: $strmExcludeFilenameRegex", e)
            false
        }
    }

    /**
     * Checks if a file should be converted to a STRM file based on filter mode and extensions.
     * @param fileName The file name to check
     * @param provider Optional provider for the file. If provided, checks provider inclusion/exclusion.
     * @return true if the file should be converted to STRM, false otherwise
     */
    fun shouldCreateStrmFile(fileName: String, provider: DebridProvider? = null): Boolean {
        if (!strmEnabled) {
            return false
        }

        // Check provider inclusion/exclusion
        if (!shouldUseStrmForProvider(provider)) {
            return false
        }

        // Check filename regex exclusion
        if (shouldExcludeFilenameFromStrm(fileName)) {
            return false
        }

        // Check file extension filter mode
        when (strmFileFilterMode.uppercase()) {
            "ALL" -> return true
            "NON_STRM" -> return !fileName.lowercase().endsWith(".strm")
            "MEDIA_ONLY" -> {
                val extension = fileName.substringAfterLast(".", "").lowercase()
                return strmMediaExtensions.any { it.lowercase() == extension }
            }
            else -> return false
        }
    }

    /**
     * Gets the STRM file name from the original file name based on extension mode.
     * @param originalFileName The original file name (e.g., "episode.mkv")
     * @return The STRM file name (e.g., "episode.strm" or "episode.mkv.strm")
     */
    fun getStrmFileName(originalFileName: String): String {
        if (!strmEnabled) {
            return originalFileName
        }

        return when (strmFileExtensionMode.uppercase()) {
            "APPEND" -> "$originalFileName.strm"
            "REPLACE" -> {
                val lastDotIndex = originalFileName.lastIndexOf(".")
                if (lastDotIndex > 0) {
                    originalFileName.substring(0, lastDotIndex) + ".strm"
                } else {
                    "$originalFileName.strm"
                }
            }
            else -> {
                // Default to REPLACE behavior
                val lastDotIndex = originalFileName.lastIndexOf(".")
                if (lastDotIndex > 0) {
                    originalFileName.substring(0, lastDotIndex) + ".strm"
                } else {
                    "$originalFileName.strm"
                }
            }
        }
    }

    /**
     * Gets the content path to write in a STRM file.
     * This includes the optional prefix if configured.
     * @param originalPath The original file path (e.g., "/tv/show/episode.mkv")
     * @return The content path to write in the STRM file (e.g., "/media/tv/show/episode.mkv" or "/tv/show/episode.mkv")
     */
    fun getStrmContentPath(originalPath: String): String {
        val prefix = strmRootPathPrefix?.trim()
        return if (prefix.isNullOrBlank()) {
            originalPath
        } else {
            val normalizedPrefix = prefix.removeSuffix("/")
            val normalizedPath = originalPath.removePrefix("/")
            "$normalizedPrefix/$normalizedPath"
        }
    }

    /**
     * Parses the comma-separated list of providers for which external URLs should be used in STRM files.
     * Supports:
     * - `ALL` or `*` to represent all providers
     * - Negation with `!` prefix (e.g., `*,!REAL_DEBRID` means all providers except REAL_DEBRID)
     * @return Pair of (allowed providers set, denied providers set), or null if not configured
     */
    private fun parseStrmUseExternalUrlForProviders(): Pair<Set<String>, Set<String>>? {
        val providers = strmUseExternalUrlForProviders?.trim()
        if (providers.isNullOrBlank()) {
            return null
        }
        
        val allowedProviders = mutableSetOf<String>()
        val deniedProviders = mutableSetOf<String>()
        var allowAll = false
        
        providers.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .forEach { provider ->
                when {
                    provider == "ALL" || provider == "*" -> {
                        allowAll = true
                    }
                    provider.startsWith("!") -> {
                        val deniedProvider = provider.removePrefix("!")
                        if (deniedProvider.isNotEmpty()) {
                            deniedProviders.add(deniedProvider)
                        }
                    }
                    else -> {
                        allowedProviders.add(provider)
                    }
                }
            }
        
        return if (allowAll) {
            // If ALL/* is specified, return special marker
            Pair(setOf("*"), deniedProviders)
        } else if (allowedProviders.isNotEmpty() || deniedProviders.isNotEmpty()) {
            Pair(allowedProviders, deniedProviders)
        } else {
            null
        }
    }

    /**
     * Checks if STRM files should use external URLs instead of VFS paths.
     * @param provider Optional provider to check. If provided and strmUseExternalUrlForProviders is set,
     *                 checks if this specific provider is allowed.
     * @return true if external URLs should be used, false otherwise
     */
    fun shouldUseExternalUrlForStrm(provider: io.skjaere.debridav.debrid.DebridProvider? = null): Boolean {
        // Check provider-specific configuration
        val providerConfig = parseStrmUseExternalUrlForProviders()
        if (providerConfig != null) {
            val (allowedProviders, deniedProviders) = providerConfig
            
            return if (provider != null) {
                val providerName = provider.name.uppercase()
                
                // First check if provider is explicitly denied
                if (deniedProviders.contains(providerName)) {
                    return false
                }
                
                // Check if ALL/* is specified (all providers allowed)
                if (allowedProviders.contains("*")) {
                    return true
                }
                
                // Check if this specific provider is in the allowed list
                allowedProviders.contains(providerName)
            } else {
                // If no provider specified but provider-specific config exists, default to false
                false
            }
        }
        
        // If no configuration is set, default to false (use VFS paths)
        return false
    }

    /**
     * Parses the comma-separated list of providers for which external URLs should use proxy URLs in STRM files.
     * Supports:
     * - `ALL` or `*` to represent all providers
     * - Negation with `!` prefix (e.g., `*,!REAL_DEBRID` means all providers except REAL_DEBRID)
     * @return Pair of (allowed providers set, denied providers set), or null if not configured
     */
    private fun parseStrmProxyExternalUrlForProviders(): Pair<Set<String>, Set<String>>? {
        val providers = strmProxyExternalUrlForProviders?.trim()
        if (providers.isNullOrBlank()) {
            return null
        }
        
        val allowedProviders = mutableSetOf<String>()
        val deniedProviders = mutableSetOf<String>()
        var allowAll = false
        
        providers.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .forEach { provider ->
                when {
                    provider == "ALL" || provider == "*" -> {
                        allowAll = true
                    }
                    provider.startsWith("!") -> {
                        val deniedProvider = provider.removePrefix("!")
                        if (deniedProvider.isNotEmpty()) {
                            deniedProviders.add(deniedProvider)
                        }
                    }
                    else -> {
                        allowedProviders.add(provider)
                    }
                }
            }
        
        return if (allowAll) {
            // If ALL/* is specified, return special marker
            Pair(setOf("*"), deniedProviders)
        } else if (allowedProviders.isNotEmpty() || deniedProviders.isNotEmpty()) {
            Pair(allowedProviders, deniedProviders)
        } else {
            null
        }
    }

    /**
     * Checks if STRM files should use proxy URLs instead of direct external URLs.
     * @param provider Optional provider to check. If provided and strmProxyExternalUrlForProviders is set,
     *                 checks if this specific provider should use proxy URLs.
     * @return true if proxy URLs should be used, false otherwise
     */
    fun shouldUseProxyUrlForStrm(provider: io.skjaere.debridav.debrid.DebridProvider? = null): Boolean {
        // Check provider-specific configuration
        val providerConfig = parseStrmProxyExternalUrlForProviders()
        if (providerConfig != null) {
            val (allowedProviders, deniedProviders) = providerConfig
            
            return if (provider != null) {
                val providerName = provider.name.uppercase()
                
                // First check if provider is explicitly denied
                if (deniedProviders.contains(providerName)) {
                    return false
                }
                
                // Check if ALL/* is specified (all providers allowed)
                if (allowedProviders.contains("*")) {
                    return true
                }
                
                // Check if this specific provider is in the allowed list
                allowedProviders.contains(providerName)
            } else {
                // If no provider specified but provider-specific config exists, default to false
                false
            }
        }
        
        // If no configuration is set, default to false (use direct URLs)
        return false
    }

    /**
     * Checks if a file should always be stored as LocalEntity (bypassing size checks).
     * This is used for subtitle files and other small metadata files that should always
     * be stored in the database regardless of size.
     * @param fileName The file name to check
     * @return true if the file extension is in the whitelist, false otherwise
     */
    fun shouldAlwaysStoreAsLocalEntity(fileName: String): Boolean {
        if (localEntityAlwaysStoreExtensions.isEmpty()) {
            return false
        }
        
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return localEntityAlwaysStoreExtensions.any { it.lowercase() == extension }
    }
}
