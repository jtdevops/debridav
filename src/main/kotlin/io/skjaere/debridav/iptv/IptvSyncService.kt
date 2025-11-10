package io.skjaere.debridav.iptv

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.skjaere.debridav.iptv.client.XtreamCodesClient
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.iptv.model.SeriesInfo
import io.skjaere.debridav.iptv.parser.M3uParser
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

@Service
class IptvSyncService(
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvContentService: IptvContentService,
    private val httpClient: HttpClient
) {
    private val logger = LoggerFactory.getLogger(IptvSyncService::class.java)
    private val m3uParser = M3uParser()
    private val xtreamCodesClient = XtreamCodesClient(httpClient)

    @Scheduled(
        initialDelayString = "#{T(java.time.Duration).parse(@environment.getProperty('iptv.initial-sync-delay', 'PT30S')).toMillis()}",
        fixedRateString = "\${iptv.sync-interval}"
    )
    fun syncIptvContent() {
        if (!iptvConfigurationProperties.enabled) {
            logger.debug("IPTV sync skipped - IPTV is disabled")
            return
        }

        logger.info("Starting IPTV content sync")
        val providerConfigs = iptvConfigurationService.getProviderConfigurations()

        if (providerConfigs.isEmpty()) {
            logger.warn("No IPTV providers configured")
            return
        }

        runBlocking {
            providerConfigs.forEach { providerConfig ->
                if (!providerConfig.syncEnabled) {
                    logger.debug("Skipping sync for provider ${providerConfig.name} (sync disabled)")
                    return@forEach
                }
                try {
                    syncProvider(providerConfig)
                } catch (e: Exception) {
                    logger.error("Error syncing IPTV provider ${providerConfig.name}", e)
                }
            }
        }

        logger.info("IPTV content sync completed")
    }

    private suspend fun syncProvider(providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration) {
        logger.info("Syncing IPTV provider: ${providerConfig.name}")

        val contentItems = when (providerConfig.type) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> {
                fetchM3uContent(providerConfig)
            }
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> {
                xtreamCodesClient.getVodContent(providerConfig)
            }
        }

        if (iptvConfigurationProperties.filterVodOnly) {
            // Filter is already applied in parsers, but double-check here
            val filtered = contentItems.filter { item ->
                item.category.contains("VOD", ignoreCase = true) ||
                item.category.contains("Movies", ignoreCase = true) ||
                item.category.contains("Series", ignoreCase = true) ||
                item.category.contains("TV Shows", ignoreCase = true)
            }
            syncContentToDatabase(providerConfig.name, filtered)
        } else {
            syncContentToDatabase(providerConfig.name, contentItems)
        }
    }

    private suspend fun fetchM3uContent(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ): List<io.skjaere.debridav.iptv.model.IptvContentItem> {
        val content = when {
            providerConfig.m3uUrl != null -> {
                val response: HttpResponse = httpClient.get(providerConfig.m3uUrl)
                if (response.status == HttpStatusCode.OK) {
                    response.body()
                } else {
                    logger.error("Failed to fetch M3U playlist from ${providerConfig.m3uUrl}: ${response.status}")
                    return emptyList()
                }
            }
            providerConfig.m3uFilePath != null -> {
                val file = File(providerConfig.m3uFilePath)
                if (file.exists()) {
                    file.readText()
                } else {
                    logger.error("M3U file not found: ${providerConfig.m3uFilePath}")
                    return emptyList()
                }
            }
            else -> {
                logger.error("No M3U URL or file path configured for provider ${providerConfig.name}")
                return emptyList()
            }
        }

        return m3uParser.parseM3u(content, providerConfig)
    }

    private fun syncContentToDatabase(
        providerName: String,
        contentItems: List<io.skjaere.debridav.iptv.model.IptvContentItem>
    ) {
        val now = Instant.now()
        val existingContent = iptvContentRepository.findByContentTypeAndIsActive(
            ContentType.MOVIE, true
        ) + iptvContentRepository.findByContentTypeAndIsActive(ContentType.SERIES, true)
        
        val existingMap = existingContent
            .filter { it.providerName == providerName }
            .associateBy { it.contentId }
        
        val incomingIds = contentItems.map { it.id }.toSet()
        
        // Mark content as inactive if it's no longer available in the provider's source
        // This only affects providers that are currently being synced (in iptv.providers list)
        // Providers removed from the config are not synced, so their content remains unchanged
        existingMap.values.forEach { existing ->
            if (!incomingIds.contains(existing.contentId)) {
                existing.isActive = false
                existing.lastSynced = now
                iptvContentRepository.save(existing)
            }
        }
        
        // Insert or update content
        contentItems.forEach { item ->
            val normalizedTitle = iptvContentService.normalizeTitle(item.title)
            val seriesInfo = item.episodeInfo?.let {
                SeriesInfo(
                    seriesName = it.seriesName,
                    season = it.season,
                    episode = it.episode
                )
            }
            
            val entity = existingMap[item.id] ?: IptvContentEntity().apply {
                this.providerName = providerName
                this.contentId = item.id
            }
            
            // Update entity properties
            entity.title = item.title
            entity.normalizedTitle = normalizedTitle
            entity.url = item.url
            entity.contentType = item.type
            entity.category = item.category
            entity.seriesInfo = seriesInfo
            entity.lastSynced = now
            entity.isActive = true
            
            iptvContentRepository.save(entity)
        }
        
        logger.info("Synced ${contentItems.size} items for provider $providerName")
    }
}

