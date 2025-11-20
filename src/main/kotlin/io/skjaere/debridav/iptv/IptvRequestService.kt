package io.skjaere.debridav.iptv

import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    private val responseFileService: IptvResponseFileService
) {
    private val logger = LoggerFactory.getLogger(IptvRequestService::class.java)
    private val xtreamCodesClient = XtreamCodesClient(httpClient, responseFileService)
    
    // Rate limiting for IPTV provider login calls: max 1 call per minute per provider
    private val iptvLoginCallTimestamps = ConcurrentHashMap<String, Long>()
    private val IPTV_LOGIN_RATE_LIMIT_MS = 60_000L // 1 minute

    @Transactional
    fun addIptvContent(contentId: String, providerName: String, category: String, magnetTitle: String? = null): Boolean {
        logger.info("Adding IPTV content: contentId=$contentId, provider=$providerName, category=$category, magnetTitle=$magnetTitle")
        
        val iptvContent = iptvContentRepository.findByProviderNameAndContentId(providerName, contentId)
            ?: run {
                logger.warn("IPTV content not found: provider=$providerName, contentId=$contentId")
                return false
            }
        
        if (!iptvContent.isActive) {
            logger.warn("IPTV content is inactive: provider=$providerName, contentId=$contentId")
            return false
        }
        
        // Use magnet title if provided, otherwise use IPTV content title
        val titleToUse = magnetTitle ?: iptvContent.title
        
        // Check if this is a series that needs episode lookup
        if (iptvContent.contentType == ContentType.SERIES && iptvContent.url.startsWith("SERIES_PLACEHOLDER:")) {
            return handleSeriesContent(iptvContent, providerName, category, contentId, magnetTitle)
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
        magnetTitle: String? = null
    ): Boolean {
        logger.info("Handling series content: seriesId=$seriesId, title=${iptvContent.title}")
        
        // Get provider configuration
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName && it.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES }
            ?: run {
                logger.error("Provider $providerName not found or not an Xtream Codes provider")
                return false
            }
        
        // Try to get episodes from cache first
        val cachedMetadata = iptvSeriesMetadataRepository.findByProviderNameAndSeriesId(providerName, seriesId)
        val episodes = if (cachedMetadata != null) {
            // Check if cache is still valid (not expired)
            val cacheAge = java.time.Duration.between(cachedMetadata.lastAccessed, java.time.Instant.now())
            if (cacheAge < iptvConfigurationProperties.seriesMetadataCacheTtl) {
                logger.debug("Using cached episodes for series $seriesId (cache age: ${cacheAge.toHours()} hours)")
                // Update last accessed time
                cachedMetadata.lastAccessed = java.time.Instant.now()
                iptvSeriesMetadataRepository.save(cachedMetadata)
                cachedMetadata.getEpisodesAsXtreamSeriesEpisode()
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
        val episodesToCreate = if (targetEpisode != null) {
            listOf(targetEpisode)
        } else {
            // If no specific episode found, log warning and create first episode as fallback
            logger.warn("Could not determine specific episode from title '${iptvContent.title}'. Creating file for first episode.")
            listOf(episodes.first())
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
            
            // Use magnet title if available, otherwise construct episode title from IPTV content
            val episodeTitleBase = if (magnetTitle != null) {
                // Use magnet title directly if provided (it should already be episode-specific)
                magnetTitle
            } else {
                // Construct episode title from IPTV content
                if (episode.season != null && episode.episode != null) {
                    "${iptvContent.title} - S${String.format("%02d", episode.season)}E${String.format("%02d", episode.episode)} - ${episode.title}"
                } else {
                    "${iptvContent.title} - ${episode.title}"
                }
            }
            
            // Extract media file extension from the episode URL
            val mediaExtension = extractMediaExtensionFromUrl(episodeUrl) ?: extension
            
            // Check if IPTV content title starts with any configured language prefix
            // If not, extract language code and append it after .IPTV
            val languageCode = extractLanguageCodeIfNotInPrefixes(iptvContent.title)
            
            // Build episode filename: insert language code between .IPTV and media extension if needed
            val episodeTitle = if (languageCode != null && episodeTitleBase.endsWith(".IPTV", ignoreCase = true)) {
                // Insert language code between .IPTV and media extension
                "${episodeTitleBase.removeSuffix(".IPTV")}.IPTV-$languageCode.$mediaExtension"
            } else if (languageCode != null) {
                // Language code but no .IPTV suffix, append it before extension
                "$episodeTitleBase-$languageCode.$mediaExtension"
            } else {
                // No language code, just append extension
                "$episodeTitleBase.$mediaExtension"
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
            
            // Create folder structure: folder name without media extension, file inside with full name including extension
            val sanitizedTitle = sanitizeFileName(episodeTitle)
            // Remove the media extension (last extension) for folder name
            val folderName = sanitizedTitle.removeSuffix(".$mediaExtension")
            val fileName = sanitizedTitle
            val filePath = "$categoryPath/$folderName/$fileName"
            
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
     * Fetches episodes from API and caches them in the database
     */
    private fun fetchAndCacheEpisodes(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        providerName: String,
        seriesId: String
    ): List<XtreamCodesClient.XtreamSeriesEpisode> {
        val episodes = runBlocking {
            xtreamCodesClient.getSeriesEpisodes(providerConfig, seriesId)
        }
        
        if (episodes.isNotEmpty()) {
            // Save or update cache
            val metadata = iptvSeriesMetadataRepository.findByProviderNameAndSeriesId(providerName, seriesId)
                ?: IptvSeriesMetadataEntity().apply {
                    this.providerName = providerName
                    this.seriesId = seriesId
                    this.createdAt = java.time.Instant.now()
                }
            
            metadata.setEpisodesFromXtreamSeriesEpisode(episodes)
            metadata.lastAccessed = java.time.Instant.now()
            iptvSeriesMetadataRepository.save(metadata)
            logger.debug("Cached ${episodes.size} episodes for series $seriesId")
        }
        
        return episodes
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
     * Attempts to fetch the actual file size from the IPTV URL using HTTP GET request with Range header (bytes=0-0).
     * Follows redirects automatically. Extracts file size from Content-Range header (e.g., "bytes 0-0/1882075726").
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
        // Rate limiting: max 1 call per minute per provider
        if (providerName != null) {
            try {
                val now = System.currentTimeMillis()
                val lastCallTime = iptvLoginCallTimestamps[providerName] ?: 0L
                val timeSinceLastCall = now - lastCallTime
                
                if (timeSinceLastCall < IPTV_LOGIN_RATE_LIMIT_MS) {
                    logger.debug("Skipping IPTV provider login call for $providerName before fetching file size (rate limited, last call was ${timeSinceLastCall}ms ago)")
                } else {
                    val providerConfigs = iptvConfigurationService.getProviderConfigurations()
                    val providerConfig = providerConfigs.find { it.name == providerName }
                    if (providerConfig != null && providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                        logger.debug("Making initial login call to IPTV provider $providerName before fetching file size")
                        val loginSuccess = xtreamCodesClient.verifyAccount(providerConfig)
                        // Update timestamp after successful call
                        iptvLoginCallTimestamps[providerName] = now
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
                val response = httpClient.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                        append(HttpHeaders.Range, "bytes=0-0")
                    }
                    timeout {
                        requestTimeoutMillis = 10000 // 10 second timeout
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
                        
                        // Create new request to redirect URL with Range header
                        val redirectResponse = httpClient.get(redirectUrl) {
                            headers {
                                append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                                append(HttpHeaders.Range, "bytes=0-0")
                            }
                            timeout {
                                requestTimeoutMillis = 10000 // 10 second timeout
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
     * Formats title for Radarr compatibility.
     * Radarr expects titles in format like: "Movie.Title.1990.1080p.BluRay.x264-GROUP"
     * We'll add quality and encoding info if available.
     */
    private fun formatTitleForRadarr(originalTitle: String, year: Int?, quality: String?, languageCodeToRemove: String? = null): String {
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
            // Year not found in sanitized title, add it as standalone
            parts.add(titleYear.toString())
        }
        // If year already exists in sanitizedTitle (in brackets or otherwise), don't add again
        
        // STEP 9: Add quality (default to 1080p if not detected)
        val finalQuality = quality ?: "1080p"
        parts.add(finalQuality)
        
        // STEP 10: Add source and codec (common defaults for IPTV)
        parts.add("BluRay")
        parts.add("x264")
        
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
    
    fun searchIptvContent(title: String, year: Int?, contentType: ContentType?, useArticleVariations: Boolean = true): List<IptvSearchResult> {
        val results = iptvContentService.searchContent(title, year, contentType, useArticleVariations)
        return results.map { entity ->
            // Log initial content title
            logger.debug("Generating magnet title - initial content title: {}", entity.title)
            
            // Generate infohash from providerName and contentId (now returns hex string)
            val infohash = generateIptvHash(entity.providerName, entity.contentId)
            // Include hash in URL for easy extraction: iptv://{hash}/{providerName}/{contentId}
            val guid = "iptv://$infohash/${entity.providerName}/${entity.contentId}"
            
            // Try to extract quality from title
            var quality = extractQualityFromTitle(entity.title)
            
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
            var radarrTitle = formatTitleForRadarr(entity.title, year, quality, languageCode)
            
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
            
            // Estimate size for Radarr compatibility
            val estimatedSize = estimateIptvSize(entity.contentType)
            
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

