package io.skjaere.debridav.iptv

import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
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

    @Transactional
    fun addIptvContent(contentId: String, providerName: String, category: String): Boolean {
        logger.info("Adding IPTV content: contentId=$contentId, provider=$providerName, category=$category")
        
        val iptvContent = iptvContentRepository.findByProviderNameAndContentId(providerName, contentId)
            ?: run {
                logger.warn("IPTV content not found: provider=$providerName, contentId=$contentId")
                return false
            }
        
        if (!iptvContent.isActive) {
            logger.warn("IPTV content is inactive: provider=$providerName, contentId=$contentId")
            return false
        }
        
        // Check if this is a series that needs episode lookup
        if (iptvContent.contentType == ContentType.SERIES && iptvContent.url.startsWith("SERIES_PLACEHOLDER:")) {
            return handleSeriesContent(iptvContent, providerName, category, contentId)
        }
        
        // For movies or series with direct URLs, resolve tokenized URL to actual URL
        val resolvedUrl = try {
            iptvContentService.resolveIptvUrl(iptvContent.url, providerName)
        } catch (e: Exception) {
            logger.error("Failed to resolve IPTV URL for provider $providerName", e)
            return false
        }
        
        // Create DebridIptvContent entity
        val debridIptvContent = DebridIptvContent(
            originalPath = iptvContent.title,
            size = null, // IPTV streams may not have known size
            modified = Instant.now().toEpochMilli(),
            iptvUrl = resolvedUrl,
            iptvProviderName = providerName,
            iptvContentId = contentId,
            mimeType = determineMimeType(iptvContent.title),
            debridLinks = mutableListOf()
        )
        // Set foreign key reference for cascading deletes
        debridIptvContent.iptvContentRefId = iptvContent.id
        
        // Create IptvFile link
        val iptvFile = IptvFile(
            path = iptvContent.title,
            size = 0L, // IPTV streams may not have known size
            mimeType = debridIptvContent.mimeType ?: "video/mp4",
            link = resolvedUrl,
            params = emptyMap(),
            lastChecked = Instant.now().toEpochMilli()
        )
        
        debridIptvContent.debridLinks.add(iptvFile)
        
        // Determine file path - use category if provided, otherwise use default
        val categoryPath = categoryService.findByName(category)?.downloadPath 
            ?: debridavConfigurationProperties.downloadPath
        val filePath = "$categoryPath/${sanitizeFileName(iptvContent.title)}"
        
        // Generate hash from content ID
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
        seriesId: String
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
            
            // Create episode title
            val episodeTitle = if (episode.season != null && episode.episode != null) {
                "${iptvContent.title} - S${String.format("%02d", episode.season)}E${String.format("%02d", episode.episode)} - ${episode.title}"
            } else {
                "${iptvContent.title} - ${episode.title}"
            }
            
            // Create DebridIptvContent entity for episode
            val debridIptvContent = DebridIptvContent(
                originalPath = episodeTitle,
                size = null,
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
                size = 0L,
                mimeType = debridIptvContent.mimeType ?: "video/mp4",
                link = episodeUrl,
                params = emptyMap(),
                lastChecked = Instant.now().toEpochMilli()
            )
            
            debridIptvContent.debridLinks.add(iptvFile)
            
            // Determine file path
            val filePath = "$categoryPath/${sanitizeFileName(episodeTitle)}"
            
            // Generate hash from episode content ID
            val hash = "${providerName}_${seriesId}_${episode.id}".hashCode().toString()
            
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
    
    fun searchIptvContent(query: String, contentType: ContentType?): List<IptvSearchResult> {
        val results = iptvContentService.searchContent(query, contentType)
        return results.map { entity ->
            IptvSearchResult(
                contentId = entity.contentId,
                providerName = entity.providerName,
                title = entity.title,
                contentType = entity.contentType,
                category = entity.category?.categoryName,
                guid = "iptv://${entity.providerName}/${entity.contentId}"
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
        val guid: String
    )
}

