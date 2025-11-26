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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder

class XtreamCodesClient(
    private val httpClient: HttpClient,
    private val responseFileService: IptvResponseFileService
) {
    private val logger = LoggerFactory.getLogger(XtreamCodesClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies that the Xtream Codes account is active by calling the player_api.php endpoint
     * without an action parameter (or with get_user_info action).
     * Returns true if the account is active, false otherwise.
     */
    suspend fun verifyAccount(providerConfig: IptvProviderConfiguration): Boolean {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return false
        val username = providerConfig.xtreamUsername ?: return false
        val password = providerConfig.xtreamPassword ?: return false
        
        try {
            val apiUrl = "$baseUrl/player_api.php"
            val requestUrl = "$apiUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***"
            logger.debug("Verifying Xtream Codes account for provider ${providerConfig.name}: $requestUrl")
            
            val response: HttpResponse = httpClient.get(apiUrl) {
                parameter("username", username)
                parameter("password", password)
            }
            
            logger.debug("Received response status ${response.status} for account verification")
            
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<String>()
                // The response should contain user info if account is active
                // Xtream Codes API can return user_info directly or wrapped in an object
                try {
                    // Try parsing as wrapped format first
                    val userInfo = json.decodeFromString<XtreamUserInfo>(body)
                    val isActive = userInfo.user_info?.status == "Active" || userInfo.user_info != null
                    if (isActive) {
                        logger.info("Account verified as active for provider ${providerConfig.name}")
                    } else {
                        logger.warn("Account status is not active for provider ${providerConfig.name}")
                    }
                    return isActive
                } catch (e: Exception) {
                    // Try parsing as direct format (some providers return user_info directly)
                    try {
                        val directUserInfo = json.decodeFromString<XtreamUserInfoDetails>(body)
                        val isActive = directUserInfo.status == "Active" || directUserInfo.auth == 1
                        if (isActive) {
                            logger.info("Account verified as active for provider ${providerConfig.name}")
                        } else {
                            logger.warn("Account status is not active for provider ${providerConfig.name}")
                        }
                        return isActive
                    } catch (e2: Exception) {
                        // If parsing fails, check if response contains error
                        if (body.contains("error", ignoreCase = true) || body.contains("invalid", ignoreCase = true)) {
                            logger.error("Account verification failed for provider ${providerConfig.name}: $body")
                            return false
                        }
                        // If it's not an error and is valid JSON, assume account is active
                        // (some providers return different formats)
                        logger.debug("Could not parse user info, but response indicates account may be active")
                        return true
                    }
                }
            } else {
                logger.error("Failed to verify account for provider ${providerConfig.name}: ${response.status}")
                return false
            }
        } catch (e: Exception) {
            logger.error("Error verifying account for Xtream Codes provider ${providerConfig.name}", e)
            return false
        }
    }

    /**
     * Gets VOD streams from the Xtream Codes provider
     * Returns a list of XtreamVodStream objects
     * @param preFetchedBody Optional pre-fetched response body to use instead of fetching
     */
    private suspend fun getVodStreams(providerConfig: IptvProviderConfiguration, preFetchedBody: String? = null): List<XtreamVodStream> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // If pre-fetched body is provided, use it directly
            if (preFetchedBody != null) {
                logger.debug("Using pre-fetched VOD streams response (length: ${preFetchedBody.length} characters)")
                val vodStreams: List<XtreamVodStream> = json.decodeFromString(preFetchedBody)
                logger.debug("Successfully parsed ${vodStreams.size} VOD streams")
                return vodStreams
            }
            
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
                // Make an initial login/test call to the provider before fetching VOD streams
                try {
                    logger.debug("Making initial login call to IPTV provider ${providerConfig.name} before fetching VOD streams")
                    val loginSuccess = verifyAccount(providerConfig)
                    if (!loginSuccess) {
                        logger.warn("IPTV provider login verification failed for ${providerConfig.name}, but continuing with VOD streams fetch")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to make initial login call to IPTV provider ${providerConfig.name} before fetching VOD streams: ${e.message}, continuing with fetch attempt", e)
                }
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
            
            return vodStreams
        } catch (e: Exception) {
            logger.error("Error fetching VOD streams from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }

    /**
     * Gets VOD categories from the Xtream Codes provider
     * Returns a list of XtreamCategory objects
     * @param preFetchedBody Optional pre-fetched response body to use instead of fetching
     */
    private suspend fun getVodCategoriesInternal(providerConfig: IptvProviderConfiguration, preFetchedBody: String? = null): List<XtreamCategory> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // If pre-fetched body is provided, use it directly
            if (preFetchedBody != null) {
                logger.debug("Using pre-fetched VOD categories response (length: ${preFetchedBody.length} characters)")
                val parsedCategories = json.decodeFromString<List<XtreamCategory>>(preFetchedBody)
                logger.debug("Successfully parsed ${parsedCategories.size} VOD categories")
                return parsedCategories
            }
            
            val apiUrl = "$baseUrl/player_api.php"
            val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
            
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
                val categoriesRequestUrl = "$apiUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***&action=get_vod_categories"
                logger.debug("Fetching VOD categories from Xtream Codes provider ${providerConfig.name}: $categoriesRequestUrl")
                
                val categoriesResponse: HttpResponse = httpClient.get(apiUrl) {
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
            
            return categories
        } catch (e: Exception) {
            logger.error("Error fetching VOD categories from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }

    suspend fun getVodContent(providerConfig: IptvProviderConfiguration, categories: List<XtreamCategory>? = null, preFetchedStreamsBody: String? = null): List<IptvContentItem> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // Get VOD streams (use pre-fetched body if available)
            val vodStreams = getVodStreams(providerConfig, preFetchedStreamsBody)
            
            // Get VOD categories (use provided categories or fetch them)
            val vodCategories = categories ?: getVodCategoriesInternal(providerConfig)
            
            // Convert category_id from Int to String for matching (API returns category_id as String in some cases)
            val categoryMap = vodCategories.associateBy { it.category_id.toString() }
            
            return vodStreams.mapNotNull { stream ->
                // Handle category_id - it may be a String in the JSON response
                val categoryIdStr = stream.category_id
                
                // Determine content type from stream_type field (more reliable than category name)
                val contentType = when (stream.stream_type?.lowercase()) {
                    "movie" -> ContentType.MOVIE
                    "series" -> ContentType.SERIES
                    else -> {
                        // Fallback: check if category exists and infer from category name
                        val categoryName = categoryIdStr?.let { categoryMap[it]?.category_name }
                        if (categoryName != null && (categoryName.contains("Movie", ignoreCase = true) ||
                            categoryName.contains("Film", ignoreCase = true))) {
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
                    categoryId = categoryIdStr,
                    categoryType = "vod",
                    type = contentType,
                    episodeInfo = episodeInfo
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching VOD content from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }

    /**
     * Gets series streams from the Xtream Codes provider
     * Returns a list of XtreamSeriesStream objects
     * @param preFetchedBody Optional pre-fetched response body to use instead of fetching
     */
    private suspend fun getSeriesStreams(providerConfig: IptvProviderConfiguration, preFetchedBody: String? = null): List<XtreamSeriesStream> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // If pre-fetched body is provided, use it directly
            if (preFetchedBody != null) {
                logger.debug("Using pre-fetched series streams response (length: ${preFetchedBody.length} characters)")
                val seriesStreams: List<XtreamSeriesStream> = json.decodeFromString(preFetchedBody)
                logger.debug("Successfully parsed ${seriesStreams.size} series streams")
                return seriesStreams
            }
            
            val seriesStreamsUrl = "$baseUrl/player_api.php"
            val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
            
            val responseBody = if (useLocal) {
                logger.info("Using local response file for series streams from provider ${providerConfig.name}")
                responseFileService.loadResponse(providerConfig, "series_streams") 
                    ?: run {
                        logger.warn("Local response file not found for series streams, falling back to HTTP request")
                        null
                    }
            } else {
                null
            }
            
            val finalSeriesStreamsBody = responseBody ?: run {
                // Make an initial login/test call to the provider before fetching series streams
                try {
                    logger.debug("Making initial login call to IPTV provider ${providerConfig.name} before fetching series streams")
                    val loginSuccess = verifyAccount(providerConfig)
                    if (!loginSuccess) {
                        logger.warn("IPTV provider login verification failed for ${providerConfig.name}, but continuing with series streams fetch")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to make initial login call to IPTV provider ${providerConfig.name} before fetching series streams: ${e.message}, continuing with fetch attempt", e)
                }
                val seriesStreamsRequestUrl = "$seriesStreamsUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***&action=get_series"
                logger.debug("Fetching series streams from Xtream Codes provider ${providerConfig.name}: $seriesStreamsRequestUrl")
                
                val response: HttpResponse = httpClient.get(seriesStreamsUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_series")
                }
                
                logger.debug("Received response status ${response.status} for series streams request")
                
                if (response.status != HttpStatusCode.OK) {
                    logger.error("Failed to fetch series streams from Xtream Codes: ${response.status}")
                    return emptyList()
                }
                
                val body = response.body<String>()
                
                // Save response if configured
                if (responseFileService.shouldSaveResponses()) {
                    responseFileService.saveResponse(providerConfig, "series_streams", body)
                }
                
                body
            }
            
            logger.debug("Parsing series streams response (length: ${finalSeriesStreamsBody.length} characters)")
            val seriesStreams: List<XtreamSeriesStream> = json.decodeFromString(finalSeriesStreamsBody)
            logger.debug("Successfully parsed ${seriesStreams.size} series streams")
            
            return seriesStreams
        } catch (e: Exception) {
            logger.error("Error fetching series streams from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }

    /**
     * Gets series categories from the Xtream Codes provider
     * Returns a list of XtreamCategory objects
     * @param preFetchedBody Optional pre-fetched response body to use instead of fetching
     */
    private suspend fun getSeriesCategoriesInternal(providerConfig: IptvProviderConfiguration, preFetchedBody: String? = null): List<XtreamCategory> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // If pre-fetched body is provided, use it directly
            if (preFetchedBody != null) {
                logger.debug("Using pre-fetched series categories response (length: ${preFetchedBody.length} characters)")
                val parsedCategories = json.decodeFromString<List<XtreamCategory>>(preFetchedBody)
                logger.debug("Successfully parsed ${parsedCategories.size} series categories")
                return parsedCategories
            }
            
            val apiUrl = "$baseUrl/player_api.php"
            val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
            
            val categoriesBody = if (useLocal) {
                logger.info("Using local response file for series categories from provider ${providerConfig.name}")
                responseFileService.loadResponse(providerConfig, "series_categories")
                    ?: run {
                        logger.warn("Local response file not found for series categories, falling back to HTTP request")
                        null
                    }
            } else {
                null
            }
            
            val categories: List<XtreamCategory> = if (categoriesBody != null) {
                logger.debug("Parsing series categories response (length: ${categoriesBody.length} characters)")
                val parsedCategories = json.decodeFromString<List<XtreamCategory>>(categoriesBody)
                logger.debug("Successfully parsed ${parsedCategories.size} series categories")
                parsedCategories
            } else {
                val categoriesRequestUrl = "$apiUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***&action=get_series_categories"
                logger.debug("Fetching series categories from Xtream Codes provider ${providerConfig.name}: $categoriesRequestUrl")
                
                val categoriesResponse: HttpResponse = httpClient.get(apiUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_series_categories")
                }
                
                logger.debug("Received response status ${categoriesResponse.status} for series categories request")
                
                if (categoriesResponse.status == HttpStatusCode.OK) {
                    val body = categoriesResponse.body<String>()
                    
                    // Save response if configured
                    if (responseFileService.shouldSaveResponses()) {
                        responseFileService.saveResponse(providerConfig, "series_categories", body)
                    }
                    
                    logger.debug("Parsing series categories response (length: ${body.length} characters)")
                    val parsedCategories = json.decodeFromString<List<XtreamCategory>>(body)
                    logger.debug("Successfully parsed ${parsedCategories.size} series categories")
                    parsedCategories
                } else {
                    logger.warn("Failed to fetch series categories: ${categoriesResponse.status}")
                    emptyList()
                }
            }
            
            return categories
        } catch (e: Exception) {
            logger.error("Error fetching series categories from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }

    suspend fun getSeriesContent(providerConfig: IptvProviderConfiguration, categories: List<XtreamCategory>? = null, preFetchedStreamsBody: String? = null): List<IptvContentItem> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyList()
        val username = providerConfig.xtreamUsername ?: return emptyList()
        val password = providerConfig.xtreamPassword ?: return emptyList()
        
        try {
            // Get series streams (use pre-fetched body if available)
            val seriesStreams = getSeriesStreams(providerConfig, preFetchedStreamsBody)
            
            // Get series categories (use provided categories or fetch them)
            val seriesCategories = categories ?: getSeriesCategoriesInternal(providerConfig)
            
            // Convert category_id from Int to String for matching (API returns category_id as String in some cases)
            val categoryMap = seriesCategories.associateBy { it.category_id.toString() }
            
            return seriesStreams.mapNotNull { stream ->
                // Handle category_id - it may be a String in the JSON response
                val categoryIdStr = stream.category_id
                
                // Series streams are always SERIES type
                val contentType = ContentType.SERIES
                
                // Parse series info from stream name
                val episodeInfo = parseSeriesInfo(stream.name)
                
                // For series, we don't create episode URLs during sync
                // Episodes will be fetched on-demand when creating virtual filesystem files
                // Use a placeholder URL that indicates this is a series requiring episode lookup
                val tokenizedUrl = "SERIES_PLACEHOLDER:${stream.series_id}"
                
                IptvContentItem(
                    id = stream.series_id.toString(),
                    title = stream.name,
                    url = tokenizedUrl,
                    categoryId = categoryIdStr,
                    categoryType = "series",
                    type = contentType,
                    episodeInfo = episodeInfo
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching series content from Xtream Codes provider ${providerConfig.name}", e)
            return emptyList()
        }
    }

    /**
     * Gets all content (both movies and TV shows) from the Xtream Codes provider
     */
    suspend fun getAllContent(providerConfig: IptvProviderConfiguration): List<IptvContentItem> {
        val vodContent = getVodContent(providerConfig)
        val seriesContent = getSeriesContent(providerConfig)
        return vodContent + seriesContent
    }

    /**
     * Gets series episodes and info from the Xtream Codes provider using get_series_info API
     * Returns a Pair containing the series info and a list of episodes for the given series_id
     * Uses local file caching if configured (files named: {provider}_series_info_{seriesId}.json)
     */
    suspend fun getSeriesEpisodes(providerConfig: IptvProviderConfiguration, seriesId: String, cachedJson: String? = null): Pair<SeriesInfo?, List<XtreamSeriesEpisode>> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return Pair(null, emptyList())
        val username = providerConfig.xtreamUsername ?: return Pair(null, emptyList())
        val password = providerConfig.xtreamPassword ?: return Pair(null, emptyList())
        
        try {
            val apiUrl = "$baseUrl/player_api.php"
            val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
            
            // Try to load from local file cache first (if configured)
            val responseType = "series_info_$seriesId"
            val responseBody = if (useLocal) {
                logger.debug("Checking for local cached file for series $seriesId from provider ${providerConfig.name}")
                responseFileService.loadResponse(providerConfig, responseType, logNotFound = false)
                    ?: run {
                        logger.debug("Local response file not found for series $seriesId, will fetch from API")
                        null
                    }
            } else {
                null
            }
            
            val finalResponseBody = cachedJson ?: responseBody ?: run {
                val seriesStreamsRequestUrl = "$apiUrl?username=${URLEncoder.encode(username, "UTF-8")}&password=***&action=get_series_info&series_id=$seriesId"
                logger.debug("Fetching series episodes from Xtream Codes provider ${providerConfig.name}: $seriesStreamsRequestUrl")
                
                val response: HttpResponse = httpClient.get(apiUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_series_info")
                    parameter("series_id", seriesId)
                }
                
                logger.debug("Received response status ${response.status} for series episodes request")
                
                if (response.status != HttpStatusCode.OK) {
                    logger.error("Failed to fetch series episodes from Xtream Codes: ${response.status}")
                    return Pair(null, emptyList())
                }
                
                val body = response.body<String>()
                
                // Save response to local file if configured
                if (responseFileService.shouldSaveResponses()) {
                    responseFileService.saveResponse(providerConfig, responseType, body)
                }
                
                body
            }
            
            if (cachedJson != null) {
                logger.debug("Using cached JSON response for series $seriesId (length: ${cachedJson.length} characters)")
            }
            
            logger.debug("Parsing series episodes response (length: ${finalResponseBody.length} characters)")
            
            // The response structure is:
            // {
            //   "seasons": [...],
            //   "info": {...},
            //   "episodes": {
            //     "1": [{ "id": "...", "episode_num": 1, "season": 1, ... }, ...],
            //     "2": [...],
            //     ...
            //   }
            // }
            // Parse the entire response as a map and extract episodes
            val responseWrapper = json.decodeFromString<SeriesInfoResponseWrapper>(finalResponseBody)
            
            val seriesInfo = responseWrapper.info
            val allEpisodes = responseWrapper.episodes?.flatMap { (seasonStr, episodesList) ->
                val seasonNum = seasonStr.toIntOrNull()
                episodesList.map { episodeRaw ->
                    // Convert raw episode to XtreamSeriesEpisode, using episode_num as episode number
                    // Use season from map key if available, otherwise use season from episode object
                    XtreamSeriesEpisode(
                        id = episodeRaw.id,
                        title = episodeRaw.title,
                        container_extension = episodeRaw.container_extension,
                        info = episodeRaw.info,
                        season = seasonNum ?: episodeRaw.season,
                        episode = episodeRaw.episode_num
                    )
                }
            } ?: emptyList()
            
            logger.debug("Successfully parsed ${allEpisodes.size} episodes for series $seriesId")
            return Pair(seriesInfo, allEpisodes)
        } catch (e: Exception) {
            logger.error("Error fetching series episodes from Xtream Codes provider ${providerConfig.name} for series_id=$seriesId", e)
            return Pair(null, emptyList())
        }
    }

    /**
     * Gets VOD categories from the Xtream Codes provider
     * Returns a list of category ID to category name mappings
     */
    suspend fun getVodCategories(providerConfig: IptvProviderConfiguration): List<Pair<String, String>> {
        val categories = getVodCategoriesInternal(providerConfig)
        return categories.map { it.category_id.toString() to it.category_name }
    }

    /**
     * Gets VOD categories as XtreamCategory objects
     * @param preFetchedBody Optional pre-fetched response body to use instead of fetching
     */
    suspend fun getVodCategoriesAsObjects(providerConfig: IptvProviderConfiguration, preFetchedBody: String? = null): List<XtreamCategory> {
        return getVodCategoriesInternal(providerConfig, preFetchedBody)
    }

    /**
     * Gets series categories from the Xtream Codes provider
     * Returns a list of category ID to category name mappings
     */
    suspend fun getSeriesCategories(providerConfig: IptvProviderConfiguration): List<Pair<String, String>> {
        val categories = getSeriesCategoriesInternal(providerConfig)
        return categories.map { it.category_id.toString() to it.category_name }
    }

    /**
     * Gets series categories as XtreamCategory objects
     * @param preFetchedBody Optional pre-fetched response body to use instead of fetching
     */
    suspend fun getSeriesCategoriesAsObjects(providerConfig: IptvProviderConfiguration, preFetchedBody: String? = null): List<XtreamCategory> {
        return getSeriesCategoriesInternal(providerConfig, preFetchedBody)
    }

    /**
     * Gets a single endpoint response body for hash checking
     * Returns the response body string, or null if fetch failed
     */
    suspend fun getSingleEndpointResponse(providerConfig: IptvProviderConfiguration, endpointType: String): String? {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return null
        val username = providerConfig.xtreamUsername ?: return null
        val password = providerConfig.xtreamPassword ?: return null
        
        val apiUrl = "$baseUrl/player_api.php"
        val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
        
        // Map endpoint type to action parameter
        val action = when (endpointType) {
            "vod_categories" -> "get_vod_categories"
            "vod_streams" -> "get_vod_streams"
            "series_categories" -> "get_series_categories"
            "series_streams" -> "get_series"
            else -> return null
        }
        
        try {
            // Try local file first if configured
            val responseBody = if (useLocal) {
                responseFileService.loadResponse(providerConfig, endpointType, logNotFound = false)
            } else {
                null
            }
            
            if (responseBody != null) {
                return responseBody
            }
            
            // Fetch from API
            val response: HttpResponse = httpClient.get(apiUrl) {
                parameter("username", username)
                parameter("password", password)
                parameter("action", action)
            }
            
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<String>()
                
                // Save response if configured
                if (responseFileService.shouldSaveResponses()) {
                    responseFileService.saveResponse(providerConfig, endpointType, body)
                }
                
                return body
            } else {
                logger.warn("Failed to fetch $endpointType: ${response.status}")
                return null
            }
        } catch (e: Exception) {
            logger.error("Error fetching $endpointType for provider ${providerConfig.name}", e)
            return null
        }
    }

    /**
     * Parses VOD categories from a response body string
     */
    fun parseVodCategoriesFromBody(body: String): List<XtreamCategory> {
        return json.decodeFromString<List<XtreamCategory>>(body)
    }

    /**
     * Parses series categories from a response body string
     */
    fun parseSeriesCategoriesFromBody(body: String): List<XtreamCategory> {
        return json.decodeFromString<List<XtreamCategory>>(body)
    }

    /**
     * Gets raw response bodies for hash checking
     * Returns a map of endpoint type to response body
     */
    suspend fun getRawResponseBodies(providerConfig: IptvProviderConfiguration): Map<String, String> {
        require(providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            "Provider ${providerConfig.name} is not an Xtream Codes provider"
        }
        
        val baseUrl = providerConfig.xtreamBaseUrl ?: return emptyMap()
        val username = providerConfig.xtreamUsername ?: return emptyMap()
        val password = providerConfig.xtreamPassword ?: return emptyMap()
        
        val apiUrl = "$baseUrl/player_api.php"
        val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
        val result = mutableMapOf<String, String>()
        
        try {
            // Get VOD streams
            // Suppress "not found" logging during hash check to avoid duplicate logs
            val vodStreamsBody = if (useLocal) {
                responseFileService.loadResponse(providerConfig, "vod_streams", logNotFound = false)
            } else {
                null
            } ?: run {
                val response: HttpResponse = httpClient.get(apiUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_vod_streams")
                }
                if (response.status == HttpStatusCode.OK) {
                    response.body<String>()
                } else {
                    logger.warn("Failed to fetch VOD streams for hash check: ${response.status}")
                    return emptyMap()
                }
            }
            result["vod_streams"] = vodStreamsBody
            
            // Get series streams
            // Suppress "not found" logging during hash check to avoid duplicate logs
            val seriesStreamsBody = if (useLocal) {
                responseFileService.loadResponse(providerConfig, "series_streams", logNotFound = false)
            } else {
                null
            } ?: run {
                val response: HttpResponse = httpClient.get(apiUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_series")
                }
                if (response.status == HttpStatusCode.OK) {
                    response.body<String>()
                } else {
                    logger.warn("Failed to fetch series streams for hash check: ${response.status}")
                    return emptyMap()
                }
            }
            result["series_streams"] = seriesStreamsBody
            
            // Get VOD categories
            // Suppress "not found" logging during hash check to avoid duplicate logs
            val vodCategoriesBody = if (useLocal) {
                responseFileService.loadResponse(providerConfig, "vod_categories", logNotFound = false)
            } else {
                null
            } ?: run {
                val response: HttpResponse = httpClient.get(apiUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_vod_categories")
                }
                if (response.status == HttpStatusCode.OK) {
                    response.body<String>()
                } else {
                    logger.warn("Failed to fetch VOD categories for hash check: ${response.status}")
                    "" // Return empty string if failed, but don't fail the whole operation
                }
            }
            result["vod_categories"] = vodCategoriesBody
            
            // Get series categories
            // Suppress "not found" logging during hash check to avoid duplicate logs
            val seriesCategoriesBody = if (useLocal) {
                responseFileService.loadResponse(providerConfig, "series_categories", logNotFound = false)
            } else {
                null
            } ?: run {
                val response: HttpResponse = httpClient.get(apiUrl) {
                    parameter("username", username)
                    parameter("password", password)
                    parameter("action", "get_series_categories")
                }
                if (response.status == HttpStatusCode.OK) {
                    response.body<String>()
                } else {
                    logger.warn("Failed to fetch series categories for hash check: ${response.status}")
                    "" // Return empty string if failed, but don't fail the whole operation
                }
            }
            result["series_categories"] = seriesCategoriesBody
            
        } catch (e: Exception) {
            logger.error("Error fetching raw response bodies for provider ${providerConfig.name}", e)
            return emptyMap()
        }
        
        return result
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
    
    /**
     * Custom serializer for fields that accept both String and Number in JSON
     * Converts numbers to their string representation
     */
    private object StringOrNumberSerializer : KSerializer<String?> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StringOrNumber") {
            element<String?>("value", isOptional = true)
        }

        override fun serialize(encoder: Encoder, value: String?) {
            val jsonEncoder = encoder as JsonEncoder
            jsonEncoder.encodeJsonElement(JsonPrimitive(value ?: ""))
        }

        override fun deserialize(decoder: Decoder): String? {
            val jsonDecoder = decoder as JsonDecoder
            val element = jsonDecoder.decodeJsonElement()
            val primitive = element.jsonPrimitive
            // JsonPrimitive.content returns string representation for both strings and numbers
            return primitive.content
        }
    }
    
    /**
     * Custom serializer for integer fields that accept both String and Number in JSON
     * Converts strings to integers, or numbers directly
     */
    private object IntOrStringSerializer : KSerializer<Int?> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("IntOrString") {
            element<Int?>("value", isOptional = true)
        }

        override fun serialize(encoder: Encoder, value: Int?) {
            val jsonEncoder = encoder as JsonEncoder
            jsonEncoder.encodeJsonElement(JsonPrimitive(value ?: 0))
        }

        override fun deserialize(decoder: Decoder): Int? {
            val jsonDecoder = decoder as JsonDecoder
            val element = jsonDecoder.decodeJsonElement()
            val primitive = element.jsonPrimitive
            return when {
                primitive.isString -> primitive.content.toIntOrNull()
                else -> {
                    // For numbers, parse the content as double and convert to int
                    primitive.content.toDoubleOrNull()?.toInt()
                }
            }
        }
    }
    
    @Serializable
    private data class XtreamVodStream(
        val num: Int? = null,
        val name: String,
        val stream_type: String? = null,
        val stream_id: Int,
        val stream_icon: String? = null,
        @Serializable(with = StringOrNumberSerializer::class)
        val rating: String? = null,
        val rating_5based: Double? = null,
        @Serializable(with = StringOrNumberSerializer::class)
        val added: String? = null,
        @Serializable(with = IntOrStringSerializer::class)
        val is_adult: Int? = null,
        @Serializable(with = StringOrNumberSerializer::class)
        val category_id: String? = null,
        val container_extension: String? = null,
        val custom_sid: String? = null,
        val direct_source: String? = null
    )
    
    @Serializable
    data class XtreamCategory(
        val category_id: Int,
        val category_name: String
    )

    @Serializable
    private data class XtreamSeriesStream(
        val num: Int? = null,
        val name: String,
        val stream_type: String? = null,
        val series_id: Int,
        val cover: String? = null,
        val plot: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val releaseDate: String? = null,
        val rating: String? = null,
        val rating_5based: Double? = null,
        @Serializable(with = StringOrNumberSerializer::class)
        val added: String? = null,
        @Serializable(with = IntOrStringSerializer::class)
        val is_adult: Int? = null,
        @Serializable(with = StringOrNumberSerializer::class)
        val category_id: String? = null,
        val container_extension: String? = null,
        val custom_sid: String? = null,
        val direct_source: String? = null
    )

    @Serializable
    private data class XtreamUserInfo(
        val user_info: XtreamUserInfoDetails? = null
    )

    @Serializable
    private data class XtreamUserInfoDetails(
        val username: String? = null,
        val password: String? = null,
        val message: String? = null,
        val auth: Int? = null,
        val status: String? = null,
        val exp_date: String? = null,
        val is_trial: String? = null,
        val active_cons: String? = null,
        val created_at: String? = null,
        val max_connections: String? = null,
        val allowed_output_formats: List<String>? = null
    )

    /**
     * Data class for series episode information from get_series_info API
     */
    @Serializable
    data class XtreamSeriesEpisode(
        val id: String,
        val title: String,
        val container_extension: String? = null,
        val info: XtreamEpisodeInfo? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    @Serializable
    data class XtreamEpisodeInfo(
        val plot: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val releaseDate: String? = null,
        @Serializable(with = StringOrNumberSerializer::class)
        val rating: String? = null,
        val rating_5based: Double? = null,
        val duration: String? = null
    )
    
    @Serializable
    private data class XtreamSeriesEpisodeRaw(
        val id: String,
        @Serializable(with = IntOrStringSerializer::class)
        val episode_num: Int? = null,
        val title: String,
        val container_extension: String? = null,
        val info: XtreamEpisodeInfo? = null,
        @Serializable(with = IntOrStringSerializer::class)
        val season: Int? = null
    )
    
    @Serializable
    data class SeriesInfo(
        val name: String? = null,
        val releaseDate: String? = null,
        val release_date: String? = null
    )
    
    @Serializable
    private data class SeriesInfoResponseWrapper(
        val info: SeriesInfo? = null,
        val episodes: Map<String, List<XtreamSeriesEpisodeRaw>>? = null
    )
}

