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
import io.skjaere.debridav.iptv.util.IptvHashUtil
import io.skjaere.debridav.iptv.util.IptvResponseFileService
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
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val iptvSyncHashRepository: IptvSyncHashRepository,
    private val httpClient: HttpClient,
    private val responseFileService: IptvResponseFileService
) {
    private val logger = LoggerFactory.getLogger(IptvSyncService::class.java)
    private val m3uParser = M3uParser()
    private val xtreamCodesClient = XtreamCodesClient(httpClient, responseFileService)

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

        // For Xtream Codes providers, verify account is active before syncing
        if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            val isAccountActive = xtreamCodesClient.verifyAccount(providerConfig)
            if (!isAccountActive) {
                logger.error("Account verification failed for provider ${providerConfig.name}. Skipping sync.")
                return
            }
        }

        // Check if content has changed by comparing hashes
        val hashCheckResult = when (providerConfig.type) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> {
                checkM3uHashChanged(providerConfig)
            }
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> {
                checkXtreamCodesHashChanged(providerConfig)
            }
        }

        if (!hashCheckResult.shouldSync) {
            logger.info("Content hash unchanged for provider ${providerConfig.name}, skipping sync")
            return
        }

        try {
            val contentItems = when (providerConfig.type) {
                io.skjaere.debridav.iptv.IptvProvider.M3U -> {
                    fetchM3uContent(providerConfig)
                }
                io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> {
                    // For Xtream Codes, sync only changed endpoints
                    syncXtreamCodesContent(providerConfig, hashCheckResult.changedEndpoints)
                }
            }

            // For M3U, sync categories from group-titles
            if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.M3U) {
                syncM3uCategories(providerConfig.name, contentItems)
            }
            
            if (iptvConfigurationProperties.filterVodOnly) {
                // Filter is already applied in parsers, but double-check here
                // For M3U, check category name; for Xtream Codes, all items are already VOD
                val filtered = contentItems.filter { item ->
                    if (item.categoryType == "m3u" && item.categoryId != null) {
                        val categoryName = item.categoryId // For M3U, categoryId is the group-title
                        categoryName.contains("VOD", ignoreCase = true) ||
                        categoryName.contains("Movies", ignoreCase = true) ||
                        categoryName.contains("Series", ignoreCase = true) ||
                        categoryName.contains("TV Shows", ignoreCase = true)
                    } else {
                        true // Xtream Codes items are already filtered
                    }
                }
                syncContentToDatabase(providerConfig.name, filtered)
            } else {
                syncContentToDatabase(providerConfig.name, contentItems)
            }

            // Update hashes for all changed endpoints after successful sync
            hashCheckResult.changedEndpoints.forEach { (endpointType, hash) ->
                updateSyncHash(providerConfig.name, endpointType, hash)
            }
            
            if (hashCheckResult.changedEndpoints.isNotEmpty()) {
                logger.info("Successfully synced and updated hashes for provider ${providerConfig.name}: ${hashCheckResult.changedEndpoints.keys}")
            }
        } catch (e: Exception) {
            logger.error("Sync failed for provider ${providerConfig.name}, hash not updated. Will retry on next sync.", e)
            throw e // Re-throw to be caught by outer try-catch
        }
    }

    private suspend fun fetchM3uContent(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ): List<io.skjaere.debridav.iptv.model.IptvContentItem> {
        val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
        
        val content = if (useLocal) {
            logger.info("Using local response file for M3U playlist from provider ${providerConfig.name}")
            responseFileService.loadResponse(providerConfig, "m3u")
                ?: run {
                    logger.warn("Local response file not found for M3U playlist, falling back to configured source")
                    null
                }
        } else {
            null
        }
        
        val finalContent = content ?: when {
            providerConfig.m3uUrl != null -> {
                logger.debug("Fetching M3U playlist from URL for provider ${providerConfig.name}: ${providerConfig.m3uUrl}")
                val response: HttpResponse = httpClient.get(providerConfig.m3uUrl)
                if (response.status == HttpStatusCode.OK) {
                    val body = response.body<String>()
                    
                    // Save response if configured
                    if (responseFileService.shouldSaveResponses()) {
                        responseFileService.saveResponse(providerConfig, "m3u", body)
                    }
                    
                    body
                } else {
                    logger.error("Failed to fetch M3U playlist from ${providerConfig.m3uUrl}: ${response.status}")
                    return emptyList()
                }
            }
            providerConfig.m3uFilePath != null -> {
                logger.debug("Reading M3U playlist from file for provider ${providerConfig.name}: ${providerConfig.m3uFilePath}")
                val file = File(providerConfig.m3uFilePath)
                if (file.exists()) {
                    val fileContent = file.readText()
                    
                    // Save response if configured (copy to response folder)
                    if (responseFileService.shouldSaveResponses()) {
                        responseFileService.saveResponse(providerConfig, "m3u", fileContent)
                    }
                    
                    fileContent
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

        return m3uParser.parseM3u(finalContent, providerConfig)
    }

    private data class HashCheckResult(
        val shouldSync: Boolean,
        val changedEndpoints: Map<String, String>, // endpointType -> currentHash
        val endpointType: String? = null // For backward compatibility with M3U
    )

    private suspend fun checkM3uHashChanged(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ): HashCheckResult {
        val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
        
        val content = if (useLocal) {
            responseFileService.loadResponse(providerConfig, "m3u")
        } else {
            null
        } ?: when {
            providerConfig.m3uUrl != null -> {
                val response: HttpResponse = httpClient.get(providerConfig.m3uUrl)
                if (response.status == HttpStatusCode.OK) {
                    response.body<String>()
                } else {
                    logger.error("Failed to fetch M3U playlist for hash check: ${response.status}")
                    return HashCheckResult(shouldSync = true, changedEndpoints = emptyMap(), endpointType = "m3u")
                }
            }
            providerConfig.m3uFilePath != null -> {
                val file = File(providerConfig.m3uFilePath)
                if (file.exists()) {
                    file.readText()
                } else {
                    logger.error("M3U file not found for hash check: ${providerConfig.m3uFilePath}")
                    return HashCheckResult(shouldSync = true, changedEndpoints = emptyMap(), endpointType = "m3u")
                }
            }
            else -> {
                return HashCheckResult(shouldSync = true, changedEndpoints = emptyMap(), endpointType = "m3u")
            }
        }

        val currentHash = IptvHashUtil.computeHash(content)
        val storedHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, "m3u")
        
        return if (storedHash?.contentHash == currentHash) {
            // Hash unchanged, just update last checked timestamp
            storedHash.lastChecked = Instant.now()
            iptvSyncHashRepository.save(storedHash)
            HashCheckResult(shouldSync = false, changedEndpoints = emptyMap(), endpointType = "m3u")
        } else {
            // Hash changed or doesn't exist, need to sync (but don't update hash yet)
            HashCheckResult(shouldSync = true, changedEndpoints = mapOf("m3u" to currentHash), endpointType = "m3u")
        }
    }

    private suspend fun checkXtreamCodesHashChanged(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ): HashCheckResult {
        val responseBodies = xtreamCodesClient.getRawResponseBodies(providerConfig)
        
        if (responseBodies.isEmpty()) {
            logger.warn("No response bodies received for hash check, proceeding with sync")
            return HashCheckResult(shouldSync = true, changedEndpoints = emptyMap())
        }

        // Check each endpoint hash separately
        val changedEndpoints = mutableMapOf<String, String>()
        val endpointTypes = listOf("vod_streams", "series_streams", "vod_categories", "series_categories")
        
        endpointTypes.forEach { endpointType ->
            val responseBody = responseBodies[endpointType] ?: ""
            val currentHash = IptvHashUtil.computeHash(responseBody)
            val storedHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
            
            if (storedHash?.contentHash != currentHash) {
                // Hash changed or doesn't exist
                changedEndpoints[endpointType] = currentHash
                logger.debug("Endpoint $endpointType changed for provider ${providerConfig.name}")
            } else {
                // Hash unchanged, just update last checked timestamp
                storedHash.lastChecked = Instant.now()
                iptvSyncHashRepository.save(storedHash)
            }
        }
        
        return HashCheckResult(
            shouldSync = changedEndpoints.isNotEmpty(),
            changedEndpoints = changedEndpoints
        )
    }

    private suspend fun syncXtreamCodesContent(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        changedEndpoints: Map<String, String>
    ): List<io.skjaere.debridav.iptv.model.IptvContentItem> {
        // Sync categories first if they changed
        if (changedEndpoints.containsKey("vod_categories")) {
            logger.debug("Syncing VOD categories for provider ${providerConfig.name}")
            syncCategories(providerConfig.name, "vod", xtreamCodesClient.getVodCategories(providerConfig))
        }
        
        if (changedEndpoints.containsKey("series_categories")) {
            logger.debug("Syncing series categories for provider ${providerConfig.name}")
            syncCategories(providerConfig.name, "series", xtreamCodesClient.getSeriesCategories(providerConfig))
        }
        
        val allContent = mutableListOf<io.skjaere.debridav.iptv.model.IptvContentItem>()
        
        // Only sync content from endpoints that changed
        // Note: We need to sync both streams if either streams or their categories changed
        val needVodContent = changedEndpoints.containsKey("vod_streams") || changedEndpoints.containsKey("vod_categories")
        val needSeriesContent = changedEndpoints.containsKey("series_streams") || changedEndpoints.containsKey("series_categories")
        
        if (needVodContent) {
            logger.debug("Syncing VOD content for provider ${providerConfig.name}")
            val vodContent = xtreamCodesClient.getVodContent(providerConfig)
            allContent.addAll(vodContent)
        }
        
        if (needSeriesContent) {
            logger.debug("Syncing series content for provider ${providerConfig.name}")
            val seriesContent = xtreamCodesClient.getSeriesContent(providerConfig)
            allContent.addAll(seriesContent)
        }
        
        return allContent
    }

    private fun syncM3uCategories(
        providerName: String,
        contentItems: List<io.skjaere.debridav.iptv.model.IptvContentItem>
    ) {
        // Extract unique category names (group-titles) from M3U content
        val categories = contentItems
            .filter { it.categoryType == "m3u" && it.categoryId != null }
            .map { it.categoryId!! to it.categoryId!! } // For M3U, categoryId is the name
            .distinct()
        
        if (categories.isNotEmpty()) {
            syncCategories(providerName, "m3u", categories)
        }
    }

    private fun syncCategories(
        providerName: String,
        categoryType: String,
        categories: List<Pair<String, String>> // categoryId to categoryName
    ) {
        val now = Instant.now()
        val existingCategories = iptvCategoryRepository.findByProviderNameAndCategoryType(providerName, categoryType)
        val existingMap = existingCategories.associateBy { it.categoryId }
        
        val incomingIds = categories.map { it.first }.toSet()
        
        // Mark categories as inactive if they're no longer available
        existingMap.values.forEach { existing ->
            if (!incomingIds.contains(existing.categoryId)) {
                existing.isActive = false
                existing.lastSynced = now
                iptvCategoryRepository.save(existing)
            }
        }
        
        // Insert or update categories
        categories.forEach { (categoryId, categoryName) ->
            val entity = existingMap[categoryId] ?: IptvCategoryEntity().apply {
                this.providerName = providerName
                this.categoryId = categoryId
                this.categoryType = categoryType
            }
            
            entity.categoryName = categoryName
            entity.lastSynced = now
            entity.isActive = true
            
            iptvCategoryRepository.save(entity)
        }
        
        logger.debug("Synced ${categories.size} $categoryType categories for provider $providerName")
    }

    private fun updateSyncHash(providerName: String, endpointType: String, newHash: String) {
        val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerName, endpointType)
            ?: IptvSyncHashEntity().apply {
                this.providerName = providerName
                this.endpointType = endpointType
            }
        
        hashEntity.contentHash = newHash
        hashEntity.lastChecked = Instant.now()
        iptvSyncHashRepository.save(hashEntity)
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
        
        // Get category map for linking
        val categoryMap = mutableMapOf<String, IptvCategoryEntity>()
        if (contentItems.any { it.categoryId != null && it.categoryType != null }) {
            val allCategories = iptvCategoryRepository.findByProviderName(providerName)
            categoryMap.putAll(allCategories.map { "${it.categoryType}:${it.categoryId}" to it })
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
            
            // Link category if available
            val category = if (item.categoryId != null && item.categoryType != null) {
                categoryMap["${item.categoryType}:${item.categoryId}"]
            } else {
                null
            }
            
            // Update entity properties
            entity.title = item.title
            entity.normalizedTitle = normalizedTitle
            entity.url = item.url
            entity.contentType = item.type
            entity.category = category
            entity.seriesInfo = seriesInfo
            entity.lastSynced = now
            entity.isActive = true
            
            iptvContentRepository.save(entity)
        }
        
        logger.info("Synced ${contentItems.size} items for provider $providerName")
    }
}

