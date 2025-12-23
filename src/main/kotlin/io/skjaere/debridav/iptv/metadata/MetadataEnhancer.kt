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

@Service
class MetadataEnhancer(
    private val videoMetadataExtractor: VideoMetadataExtractor
) {
    private val logger = LoggerFactory.getLogger(MetadataEnhancer::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    /**
     * Enhances movie metadata by extracting video information from the media file.
     * Updates the response_json in the database if enhancement is successful.
     */
    suspend fun enhanceMovieMetadata(
        metadata: IptvMovieMetadataEntity,
        movieInfo: XtreamCodesClient.MovieInfo,
        providerConfig: IptvProviderConfiguration
    ): Boolean {
        // Check if video info is missing or empty
        val videoInfo = movieInfo.info?.video
        val needsEnhancement = videoInfo == null || 
            (videoInfo.width == null && videoInfo.height == null) ||
            videoInfo.width == 0 || videoInfo.height == 0
        
        if (!needsEnhancement) {
            logger.debug("Movie metadata already has video info, skipping enhancement")
            return false
        }
        
        // Build movie URL
        val movieUrl = buildMovieUrl(providerConfig, metadata.movieId, movieInfo)
        if (movieUrl == null) {
            logger.debug("Could not build movie URL for enhancement")
            return false
        }
        
        logger.debug("Extracting video metadata from movie URL: ${movieUrl.take(100)}")
        
        // Extract video metadata (always attempts to get file size, even if FFprobe fails)
        val videoMetadata = videoMetadataExtractor.extractVideoMetadata(movieUrl)
        if (videoMetadata == null) {
            logger.debug("Could not extract video metadata or file size from movie file")
            return false
        }
        
        // Update response_json if we have at least file size or video info
        if (videoMetadata.hasFileSize() || videoMetadata.hasVideoInfo()) {
            val updatedJson = updateMovieResponseJson(metadata.responseJson, videoMetadata)
            if (updatedJson != null) {
                metadata.responseJson = updatedJson
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
        providerConfig: IptvProviderConfiguration
    ): Boolean {
        // Find reference episode (S01E01)
        val referenceEpisode = episodes.find { it.season == 1 && it.episode == 1 }
        if (referenceEpisode == null) {
            logger.debug("Reference episode S01E01 not found, skipping enhancement")
            return false
        }
        
        // Check if video info is missing or empty
        val videoInfo = referenceEpisode.info?.video
        val needsEnhancement = videoInfo == null || 
            (videoInfo.width == null && videoInfo.height == null) ||
            videoInfo.width == 0 || videoInfo.height == 0
        
        if (!needsEnhancement) {
            logger.debug("Reference episode already has video info, skipping enhancement")
            return false
        }
        
        // Build episode URL
        val episodeUrl = buildEpisodeUrl(providerConfig, referenceEpisode)
        if (episodeUrl == null) {
            logger.debug("Could not build episode URL for enhancement")
            return false
        }
        
        logger.debug("Extracting video metadata from reference episode URL: ${episodeUrl.take(100)}")
        
        // Extract video metadata (always attempts to get file size, even if FFprobe fails)
        val videoMetadata = videoMetadataExtractor.extractVideoMetadata(episodeUrl)
        if (videoMetadata == null) {
            logger.debug("Could not extract video metadata or file size from episode file")
            return false
        }
        
        // Update response_json if we have at least file size or video info
        if (videoMetadata.hasFileSize() || videoMetadata.hasVideoInfo()) {
            val updatedJson = updateSeriesResponseJson(metadata.responseJson, referenceEpisode.id, videoMetadata)
            if (updatedJson != null) {
                metadata.responseJson = updatedJson
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
     * Builds movie URL for Xtream Codes provider.
     * Format: {baseUrl}/movie/{username}/{password}/{vodId}.{extension}
     */
    private fun buildMovieUrl(
        providerConfig: IptvProviderConfiguration,
        vodId: String,
        movieInfo: XtreamCodesClient.MovieInfo
    ): String? {
        val baseUrl = providerConfig.xtreamBaseUrl ?: return null
        val username = providerConfig.xtreamUsername ?: return null
        val password = providerConfig.xtreamPassword ?: return null
        
        // Try to determine extension from container_extension or default to mp4
        // Note: MovieInfo doesn't have container_extension, so we default to mp4
        val extension = "mp4"
        
        return "$baseUrl/movie/$username/$password/$vodId.$extension"
    }
    
    /**
     * Builds episode URL for Xtream Codes provider.
     * Format: {baseUrl}/series/{username}/{password}/{episode_id}.{extension}
     */
    private fun buildEpisodeUrl(
        providerConfig: IptvProviderConfiguration,
        episode: XtreamCodesClient.XtreamSeriesEpisode
    ): String? {
        val baseUrl = providerConfig.xtreamBaseUrl ?: return null
        val username = providerConfig.xtreamUsername ?: return null
        val password = providerConfig.xtreamPassword ?: return null
        val extension = episode.container_extension ?: "mp4"
        return "$baseUrl/series/$username/$password/${episode.id}.$extension"
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

