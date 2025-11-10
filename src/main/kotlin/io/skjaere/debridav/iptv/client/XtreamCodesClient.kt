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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder

class XtreamCodesClient(
    private val httpClient: HttpClient
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
            val response: HttpResponse = httpClient.get(vodStreamsUrl) {
                parameter("username", username)
                parameter("password", password)
                parameter("action", "get_vod_streams")
            }
            
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch VOD streams from Xtream Codes: ${response.status}")
                return emptyList()
            }
            
            val vodStreams: List<XtreamVodStream> = json.decodeFromString(response.body())
            
            // Get VOD categories for categorization
            val categoriesResponse: HttpResponse = httpClient.get(vodStreamsUrl) {
                parameter("username", username)
                parameter("password", password)
                parameter("action", "get_vod_categories")
            }
            
            val categories: List<XtreamCategory> = if (categoriesResponse.status == HttpStatusCode.OK) {
                json.decodeFromString(categoriesResponse.body())
            } else {
                emptyList()
            }
            
            val categoryMap = categories.associateBy { it.category_id }
            
            return vodStreams.mapNotNull { stream ->
                val category = categoryMap[stream.category_id]?.category_name ?: "Unknown"
                
                // Determine content type - Xtream Codes typically has separate endpoints for movies and series
                // For now, we'll use category name to determine type
                val contentType = if (category.contains("Movie", ignoreCase = true) ||
                                     category.contains("Film", ignoreCase = true)) {
                    ContentType.MOVIE
                } else {
                    ContentType.SERIES
                }
                
                // Parse series info if it's a series
                val episodeInfo = if (contentType == ContentType.SERIES) {
                    parseSeriesInfo(stream.name)
                } else {
                    null
                }
                
                // Tokenize URL
                val tokenizedUrl = tokenizeUrl(stream.stream_url, providerConfig)
                
                IptvContentItem(
                    id = stream.stream_id,
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
        val stream_id: String,
        val name: String,
        val stream_icon: String? = null,
        val stream_type: String? = null,
        val stream_url: String,
        val category_id: String? = null
    )
    
    @Serializable
    private data class XtreamCategory(
        val category_id: String,
        val category_name: String
    )
}

