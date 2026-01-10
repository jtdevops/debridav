package io.skjaere.debridav.iptv.metadata

import io.skjaere.debridav.iptv.IptvMovieMetadataEntity
import io.skjaere.debridav.iptv.IptvSeriesMetadataEntity
import io.skjaere.debridav.iptv.client.XtreamCodesClient
import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import io.skjaere.debridav.iptv.model.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import io.skjaere.debridav.iptv.util.IptvUrlBuilder
import io.skjaere.debridav.iptv.IptvContentRepository
import io.skjaere.debridav.iptv.IptvContentService
import java.time.Duration
import java.time.Instant

@Service
class MetadataEnhancer(
    private val videoMetadataExtractor: VideoMetadataExtractor,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvContentService: IptvContentService
) {
    private val logger = LoggerFactory.getLogger(MetadataEnhancer::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    /**
     * Reads ffprobe_last_processed timestamp from JSON response root level.
     * Returns null if not found or invalid.
     */
    private fun getFfprobeLastProcessedFromJson(responseJson: String): Instant? {
        return try {
            val jsonObject = json.parseToJsonElement(responseJson).jsonObject
            val lastProcessedStr = jsonObject["ffprobe_last_processed"]?.jsonPrimitive?.content
            lastProcessedStr?.let { Instant.parse(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Updates ffprobe_last_processed timestamp in JSON response root level.
     * Preserves all existing fields and adds/updates ffprobe_last_processed.
     */
    private fun updateFfprobeLastProcessedInJson(responseJson: String, timestamp: Instant): String {
        return try {
            val jsonObject = json.parseToJsonElement(responseJson).jsonObject
            val updatedJson = buildJsonObject {
                // Copy all existing fields
                jsonObject.forEach { (key, value) ->
                    if (key != "ffprobe_last_processed") {
                        put(key, value)
                    }
                }
                // Add or update ffprobe_last_processed
                put("ffprobe_last_processed", timestamp.toString())
            }
            json.encodeToString(JsonObject.serializer(), updatedJson)
        } catch (e: Exception) {
            logger.warn("Failed to update ffprobe_last_processed in JSON: ${e.message}", e)
            responseJson // Return original if update fails
        }
    }
    
    /**
     * Enhances movie metadata by extracting video information from the media file.
     * Updates the response_json in the database if enhancement is successful.
     */
    suspend fun enhanceMovieMetadata(
        metadata: IptvMovieMetadataEntity,
        movieInfo: XtreamCodesClient.MovieInfo,
        providerConfig: IptvProviderConfiguration,
        enhanceMetadata: Boolean = false
    ): Boolean {
        if (!enhanceMetadata) {
            return false
        }
        
        // Check if video info is missing or empty
        val videoInfo = movieInfo.info?.video
        val existingWidth = videoInfo?.width
        val existingHeight = videoInfo?.height
        val existingCodec = videoInfo?.codec_name
        val existingFileSize = videoInfo?.tags?.let { tags ->
            val sizeKeys = listOf("NUMBER_OF_BYTES-eng", "NUMBER_OF_BYTES", "NUMBER_OF_BYTES-ENG")
            sizeKeys.firstOrNull { tags.containsKey(it) }?.let { key ->
                tags[key]?.toLongOrNull()
            }
        }
        
        val needsEnhancement = videoInfo == null || 
            (existingWidth == null && existingHeight == null) ||
            existingWidth == 0 || existingHeight == 0
        
        if (!needsEnhancement) {
            val metadataDetails = buildString {
                append("width=${existingWidth ?: "null"}, height=${existingHeight ?: "null"}")
                existingCodec?.let { codec -> append(", codec=$codec") }
                existingFileSize?.let { size -> append(", fileSize=${size / 1_000_000}MB") }
            }
            logger.debug("Movie metadata for ${metadata.movieId} already has complete video info ($metadataDetails), skipping FFprobe extraction (enhanceMetadata=true but metadata already exists)")
            return false
        }
        
        // Check if we've processed this recently (within 1 hour) - read from JSON
        val now = Instant.now()
        val lastProcessed = getFfprobeLastProcessedFromJson(metadata.responseJson)
        if (lastProcessed != null) {
            val timeSinceLastProcess = Duration.between(lastProcessed, now)
            if (timeSinceLastProcess.toHours() < 1) {
                logger.debug("Movie metadata for ${metadata.movieId} was processed ${timeSinceLastProcess.toMinutes()} minutes ago, skipping FFprobe processing (will retry after 1 hour)")
                return false
            }
        }
        
        // Check if file size already exists in cached metadata to avoid unnecessary external fetch
        // (Note: existingFileSize was already extracted above, but we need to check it again here for the file size check)
        val fileSizeFromTags = videoInfo?.tags?.let { tags ->
            val sizeKeys = listOf("NUMBER_OF_BYTES-eng", "NUMBER_OF_BYTES", "NUMBER_OF_BYTES-ENG")
            sizeKeys.firstOrNull { tags.containsKey(it) }?.let { key ->
                tags[key]?.toLongOrNull()
            }
        }
        
        if (fileSizeFromTags != null && fileSizeFromTags > 0) {
            // Check if it's not the default estimated size (2GB for movies)
            val isDefaultSize = fileSizeFromTags == 2_000_000_000L
            if (!isDefaultSize) {
                logger.debug("File size already exists in cached metadata for movie ${metadata.movieId}: ${fileSizeFromTags / 1_000_000}MB, will reuse and skip external fetch during FFprobe extraction")
            } else {
                logger.debug("File size in cached metadata for movie ${metadata.movieId} is default estimate (${fileSizeFromTags / 1_000_000}MB), will fetch actual size externally during FFprobe extraction")
            }
        }
        
        // Try to extract extension from content entity URL if available
        val movieExtension = iptvContentRepository.findByProviderNameAndContentId(metadata.providerName, metadata.movieId)
            ?.let { contentEntity ->
                try {
                    // Resolve the tokenized URL to get the actual URL with extension
                    val resolvedUrl = iptvContentService.resolveIptvUrl(contentEntity.url, metadata.providerName)
                    IptvUrlBuilder.extractMediaExtensionFromUrl(resolvedUrl)
                } catch (e: Exception) {
                    logger.debug("Could not resolve URL to extract extension for movie ${metadata.movieId}: ${e.message}")
                    null
                }
            }
        
        // Build movie URL with extracted extension or default to mp4
        val movieUrl = IptvUrlBuilder.buildMovieUrl(providerConfig, metadata.movieId, movieExtension)
        if (movieUrl == null) {
            logger.debug("Could not build movie URL for enhancement")
            // Mark as processed even if URL build fails to prevent repeated attempts
            metadata.responseJson = updateFfprobeLastProcessedInJson(metadata.responseJson, now)
            return false
        }
        
        logger.debug("Extracting video metadata from movie URL: ${movieUrl.take(100)}")
        
        // Extract video metadata (always attempts to get file size, even if FFprobe fails)
        // Pass contentType and providerName so redirect resolution uses the same logic as fetchActualFileSize
        // Pass existing file size if available to avoid unnecessary external fetch
        val videoMetadata = videoMetadataExtractor.extractVideoMetadata(
            movieUrl, 
            ContentType.MOVIE, 
            providerConfig.name,
            existingFileSize = if (fileSizeFromTags != null && fileSizeFromTags > 0 && fileSizeFromTags != 2_000_000_000L) fileSizeFromTags else null,
            enhanceMetadata = enhanceMetadata
        )
        
        // Always update ffprobe_last_processed timestamp in JSON, even if extraction failed
        metadata.responseJson = updateFfprobeLastProcessedInJson(metadata.responseJson, now)
        
        if (videoMetadata == null) {
            logger.debug("Could not extract video metadata or file size from movie file")
            return false
        }
        
        // Update response_json if we have at least file size or video info
        if (videoMetadata.hasFileSize() || videoMetadata.hasVideoInfo()) {
            var updatedJson = updateMovieResponseJson(metadata.responseJson, videoMetadata)
            if (updatedJson != null) {
                // Ensure ffprobe_last_processed is preserved in the updated JSON
                updatedJson = updateFfprobeLastProcessedInJson(updatedJson, now)
                metadata.responseJson = updatedJson
                
                // Calculate resolution for logging
                val resolution = videoMetadata.width?.let { width ->
                    videoMetadata.height?.let { height ->
                        when {
                            width >= 1920 || height >= 1080 -> "1080p"
                            width >= 1280 || height >= 720 -> "720p"
                            else -> "480p"
                        }
                    }
                } ?: "unknown"
                
                // Log final values used for magnet title generation
                logger.debug("FFprobe processing complete for movie ${metadata.movieId} - Final values for magnet title: fileSize=${videoMetadata.fileSize?.let { "${it / 1_000_000}MB" } ?: "not available"}, resolution=$resolution, codec=${videoMetadata.codecName ?: "not available"}")
                
                if (videoMetadata.hasVideoInfo()) {
                    logger.info("Enhanced movie metadata with video info: width=${videoMetadata.width}, height=${videoMetadata.height}, codec=${videoMetadata.codecName}, fileSize=${videoMetadata.fileSize}")
                } else if (videoMetadata.hasFileSize()) {
                    logger.info("Enhanced movie metadata with file size: fileSize=${videoMetadata.fileSize} (video metadata extraction failed)")
                }
                return true
            }
        }
        
        return false
    }
    
    /**
     * Enhances series metadata by extracting video information from a reference episode.
     * Updates the response_json in the database if enhancement is successful.
     */
    suspend fun enhanceSeriesMetadata(
        metadata: IptvSeriesMetadataEntity,
        episodes: List<XtreamCodesClient.XtreamSeriesEpisode>,
        providerConfig: IptvProviderConfiguration,
        enhanceMetadata: Boolean = false
    ): Boolean {
        if (!enhanceMetadata) {
            return false
        }
        
        // Find reference episode (S01E01)
        val referenceEpisode = episodes.find { it.season == 1 && it.episode == 1 }
        if (referenceEpisode == null) {
            logger.debug("Reference episode S01E01 not found, skipping FFprobe extraction for series ${metadata.seriesId}")
            return false
        }
        
        // Check if video info is missing or empty
        val videoInfo = referenceEpisode.info?.video
        val existingWidth = videoInfo?.width
        val existingHeight = videoInfo?.height
        val existingCodec = videoInfo?.codec_name
        val existingFileSize = videoInfo?.tags?.let { tags ->
            val sizeKeys = listOf("NUMBER_OF_BYTES-eng", "NUMBER_OF_BYTES", "NUMBER_OF_BYTES-ENG")
            sizeKeys.firstOrNull { tags.containsKey(it) }?.let { key ->
                tags[key]?.toLongOrNull()
            }
        }
        
        val needsEnhancement = videoInfo == null || 
            (existingWidth == null && existingHeight == null) ||
            existingWidth == 0 || existingHeight == 0
        
        if (!needsEnhancement) {
            val metadataDetails = buildString {
                append("width=${existingWidth ?: "null"}, height=${existingHeight ?: "null"}")
                existingCodec?.let { codec -> append(", codec=$codec") }
                existingFileSize?.let { size -> append(", fileSize=${size / 1_000_000}MB") }
            }
            logger.debug("Series metadata for ${metadata.seriesId} reference episode (S01E01) already has complete video info ($metadataDetails), skipping FFprobe extraction (enhanceMetadata=true but metadata already exists)")
            return false
        }
        
        // Check if we've processed this recently (within 1 hour) - read from JSON
        val now = Instant.now()
        val lastProcessed = getFfprobeLastProcessedFromJson(metadata.responseJson)
        if (lastProcessed != null) {
            val timeSinceLastProcess = Duration.between(lastProcessed, now)
            if (timeSinceLastProcess.toHours() < 1) {
                logger.debug("Series metadata for ${metadata.seriesId} was processed ${timeSinceLastProcess.toMinutes()} minutes ago, skipping FFprobe processing (will retry after 1 hour)")
                return false
            }
        }
        
        // Check if file size already exists in cached metadata to avoid unnecessary external fetch
        // (Note: existingFileSize was already extracted above, but we need to check it again here for the file size check)
        val fileSizeFromTags = videoInfo?.tags?.let { tags ->
            val sizeKeys = listOf("NUMBER_OF_BYTES-eng", "NUMBER_OF_BYTES", "NUMBER_OF_BYTES-ENG")
            sizeKeys.firstOrNull { tags.containsKey(it) }?.let { key ->
                tags[key]?.toLongOrNull()
            }
        }
        
        if (fileSizeFromTags != null && fileSizeFromTags > 0) {
            // Check if it's not the default estimated size (1GB for episodes)
            val isDefaultSize = fileSizeFromTags == 1_000_000_000L
            if (!isDefaultSize) {
                logger.debug("File size already exists in cached metadata for series ${metadata.seriesId} reference episode: ${fileSizeFromTags / 1_000_000}MB, will reuse and skip external fetch during FFprobe extraction")
            } else {
                logger.debug("File size in cached metadata for series ${metadata.seriesId} reference episode is default estimate (${fileSizeFromTags / 1_000_000}MB), will fetch actual size externally during FFprobe extraction")
            }
        }
        
        // Build episode URL
        val episodeUrl = IptvUrlBuilder.buildEpisodeUrl(providerConfig, referenceEpisode)
        if (episodeUrl == null) {
            logger.debug("Could not build episode URL for enhancement")
            // Mark as processed even if URL build fails to prevent repeated attempts
            metadata.responseJson = updateFfprobeLastProcessedInJson(metadata.responseJson, now)
            return false
        }
        
        logger.debug("Extracting video metadata from reference episode URL: ${episodeUrl.take(100)}")
        
        // Extract video metadata (always attempts to get file size, even if FFprobe fails)
        // Pass contentType and providerName so redirect resolution uses the same logic as fetchActualFileSize
        // Pass existing file size if available to avoid unnecessary external fetch
        val videoMetadata = videoMetadataExtractor.extractVideoMetadata(
            episodeUrl, 
            ContentType.SERIES, 
            providerConfig.name,
            existingFileSize = if (fileSizeFromTags != null && fileSizeFromTags > 0 && fileSizeFromTags != 1_000_000_000L) fileSizeFromTags else null,
            enhanceMetadata = enhanceMetadata
        )
        
        // Always update ffprobe_last_processed timestamp in JSON, even if extraction failed
        metadata.responseJson = updateFfprobeLastProcessedInJson(metadata.responseJson, now)
        
        if (videoMetadata == null) {
            logger.debug("Could not extract video metadata or file size from episode file")
            return false
        }
        
        // Update response_json if we have at least file size or video info
        if (videoMetadata.hasFileSize() || videoMetadata.hasVideoInfo()) {
            var updatedJson = updateSeriesResponseJson(metadata.responseJson, referenceEpisode.id, videoMetadata)
            if (updatedJson != null) {
                // Ensure ffprobe_last_processed is preserved in the updated JSON
                updatedJson = updateFfprobeLastProcessedInJson(updatedJson, now)
                metadata.responseJson = updatedJson
                
                // Calculate resolution for logging
                val resolution = videoMetadata.width?.let { width ->
                    videoMetadata.height?.let { height ->
                        when {
                            width >= 1920 || height >= 1080 -> "1080p"
                            width >= 1280 || height >= 720 -> "720p"
                            else -> "480p"
                        }
                    }
                } ?: "unknown"
                
                // Log final values used for magnet title generation
                logger.debug("FFprobe processing complete for series ${metadata.seriesId} (S01E01) - Final values for magnet title: fileSize=${videoMetadata.fileSize?.let { "${it / 1_000_000}MB" } ?: "not available"}, resolution=$resolution, codec=${videoMetadata.codecName ?: "not available"}")
                
                if (videoMetadata.hasVideoInfo()) {
                    logger.info("Enhanced series metadata with video info from S01E01: width=${videoMetadata.width}, height=${videoMetadata.height}, codec=${videoMetadata.codecName}, fileSize=${videoMetadata.fileSize}")
                } else if (videoMetadata.hasFileSize()) {
                    logger.info("Enhanced series metadata with file size from S01E01: fileSize=${videoMetadata.fileSize} (video metadata extraction failed)")
                }
                return true
            }
        }
        
        return false
    }
    
    /**
     * Updates movie response JSON with enhanced video metadata.
     */
    private fun updateMovieResponseJson(
        responseJson: String,
        videoMetadata: VideoMetadataExtractor.VideoMetadata
    ): String? {
        return try {
            val jsonObject = json.parseToJsonElement(responseJson).jsonObject
            
            // Handle different JSON formats:
            // Format 1: { "movie_data": { "info": { "video": {...} } } }
            // Format 2: { "info": { "video": {...} } }
            // Format 3: Direct { "info": { "video": {...} } }
            
            val updatedJson = when {
                jsonObject.containsKey("movie_data") -> {
                    // Format 1: wrapped in movie_data
                    val movieData = jsonObject["movie_data"]?.jsonObject
                    if (movieData != null) {
                        val updatedMovieData = updateVideoInfoInObject(movieData, videoMetadata)
                        buildJsonObject {
                            putJsonObject("movie_data") {
                                updatedMovieData.forEach { (key, value) ->
                                    put(key, value)
                                }
                            }
                            // Copy other top-level fields
                            jsonObject.forEach { (key, value) ->
                                if (key != "movie_data") {
                                    put(key, value)
                                }
                            }
                        }
                    } else {
                        jsonObject
                    }
                }
                jsonObject.containsKey("info") -> {
                    // Format 2 or 3: info at top level
                    updateVideoInfoInObject(jsonObject, videoMetadata)
                }
                else -> {
                    // Try to find info nested somewhere
                    updateVideoInfoInObject(jsonObject, videoMetadata)
                }
            }
            
            json.encodeToString(JsonObject.serializer(), updatedJson)
        } catch (e: Exception) {
            logger.warn("Failed to update movie response JSON: ${e.message}", e)
            null
        }
    }
    
    /**
     * Updates series response JSON with enhanced video metadata for a specific episode.
     * Series JSON structure: { "info": {...}, "episodes": { "1": [{ "id": "...", "info": {...} }, ...], "2": [...] } }
     */
    private fun updateSeriesResponseJson(
        responseJson: String,
        episodeId: String,
        videoMetadata: VideoMetadataExtractor.VideoMetadata
    ): String? {
        return try {
            // Parse using the same structure as XtreamCodesClient
            val jsonObject = json.parseToJsonElement(responseJson).jsonObject
            
            val episodesObj = jsonObject["episodes"]?.jsonObject
            if (episodesObj == null) {
                logger.debug("No episodes object found in series JSON")
                return null
            }
            
            // Find and update the episode in any season
            var found = false
            val updatedEpisodesMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            
            episodesObj.forEach { (seasonKey, episodesElement) ->
                // EpisodesElement should be a JSON array
                try {
                    // Parse as array of episode objects
                    val episodesJsonArray = json.parseToJsonElement(episodesElement.toString())
                    if (episodesJsonArray is kotlinx.serialization.json.JsonArray) {
                        val updatedEpisodes = episodesJsonArray.map { episodeElement ->
                            val episodeObj = episodeElement.jsonObject
                            val epId = episodeObj["id"]?.jsonPrimitive?.content
                            
                            if (epId == episodeId) {
                                found = true
                                // Update this episode's video info
                                updateEpisodeVideoInfo(episodeObj, videoMetadata)
                            } else {
                                episodeObj
                            }
                        }
                        
                        // Rebuild JSON array
                        val updatedArray = buildJsonArray {
                            updatedEpisodes.forEach { episodeObj ->
                                add(buildJsonObject {
                                    episodeObj.forEach { (key, value) ->
                                        put(key, value)
                                    }
                                })
                            }
                        }
                        updatedEpisodesMap[seasonKey] = updatedArray
                    } else {
                        // Not an array, keep original
                        updatedEpisodesMap[seasonKey] = episodesElement
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to parse episodes for season $seasonKey: ${e.message}")
                    // Keep original if parsing fails
                    updatedEpisodesMap[seasonKey] = episodesElement
                }
            }
            
            if (!found) {
                logger.debug("Episode $episodeId not found in series JSON")
                return null
            }
            
            // Rebuild JSON with updated episodes
            val updatedJson = buildJsonObject {
                jsonObject.forEach { (key, value) ->
                    if (key == "episodes") {
                        putJsonObject("episodes") {
                            updatedEpisodesMap.forEach { (seasonKey, episodes) ->
                                put(seasonKey, episodes)
                            }
                        }
                    } else {
                        put(key, value)
                    }
                }
            }
            
            json.encodeToString(JsonObject.serializer(), updatedJson)
        } catch (e: Exception) {
            logger.warn("Failed to update series response JSON: ${e.message}", e)
            null
        }
    }
    
    /**
     * Updates video info in an episode JSON object.
     */
    private fun updateEpisodeVideoInfo(
        episodeObj: JsonObject,
        videoMetadata: VideoMetadataExtractor.VideoMetadata
    ): JsonObject {
        val infoObj = episodeObj["info"]?.jsonObject
        val updatedVideoObj = buildJsonObject {
            videoMetadata.width?.let { put("width", it) }
            videoMetadata.height?.let { put("height", it) }
            videoMetadata.codecName?.let { put("codec_name", it) }
            videoMetadata.fileSize?.let { fileSize ->
                putJsonObject("tags") {
                    put("NUMBER_OF_BYTES", fileSize.toString())
                }
            }
        }
        
        val updatedInfoObj = if (infoObj == null) {
            buildJsonObject {
                putJsonObject("video") {
                    updatedVideoObj.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            }
        } else {
            buildJsonObject {
                infoObj.forEach { (key, value) ->
                    if (key == "video") {
                        putJsonObject("video") {
                            updatedVideoObj.forEach { (k, v) ->
                                put(k, v)
                            }
                        }
                    } else {
                        put(key, value)
                    }
                }
                if (!infoObj.containsKey("video")) {
                    putJsonObject("video") {
                        updatedVideoObj.forEach { (key, value) ->
                            put(key, value)
                        }
                    }
                }
            }
        }
        
        return buildJsonObject {
            episodeObj.forEach { (key, value) ->
                if (key == "info") {
                    putJsonObject("info") {
                        updatedInfoObj.forEach { (k, v) ->
                            put(k, v)
                        }
                    }
                } else {
                    put(key, value)
                }
            }
        }
    }
    
    /**
     * Updates video info in a JSON object (for movies).
     */
    private fun updateVideoInfoInObject(
        jsonObject: JsonObject,
        videoMetadata: VideoMetadataExtractor.VideoMetadata
    ): JsonObject {
        val infoObj = jsonObject["info"]?.jsonObject
        if (infoObj == null) {
            // No info object, create one
            return buildJsonObject {
                jsonObject.forEach { (key, value) ->
                    put(key, value)
                }
                putJsonObject("info") {
                    putJsonObject("video") {
                        videoMetadata.width?.let { put("width", it) }
                        videoMetadata.height?.let { put("height", it) }
                        videoMetadata.codecName?.let { put("codec_name", it) }
                        // Add file size to tags if available
                        videoMetadata.fileSize?.let { fileSize ->
                            putJsonObject("tags") {
                                put("NUMBER_OF_BYTES", fileSize.toString())
                            }
                        }
                    }
                }
            }
        }
        
        // Update existing info object
        val videoObj = infoObj["video"]?.jsonObject
        val updatedVideoObj = if (videoObj == null) {
            // Create new video object
            buildJsonObject {
                videoMetadata.width?.let { put("width", it) }
                videoMetadata.height?.let { put("height", it) }
                videoMetadata.codecName?.let { put("codec_name", it) }
                videoMetadata.fileSize?.let { fileSize ->
                    putJsonObject("tags") {
                        put("NUMBER_OF_BYTES", fileSize.toString())
                    }
                }
            }
        } else {
            // Update existing video object
            buildJsonObject {
                // Preserve existing fields
                videoObj.forEach { (key, value) ->
                    put(key, value)
                }
                // Override with new values
                videoMetadata.width?.let { put("width", it) }
                videoMetadata.height?.let { put("height", it) }
                videoMetadata.codecName?.let { put("codec_name", it) }
                // Update tags
                val existingTags = videoObj["tags"]?.jsonObject
                val updatedTags = buildJsonObject {
                    existingTags?.forEach { (key, value) ->
                        put(key, value)
                    }
                    videoMetadata.fileSize?.let { fileSize ->
                        put("NUMBER_OF_BYTES", fileSize.toString())
                    }
                }
                putJsonObject("tags") {
                    updatedTags.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            }
        }
        
        // Rebuild info object
        val updatedInfoObj = buildJsonObject {
            infoObj.forEach { (key, value) ->
                if (key == "video") {
                    putJsonObject("video") {
                        updatedVideoObj.forEach { (k, v) ->
                            put(k, v)
                        }
                    }
                } else {
                    put(key, value)
                }
            }
            // Ensure video is set
            if (!infoObj.containsKey("video")) {
                putJsonObject("video") {
                    updatedVideoObj.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            }
        }
        
        // Rebuild top-level object
        return buildJsonObject {
            jsonObject.forEach { (key, value) ->
                if (key == "info") {
                    putJsonObject("info") {
                        updatedInfoObj.forEach { (k, v) ->
                            put(k, v)
                        }
                    }
                } else {
                    put(key, value)
                }
            }
        }
    }
    
}

