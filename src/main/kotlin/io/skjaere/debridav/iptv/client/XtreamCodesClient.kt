package io.skjaere.debridav.iptv.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.iptv.model.EpisodeInfo
import io.skjaere.debridav.iptv.model.IptvContentItem
import io.skjaere.debridav.iptv.util.IptvResponseFileService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder

class XtreamCodesClient(
    private val httpClient: HttpClient,
    private val responseFileService: IptvResponseFileService
) {
    private val logger = LoggerFactory.getLogger(XtreamCodesClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getVodContent(providerConfig: IptvProviderConfiguration): List<IptvContentItem> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // Get VOD streams
            val vodStreamsUrl = "$baseUrl/player_api.php"
            val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
            
            val responseBody = if (useLocal) {
                logger.info("Using local response file for VOD streams from provider ${providerConfig.name}")
                responseFileService.loadResponse(providerConfig, "vod_streams") 
                    ?: run {
                        logger.warn("Local response file not found for VOD streams, falling back to HTTP request")
                        null
                    }
            } else {
                null
            }
            
            val finalVodStreamsBody = responseBody ?: run {
                val vodStreamsRequestUrl = "$vodStreamsUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***&action=get_vod_streams"
                logger.debug("Fetching VOD streams from Xtream Codes provider ${providerConfig.name}: $vodStreamsRequestUrl")
                
                val response: HttpResponse = httpClient.get(vodStreamsUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_vod_streams")
                }
                
                logger.debug("Received response status ${response.status} for VOD streams request")
                
                if (response.status != HttpStatusCode.OK) {
                    logger.error("Failed to fetch VOD streams from Xtream Codes: ${response.status}")
                    return emptyList()
                }
                
                val body = response.body<String>()
                
                // Save response if configured
                if (responseFileService.shouldSaveResponses()) {
                    responseFileService.saveResponse(providerConfig, "vod_streams", body)
                }
                
                body
            }
            
            logger.debug("Parsing VOD streams response (length: ${finalVodStreamsBody.length} characters)")
            val vodStreams: List<XtreamVodStream> = json.decodeFromString(finalVodStreamsBody)
            logger.debug("Successfully parsed ${vodStreams.size} VOD streams")
            
            // Get VOD categories for categorization
            val categoriesBody = if (useLocal) {
                logger.info("Using local response file for VOD categories from provider ${providerConfig.name}")
                responseFileService.loadResponse(providerConfig, "vod_categories")
                    ?: run {
                        logger.warn("Local response file not found for VOD categories, falling back to HTTP request")
                        null
                    }
            } else {
                null
            }
            
            val categories: List<XtreamCategory> = if (categoriesBody != null) {
                logger.debug("Parsing VOD categories response (length: ${categoriesBody.length} characters)")
                val parsedCategories = json.decodeFromString<List<XtreamCategory>>(categoriesBody)
                logger.debug("Successfully parsed ${parsedCategories.size} VOD categories")
                parsedCategories
            } else {
                val categoriesRequestUrl = "$vodStreamsUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***&action=get_vod_categories"
                logger.debug("Fetching VOD categories from Xtream Codes provider ${providerConfig.name}: $categoriesRequestUrl")
                
                val categoriesResponse: HttpResponse = httpClient.get(vodStreamsUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_vod_categories")
                }
                
                logger.debug("Received response status ${categoriesResponse.status} for VOD categories request")
                
                if (categoriesResponse.status == HttpStatusCode.OK) {
                    val body = categoriesResponse.body<String>()
                    
                    // Save response if configured
                    if (responseFileService.shouldSaveResponses()) {
                        responseFileService.saveResponse(providerConfig, "vod_categories", body)
                    }
                    
                    logger.debug("Parsing VOD categories response (length: ${body.length} characters)")
                    val parsedCategories = json.decodeFromString<List<XtreamCategory>>(body)
                    logger.debug("Successfully parsed ${parsedCategories.size} VOD categories")
                    parsedCategories
                } else {
                    logger.warn("Failed to fetch VOD categories: ${categoriesResponse.status}")
                    emptyList()
                }
            }
            
            // Convert category_id from Int to String for matching (API returns category_id as String in some cases)
            val categoryMap = categories.associateBy { it.category_id.toString() }
            
            return vodStreams.mapNotNull { stream ->
                // Handle category_id - it may be a String in the JSON response
                val categoryIdStr = stream.category_id
                val category = if (categoryIdStr != null) {
                    categoryMap[categoryIdStr]?.category_name ?: "Unknown"
                } else {
                    "Unknown"
                }
                
                // Determine content type from stream_type field (more reliable than category name)
                val contentType = when (stream.stream_type?.lowercase()) {
                    "movie" -> ContentType.MOVIE
                    "series" -> ContentType.SERIES
                    else -> {
                        // Fallback to category name if stream_type is not available
                        if (category.contains("Movie", ignoreCase = true) ||
                            category.contains("Film", ignoreCase = true)) {
                            ContentType.MOVIE
                        } else {
                            ContentType.SERIES
                        }
                    }
                }
                
                // Parse series info if it's a series
                val episodeInfo = if (contentType == ContentType.SERIES) {
                    parseSeriesInfo(stream.name)
                } else {
                    null
                }
                
                // Construct stream URL from available fields
                // Xtream Codes format: {baseUrl}/{stream_type}/{username}/{password}/{stream_id}.{extension}
                val extension = stream.container_extension ?: "mp4"
                val streamTypePath = when (contentType) {
                    ContentType.MOVIE -> "movie"
                    ContentType.SERIES -> "series"
                }
                val streamUrl = "$baseUrl/$streamTypePath/$username/$password/${stream.stream_id}.$extension"
                
                // Tokenize URL
                val tokenizedUrl = tokenizeUrl(streamUrl, providerConfig)
                
                IptvContentItem(
                    id = stream.stream_id.toString(),
                    title = stream.name,
                    url = tokenizedUrl,
                    category = category,
                    type = contentType,
                    episodeInfo = episodeInfo
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching VOD content from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }
    
    private fun parseSeriesInfo(title: String): EpisodeInfo? {
        // Try to parse series info from title
        // Pattern: Series Name S01E01 or Series Name - S01E01
        val pattern = Regex("""(.+?)\s*[-]?\s*[Ss](\d+)[Ee](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(title)
        
        return match?.let {
            EpisodeInfo(
                seriesName = it.groupValues[1].trim(),
                season = it.groupValues[2].toIntOrNull(),
                episode = it.groupValues[3].toIntOrNull()
            )
        }
    }
    
    private fun tokenizeUrl(url: String, providerConfig: IptvProviderConfiguration): String {
        var tokenized = url
        
        // Replace base URL with placeholder
        providerConfig.xtreamBaseUrl?.let { baseUrl ->
            tokenized = tokenized.replace(baseUrl, "{BASE_URL}")
        }
        
        // Replace username/password in URL if present
        providerConfig.xtreamUsername?.let { username ->
            tokenized = tokenized.replace(username, "{USERNAME}")
        }
        providerConfig.xtreamPassword?.let { password ->
            tokenized = tokenized.replace(password, "{PASSWORD}")
        }
        
        // Also handle URL parameters
        tokenized = tokenized.replace(Regex("[?&]username=([^&]+)"), "?username={USERNAME}")
        tokenized = tokenized.replace(Regex("[?&]password=([^&]+)"), "&password={PASSWORD}")
        
        return tokenized
    }
    
    @Serializable
    private data class XtreamVodStream(
        val num: Int? = null,
        val name: String,
        val stream_type: String? = null,
        val stream_id: Int,
        val stream_icon: String? = null,
        val rating: String? = null,
        val rating_5based: Double? = null,
        val added: String? = null,
        val is_adult: Int? = null,
        val category_id: String? = null,
        val container_extension: String? = null,
        val custom_sid: String? = null,
        val direct_source: String? = null
    )
    
    @Serializable
    private data class XtreamCategory(
        val category_id: Int,
        val category_name: String
    )
}

