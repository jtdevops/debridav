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

        try {
            val contentItems = when (providerConfig.type) {
                io.skjaere.debridav.iptv.IptvProvider.M3U -> {
                    // Check hash first for M3U
                    val hashCheckResult = checkM3uHashChanged(providerConfig)
                    if (!hashCheckResult.shouldSync) {
                        logger.info("Content hash unchanged for provider ${providerConfig.name}, skipping sync")
                        return
                    }
                    fetchM3uContent(providerConfig)
                }
                io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> {
                    // For Xtream Codes, process one group at a time to limit memory usage
                    syncXtreamCodesContent(providerConfig)
                }
            }

            // Skip database sync if no content items to sync
            if (contentItems.isEmpty()) {
                // For Xtream Codes, this means all endpoints were unchanged (already logged)
                // For M3U, this shouldn't happen as we check hash before fetching
                return
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
                if (filtered.isNotEmpty()) {
                    syncContentToDatabase(providerConfig.name, filtered)
                }
            } else {
                syncContentToDatabase(providerConfig.name, contentItems)
            }

            // Hashes are updated during sync for Xtream Codes, or here for M3U
            if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.M3U) {
                val hashCheckResult = checkM3uHashChanged(providerConfig)
                hashCheckResult.changedEndpoints.forEach { (endpointType, hash) ->
                    updateSyncHash(providerConfig.name, endpointType, hash)
                }
                if (hashCheckResult.changedEndpoints.isNotEmpty()) {
                    logger.info("Successfully synced and updated hashes for provider ${providerConfig.name}: ${hashCheckResult.changedEndpoints.keys}")
                }
            } else if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                // For Xtream Codes, hashes are updated during sync
                // Completion is logged in syncXtreamCodesContent if changes were made
                // If no changes, it's already logged there
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

    /**
     * Checks if a single endpoint has changed by fetching it and comparing hash
     * Returns the response body if changed, null if unchanged
     */
    private suspend fun checkSingleEndpointHash(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        endpointType: String
    ): Pair<String?, String?> { // responseBody, hash
        // Construct URL for logging (needed regardless of cache check)
        val baseUrl = providerConfig.xtreamBaseUrl
        val username = providerConfig.xtreamUsername
        val password = providerConfig.xtreamPassword
        
        if (baseUrl == null || username == null || password == null) {
            logger.warn("Missing required configuration for endpoint $endpointType from provider ${providerConfig.name}")
            return null to null
        }
        
        val apiUrl = "$baseUrl/player_api.php"
        val action = when (endpointType) {
            "vod_categories" -> "get_vod_categories"
            "vod_streams" -> "get_vod_streams"
            "series_categories" -> "get_series_categories"
            "series_streams" -> "get_series"
            else -> {
                logger.warn("Unknown endpoint type: $endpointType")
                return null to null
            }
        }
        val fullUrl = "$apiUrl?username=$username&password=***&action=$action"
        
        // Check for local cached file first
        val useLocal = responseFileService.shouldUseLocalResponses(providerConfig)
        val cachedFilePath = if (useLocal) {
            val saveFolder = iptvConfigurationProperties.responseSaveFolder
            if (saveFolder != null) {
                val sanitizedProviderName = providerConfig.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val extension = when (providerConfig.type) {
                    io.skjaere.debridav.iptv.IptvProvider.M3U -> "m3u"
                    io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> "json"
                }
                val filename = "${sanitizedProviderName}_${endpointType}.$extension"
                File(saveFolder, filename).absolutePath
            } else {
                null
            }
        } else {
            null
        }
        
        val cachedResponse = if (useLocal) {
            if (cachedFilePath != null) {
                logger.info("Checking for local cached file at: $cachedFilePath")
            }
            responseFileService.loadResponse(providerConfig, endpointType, logNotFound = false)
        } else {
            null
        }
        
        val responseBody = if (cachedResponse != null) {
            logger.info("Using local cached file for endpoint $endpointType from provider ${providerConfig.name} at: $cachedFilePath")
            cachedResponse
        } else {
            if (useLocal && cachedFilePath != null) {
                logger.info("Local cached file not found at: $cachedFilePath, fetching from: $fullUrl")
            } else {
                logger.info("Fetching endpoint $endpointType from: $fullUrl")
            }
            
            // Fetch from API
            xtreamCodesClient.getSingleEndpointResponse(providerConfig, endpointType)
        }
        
        if (responseBody == null) {
            logger.warn("No response body received for endpoint $endpointType, treating as changed")
            return null to null
        }
        
        val currentHash = IptvHashUtil.computeHash(responseBody)
        val storedHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
        
        if (storedHash?.contentHash != currentHash) {
            // Hash changed or doesn't exist
            logger.info("Endpoint $endpointType changed for provider ${providerConfig.name}, will sync")
            return responseBody to currentHash
        } else {
            // Hash unchanged, just update last checked timestamp
            logger.info("Endpoint $endpointType unchanged for provider ${providerConfig.name}, skipping sync")
            storedHash.lastChecked = Instant.now()
            iptvSyncHashRepository.save(storedHash)
            return null to null
        }
    }

    /**
     * Syncs Xtream Codes content, processing one group at a time to limit memory usage.
     * Process order: vod_categories + vod_streams, then series_categories + series_streams
     */
    private suspend fun syncXtreamCodesContent(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
    ): List<io.skjaere.debridav.iptv.model.IptvContentItem> {
        val allContent = mutableListOf<io.skjaere.debridav.iptv.model.IptvContentItem>()
        var hasChanges = false
        
        // Process VOD group: categories + streams
        // Step 1: Fetch and check VOD categories hash
        val (vodCategoriesBody, vodCategoriesHash) = checkSingleEndpointHash(providerConfig, "vod_categories")
        
        // Step 2: Always check VOD streams hash (streams can change independently)
        val (vodStreamsBody, vodStreamsHash) = checkSingleEndpointHash(providerConfig, "vod_streams")
        
        // Step 3: Process VOD group if either changed
        if (vodCategoriesBody != null || vodStreamsBody != null) {
            hasChanges = true
            logger.debug("Syncing VOD content for provider ${providerConfig.name}")
            
            // Parse categories from in-memory body
            val vodCategories = if (vodCategoriesBody != null) {
                val categoriesList = xtreamCodesClient.parseVodCategoriesFromBody(vodCategoriesBody)
                syncCategories(providerConfig.name, "vod", categoriesList.map { it.category_id.toString() to it.category_name })
                categoriesList
            } else {
                // Categories unchanged, fetch them normally
                xtreamCodesClient.getVodCategoriesAsObjects(providerConfig)
            }
            
            // Process streams using in-memory body if available
            val vodContent = xtreamCodesClient.getVodContent(providerConfig, vodCategories, vodStreamsBody)
            allContent.addAll(vodContent)
            
            // Update hashes after successful processing
            if (vodCategoriesHash != null) {
                updateSyncHash(providerConfig.name, "vod_categories", vodCategoriesHash)
            }
            if (vodStreamsHash != null) {
                updateSyncHash(providerConfig.name, "vod_streams", vodStreamsHash)
            }
            
            // Clear memory for VOD group
            // (Kotlin GC will handle this, but we're done with these variables)
        }
        
        // Process Series group: categories + streams
        // Step 4: Fetch and check series categories hash
        val (seriesCategoriesBody, seriesCategoriesHash) = checkSingleEndpointHash(providerConfig, "series_categories")
        
        // Step 5: Always check series streams hash (streams can change independently)
        val (seriesStreamsBody, seriesStreamsHash) = checkSingleEndpointHash(providerConfig, "series_streams")
        
        // Step 6: Process series group if either changed
        if (seriesCategoriesBody != null || seriesStreamsBody != null) {
            hasChanges = true
            logger.debug("Syncing series content for provider ${providerConfig.name}")
            
            // Parse categories from in-memory body
            val seriesCategories = if (seriesCategoriesBody != null) {
                val categoriesList = xtreamCodesClient.parseSeriesCategoriesFromBody(seriesCategoriesBody)
                syncCategories(providerConfig.name, "series", categoriesList.map { it.category_id.toString() to it.category_name })
                categoriesList
            } else {
                // Categories unchanged, fetch them normally
                xtreamCodesClient.getSeriesCategoriesAsObjects(providerConfig)
            }
            
            // Process streams using in-memory body if available
            val seriesContent = xtreamCodesClient.getSeriesContent(providerConfig, seriesCategories, seriesStreamsBody)
            allContent.addAll(seriesContent)
            
            // Update hashes after successful processing
            if (seriesCategoriesHash != null) {
                updateSyncHash(providerConfig.name, "series_categories", seriesCategoriesHash)
            }
            if (seriesStreamsHash != null) {
                updateSyncHash(providerConfig.name, "series_streams", seriesStreamsHash)
            }
        }
        
        if (!hasChanges) {
            logger.info("Content hash unchanged for provider ${providerConfig.name}, skipping sync")
        } else {
            logger.info("Successfully synced provider ${providerConfig.name} (${allContent.size} items)")
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
        // Query all content for this provider (including inactive) to avoid duplicate key violations
        val existingContent = iptvContentRepository.findByProviderName(providerName)
        
        val existingMap = existingContent.associateBy { it.contentId }.toMutableMap()
        
        // Deduplicate contentItems by id to avoid processing the same item twice
        val uniqueContentItems = contentItems.distinctBy { it.id }
        val incomingIds = uniqueContentItems.map { it.id }.toSet()
        
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
        if (uniqueContentItems.any { it.categoryId != null && it.categoryType != null }) {
            val allCategories = iptvCategoryRepository.findByProviderName(providerName)
            categoryMap.putAll(allCategories.map { "${it.categoryType}:${it.categoryId}" to it })
        }
        
        // Insert or update content
        uniqueContentItems.forEach { item ->
            // Check if entity already exists in database or was already processed in this batch
            val entity = existingMap[item.id] ?: run {
                // Try to find in database one more time (in case it was just created)
                iptvContentRepository.findByProviderNameAndContentId(providerName, item.id)
                    ?: IptvContentEntity().apply {
                        this.providerName = providerName
                        this.contentId = item.id
                    }
            }
            
            // Link category if available
            val category = if (item.categoryId != null && item.categoryType != null) {
                categoryMap["${item.categoryType}:${item.categoryId}"]
            } else {
                null
            }
            
            // Update entity properties
            entity.title = item.title
            entity.normalizedTitle = iptvContentService.normalizeTitle(item.title)
            entity.url = item.url
            entity.contentType = item.type
            entity.category = category
            entity.seriesInfo = item.episodeInfo?.let {
                SeriesInfo(
                    seriesName = it.seriesName,
                    season = it.season,
                    episode = it.episode
                )
            }
            entity.lastSynced = now
            entity.isActive = true
            
            val savedEntity = iptvContentRepository.save(entity)
            // Update the map so subsequent items with the same id will use this entity
            existingMap[item.id] = savedEntity
        }
        
        logger.info("Synced ${uniqueContentItems.size} items for provider $providerName")
    }
}

