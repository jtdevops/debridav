package io.skjaere.debridav.iptv

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.iptv.client.XtreamCodesClient
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.iptv.util.IptvResponseFileService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class IptvRequestService(
    private val iptvContentRepository: IptvContentRepository,
    private val iptvContentService: IptvContentService,
    private val databaseFileService: DatabaseFileService,
    private val categoryService: CategoryService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvConfigurationProperties: io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties,
    private val iptvSeriesMetadataRepository: IptvSeriesMetadataRepository,
    private val httpClient: HttpClient,
    private val responseFileService: IptvResponseFileService,
    private val iptvLoginRateLimitService: IptvLoginRateLimitService
) {
    private val logger = LoggerFactory.getLogger(IptvRequestService::class.java)
    private val xtreamCodesClient = XtreamCodesClient(httpClient, responseFileService, iptvConfigurationProperties.userAgent)
    
    // Cache for redirect URLs: original URL -> redirect URL
    // DISABLED: Diagnostics show redirect URLs expire almost immediately (within seconds)
    // Redirect URLs are single-use/time-limited and return 404 if reused
    // Always use fresh redirects from response headers instead of cached ones
    // Keeping cache structure for potential future use, but with very short expiration
    private val redirectUrlCache: LoadingCache<String, String?> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(2)) // 2 seconds - redirect URLs expire almost immediately
        .maximumSize(1000L)
        .build(CacheLoader<String, String?> { originalUrl ->
            runBlocking {
                resolveRedirectUrl(originalUrl)
            }
        })

    @Transactional
    fun addIptvContent(
        contentId: String, 
        providerName: String, 
        category: String, 
        magnetTitle: String? = null,
        season: Int? = null, // Season number for series filtering
        episode: Int? = null // Episode number within season (optional)
    ): Boolean {
        logger.info("Adding IPTV content: contentId=$contentId, iptvProvider=$providerName, category=$category, magnetTitle=$magnetTitle, season=$season, episode=$episode")
        
        val iptvContent = iptvContentRepository.findByProviderNameAndContentId(providerName, contentId)
            ?: run {
                logger.warn("IPTV content not found: iptvProvider=$providerName, contentId=$contentId")
                return false
            }
        
        if (!iptvContent.isActive) {
            logger.warn("IPTV content is inactive: iptvProvider=$providerName, contentId=$contentId")
            return false
        }
        
        // Use magnet title if provided, otherwise use IPTV content title
        val titleToUse = magnetTitle ?: iptvContent.title
        
        // Check if this is a series that needs episode lookup
        // For Xtream Codes providers, all series need episode fetching via get_series_info API
        if (iptvContent.contentType == ContentType.SERIES) {
            // Check if provider is Xtream Codes
            val providerConfig = iptvConfigurationService.getProviderConfigurations()
                .find { it.name == providerName && it.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES }
            
            if (providerConfig != null) {
                // For Xtream Codes series, contentId is the series_id (stored in database)
                // This is more reliable than parsing from URL and works regardless of URL format
                val seriesId = contentId
                logger.info("Detected Xtream Codes series, fetching episodes via get_series_info API: seriesId=$seriesId, contentId=$contentId, title=${iptvContent.title}, url=${iptvContent.url}, season=$season, episode=$episode")
                return handleSeriesContent(iptvContent, providerName, category, seriesId, magnetTitle, season, episode)
            } else {
                logger.debug("Series content detected but provider $providerName is not Xtream Codes, treating as regular content")
            }
        }
        
        // For movies or series with direct URLs, resolve tokenized URL to actual URL
        logger.debug("IPTV content original URL (tokenized): {}", iptvContent.url)
        val resolvedUrl = try {
            val resolved = iptvContentService.resolveIptvUrl(iptvContent.url, providerName)
            logger.debug("IPTV content resolved URL (tokens replaced): {}", resolved)
            resolved
        } catch (e: Exception) {
            logger.error("Failed to resolve IPTV URL for provider $providerName", e)
            return false
        }
        
        // Extract media file extension from the resolved URL
        val urlMediaExtension = extractMediaExtensionFromUrl(resolvedUrl)
        
        // Check if magnet title already contains a media extension
        // Common video extensions to check
        val videoExtensions = listOf("mp4", "mkv", "avi", "ts", "mov", "wmv", "flv", "webm", "m4v", 
            "m2ts", "mts", "vob", "ogv", "3gp", "asf", "rm", "rmvb")
        val titleMediaExtension = videoExtensions.firstOrNull { ext ->
            titleToUse.endsWith(".$ext", ignoreCase = true)
        }
        
        // Use extension from title if present, otherwise use extension from URL
        val mediaExtension = titleMediaExtension ?: urlMediaExtension
        
        // Check if magnet title already has a language code at the end (e.g., "-PRMT", ".PRMT")
        // Extract it and remove from beginning to avoid duplication
        val languageCodeAtEnd = extractLanguageCodeFromEnd(titleToUse)
        var languageCode: String? = null
        var cleanedTitleToUse = titleToUse
        
        if (languageCodeAtEnd != null) {
            // Language code found at the end, use it and remove from beginning
            languageCode = languageCodeAtEnd
            // Remove language code from beginning of titleToUse
            cleanedTitleToUse = removeSpecificLanguageCodeFromBeginning(titleToUse, languageCode)
        } else {
            // No language code at end, check if IPTV content title has one at beginning
            languageCode = extractLanguageCodeIfNotInPrefixes(iptvContent.title)
        }
        
        // Get title without extension for folder name (if title had extension, remove it)
        // Use cleaned title if we removed language code from beginning
        val titleWithoutExtension = if (titleMediaExtension != null) {
            cleanedTitleToUse.removeSuffix(".$titleMediaExtension").removeSuffix(".${titleMediaExtension.uppercase()}")
        } else {
            cleanedTitleToUse
        }
        
        // If language code is at the end of titleWithoutExtension, don't add it again
        if (languageCode != null && titleWithoutExtension.endsWith("-$languageCode", ignoreCase = true)) {
            languageCode = null // Already at the end, don't add again
        }
        
        // Build filename: insert language code between .IPTV and media extension if needed
        // Use cleanedTitleToUse (which has language code removed from beginning if it was at the end)
        // If title already had extension, use it as-is (don't add another)
        val fileNameWithExtension = if (titleMediaExtension != null) {
            // Title already has extension - use it as-is, but handle language code if needed
            if (languageCode != null && titleWithoutExtension.endsWith(".IPTV", ignoreCase = true)) {
                // Insert language code between .IPTV and media extension
                "${titleWithoutExtension.removeSuffix(".IPTV")}.IPTV-$languageCode.$titleMediaExtension"
            } else if (languageCode != null) {
                // Language code but no .IPTV suffix, insert before extension
                val baseWithoutExt = titleWithoutExtension
                "$baseWithoutExt-$languageCode.$titleMediaExtension"
            } else {
                // No language code, use cleaned title as-is
                cleanedTitleToUse
            }
        } else if (mediaExtension != null) {
            // Title doesn't have extension, add it
            if (languageCode != null && cleanedTitleToUse.endsWith(".IPTV", ignoreCase = true)) {
                // Insert language code between .IPTV and media extension
                "${cleanedTitleToUse.removeSuffix(".IPTV")}.IPTV-$languageCode.$mediaExtension"
            } else if (languageCode != null) {
                // Language code but no .IPTV suffix, append it before extension
                "$cleanedTitleToUse-$languageCode.$mediaExtension"
            } else {
                // No language code, just append extension
                "$cleanedTitleToUse.$mediaExtension"
            }
        } else {
            // No media extension available, append language code if present
            if (languageCode != null && cleanedTitleToUse.endsWith(".IPTV", ignoreCase = true)) {
                "${cleanedTitleToUse.removeSuffix(".IPTV")}.IPTV-$languageCode"
            } else if (languageCode != null) {
                "$cleanedTitleToUse-$languageCode"
            } else {
                cleanedTitleToUse
            }
        }
        
        // Try to fetch actual file size from IPTV URL, fallback to estimated size
        logger.debug("Fetching file size for IPTV content - original URL: {}, resolved URL: {}", iptvContent.url, resolvedUrl)
        val fileSize = runBlocking {
            fetchActualFileSize(resolvedUrl, iptvContent.contentType, providerName)
        }
        
        // Create DebridIptvContent entity
        val debridIptvContent = DebridIptvContent(
            originalPath = fileNameWithExtension,
            size = fileSize,
            modified = Instant.now().toEpochMilli(),
            iptvUrl = resolvedUrl,
            iptvProviderName = providerName,
            iptvContentId = contentId,
            mimeType = determineMimeType(fileNameWithExtension),
            debridLinks = mutableListOf()
        )
        // Set foreign key reference for cascading deletes
        debridIptvContent.iptvContentRefId = iptvContent.id
        
        // Create IptvFile link
        val iptvFile = IptvFile(
            path = fileNameWithExtension,
            size = fileSize,
            mimeType = debridIptvContent.mimeType ?: "video/mp4",
            link = resolvedUrl,
            params = emptyMap(),
            lastChecked = Instant.now().toEpochMilli()
        )
        
        debridIptvContent.debridLinks.add(iptvFile)
        
        // Determine file path - use category if provided, otherwise use default
        val categoryPath = categoryService.findByName(category)?.downloadPath 
            ?: debridavConfigurationProperties.downloadPath
        
        // Create folder structure: folder name without media extension, file inside with full name including extension
        // Start with titleWithoutExtension (which already has extension removed if it was in magnet title)
        // Then apply language code if needed, but don't add extension to folder name
        val folderBaseName = if (languageCode != null && titleWithoutExtension.endsWith(".IPTV", ignoreCase = true)) {
            // Insert language code between .IPTV (but no extension for folder)
            "${titleWithoutExtension.removeSuffix(".IPTV")}.IPTV-$languageCode"
        } else if (languageCode != null) {
            // Language code but no .IPTV suffix, append it
            "$titleWithoutExtension-$languageCode"
        } else {
            // No language code, use titleWithoutExtension as-is
            titleWithoutExtension
        }
        val folderName = sanitizeFileName(folderBaseName)
        val fileName = sanitizeFileName(fileNameWithExtension)
        val filePath = "$categoryPath/$folderName/$fileName"
        
        // Generate hash from content ID - use original format for database compatibility
        // The database stores hashCode().toString() format, not hex
        val hash = "${providerName}_${contentId}".hashCode().toString()
        
        // Create virtual file
        try {
            databaseFileService.createDebridFile(filePath, hash, debridIptvContent)
            logger.info("Successfully created IPTV virtual file: $filePath")
            return true
        } catch (e: Exception) {
            logger.error("Failed to create IPTV virtual file: $filePath", e)
            return false
        }
    }
    
    /**
     * Handles series content by fetching episodes on-demand and creating virtual files
     */
    private fun handleSeriesContent(
        iptvContent: IptvContentEntity,
        providerName: String,
        category: String,
        seriesId: String,
        magnetTitle: String? = null,
        requestedSeason: Int? = null, // Season number to filter episodes
        requestedEpisode: Int? = null // Episode number to filter (optional)
    ): Boolean {
        logger.info("Handling series content: seriesId=$seriesId, title=${iptvContent.title}, requestedSeason=$requestedSeason, requestedEpisode=$requestedEpisode")
        
        // Get provider configuration
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName && it.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES }
            ?: run {
                logger.error("Provider $providerName not found or not an Xtream Codes provider")
                return false
            }
        
        // Try to get episodes from cache first
        val cachedMetadata = iptvSeriesMetadataRepository.findByProviderNameAndSeriesId(providerName, seriesId)
        val (seriesInfo, episodes) = if (cachedMetadata != null) {
            // Check if cache is still valid (not expired)
            val cacheAge = java.time.Duration.between(cachedMetadata.lastAccessed, java.time.Instant.now())
            if (cacheAge < iptvConfigurationProperties.seriesMetadataCacheTtl) {
                logger.debug("Using cached episodes for series $seriesId (cache age: ${cacheAge.toHours()} hours)")
                // Update last accessed time
                cachedMetadata.lastAccessed = java.time.Instant.now()
                iptvSeriesMetadataRepository.save(cachedMetadata)
                
                // Parse from cached JSON response
                logger.debug("Parsing episodes from cached JSON response for series $seriesId")
                val (cachedSeriesInfo, cachedEpisodes) = parseSeriesEpisodesFromJson(providerConfig, seriesId, cachedMetadata.responseJson)
                
                // Log cached episode details at DEBUG level - filter by requested season if provided
                val episodesToLog = if (requestedSeason != null) {
                    cachedEpisodes.filter { it.season == requestedSeason }
                } else {
                    cachedEpisodes
                }
                logger.debug("Retrieved ${cachedEpisodes.size} episodes from cache for series $seriesId${if (requestedSeason != null) " (showing ${episodesToLog.size} episodes from season $requestedSeason)" else ""}")
                if (cachedSeriesInfo != null) {
                    logger.debug("Retrieved series info from cache: name='${cachedSeriesInfo.name}', releaseDate='${cachedSeriesInfo.releaseDate}', release_date='${cachedSeriesInfo.release_date}'")
                }
                episodesToLog.forEach { ep ->
                    logger.debug("  Episode: id=${ep.id}, title='${ep.title}', season=${ep.season}, episode=${ep.episode}, extension=${ep.container_extension ?: "mp4"}")
                }
                Pair(cachedSeriesInfo, cachedEpisodes)
            } else {
                logger.debug("Cache expired for series $seriesId (age: ${cacheAge.toHours()} hours, TTL: ${iptvConfigurationProperties.seriesMetadataCacheTtl.toHours()} hours)")
                // Cache expired, fetch fresh data
                fetchAndCacheEpisodes(providerConfig, providerName, seriesId)
            }
        } else {
            // No cache, fetch and store
            logger.debug("No cache found for series $seriesId, fetching from API")
            fetchAndCacheEpisodes(providerConfig, providerName, seriesId)
        }
        
        if (episodes.isEmpty()) {
            logger.warn("No episodes found for series $seriesId")
            return false
        }
        
        logger.info("Found ${episodes.size} episodes for series $seriesId")
        
        // Filter episodes by requested season/episode if provided
        val episodesToCreate = if (requestedSeason != null) {
            val seasonEpisodes = episodes.filter { ep ->
                ep.season == requestedSeason
            }
            if (seasonEpisodes.isEmpty()) {
                logger.warn("No episodes found for season $requestedSeason in series $seriesId")
                return false
            }
            
            // If specific episode requested, filter further
            if (requestedEpisode != null) {
                val specificEpisode = seasonEpisodes.find { ep ->
                    ep.episode == requestedEpisode
                }
                if (specificEpisode != null) {
                    logger.info("Found specific episode: season $requestedSeason, episode $requestedEpisode")
                    listOf(specificEpisode)
                } else {
                    logger.warn("Episode $requestedEpisode not found in season $requestedSeason, creating all episodes from season")
                    seasonEpisodes
                }
            } else {
                logger.info("Creating all ${seasonEpisodes.size} episodes from season $requestedSeason")
                seasonEpisodes
            }
        } else {
            // Try to parse season/episode from title to find specific episode
            val episodeInfo = parseSeriesInfo(iptvContent.title)
            val targetEpisode = if (episodeInfo?.season != null && episodeInfo.episode != null) {
                episodes.find { ep ->
                    ep.season == episodeInfo.season && ep.episode == episodeInfo.episode
                }
            } else {
                null
            }
            
            // If we found a specific episode, create file for that episode only
            // Otherwise, create files for all episodes (for now, we'll create the first one as a fallback)
            if (targetEpisode != null) {
                logger.info("Found episode from title parsing: season ${episodeInfo?.season}, episode ${episodeInfo?.episode}")
                listOf(targetEpisode)
            } else {
                // If no specific episode found, log warning and create first episode as fallback
                logger.warn("Could not determine specific episode from title '${iptvContent.title}'. Creating file for first episode.")
                listOf(episodes.first())
            }
        }
        
        val categoryPath = categoryService.findByName(category)?.downloadPath 
            ?: debridavConfigurationProperties.downloadPath
        
        var successCount = 0
        for (episode in episodesToCreate) {
            // Construct episode URL: {baseUrl}/series/{username}/{password}/{episode_id}.{extension}
            val baseUrl = providerConfig.xtreamBaseUrl ?: continue
            val username = providerConfig.xtreamUsername ?: continue
            val password = providerConfig.xtreamPassword ?: continue
            val extension = episode.container_extension ?: "mp4"
            val episodeUrl = "$baseUrl/series/$username/$password/${episode.id}.$extension"
            
            // Extract media file extension from the episode URL
            val mediaExtension = extractMediaExtensionFromUrl(episodeUrl) ?: extension
            
            // Determine folder name: use magnet title if available, otherwise use IPTV content title
            val folderNameBase = if (magnetTitle != null) {
                // Use magnet title as-is for folder name (without episode number)
                magnetTitle
            } else {
                // Use IPTV content title for folder name
                iptvContent.title
            }
            
            // Check if IPTV content title starts with any configured language prefix
            // If not, extract language code and append it after .IPTV
            val languageCode = extractLanguageCodeIfNotInPrefixes(iptvContent.title)
            
            // Build folder name: insert language code between .IPTV and media extension if needed
            val folderNameWithExtension = if (languageCode != null && folderNameBase.endsWith(".IPTV", ignoreCase = true)) {
                // Insert language code between .IPTV and media extension
                "${folderNameBase.removeSuffix(".IPTV")}.IPTV-$languageCode.$mediaExtension"
            } else if (languageCode != null) {
                // Language code but no .IPTV suffix, append it before extension
                "$folderNameBase-$languageCode.$mediaExtension"
            } else {
                // No language code, just append extension
                "$folderNameBase.$mediaExtension"
            }
            
            // Sanitize folder name and remove extension for folder
            val sanitizedFolderName = sanitizeFileName(folderNameWithExtension.removeSuffix(".$mediaExtension"))
            
            // Full episode title for metadata (used in DebridIptvContent)
            val episodeTitle = if (magnetTitle != null && episode.season != null && episode.episode != null) {
                // Check if magnet title has season but no episode number
                val seasonPattern = Regex("S(\\d+)")
                val episodePattern = Regex("E(\\d+)")
                val hasSeason = seasonPattern.containsMatchIn(magnetTitle)
                val hasEpisode = episodePattern.containsMatchIn(magnetTitle)
                
                if (hasSeason && !hasEpisode) {
                    // Magnet title has season but no episode, insert episode number after season
                    val episodeNumber = String.format("E%02d", episode.episode)
                    val titleWithEpisode = seasonPattern.replace(magnetTitle) { matchResult ->
                        "${matchResult.value}$episodeNumber"
                    }
                    // Add extension
                    if (languageCode != null && titleWithEpisode.endsWith(".IPTV", ignoreCase = true)) {
                        "${titleWithEpisode.removeSuffix(".IPTV")}.IPTV-$languageCode.$mediaExtension"
                    } else if (languageCode != null) {
                        "$titleWithEpisode-$languageCode.$mediaExtension"
                    } else {
                        "$titleWithEpisode.$mediaExtension"
                    }
                } else {
                    // Magnet title already has episode or no season, use as-is with extension
                    if (languageCode != null && magnetTitle.endsWith(".IPTV", ignoreCase = true)) {
                        "${magnetTitle.removeSuffix(".IPTV")}.IPTV-$languageCode.$mediaExtension"
                    } else if (languageCode != null) {
                        "$magnetTitle-$languageCode.$mediaExtension"
                    } else {
                        "$magnetTitle.$mediaExtension"
                    }
                }
            } else {
                // Construct episode title from IPTV content
                if (episode.season != null && episode.episode != null) {
                    "${iptvContent.title} - S${String.format("%02d", episode.season)}E${String.format("%02d", episode.episode)} - ${episode.title}.$mediaExtension"
                } else {
                    "${iptvContent.title} - ${episode.title}.$mediaExtension"
                }
            }
            
            // Log episode URL information
            logger.debug("Fetching file size for IPTV episode - original URL: {}, episode URL: {}", iptvContent.url, episodeUrl)
            
            // Try to fetch actual file size from IPTV URL, fallback to estimated size
            val episodeFileSize = runBlocking {
                fetchActualFileSize(episodeUrl, ContentType.SERIES, providerName)
            }
            
            // Create DebridIptvContent entity for episode
            val debridIptvContent = DebridIptvContent(
                originalPath = episodeTitle,
                size = episodeFileSize,
                modified = Instant.now().toEpochMilli(),
                iptvUrl = episodeUrl,
                iptvProviderName = providerName,
                iptvContentId = "${seriesId}_${episode.id}", // Use series_id_episode_id as content ID
                mimeType = determineMimeType(episodeTitle),
                debridLinks = mutableListOf()
            )
            debridIptvContent.iptvContentRefId = iptvContent.id
            
            // Create IptvFile link
            val iptvFile = IptvFile(
                path = episodeTitle,
                size = episodeFileSize,
                mimeType = debridIptvContent.mimeType ?: "video/mp4",
                link = episodeUrl,
                params = emptyMap(),
                lastChecked = Instant.now().toEpochMilli()
            )
            
            debridIptvContent.debridLinks.add(iptvFile)
            
            // Build filename: use full episode title (sanitized)
            val fileName = sanitizeFileName(episodeTitle)
            
            // Create folder structure: folder name = magnet title (sanitized), file name = full episode title
            val filePath = "$categoryPath/$sanitizedFolderName/$fileName"
            
            // Generate hash from episode content ID (series episodes use seriesId_episodeId format)
            // Use original format for database compatibility
            val episodeContentId = "${seriesId}_${episode.id}"
            val hash = "${providerName}_${episodeContentId}".hashCode().toString()
            
            // Create virtual file
            try {
                databaseFileService.createDebridFile(filePath, hash, debridIptvContent)
                logger.info("Successfully created IPTV virtual file for episode: $filePath")
                successCount++
            } catch (e: Exception) {
                logger.error("Failed to create IPTV virtual file for episode: $filePath", e)
            }
        }
        
        return successCount > 0
    }
    
    /**
     * Parses series episodes from cached JSON response
     */
    private fun parseSeriesEpisodesFromJson(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        seriesId: String,
        jsonString: String
    ): Pair<io.skjaere.debridav.iptv.client.XtreamCodesClient.SeriesInfo?, List<XtreamCodesClient.XtreamSeriesEpisode>> {
        return runBlocking {
            xtreamCodesClient.getSeriesEpisodes(providerConfig, seriesId, cachedJson = jsonString)
        }
    }
    
    /**
     * Fetches episodes from API and caches them in the database
     */
    private fun fetchAndCacheEpisodes(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        providerName: String,
        seriesId: String
    ): Pair<io.skjaere.debridav.iptv.client.XtreamCodesClient.SeriesInfo?, List<XtreamCodesClient.XtreamSeriesEpisode>> {
        // Fetch the raw JSON response first
        val responseJson = runBlocking {
            val apiUrl = "${providerConfig.xtreamBaseUrl}/player_api.php"
            try {
                val response = httpClient.get(apiUrl) {
                    parameter("username", providerConfig.xtreamUsername ?: "")
                    parameter("password", providerConfig.xtreamPassword ?: "")
                    parameter("action", "get_series_info")
                    parameter("series_id", seriesId)
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                }
                if (response.status.isSuccess()) {
                    response.body<String>()
                } else {
                    logger.error("Failed to fetch series episodes: ${response.status}")
                    null
                }
            } catch (e: Exception) {
                logger.error("Error fetching series episodes JSON: ${e.message}", e)
                null
            }
        }
        
        if (responseJson == null) {
            return Pair(null, emptyList())
        }
        
        // Parse the JSON response
        val (seriesInfo, episodes) = parseSeriesEpisodesFromJson(providerConfig, seriesId, responseJson)
        
        if (episodes.isNotEmpty()) {
            // Log summary only - individual episode details are logged later when filtered by season
            logger.debug("Fetched ${episodes.size} episodes for series $seriesId")
            if (seriesInfo != null) {
                logger.debug("Series info: name='${seriesInfo.name}', releaseDate='${seriesInfo.releaseDate}', release_date='${seriesInfo.release_date}'")
            }
            
            // Save or update cache with raw JSON response
            val metadata = iptvSeriesMetadataRepository.findByProviderNameAndSeriesId(providerName, seriesId)
                ?: IptvSeriesMetadataEntity().apply {
                    this.providerName = providerName
                    this.seriesId = seriesId
                    this.createdAt = java.time.Instant.now()
                }
            
            metadata.responseJson = responseJson
            metadata.lastAccessed = java.time.Instant.now()
            iptvSeriesMetadataRepository.save(metadata)
            logger.debug("Cached raw JSON response for series $seriesId (${episodes.size} episodes)")
        }
        
        return Pair(seriesInfo, episodes)
    }
    
    /**
     * Extracts year from releaseDate string (e.g., "2006-10-01" -> 2006)
     */
    private fun extractYearFromReleaseDate(releaseDate: String?): Int? {
        if (releaseDate == null || releaseDate.isBlank()) return null
        // Try to extract year from date formats like "2006-10-01" or "2006"
        val yearMatch = Regex("^(\\d{4})").find(releaseDate)
        return yearMatch?.groupValues?.get(1)?.toIntOrNull()
    }
    
    /**
     * Parses season number from episode string (e.g., "S08" -> 8, "S08E01" -> 8)
     * @param episode Episode string in format "S##" or "S##E##"
     * @return Season number if successfully parsed, null otherwise
     */
    private fun parseSeasonFromEpisode(episode: String): Int? {
        val normalized = episode.trim().uppercase()
        // Match S followed by digits, optionally followed by E and more digits
        val pattern = Regex("^S(\\d+)(?:E\\d+)?$")
        val match = pattern.find(normalized)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    /**
     * Parses the episode number from an episode string (e.g., "S07E01" -> 1).
     * @param episode Episode string in format "S{season}E{episode}" or "S{season}"
     * @return Episode number if successfully parsed, null otherwise
     */
    private fun parseEpisodeNumberFromEpisode(episode: String): Int? {
        val normalized = episode.trim().uppercase()
        // Match S followed by digits, optionally followed by E and more digits
        val pattern = Regex("^S\\d+E(\\d+)$")
        val match = pattern.find(normalized)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun parseSeriesInfo(title: String): io.skjaere.debridav.iptv.model.EpisodeInfo? {
        // Try to parse series info from title
        // Pattern: Series Name S01E01 or Series Name - S01E01
        val pattern = Regex("""(.+?)\s*[-]?\s*[Ss](\d+)[Ee](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(title)
        
        return match?.let {
            io.skjaere.debridav.iptv.model.EpisodeInfo(
                seriesName = it.groupValues[1].trim(),
                season = it.groupValues[2].toIntOrNull(),
                episode = it.groupValues[3].toIntOrNull()
            )
        }
    }
    
    /**
     * Generates a consistent hash from provider name and content ID.
     * This hash is used as the infohash in search results and matches the hash
     * used when creating Torrent entities from IPTV content.
     * 
     * Returns a positive hex string suitable for use in magnet URIs.
     * 
     * Reverse lookup: The hash can be used to find the IPTV content via:
     * 1. Look up Torrent by hash: torrentRepository.getByHashIgnoreCase(hash)
     * 2. Get files from Torrent: torrent.files
     * 3. Extract IPTV info: (file.contents as DebridIptvContent).iptvProviderName and iptvContentId
     */
    fun generateIptvHash(providerName: String, contentId: String): String {
        // Generate hash code and convert to positive hex string
        val hashInt = "${providerName}_${contentId}".hashCode()
        // Convert to unsigned long to ensure positive, then to hex
        val unsignedHash = hashInt.toLong() and 0xFFFFFFFFL
        return unsignedHash.toString(16).uppercase().padStart(8, '0')
    }
    
    /**
     * Extracts the hash from an IPTV URL.
     * Supports both formats:
     * - New format: iptv://{hash}/{providerName}/{contentId}
     * - Old format: iptv://{providerName}/{contentId} (will compute hash)
     * 
     * @param iptvUrl The IPTV URL to parse
     * @return The hash, or null if URL format is invalid
     */
    fun extractHashFromIptvUrl(iptvUrl: String): String? {
        if (!iptvUrl.startsWith("iptv://")) {
            return null
        }
        
        val linkWithoutProtocol = iptvUrl.removePrefix("iptv://")
        val parts = linkWithoutProtocol.split("/")
        
        return when (parts.size) {
            3 -> {
                // New format: iptv://{hash}/{providerName}/{contentId}
                parts[0]
            }
            2 -> {
                // Old format: iptv://{providerName}/{contentId} - compute hash
                generateIptvHash(parts[0], parts[1])
            }
            else -> null
        }
    }
    
    /**
     * Creates a magnet URI for IPTV content that Radarr will accept.
     * Uses standard BitTorrent magnet format but encodes IPTV info in the tracker parameter.
     * Format: magnet:?xt=urn:btih:{hash}&dn={encodedTitle}&tr={iptv://guid}
     * 
     * Note: We use a fake BTIH hash (same as our IPTV hash) so Radarr accepts it,
     * but our system recognizes it as IPTV via the tracker parameter.
     */
    private fun createIptvMagnetUri(hash: String, title: String, guid: String): String {
        // URL encode the title for the magnet link
        val encodedTitle = java.net.URLEncoder.encode(title, Charsets.UTF_8.name())
        // Create a standard magnet URI format that Radarr will accept
        // Use BTIH format (BitTorrent Info Hash) - must be exactly 40 hex characters
        // Pad hash to 40 chars (standard BTIH length) with zeros
        val btihHash = hash.lowercase().padEnd(40, '0').take(40)
        return "magnet:?xt=urn:btih:$btihHash&dn=$encodedTitle&tr=${java.net.URLEncoder.encode(guid, Charsets.UTF_8.name())}"
    }
    
    /**
     * Resolves the redirect URL for a given original URL.
     * Tries HEAD request first for speed (no body transfer).
     * Falls back to GET with Range header (bytes=0-0) if HEAD is rejected (e.g., 405 Method Not Allowed).
     * Returns null if no redirect or if request fails.
     */
    private suspend fun resolveRedirectUrl(originalUrl: String): String? {
        // Try HEAD request first (faster, no body transfer)
        val headResponse = try {
            httpClient.head(originalUrl) {
                headers {
                    append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                }
                timeout {
                    requestTimeoutMillis = 5000 // 5 second timeout
                    connectTimeoutMillis = 2000 // 2 second connect timeout
                }
            }
        } catch (e: Exception) {
            logger.debug("HEAD request failed for {}, will try GET fallback: {}", originalUrl.take(100), e.message)
            null
        }
        
        // Check if HEAD request was successful and returned a redirect
        if (headResponse != null) {
            if (headResponse.status.value in 300..399) {
                val redirectLocation = headResponse.headers["Location"]
                if (redirectLocation != null) {
                    val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                        redirectLocation
                    } else {
                        // Relative redirect - construct absolute URL
                        val originalUri = java.net.URI(originalUrl)
                        originalUri.resolve(redirectLocation).toString()
                    }
                    logger.debug("Resolved redirect URL via HEAD: originalUrl={}, redirectUrl={}", originalUrl.take(100), redirectUrl.take(100))
                    return redirectUrl
                }
            } else if (headResponse.status.isSuccess()) {
                // HEAD succeeded but no redirect - URL doesn't redirect
                logger.debug("HEAD request succeeded but no redirect for: {}", originalUrl.take(100))
                return null
            }
            // If HEAD returned an error status (like 405 Method Not Allowed), fall through to GET
        }
        
        // Fallback to GET with Range header if HEAD failed or was rejected
        // Some providers don't support HEAD requests, so we use GET with minimal data transfer
        return try {
            val getResponse = httpClient.get(originalUrl) {
                headers {
                    append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    append(HttpHeaders.Range, "bytes=0-0") // Request only first byte to minimize data transfer
                }
                timeout {
                    requestTimeoutMillis = 5000 // 5 second timeout
                    connectTimeoutMillis = 2000 // 2 second connect timeout
                }
            }
            
            if (getResponse.status.value in 300..399) {
                val redirectLocation = getResponse.headers["Location"]
                if (redirectLocation != null) {
                    // Consume response body to ensure proper cleanup
                    try {
                        getResponse.body<ByteReadChannel>()
                    } catch (e: Exception) {
                        // Ignore errors when consuming response body
                    }
                    
                    val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                        redirectLocation
                    } else {
                        // Relative redirect - construct absolute URL
                        val originalUri = java.net.URI(originalUrl)
                        originalUri.resolve(redirectLocation).toString()
                    }
                    logger.debug("Resolved redirect URL via GET fallback: originalUrl={}, redirectUrl={}", originalUrl.take(100), redirectUrl.take(100))
                    redirectUrl
                } else {
                    null
                }
            } else {
                // Consume response body to ensure proper cleanup
                try {
                    getResponse.body<ByteReadChannel>()
                } catch (e: Exception) {
                    // Ignore errors when consuming response body
                }
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to resolve redirect URL via GET fallback for {}: {}", originalUrl.take(100), e.message)
            null
        }
    }
    
    /**
     * Gets the cached redirect URL for a given original URL, or resolves it if not cached.
     * Returns null if no redirect exists or if resolution fails.
     */
    suspend fun getCachedRedirectUrl(originalUrl: String): String? {
        return try {
            redirectUrlCache.get(originalUrl)
        } catch (e: Exception) {
            logger.debug("Failed to get cached redirect URL for {}: {}", originalUrl.take(100), e.message)
            null
        }
    }
    
    /**
     * Invalidates the cached redirect URL for a given original URL.
     * Call this when a cached redirect URL fails, so it will be re-resolved next time.
     */
    fun invalidateRedirectUrlCache(originalUrl: String) {
        redirectUrlCache.invalidate(originalUrl)
        logger.debug("Invalidated redirect URL cache for: {}", originalUrl.take(100))
    }
    
    /**
     * Pre-populates the cache with a redirect URL.
     * Useful when we've already resolved a redirect and want to cache it for future use.
     */
    fun cacheRedirectUrl(originalUrl: String, redirectUrl: String) {
        redirectUrlCache.put(originalUrl, redirectUrl)
        logger.debug("Cached redirect URL: originalUrl={}, redirectUrl={}", originalUrl.take(100), redirectUrl.take(100))
    }
    
    /**
     * Attempts to fetch the actual file size from the IPTV URL using HTTP GET request with Range header (bytes=0-0).
     * Uses cached redirect URL if available to avoid slow redirect resolution.
     * Extracts file size from Content-Range header (e.g., "bytes 0-0/1882075726").
     * Falls back to Content-Length header if Content-Range is not available.
     * Falls back to estimated size if request fails or headers are not available.
     * Uses retry logic based on streaming configuration.
     * 
     * @param url The resolved IPTV URL
     * @param contentType The content type (for fallback estimation)
     * @param providerName The IPTV provider name (for login call before fetching)
     * @return The actual file size if available, otherwise estimated size
     */
    private suspend fun fetchActualFileSize(url: String, contentType: ContentType, providerName: String?): Long {
        // Make an initial login/test call to the provider before fetching file size
        // Rate limiting: shared across all services per provider
        if (providerName != null) {
            try {
                if (iptvLoginRateLimitService.shouldRateLimit(providerName)) {
                    val timeSinceLastCall = iptvLoginRateLimitService.getTimeSinceLastCall(providerName)
                    logger.debug("Skipping IPTV provider login call for $providerName before fetching file size (rate limited, last call was ${timeSinceLastCall}ms ago)")
                } else {
                    val providerConfigs = iptvConfigurationService.getProviderConfigurations()
                    val providerConfig = providerConfigs.find { it.name == providerName }
                    if (providerConfig != null && providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                        logger.debug("Making initial login call to IPTV provider $providerName before fetching file size")
                        val loginSuccess = xtreamCodesClient.verifyAccount(providerConfig)
                        // Update timestamp after successful call
                        iptvLoginRateLimitService.recordLoginCall(providerName)
                        if (!loginSuccess) {
                            logger.warn("IPTV provider login verification failed for $providerName, but continuing with file size fetch")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to make initial login call to IPTV provider $providerName before fetching file size: ${e.message}, continuing with fetch attempt", e)
            }
        }
        
        val maxRetries = debridavConfigurationProperties.streamingRetriesOnProviderError.toInt()
        val delayBetweenRetries = debridavConfigurationProperties.streamingDelayBetweenRetries
        val waitAfterNetworkError = debridavConfigurationProperties.streamingWaitAfterNetworkError
        
        for (attempt in 0..maxRetries) {
            try {
                // Diagnostics show redirect URLs expire almost immediately (within seconds)
                // Always use original URL and follow redirects fresh - never use cached redirects
                val targetUrl = url
                
                val response = httpClient.get(targetUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 5000 // 5 second timeout - fail fast for slow providers
                        connectTimeoutMillis = 2000 // 2 second connect timeout
                    }
                }
                
                // Check if this is a redirect response - redirects don't have content bodies
                // HttpRedirect plugin may not follow redirects for range requests, so we need to handle it manually
                if (response.status.value in 300..399) {
                    val redirectLocation = response.headers["Location"]
                    if (redirectLocation != null) {
                        logger.debug("IPTV file size request received redirect response (status ${response.status.value}), following redirect manually: originalUrl={}, redirectLocation={}", url, redirectLocation)
                        
                        // Consume the redirect response body to ensure proper cleanup
                        try {
                            response.body<ByteReadChannel>()
                        } catch (e: Exception) {
                            // Ignore errors when consuming redirect body
                        }
                        
                        // Make new request to redirect location with Range header preserved
                        val redirectUrl = if (redirectLocation.startsWith("http://") || redirectLocation.startsWith("https://")) {
                            redirectLocation
                        } else {
                            // Relative redirect - construct absolute URL using URI
                            val originalUri = java.net.URI(url)
                            originalUri.resolve(redirectLocation).toString()
                        }
                        
                        // Do NOT cache redirect URL - diagnostics show they expire almost immediately
                        // Cache would cause 404 errors on subsequent requests
                        
                        // Create new request to redirect URL with Range header
                        // Use shorter timeout for redirects (3 seconds) - if redirect URLs are slow,
                        // we'll fall back to estimated size faster rather than blocking on slow providers
                        val redirectResponse = httpClient.get(redirectUrl) {
                            headers {
                                append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                                append(HttpHeaders.Range, "bytes=0-0")
                            }
                            timeout {
                                requestTimeoutMillis = 3000 // 3 second timeout - fail fast for slow redirect URLs
                                connectTimeoutMillis = 2000 // 2 second connect timeout
                            }
                        }
                        
                        // Check if redirect response was successful
                        if (!redirectResponse.status.isSuccess()) {
                            logger.debug("HTTP GET request to redirect URL returned non-success status ${redirectResponse.status.value} for IPTV URL, using estimated size (redirectUrl: $redirectUrl)")
                            try {
                                redirectResponse.body<ByteReadChannel>()
                            } catch (e: Exception) {
                                // Ignore errors when consuming response body
                            }
                            return estimateIptvSize(contentType)
                        }
                        
                        // Try to extract file size from Content-Range header first (e.g., "bytes 0-0/1882075726")
                        val contentRange = redirectResponse.headers["Content-Range"]
                        if (contentRange != null) {
                            // Parse Content-Range: bytes 0-0/1882075726
                            val rangeRegex = Regex("bytes\\s+\\d+-\\d+/(\\d+)")
                            val matchResult = rangeRegex.find(contentRange)
                            val totalSize = matchResult?.groupValues?.get(1)?.toLongOrNull()
                            if (totalSize != null && totalSize > 0) {
                                logger.debug("Retrieved actual file size from IPTV redirect URL Content-Range header: $totalSize bytes (redirectUrl: $redirectUrl)")
                                try {
                                    redirectResponse.body<io.ktor.utils.io.ByteReadChannel>()
                                } catch (e: Exception) {
                                    // Ignore errors when consuming response body
                                }
                                return totalSize
                            }
                        }
                        
                        // Fallback to Content-Length header if Content-Range is not available
                        val contentLength = redirectResponse.headers["Content-Length"]?.toLongOrNull()
                        try {
                            redirectResponse.body<io.ktor.utils.io.ByteReadChannel>()
                        } catch (e: Exception) {
                            // Ignore errors when consuming response body
                        }
                        if (contentLength != null && contentLength > 0) {
                            logger.debug("Retrieved actual file size from IPTV redirect URL Content-Length header: $contentLength bytes (redirectUrl: $redirectUrl)")
                            return contentLength
                        } else {
                            logger.debug("Content-Range and Content-Length headers not available for IPTV redirect URL, using estimated size (redirectUrl: $redirectUrl)")
                            return estimateIptvSize(contentType)
                        }
                    } else {
                        // No redirect location - fallback to estimated size
                        logger.debug("HTTP GET request returned redirect status ${response.status.value} but no Location header for IPTV URL, using estimated size ($url)")
                                try {
                                    response.body<ByteReadChannel>()
                                } catch (e: Exception) {
                                    // Ignore errors when consuming response body
                                }
                        return estimateIptvSize(contentType)
                    }
                }
                
                // Check if request was successful (non-redirect)
                if (!response.status.isSuccess()) {
                    logger.debug("HTTP GET request returned non-success status ${response.status.value} for IPTV URL, using estimated size ($url)")
                                try {
                                    response.body<ByteReadChannel>()
                                } catch (e: Exception) {
                                    // Ignore errors when consuming response body
                                }
                    return estimateIptvSize(contentType)
                }
                
                // Try to extract file size from Content-Range header first (e.g., "bytes 0-0/1882075726")
                val contentRange = response.headers["Content-Range"]
                if (contentRange != null) {
                    // Parse Content-Range: bytes 0-0/1882075726
                    val rangeRegex = Regex("bytes\\s+\\d+-\\d+/(\\d+)")
                    val matchResult = rangeRegex.find(contentRange)
                    val totalSize = matchResult?.groupValues?.get(1)?.toLongOrNull()
                    if (totalSize != null && totalSize > 0) {
                        logger.debug("Retrieved actual file size from IPTV URL Content-Range header: $totalSize bytes ($url)")
                                try {
                                    response.body<ByteReadChannel>()
                                } catch (e: Exception) {
                                    // Ignore errors when consuming response body
                                }
                        return totalSize
                    }
                }
                
                // Fallback to Content-Length header if Content-Range is not available
                val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                                try {
                                    response.body<ByteReadChannel>()
                                } catch (e: Exception) {
                                    // Ignore errors when consuming response body
                                }
                if (contentLength != null && contentLength > 0) {
                    logger.debug("Retrieved actual file size from IPTV URL Content-Length header: $contentLength bytes ($url)")
                    return contentLength
                } else {
                    logger.debug("Content-Range and Content-Length headers not available for IPTV URL, using estimated size ($url)")
                    return estimateIptvSize(contentType)
                }
            } catch (e: Exception) {
                val isNetworkError = e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("network", ignoreCase = true) == true
                
                if (attempt < maxRetries) {
                    val waitTime = if (isNetworkError) waitAfterNetworkError else delayBetweenRetries
                    logger.debug("Failed to fetch file size from IPTV URL (attempt ${attempt + 1}/${maxRetries + 1}), retrying after ${waitTime.toMillis()}ms: ${e.message}")
                    kotlinx.coroutines.delay(waitTime.toMillis())
                } else {
                    logger.warn("Failed to fetch file size from IPTV URL after ${maxRetries + 1} attempts ($url), using estimated size: ${e.message}")
                }
            }
        }
        
        // Fallback to estimated size if all retries failed
        return estimateIptvSize(contentType)
    }
    
    /**
     * Estimates file size for IPTV content based on content type.
     * Since IPTV streams don't have known sizes, we provide reasonable estimates.
     */
    private fun estimateIptvSize(contentType: ContentType): Long {
        return when (contentType) {
            ContentType.MOVIE -> 2_000_000_000L // ~2GB for movies
            ContentType.SERIES -> 1_000_000_000L // ~1GB for episodes
        }
    }
    
    /**
     * Checks if a file size is a default/estimated value.
     * Default values are 2GB for movies and 1GB for episodes.
     * 
     * @param fileSize The file size to check
     * @param contentType The content type (MOVIE or SERIES)
     * @return true if the file size matches the default value for the content type
     */
    fun isDefaultFileSize(fileSize: Long?, contentType: ContentType): Boolean {
        if (fileSize == null) return false
        val defaultSize = estimateIptvSize(contentType)
        return fileSize == defaultSize
    }
    
    /**
     * Gets the content type for an IPTV content reference ID.
     * 
     * @param iptvContentRefId The IPTV content reference ID
     * @return The content type, or null if not found
     */
    fun getContentTypeForRefId(iptvContentRefId: Long?): ContentType? {
        if (iptvContentRefId == null) return null
        return try {
            val iptvContentEntity = iptvContentRepository.findById(iptvContentRefId).orElse(null)
            iptvContentEntity?.contentType
        } catch (e: Exception) {
            logger.debug("Failed to get content type for refId $iptvContentRefId: ${e.message}")
            null
        }
    }
    
    /**
     * Attempts to refetch the actual file size from the IPTV provider and update it in the database.
     * This is useful when a file has a default file size assigned and we need the actual size for byte range headers.
     * 
     * @param iptvContent The IPTV content entity to update
     * @param remotelyCachedEntity The remotely cached entity containing the file
     * @return The new file size if successfully fetched, null otherwise
     */
    @Transactional
    suspend fun refetchAndUpdateFileSize(
        iptvContent: DebridIptvContent,
        remotelyCachedEntity: io.skjaere.debridav.fs.RemotelyCachedEntity
    ): Long? {
        val iptvUrl = iptvContent.iptvUrl
        val providerName = iptvContent.iptvProviderName
        
        if (iptvUrl == null || providerName == null) {
            logger.debug("Cannot refetch file size: missing URL or provider name")
            return null
        }
        
        // Determine content type from the IPTV content entity
        val contentType = try {
            val iptvContentRefId = iptvContent.iptvContentRefId
            if (iptvContentRefId == null) {
                logger.debug("Cannot determine content type: iptvContentRefId is null")
                ContentType.MOVIE // Default to MOVIE if we can't determine
            } else {
                val iptvContentEntity = iptvContentRepository.findById(iptvContentRefId).orElse(null)
                if (iptvContentEntity == null) {
                    logger.debug("IPTV content entity not found for refId: $iptvContentRefId")
                    ContentType.MOVIE // Default to MOVIE if entity not found
                } else {
                    iptvContentEntity.contentType
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to determine content type for file size refetch: ${e.message}")
            // Default to MOVIE if we can't determine
            ContentType.MOVIE
        }
        
        logger.info("Refetching file size for IPTV content: url={}, iptvProvider={}, contentType={}", 
            iptvUrl.take(100), providerName, contentType)
        
        try {
            val oldFileSize = iptvContent.size
            val newFileSize = fetchActualFileSize(iptvUrl, contentType, providerName)
            
            // Only update if we got a different size
            if (newFileSize != oldFileSize) {
                // Update the DebridIptvContent entity
                iptvContent.size = newFileSize
                
                // Update the RemotelyCachedEntity size
                remotelyCachedEntity.size = newFileSize
                
                // Update IptvFile link size if present
                iptvContent.debridLinks.firstOrNull()?.let { link ->
                    if (link is IptvFile) {
                        link.size = newFileSize
                    }
                }
                
                // Save the updated entity
                databaseFileService.saveDbEntity(remotelyCachedEntity)
                
                logger.info("Successfully updated file size: oldSize={}, newSize={}, url={}", 
                    oldFileSize, newFileSize, iptvUrl.take(100))
                
                return newFileSize
            } else {
                logger.debug("File size unchanged or still default: oldSize={}, newSize={}, url={}", 
                    oldFileSize, newFileSize, iptvUrl.take(100))
                return newFileSize
            }
        } catch (e: Exception) {
            logger.warn("Failed to refetch file size from IPTV provider: url={}, iptvProvider={}, error={}", 
                iptvUrl.take(100), providerName, e.message, e)
            return null
        }
    }
    
    /**
     * Attempts to extract quality information from title.
     * Returns quality string like "1080p", "720p", "4K", etc. or null if not found.
     */
    private fun extractQualityFromTitle(title: String): String? {
        val qualityPatterns = listOf(
            Regex("4K|2160p", RegexOption.IGNORE_CASE),
            Regex("1080p|FHD", RegexOption.IGNORE_CASE),
            Regex("720p|HD", RegexOption.IGNORE_CASE),
            Regex("480p|SD", RegexOption.IGNORE_CASE)
        )
        
        return qualityPatterns.firstOrNull { it.containsMatchIn(title) }?.find(title)?.value?.uppercase()
    }
    
    /**
     * Extracts resolution from video width and height, rounding to closest standard resolution.
     * Returns "1080p", "720p", or "480p" based on height.
     */
    private fun extractResolutionFromVideo(width: Int?, height: Int?): String? {
        if (height == null) return null
        
        // Round to closest standard resolution based on height
        return when {
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            else -> "480p" // Default to 480p for lower resolutions
        }
    }
    
    /**
     * Extracts file size from video tags (NUMBER_OF_BYTES-eng).
     * Returns file size in bytes, or null if not found.
     */
    private fun extractFileSizeFromVideoTags(tags: Map<String, String>?): Long? {
        if (tags == null) return null
        
        // Try different tag key variations
        val sizeKeys = listOf(
            "NUMBER_OF_BYTES-eng",
            "NUMBER_OF_BYTES",
            "NUMBER_OF_BYTES-ENG"
        )
        
        for (key in sizeKeys) {
            val bytesStr = tags[key]
            if (bytesStr != null) {
                val bytes = bytesStr.toLongOrNull()
                if (bytes != null && bytes > 0) {
                    return bytes
                }
            }
        }
        
        return null
    }
    
    /**
     * Maps video codec name to magnet title codec format.
     * Returns "x265" for HEVC/H.265, "x264" for H.264, or "x264" as default.
     */
    private fun mapCodecToMagnetFormat(codecName: String?): String {
        if (codecName == null) return "x264"
        
        return when (codecName.lowercase()) {
            "hevc", "h.265", "h265" -> "x265"
            "h264", "h.264", "avc" -> "x264"
            else -> "x264" // Default to x264
        }
    }
    
    /**
     * Formats title for Radarr compatibility.
     * Radarr expects titles in format like: "Movie.Title.1990.1080p.BluRay.x264-GROUP"
     * We'll add quality and encoding info if available.
     */
    private fun formatTitleForRadarr(originalTitle: String, year: Int?, quality: String?, languageCodeToRemove: String? = null, episode: String? = null, codec: String? = null): String {
        // STEP 1: Remove configured language prefixes if present (e.g., "EN| ", "EN - ", "NL| ")
        var cleanTitle = removeLanguagePrefixes(originalTitle)
        
        // STEP 2: Also remove any language code pattern from the beginning (e.g., "PRMT - ", "NL| ")
        // This handles cases where language codes are not in configured prefixes
        cleanTitle = removeLanguageCodePrefix(cleanTitle)
        
        // STEP 3: If a specific language code is being added to the end, remove it from the beginning
        if (languageCodeToRemove != null) {
            cleanTitle = removeSpecificLanguageCodeFromBeginning(cleanTitle, languageCodeToRemove)
        }
        
        // STEP 4: Extract year from title if not provided
        val titleYear = year ?: extractYearFromTitle(cleanTitle)
        
        // STEP 5: Remove standalone year from title to avoid duplication (keep year in brackets if present)
        // This removes standalone "1986" if "(1986)" exists in the title
        cleanTitle = removeStandaloneYearFromTitle(cleanTitle, titleYear)
        
        // Build Radarr-compatible title
        val parts = mutableListOf<String>()
        
        // STEP 7: Add title (sanitize for filename)
        val sanitizedTitle = cleanTitle
            .replace(Regex("[<>:\"/\\|?*]"), ".")
            .replace(Regex("\\s+"), ".")
            .replace(Regex("\\.+"), ".")
            .trim('.')
        parts.add(sanitizedTitle)
        
        // STEP 8: Conditionally add year - only if not already present in the sanitized title
        // Check if year already exists in sanitized title (could be in brackets like "(1986)" or as standalone)
        val yearAlreadyInTitle = if (titleYear != null) {
            val yearPattern = Regex("(?:^|[.\\(-])$titleYear(?:$|[.)-])")
            yearPattern.containsMatchIn(sanitizedTitle)
        } else {
            true // No year to add
        }
        
        if (titleYear != null && !yearAlreadyInTitle) {
            // Year not found in sanitized title, add it in round brackets
            parts.add("($titleYear)")
        }
        // If year already exists in sanitizedTitle (in brackets or otherwise), don't add again
        
        // STEP 9: Add episode info if provided (for series)
        // Episode parameter can be in format "S08" or "S08E01"
        if (episode != null && episode.isNotBlank()) {
            // Normalize episode format - ensure it starts with S and is uppercase
            val normalizedEpisode = episode.trim().uppercase()
            // Validate format (should start with S followed by digits, optionally followed by E and more digits)
            if (normalizedEpisode.matches(Regex("^S\\d+(?:E\\d+)?$"))) {
                parts.add(normalizedEpisode)
            } else {
                logger.debug("Invalid episode format '$episode', skipping episode in title")
            }
        }
        
        // STEP 10: Add quality (default to 1080p if not detected)
        val finalQuality = quality ?: "1080p"
        parts.add(finalQuality)
        
        // STEP 11: Add source and codec (use detected codec or default to x264)
        parts.add("BluRay")
        val finalCodec = codec ?: "x264"
        parts.add(finalCodec)
        
        // STEP 11: Join parts with dots, then add release group with dash (e.g., "Movie.Title.1990.1080p.BluRay.x264-IPTV")
        val baseTitle = parts.joinToString(".")
        return "$baseTitle-IPTV"
    }
    
    /**
     * Extracts year from title if present.
     */
    private fun extractYearFromTitle(title: String): Int? {
        val yearPattern = Regex("\\b(19|20)\\d{2}\\b")
        return yearPattern.find(title)?.value?.toIntOrNull()
    }
    
    /**
     * Removes any language code pattern from the beginning of a title.
     * Pattern: uppercase letters followed by '|' or '-' and optional space
     * Examples: "PRMT - ", "NL| ", "EN - "
     */
    private fun removeLanguageCodePrefix(title: String): String {
        val languagePattern = Regex("^([A-Z]{2,})\\s*[|\\-]\\s*")
        return languagePattern.replace(title, "").trimStart()
    }
    
    /**
     * Removes a specific language code from the beginning of a title.
     * Handles patterns like "PRMT - ", "PRMT| ", "PRMT-", etc.
     */
    private fun removeSpecificLanguageCodeFromBeginning(title: String, languageCode: String): String {
        // Try various patterns: "PRMT - ", "PRMT| ", "PRMT-", "PRMT|", etc.
        val patterns = listOf(
            Regex("^$languageCode\\s*[|\\-]\\s+", RegexOption.IGNORE_CASE),
            Regex("^$languageCode\\s*[|\\-]", RegexOption.IGNORE_CASE),
            Regex("^$languageCode\\s+", RegexOption.IGNORE_CASE)
        )
        
        var result = title
        for (pattern in patterns) {
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, "").trimStart()
                break
            }
        }
        return result
    }
    
    /**
     * Extracts language code from the end of a title.
     * Looks for patterns like "-PRMT", ".PRMT" at the end of the title.
     * Returns the language code if found, null otherwise.
     */
    private fun extractLanguageCodeFromEnd(title: String): String? {
        // Pattern: ends with "-CODE" or ".CODE" where CODE is 2+ uppercase letters
        val endPattern = Regex("[-.]([A-Z]{2,})$")
        val match = endPattern.find(title)
        return match?.groupValues?.get(1)?.uppercase()
    }
    
    /**
     * Removes standalone year from title if year is already present in brackets.
     * Example: "Movie (1986) 1986" -> "Movie (1986)"
     * Keeps the year in brackets and removes standalone duplicate.
     * Also handles cases where brackets become dots after sanitization: "Movie.(1986).1986" -> "Movie.(1986)"
     */
    private fun removeStandaloneYearFromTitle(title: String, year: Int?): String {
        if (year == null) {
            return title
        }
        
        val yearStr = year.toString()
        val yearInBrackets = "($yearStr)"
        val yearInBracketsWithDots = "\\.\\($yearStr\\)\\." // Pattern: .(1986).
        
        // Check if year is already in brackets (either as "(1986)" or ".(1986).")
        val hasYearInBrackets = title.contains(yearInBrackets) || 
                               Regex(yearInBracketsWithDots).containsMatchIn(title)
        
        if (hasYearInBrackets) {
            // Remove standalone year occurrences (not in brackets)
            // Pattern: word boundary or dot, year, word boundary or dot (but not if preceded by '(' or followed by ')')
            // Handle cases like: "Movie.(1986).1986" -> "Movie.(1986)"
            val standaloneYearPattern = Regex("(?<![(])(?:^|[.\\s-])$yearStr(?![)])(?:$|[.\\s-])")
            var result = standaloneYearPattern.replace(title, "")
            // Clean up multiple consecutive dots/spaces
            result = result.replace(Regex("\\.{2,}"), ".")
            result = result.replace(Regex("\\s{2,}"), " ")
            return result.trim()
        }
        
        return title
    }
    
    fun searchIptvContent(title: String, year: Int?, contentType: ContentType?, useArticleVariations: Boolean = true, episode: String? = null, startYear: Int? = null, endYear: Int? = null): List<IptvSearchResult> {
        val results = iptvContentService.searchContent(title, year, contentType, useArticleVariations)
        
        // Parse episode parameter to extract season number if provided (e.g., "S08" -> 8)
        val requestedSeason = episode?.let { parseSeasonFromEpisode(it) }
        // Parse episode parameter to extract episode number if provided (e.g., "S07E01" -> 1)
        val requestedEpisodeNumber = episode?.let { parseEpisodeNumberFromEpisode(it) }
        
        // For series with episode parameter, fetch episodes to verify season availability and calculate accurate size
        // Store episode count per entity for size calculation
        val entityEpisodeCounts = mutableMapOf<String, Int>() // Key: "${providerName}_${contentId}", Value: episode count
        // Store requested episode number per entity for single episode size calculation
        val entityRequestedEpisodeNumbers = mutableMapOf<String, Int>() // Key: "${providerName}_${contentId}", Value: requested episode number
        // Store series year from info per entity for magnet title
        val entitySeriesYears = mutableMapOf<String, Int>() // Key: "${providerName}_${contentId}", Value: series year from info
        // Store S01E01 file size per entity for accurate size calculation
        val entityS01E01FileSizes = mutableMapOf<String, Long>() // Key: "${providerName}_${contentId}", Value: S01E01 file size in bytes
        // Store resolution per entity from S01E01 video metadata
        val entityResolutions = mutableMapOf<String, String>() // Key: "${providerName}_${contentId}", Value: resolution (1080p, 720p, 480p)
        // Store codec per entity from S01E01 video metadata
        val entityCodecs = mutableMapOf<String, String>() // Key: "${providerName}_${contentId}", Value: codec (x265, x264)
        
        val seriesWithEpisodes = if (contentType == ContentType.SERIES && requestedSeason != null) {
            logger.debug("Episode parameter provided (episode=$episode, parsed season=$requestedSeason), fetching episodes for series to verify availability")
            results.filter { entity ->
                // Only check Xtream Codes providers
                val providerConfig = iptvConfigurationService.getProviderConfigurations()
                    .find { it.name == entity.providerName && it.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES }
                
                if (providerConfig != null) {
                    // Fetch episodes to verify season exists and get series info for year comparison
                    val (seriesInfo, episodes) = try {
                        val cachedMetadata = iptvSeriesMetadataRepository.findByProviderNameAndSeriesId(entity.providerName, entity.contentId)
                        if (cachedMetadata != null) {
                            val cacheAge = java.time.Duration.between(cachedMetadata.lastAccessed, java.time.Instant.now())
                            if (cacheAge < iptvConfigurationProperties.seriesMetadataCacheTtl) {
                                logger.debug("Using cached episodes for series ${entity.contentId} during search (cache age: ${cacheAge.toHours()} hours)")
                                
                                // Update last accessed time before using cached data
                                cachedMetadata.lastAccessed = java.time.Instant.now()
                                iptvSeriesMetadataRepository.save(cachedMetadata)
                                
                                // Parse from cached JSON response
                                logger.debug("Parsing episodes from cached JSON response for series ${entity.contentId}")
                                val (cachedSeriesInfo, cachedEpisodes) = parseSeriesEpisodesFromJson(providerConfig, entity.contentId, cachedMetadata.responseJson)
                                
                                if (cachedSeriesInfo != null) {
                                    logger.debug("Retrieved series info from cache: name='${cachedSeriesInfo.name}', releaseDate='${cachedSeriesInfo.releaseDate}', release_date='${cachedSeriesInfo.release_date}'")
                                }
                                Pair(cachedSeriesInfo, cachedEpisodes)
                            } else {
                                // Cache expired, fetch fresh
                                fetchAndCacheEpisodes(providerConfig, entity.providerName, entity.contentId)
                            }
                        } else {
                            // No cache, fetch fresh
                            fetchAndCacheEpisodes(providerConfig, entity.providerName, entity.contentId)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch episodes for series ${entity.contentId} during search: ${e.message}", e)
                        Pair(null, emptyList())
                    }
                    
                    // Extract year from series info and compare with requested year range
                    val seriesYear = seriesInfo?.let { info ->
                        extractYearFromReleaseDate(info.release_date ?: info.releaseDate)
                    }
                    
                    // Store series year for magnet title generation
                    if (seriesYear != null) {
                        entitySeriesYears["${entity.providerName}_${entity.contentId}"] = seriesYear
                        logger.debug("Stored series year $seriesYear from info for series ${entity.contentId} (${entity.title})")
                    }
                    
                    // Check year match: Compare series year (startYear from info) with OMDB startYear
                    // The series year from info is the startYear that should match the OMDB startYear
                    val yearMatches = if (startYear != null) {
                        when {
                            seriesYear == null -> {
                                // No year in series info, include it (can't verify)
                                logger.debug("Series ${entity.contentId} (${entity.title}) has no release date, including in results")
                                true
                            }
                            else -> {
                                // Compare series year (from info) with OMDB startYear
                                // Allow some flexibility: exact match or within 1 year (for rounding differences)
                                val matches = seriesYear == startYear || (seriesYear >= startYear && seriesYear <= (startYear + 1))
                                if (!matches) {
                                    logger.debug("Series ${entity.contentId} (${entity.title}) year $seriesYear (from info) does not match OMDB startYear $startYear")
                                } else {
                                    logger.debug("Series ${entity.contentId} (${entity.title}) year $seriesYear (from info) matches OMDB startYear $startYear")
                                }
                                matches
                            }
                        }
                    } else {
                        true // No year filtering requested
                    }
                    
                    if (!yearMatches) {
                        return@filter false
                    }
                    
                    // Find S01E01 to extract file size, resolution, and codec (should always exist)
                    val s01e01 = episodes.find { it.season == 1 && it.episode == 1 }
                    if (s01e01 != null) {
                        // Extract file size from video tags
                        val s01e01FileSize = s01e01.info?.video?.tags?.let { tags ->
                            extractFileSizeFromVideoTags(tags)
                        }
                        if (s01e01FileSize != null) {
                            entityS01E01FileSizes["${entity.providerName}_${entity.contentId}"] = s01e01FileSize
                            logger.debug("Extracted S01E01 file size for series ${entity.contentId}: ${s01e01FileSize / 1_000_000}MB")
                        }
                        
                        // Extract resolution from video metadata
                        val resolution = s01e01.info?.video?.let { video ->
                            extractResolutionFromVideo(video.width, video.height)
                        }
                        if (resolution != null) {
                            entityResolutions["${entity.providerName}_${entity.contentId}"] = resolution
                            logger.debug("Extracted resolution from S01E01 for series ${entity.contentId}: $resolution (width=${s01e01.info?.video?.width}, height=${s01e01.info?.video?.height})")
                        }
                        
                        // Extract codec from video metadata
                        val codec = s01e01.info?.video?.codec_name?.let { codecName ->
                            mapCodecToMagnetFormat(codecName)
                        }
                        if (codec != null) {
                            entityCodecs["${entity.providerName}_${entity.contentId}"] = codec
                            logger.debug("Extracted codec from S01E01 for series ${entity.contentId}: $codec (codec_name=${s01e01.info?.video?.codec_name})")
                        }
                    } else {
                        logger.debug("S01E01 not found for series ${entity.contentId}, will use default estimates")
                    }
                    
                    // Check if requested season exists
                    val hasSeason = episodes.any { it.season == requestedSeason }
                    if (!hasSeason && episodes.isNotEmpty()) {
                        val availableSeasons = episodes.mapNotNull { it.season }.distinct().sorted()
                        logger.debug("Series ${entity.contentId} (${entity.title}) does not have season $requestedSeason. Available seasons: $availableSeasons")
                    } else if (hasSeason) {
                        val seasonEpisodes = episodes.filter { it.season == requestedSeason }
                        val episodeCount = seasonEpisodes.size
                        logger.debug("Series ${entity.contentId} (${entity.title}, year=$seriesYear) has $episodeCount episodes in season $requestedSeason")
                        // Log episode details at DEBUG level - only for the requested season
                        logger.debug("Episodes for series ${entity.contentId} (${entity.title}), season $requestedSeason:")
                        seasonEpisodes.forEach { ep ->
                            logger.debug("  Episode: id=${ep.id}, title='${ep.title}', season=${ep.season}, episode=${ep.episode}, extension=${ep.container_extension ?: "mp4"}")
                        }
                        // Store episode count for size calculation
                        entityEpisodeCounts["${entity.providerName}_${entity.contentId}"] = episodeCount
                        // Store requested episode number if a specific episode was requested
                        if (requestedEpisodeNumber != null) {
                            entityRequestedEpisodeNumbers["${entity.providerName}_${entity.contentId}"] = requestedEpisodeNumber
                        }
                    }
                    hasSeason
                } else {
                    // Not Xtream Codes provider, include it (can't verify episodes)
                    true
                }
            }
        } else {
            // No episode parameter or not a series, return all results
            results
        }
        
        return seriesWithEpisodes.map { entity ->
            // Log initial content title
            logger.debug("Generating magnet title - initial content title: {}", entity.title)
            
            // Generate infohash from providerName and contentId (now returns hex string)
            val infohash = generateIptvHash(entity.providerName, entity.contentId)
            // Include hash in URL for easy extraction: iptv://{hash}/{providerName}/{contentId}
            val guid = "iptv://$infohash/${entity.providerName}/${entity.contentId}"
            
            // Try to extract quality from title
            var quality = extractQualityFromTitle(entity.title)
            
            // For series, try to get resolution from S01E01 metadata first (more accurate than title)
            if (entity.contentType == ContentType.SERIES) {
                val resolutionFromMetadata = entityResolutions["${entity.providerName}_${entity.contentId}"]
                if (resolutionFromMetadata != null) {
                    quality = resolutionFromMetadata
                    logger.debug("Using resolution from S01E01 metadata for series ${entity.contentId}: $resolutionFromMetadata")
                }
            }
            
            // STEP 1: Identify language code early - check if IPTV content title starts with any configured language prefix
            // If not, extract language code and adjust quality if needed
            val languageCode = extractLanguageCodeIfNotInPrefixes(entity.title)
            
            // STEP 2: Adjust quality based on language code (if valid ISO 639-1 language code but not EN)
            if (languageCode != null && isValidLanguageCode(languageCode) && languageCode != "EN") {
                // Valid non-EN language code found, downgrade quality to 480p
                val currentQuality = quality ?: "1080p"
                if (currentQuality.equals("1080p", ignoreCase = true) || currentQuality.equals("FHD", ignoreCase = true)) {
                    quality = "480p"
                    logger.debug("Valid language code '$languageCode' detected (non-EN), downgrading quality from 1080p to 480p")
                }
            } else if (languageCode != null && !isValidLanguageCode(languageCode)) {
                logger.debug("Extracted code '$languageCode' is not a valid ISO 639-1 language code, skipping quality adjustment")
            }
            
            // STEP 3: Format title for Radarr compatibility (includes quality, codec, etc.)
            // This will remove language code from beginning and handle duplicate years
            // Include episode info (e.g., "S08" or "S08E01") in title if provided (for series)
            // Use series year from info if available, otherwise fall back to year parameter
            val yearForTitle = entitySeriesYears["${entity.providerName}_${entity.contentId}"] ?: year
            // Get codec from S01E01 metadata if available (for series)
            val codecForTitle = if (entity.contentType == ContentType.SERIES) {
                entityCodecs["${entity.providerName}_${entity.contentId}"]
            } else {
                null
            }
            var radarrTitle = formatTitleForRadarr(entity.title, yearForTitle, quality, languageCode, episode, codecForTitle)
            
            // STEP 4: Conditionally build release group suffix - only add language code if not already at the end
            val releaseGroupParts = mutableListOf("IPTV")
            
            // Add provider name if configured
            if (iptvConfigurationProperties.includeProviderInMagnetTitle) {
                releaseGroupParts.add(entity.providerName)
            }
            
            // Conditionally add language code - only if it's not already at the end of the formatted title
            val shouldAddLanguageCode = languageCode != null && 
                                       !radarrTitle.endsWith("-$languageCode", ignoreCase = true) &&
                                       !radarrTitle.endsWith(".$languageCode", ignoreCase = true)
            
            if (shouldAddLanguageCode) {
                releaseGroupParts.add(languageCode)
            }
            
            // STEP 5: Replace -IPTV with the full release group (e.g., -IPTV-mega-NL)
            if (radarrTitle.endsWith("-IPTV", ignoreCase = true)) {
                radarrTitle = "${radarrTitle.removeSuffix("-IPTV")}-${releaseGroupParts.joinToString("-")}"
            } else if (shouldAddLanguageCode || iptvConfigurationProperties.includeProviderInMagnetTitle) {
                // Title doesn't end with -IPTV, append the release group
                radarrTitle = "$radarrTitle-${releaseGroupParts.joinToString("-")}"
            }
            
            // Log final magnet title
            logger.debug("Generating magnet title - final magnet title: {}", radarrTitle)
            
            // Note: The magnet title should NOT contain the media extension
            // The extension will be added when creating the actual file, but the magnet title
            // (used as folder name) should be without extension
            
            // Create magnet URI for Radarr compatibility - use formatted title without extension
            val magnetUri = createIptvMagnetUri(infohash, radarrTitle, guid)
            
            // Calculate size for Radarr compatibility
            // For series with episode parameter, calculate size based on number of episodes
            // If a specific episode is requested (e.g., S07E01), use size for 1 episode only
            val estimatedSize = if (entity.contentType == ContentType.SERIES && requestedSeason != null) {
                // Check if a specific episode number was requested
                val requestedEpisode = entityRequestedEpisodeNumbers["${entity.providerName}_${entity.contentId}"]
                val episodeCount = if (requestedEpisode != null) {
                    // Specific episode requested, use count of 1
                    1
                } else {
                    // No specific episode requested, use season episode count
                    entityEpisodeCounts["${entity.providerName}_${entity.contentId}"] ?: 0
                }
                
                if (episodeCount > 0) {
                    // Try to use S01E01 file size for accurate calculation
                    val s01e01FileSize = entityS01E01FileSizes["${entity.providerName}_${entity.contentId}"]
                    if (s01e01FileSize != null && s01e01FileSize > 0) {
                        // Use S01E01 file size as base and multiply by episode count
                        val calculatedSize = episodeCount * s01e01FileSize
                        if (requestedEpisode != null) {
                            logger.debug("Calculated size for series ${entity.contentId} episode S${requestedSeason}E${requestedEpisode} using S01E01 file size: $episodeCount episode × ${s01e01FileSize / 1_000_000}MB = ${calculatedSize / 1_000_000_000.0}GB")
                        } else {
                            logger.debug("Calculated size for series ${entity.contentId} using S01E01 file size: $episodeCount episodes × ${s01e01FileSize / 1_000_000}MB = ${calculatedSize / 1_000_000_000.0}GB")
                        }
                        calculatedSize
                    } else {
                        // Fallback to per-episode estimates based on quality
                        // Use per-episode estimates: ~120MB for 1080p, ~60MB for 720p, ~30MB for 480p
                        // These are generous estimates to ensure Sonarr accepts the release
                        val perEpisodeSize = when {
                            quality?.contains("1080", ignoreCase = true) == true -> 120_000_000L // ~120MB per episode for 1080p
                            quality?.contains("720", ignoreCase = true) == true -> 60_000_000L // ~60MB per episode for 720p
                            quality?.contains("480", ignoreCase = true) == true -> 30_000_000L // ~30MB per episode for 480p
                            else -> 120_000_000L // Default to 120MB for 1080p
                        }
                        val calculatedSize = episodeCount * perEpisodeSize
                        if (requestedEpisode != null) {
                            logger.debug("Calculated size for series ${entity.contentId} episode S${requestedSeason}E${requestedEpisode} using quality-based estimates: $episodeCount episode × ${perEpisodeSize / 1_000_000}MB = ${calculatedSize / 1_000_000_000.0}GB")
                        } else {
                            logger.debug("Calculated size for series ${entity.contentId} using quality-based estimates: $episodeCount episodes × ${perEpisodeSize / 1_000_000}MB = ${calculatedSize / 1_000_000_000.0}GB")
                        }
                        calculatedSize
                    }
                } else {
                    // Fallback to default estimate
                    estimateIptvSize(entity.contentType)
                }
            } else {
                // For movies or series without episode parameter, use default estimate
                estimateIptvSize(entity.contentType)
            }
            
            IptvSearchResult(
                contentId = entity.contentId,
                providerName = entity.providerName,
                title = radarrTitle, // Use Radarr-formatted title without extension
                contentType = entity.contentType,
                category = entity.category?.categoryName,
                guid = guid,
                infohash = infohash,
                url = magnetUri, // Use magnet URI for Radarr compatibility
                magnetUri = magnetUri, // Also provide as magnet field
                size = estimatedSize,
                quality = quality ?: "1080p" // Use adjusted quality (may be downgraded to 480p for non-EN languages)
            )
        }
    }
    
    private fun determineMimeType(title: String): String {
        return when {
            title.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            title.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            title.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            title.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            else -> "video/mp4" // Default
        }
    }
    
    /**
     * Extracts the media file extension from an IPTV URL.
     * URLs are typically in format: {BASE_URL}/movie/{USERNAME}/{PASSWORD}/401804493.mkv
     * or: {BASE_URL}/series/{USERNAME}/{PASSWORD}/12345.mp4
     * 
     * @param url The resolved IPTV URL
     * @return The file extension (without the dot), or null if not found
     */
    private fun extractMediaExtensionFromUrl(url: String): String? {
        // Extract extension from URL - look for the last dot before any query parameters
        val urlWithoutQuery = url.substringBefore("?")
        val lastDotIndex = urlWithoutQuery.lastIndexOf(".")
        if (lastDotIndex == -1 || lastDotIndex == urlWithoutQuery.length - 1) {
            return null
        }
        
        val extension = urlWithoutQuery.substring(lastDotIndex + 1)
        // Validate it's a reasonable extension (alphanumeric, 2-5 chars)
        return if (extension.matches(Regex("[a-zA-Z0-9]{2,5}"))) {
            extension.lowercase()
        } else {
            null
        }
    }
    
    /**
     * Checks if the IPTV content title starts with any configured language prefix.
     * If not, extracts the language code from the beginning of the title.
     * 
     * Language code format: uppercase letters followed by '|' or '-' (e.g., "NL| ", "NL- ")
     * 
     * @param iptvContentTitle The IPTV content title (e.g., "NL| The Breakfast Club")
     * @return The uppercase language code if not in configured prefixes, null otherwise
     */
    private fun extractLanguageCodeIfNotInPrefixes(iptvContentTitle: String): String? {
        val languagePrefixes = iptvConfigurationProperties.languagePrefixes
        
        // Check if title starts with any configured prefix (after stripping quotes)
        val startsWithConfiguredPrefix = languagePrefixes.any { prefix ->
            val cleanedPrefix = stripQuotes(prefix)
            iptvContentTitle.startsWith(cleanedPrefix, ignoreCase = false)
        }
        
        // If it starts with a configured prefix, don't extract language code
        if (startsWithConfiguredPrefix) {
            return null
        }
        
        // Extract language code from the beginning of the title
        // Pattern: uppercase letters followed by '|' or '-' and optional space
        val languagePattern = Regex("^([A-Z]{2,})\\s*[|\\-]\\s*")
        val match = languagePattern.find(iptvContentTitle)
        
        return match?.groupValues?.get(1)?.uppercase()
    }
    
    /**
     * Removes configured language prefixes from the beginning of a title.
     * This ensures that titles like "EN - Titanic (1997)" become "Titanic (1997)".
     */
    private fun removeLanguagePrefixes(title: String): String {
        val languagePrefixes = iptvConfigurationProperties.languagePrefixes
        
        // Try each configured prefix and remove the first matching one
        for (prefix in languagePrefixes) {
            val cleanedPrefix = stripQuotes(prefix)
            if (title.startsWith(cleanedPrefix, ignoreCase = false)) {
                return title.removePrefix(cleanedPrefix).trimStart()
            }
        }
        
        // Fallback to old regex pattern for backward compatibility
        return title.replace(Regex("^[A-Z]{2}\\s*[|-]\\s*"), "")
    }
    
    /**
     * Strips surrounding quotes (single or double) from a string.
     * Used to clean language prefixes that may be quoted in configuration.
     */
    private fun stripQuotes(value: String): String {
        return value.trim().removeSurrounding("\"").removeSurrounding("'")
    }
    
    /**
     * Validates if a code is a valid ISO 639-1 language code.
     * ISO 639-1 codes are 2-letter codes for languages (e.g., EN, NL, DE, FR).
     * This excludes country codes (e.g., AM for America, US for United States).
     * 
     * @param code The code to validate (should be uppercase 2-letter code)
     * @return true if the code is a valid ISO 639-1 language code, false otherwise
     */
    private fun isValidLanguageCode(code: String): Boolean {
        if (code.length != 2) {
            return false
        }
        
        // Common ISO 639-1 language codes (2-letter codes)
        // This is a subset of commonly used language codes for IPTV content
        // Note: Excludes ambiguous codes that might be used as country/region codes
        // (e.g., "AM" could mean Amharic language or America region, so we exclude it)
        val validLanguageCodes = setOf(
            "AA", "AB", "AE", "AF", "AK", "AN", "AR", "AS", "AV", "AY", "AZ", // A (excluded AM - ambiguous)
            "BA", "BE", "BG", "BH", "BI", "BM", "BN", "BO", "BR", "BS", // B
            "CA", "CE", "CH", "CO", "CR", "CS", "CU", "CV", "CY", // C
            "DA", "DE", "DV", "DZ", // D
            "EE", "EL", "EN", "EO", "ES", "ET", "EU", // E
            "FA", "FF", "FI", "FJ", "FO", "FR", "FY", // F
            "GA", "GD", "GL", "GN", "GU", "GV", // G
            "HA", "HE", "HI", "HO", "HR", "HT", "HU", "HY", "HZ", // H
            "IA", "ID", "IE", "IG", "II", "IK", "IO", "IS", "IT", "IU", // I
            "JA", "JV", // J
            "KA", "KG", "KI", "KJ", "KK", "KL", "KM", "KN", "KO", "KR", "KS", "KU", "KV", "KW", "KY", "KZ", // K
            "LA", "LB", "LG", "LI", "LN", "LO", "LT", "LU", "LV", // L
            "MG", "MH", "MI", "MK", "ML", "MN", "MR", "MS", "MT", "MY", // M
            "NA", "NB", "ND", "NE", "NG", "NL", "NN", "NO", "NR", "NV", "NY", // N
            "OC", "OJ", "OM", "OR", "OS", // O
            "PA", "PI", "PL", "PS", "PT", // P
            "QU", // Q
            "RM", "RN", "RO", "RU", "RW", // R
            "SA", "SC", "SD", "SE", "SG", "SI", "SK", "SL", "SM", "SN", "SO", "SQ", "SR", "SS", "ST", "SU", "SV", "SW", "SY", "SZ", // S
            "TA", "TE", "TG", "TH", "TI", "TK", "TL", "TN", "TO", "TR", "TS", "TT", "TW", "TY", // T
            "UG", "UK", "UR", "UZ", // U
            "VE", "VI", "VO", // V
            "WA", "WO", // W
            "XH", // X
            "YI", "YO", "YU", // Y
            "ZA", "ZH", "ZU" // Z
        )
        
        return validLanguageCodes.contains(code.uppercase())
    }
    
    private fun sanitizeFileName(fileName: String): String {
        // Remove invalid file system characters
        return fileName
            .replace(Regex("[<>:\"/\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    data class IptvSearchResult(
        @JsonProperty("contentId")
        val contentId: String,
        @JsonProperty("providerName")
        val providerName: String,
        @JsonProperty("title")
        val title: String,
        @JsonProperty("contentType")
        val contentType: ContentType,
        @JsonProperty("category")
        val category: String?,
        @JsonProperty("guid")
        val guid: String,
        @JsonProperty("infohash")
        val infohash: String,
        @JsonProperty("url")
        val url: String,
        @JsonProperty("magnetUri")
        val magnetUri: String,
        @JsonProperty("size")
        val size: Long,
        @JsonProperty("quality")
        val quality: String?
    )
}

