package io.skjaere.debridav.iptv.metadata

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MetadataService(
    private val httpClient: HttpClient,
    private val metadataConfigurationProperties: MetadataConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(MetadataService::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetches metadata for a movie or TV show by IMDB ID
     * @param imdbId The IMDB ID (e.g., "tt12584954")
     * @return Metadata containing title and year, or null if not found or error occurred
     */
    suspend fun getMetadataByImdbId(imdbId: String): MediaMetadata? {
        if (metadataConfigurationProperties.omdbApiKey.isBlank()) {
            logger.debug("OMDB API key not configured, skipping metadata lookup for IMDB ID: $imdbId")
            return null
        }

        val apiKey = metadataConfigurationProperties.omdbApiKey
        val baseUrl = metadataConfigurationProperties.omdbBaseUrl

        return try {
            val url = "$baseUrl/?apikey=$apiKey&i=$imdbId"
            logger.debug("Fetching metadata from OMDB API for IMDB ID: $imdbId")
            
            val response: HttpResponse = httpClient.get(url)
            
            if (response.status == HttpStatusCode.OK) {
                // Parse JSON manually since OMDB uses capital field names
                val jsonElement: JsonElement = response.body()
                val jsonObject = jsonElement.jsonObject
                
                val responseValue = jsonObject["Response"]?.jsonPrimitive?.content
                val title = jsonObject["Title"]?.jsonPrimitive?.content
                val year = jsonObject["Year"]?.jsonPrimitive?.content
                val error = jsonObject["Error"]?.jsonPrimitive?.content
                
                if (responseValue == "True" && title != null) {
                    logger.debug("Successfully fetched metadata: title='$title', year='$year'")
                    MediaMetadata(
                        title = title,
                        year = year?.toIntOrNull(),
                        imdbId = imdbId
                    )
                } else {
                    logger.warn("OMDB API returned False response for IMDB ID: $imdbId, error: $error")
                    null
                }
            } else {
                logger.warn("OMDB API returned status ${response.status} for IMDB ID: $imdbId")
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch metadata from OMDB API for IMDB ID: $imdbId", e)
            null
        }
    }

    data class MediaMetadata(
        val title: String,
        val year: Int?,
        val imdbId: String
    )
}
