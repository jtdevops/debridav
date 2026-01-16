package io.skjaere.debridav.iptv

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
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
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

@Service
class IptvSyncService(
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvConfigurationService: IptvConfigurationService,
    private val iptvContentRepository: IptvContentRepository,
    private val iptvContentService: IptvContentService,
    private val iptvCategoryRepository: IptvCategoryRepository,
    private val iptvSyncHashRepository: IptvSyncHashRepository,
    private val httpClient: HttpClient,
    private val responseFileService: IptvResponseFileService,
    private val iptvLoginRateLimitService: IptvLoginRateLimitService,
    private val liveChannelDatabaseFilterService: LiveChannelDatabaseFilterService,
    private val liveChannelSyncService: LiveChannelSyncService
) {
    private val logger = LoggerFactory.getLogger(IptvSyncService::class.java)
    private val m3uParser = M3uParser()
    private val xtreamCodesClient = XtreamCodesClient(httpClient, responseFileService, iptvConfigurationProperties.userAgent)
    
    // Track if initial sequential sync has completed (VOS/SERIES then LIVE)
    private val initialSequentialSyncCompleted = AtomicBoolean(false)
    
    // Track if live sync was just executed as part of initial sequential sync
    // Used to prevent double execution when triggerPendingSyncs() is called
    private val liveSyncJustExecutedInSequential = AtomicBoolean(false)
    
    // Lock to ensure only one IPTV sync runs at a time (prevents collisions between VOD/SERIES and LIVE syncs)
    private val syncLock = ReentrantLock()
    
    // Track pending syncs that were skipped due to another sync in progress
    // Values: null (no pending), "vod_series", "live", or "both"
    private val pendingSync = AtomicReference<String?>(null)

    /**
     * Checks if live sync is enabled for a provider.
     * Per-provider setting defaults to false (opt-in), independent of global folder visibility.
     */
    private fun isLiveSyncEnabled(providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration): Boolean {
        return providerConfig.liveSyncEnabled
    }

    @Scheduled(
        initialDelayString = "#{T(java.time.Duration).parse(@environment.getProperty('iptv.initial-sync-delay', 'PT30S')).toMillis()}",
        fixedDelayString = "\${iptv.sync-interval}"
    )
    fun syncIptvContent() {
        // Try to acquire lock - if another sync is running, mark as pending and skip
        if (!syncLock.tryLock()) {
            logger.info("Skipping scheduled IPTV content sync - another sync is already in progress. Will trigger when current sync completes.")
            markPendingSync("vod_series")
            return
        }
        try {
            syncIptvContent(forceSync = false, skipLockCheck = true)
        } finally {
            syncLock.unlock()
            // Check and trigger any pending syncs after releasing the lock
            triggerPendingSyncs()
        }
    }

    /**
     * Separate scheduled sync for live content only (if liveSyncInterval is configured)
     * This allows live content to sync more frequently than VOD/Series content
     * Note: This scheduled method will run, but returns early if liveSyncInterval is not configured
     * Uses a very long default interval (1 year) so it effectively doesn't run unless configured
     * 
     * On startup, this is skipped to allow sequential execution (VOS/SERIES first, then LIVE).
     * After initial sequential sync completes, this runs on its own schedule.
     */
    @Scheduled(
        initialDelayString = "#{T(java.time.Duration).parse(@environment.getProperty('iptv.initial-sync-delay', 'PT30S')).toMillis()}",
        fixedDelayString = "\${iptv.live.sync-interval:PT8760H}"
    )
    fun syncLiveContentOnly() {
        // Skip scheduled execution on first run - it will be triggered sequentially after VOS/SERIES sync
        if (!initialSequentialSyncCompleted.get()) {
            logger.debug("Skipping scheduled live sync - waiting for initial sequential sync to complete")
            return
        }
        // Try to acquire lock - if another sync is running, mark as pending and skip
        if (!syncLock.tryLock()) {
            logger.info("Skipping scheduled IPTV live content sync - another sync is already in progress. Will trigger when current sync completes.")
            markPendingSync("live")
            return
        }
        try {
            // Check if we just completed initial sequential sync and live sync was already run
            // If so, clear any pending live sync to avoid double execution
            if (!initialSequentialSyncCompleted.get()) {
                // This shouldn't happen since we check above, but handle gracefully
                logger.debug("Initial sequential sync not completed yet, skipping scheduled live sync")
                return
            }
            syncLiveContentOnly(forceSync = false, skipLockCheck = true)
        } finally {
            syncLock.unlock()
            // Check and trigger any pending syncs after releasing the lock
            triggerPendingSyncs()
        }
    }
    
    /**
     * Syncs live content only (can be called manually via API or scheduled)
     * @param forceSync If true, bypasses sync interval check and forces sync regardless of last sync time
     * @param skipLockCheck If true, skips the lock check (used when called from within syncIptvContent to avoid double-locking)
     */
    fun syncLiveContentOnly(forceSync: Boolean = false, skipLockCheck: Boolean = false) {
        // Check if /live folder is enabled (for visibility)
        // But actual sync is controlled by per-provider live.sync-enabled
        if (!iptvConfigurationProperties.liveEnabled) {
            return
        }
        
        val liveSyncInterval = iptvConfigurationProperties.liveSyncInterval
        if (liveSyncInterval == null && !forceSync) {
            // If no separate interval configured, live syncs during main sync
            // This scheduled method will still run but returns early
            // Unless forceSync is true (manual trigger)
            return
        }
        
        // Acquire lock if not already held (skip if called from within syncIptvContent)
        val lockAcquired = if (skipLockCheck || syncLock.isHeldByCurrentThread) {
            true
        } else {
            syncLock.tryLock()
        }
        
        if (!lockAcquired) {
            logger.info("Skipping IPTV live content sync - another sync is already in progress. Will trigger when current sync completes.")
            markPendingSync("live")
            return
        }
        
        try {
            logger.info("Starting IPTV live content sync${if (forceSync) " (forced)" else " (independent sync)"}")
            
            // Only sync providers that have live sync explicitly enabled (opt-in)
            val providerConfigs = iptvConfigurationService.getProviderConfigurations()
                .filter { it.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES && it.syncEnabled && isLiveSyncEnabled(it) }
            
            if (providerConfigs.isEmpty()) {
                logger.debug("No Xtream Codes providers configured for live sync")
                return
            }
            
            // Sync providers sequentially (one at a time) to prevent concurrent provider syncs
            // The syncLock ensures only one sync type (VOD/SERIES or LIVE) runs at a time,
            // and this forEach ensures providers within a sync type don't run concurrently
            runBlocking {
                providerConfigs.forEach { providerConfig ->
                    try {
                        syncLiveContentOnly(providerConfig, forceSync)
                    } catch (e: Exception) {
                        logger.error("Error syncing live content for IPTV provider ${providerConfig.name}", e)
                    }
                }
            }
            
            logger.info("IPTV live content sync completed")
        } finally {
            // Only release lock if we acquired it (not if it was already held)
            if (!skipLockCheck) {
                syncLock.unlock()
                // Check and trigger any pending syncs after releasing the lock
                triggerPendingSyncs()
            }
        }
    }

    fun syncIptvContent(forceSync: Boolean = false, skipLockCheck: Boolean = false) {
        if (!iptvConfigurationProperties.enabled) {
            logger.debug("IPTV sync skipped - IPTV is disabled")
            return
        }

        // Acquire lock if not already held (skip if called from scheduled method which already holds it)
        val lockAcquired = if (skipLockCheck || syncLock.isHeldByCurrentThread) {
            true
        } else {
            syncLock.tryLock()
        }
        
        if (!lockAcquired) {
            logger.info("Skipping IPTV content sync - another sync is already in progress. Will trigger when current sync completes.")
            markPendingSync("vod_series")
            return
        }
        
        try {
            logger.info("Starting IPTV content sync${if (forceSync) " (forced)" else ""}")
            
            // Check for interrupted syncs from previous run
            checkAndResumeInterruptedSyncs()
            
            val providerConfigs = iptvConfigurationService.getProviderConfigurations()

            if (providerConfigs.isEmpty()) {
                logger.warn("No IPTV providers configured")
                return
            }

            val syncInterval = iptvConfigurationProperties.syncInterval

            // Sync providers sequentially (one at a time) to prevent concurrent provider syncs
            // The syncLock ensures only one sync type (VOD/SERIES or LIVE) runs at a time,
            // and this forEach ensures providers within a sync type don't run concurrently
            runBlocking {
                providerConfigs.forEach { providerConfig ->
                    if (!providerConfig.syncEnabled) {
                        logger.debug("Skipping sync for provider ${providerConfig.name} (sync disabled)")
                        return@forEach
                    }
                    
                    // Check for interrupted syncs for this provider - if found, resync immediately
                    val interruptedSyncs = iptvSyncHashRepository.findByProviderNameAndSyncStatusInProgress(providerConfig.name)
                    if (interruptedSyncs.isNotEmpty()) {
                        logger.info("Provider ${providerConfig.name} has ${interruptedSyncs.size} interrupted sync(s), will resync immediately")
                    } else {
                        // Check for failed syncs - if found, resync immediately (bypass timer)
                        val failedSyncs = iptvSyncHashRepository.findByProviderNameAndSyncStatusFailed(providerConfig.name)
                        if (failedSyncs.isNotEmpty()) {
                            logger.info("Provider ${providerConfig.name} has ${failedSyncs.size} failed sync(s), will resync immediately")
                        } else {
                            // Skip time interval check if forceSync is true
                            if (!forceSync) {
                                // Check per-provider timing instead of global timing
                                // This allows new providers to sync immediately even if other providers were synced recently
                                val mostRecentSync = iptvSyncHashRepository.findMostRecentLastCheckedByProvider(providerConfig.name)
                                if (mostRecentSync != null) {
                                    val timeSinceLastSync = Duration.between(mostRecentSync, Instant.now())
                                    
                                    if (timeSinceLastSync < syncInterval) {
                                        val timeUntilNextSync = syncInterval.minus(timeSinceLastSync)
                                        logger.info("Skipping sync for provider ${providerConfig.name} - only ${formatDuration(timeSinceLastSync)} since last sync. Next sync in ${formatDuration(timeUntilNextSync)}")
                                        return@forEach
                                    }
                                } else {
                                    logger.info("Provider ${providerConfig.name} has no sync history, will sync immediately")
                                }
                            } else {
                                logger.info("Provider ${providerConfig.name} sync forced via API, bypassing time interval check")
                            }
                        }
                    }
                    
                    try {
                        syncProvider(providerConfig)
                    } catch (e: Exception) {
                        logger.error("Error syncing IPTV provider ${providerConfig.name}", e)
                    }
                }
            }

            logger.info("IPTV content sync completed")
            
            // On first run, trigger live sync sequentially after VOS/SERIES sync completes
            // Pass skipLockCheck=true since we already hold the lock
            if (!initialSequentialSyncCompleted.get()) {
                val liveSyncInterval = iptvConfigurationProperties.liveSyncInterval
                if (liveSyncInterval != null && iptvConfigurationProperties.liveEnabled) {
                    logger.info("Initial sequential sync: triggering live content sync after VOS/SERIES sync completion")
                    // Mark that we're about to run live sync as part of initial sequential sync
                    liveSyncJustExecutedInSequential.set(true)
                    try {
                        syncLiveContentOnly(forceSync = false, skipLockCheck = true)
                    } catch (e: Exception) {
                        logger.error("Error during initial sequential live sync", e)
                        // Reset flag if sync failed
                        liveSyncJustExecutedInSequential.set(false)
                    }
                }
                // Mark initial sequential sync as completed BEFORE releasing lock
                // This prevents scheduled methods from marking themselves as pending
                // (they check this flag and return early if false)
                initialSequentialSyncCompleted.set(true)
                logger.info("Initial sequential sync completed - syncs will now run on their own schedules")
                
                // Clear any pending "live" sync since we just ran it as part of initial sequential sync
                // This prevents it from running again when triggerPendingSyncs() is called
                // Do this after setting the flag so scheduled methods won't mark themselves as pending
                pendingSync.updateAndGet { current ->
                    when (current) {
                        "live" -> null // Clear live pending since we just ran it
                        "both" -> "vod_series" // Keep vod_series pending, remove live
                        else -> current // Keep other pending states
                    }
                }
            }
        } finally {
            // Only release lock if we acquired it (not if it was already held)
            if (!skipLockCheck) {
                syncLock.unlock()
                // Check and trigger any pending syncs after releasing the lock
                triggerPendingSyncs()
            }
        }
    }
    
    /**
     * Marks a sync type as pending (will be triggered when current sync completes)
     */
    private fun markPendingSync(syncType: String) {
        pendingSync.updateAndGet { current ->
            when (current) {
                null -> syncType
                syncType -> current // Already marked, keep it
                else -> "both" // Both types are pending
            }
        }
    }
    
    /**
     * Triggers any pending syncs that were skipped due to another sync in progress.
     * This ensures we don't miss syncs when schedules collide.
     * 
     * Note: This method should only be called when the lock is NOT held (after a sync completes).
     */
    private fun triggerPendingSyncs() {
        val pending = pendingSync.getAndSet(null)
        if (pending == null) {
            return
        }
        
        // Try to acquire lock to run pending syncs
        if (!syncLock.tryLock()) {
            // Lock is held (shouldn't happen since we just released it, but handle gracefully)
            logger.debug("Could not acquire lock for pending syncs, will retry on next completion")
            pendingSync.set(pending) // Restore pending state
            return
        }
        
        try {
            when (pending) {
                "vod_series" -> {
                    logger.info("Triggering pending VOD/SERIES sync that was skipped earlier")
                    syncIptvContent(forceSync = false, skipLockCheck = true)
                }
                "live" -> {
                    // Check if live sync was just executed as part of initial sequential sync
                    // If so, skip triggering it again to prevent double execution
                    if (liveSyncJustExecutedInSequential.getAndSet(false)) {
                        logger.debug("Skipping pending LIVE sync - it was just executed as part of initial sequential sync")
                        return
                    }
                    logger.info("Triggering pending LIVE sync that was skipped earlier")
                    syncLiveContentOnly(forceSync = false, skipLockCheck = true)
                }
                "both" -> {
                    // Both are pending - run VOD/SERIES first, then LIVE
                    logger.info("Triggering pending VOD/SERIES sync that was skipped earlier")
                    syncIptvContent(forceSync = false, skipLockCheck = true)
                    // After VOD/SERIES completes, check if LIVE is still needed
                    // (it might have been cleared if it was the initial sequential sync)
                    val stillPending = pendingSync.get()
                    if (stillPending == "live" || stillPending == "both") {
                        // Check if live sync was just executed as part of initial sequential sync
                        // If so, skip triggering it again to prevent double execution
                        if (liveSyncJustExecutedInSequential.getAndSet(false)) {
                            logger.debug("Skipping pending LIVE sync - it was just executed as part of initial sequential sync")
                            pendingSync.updateAndGet { current ->
                                when (current) {
                                    "live" -> null
                                    "both" -> "vod_series"
                                    else -> current
                                }
                            }
                        } else {
                            logger.info("Triggering pending LIVE sync that was skipped earlier")
                            syncLiveContentOnly(forceSync = false, skipLockCheck = true)
                            pendingSync.set(null) // Clear after both are done
                        }
                    }
                }
            }
        } finally {
            syncLock.unlock()
            // Check for any new pending syncs that might have been queued while we were running
            // (e.g., if a scheduled sync fired while we were running pending syncs)
            // Only check once to avoid infinite recursion
            val newPending = pendingSync.get()
            if (newPending != null) {
                logger.debug("New pending sync detected after completing previous pending sync, will trigger")
                triggerPendingSyncs()
            }
        }
    }

    /**
     * Checks for interrupted syncs from previous application run and marks them for resync
     */
    private fun checkAndResumeInterruptedSyncs() {
        val interruptedSyncs = iptvSyncHashRepository.findBySyncStatusInProgress()
        if (interruptedSyncs.isNotEmpty()) {
            logger.warn("Found ${interruptedSyncs.size} interrupted sync(s) from previous run. Will resync these providers.")
            interruptedSyncs.forEach { syncHash ->
                logger.info("Resetting sync status for provider ${syncHash.providerName}, endpoint ${syncHash.endpointType} " +
                        "(sync started at ${syncHash.syncStartedAt})")
                syncHash.syncStatus = "FAILED"
                syncHash.syncStartedAt = null
                iptvSyncHashRepository.save(syncHash)
            }
        }
    }
    
    private suspend fun syncProvider(providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration) {
        logger.info("Syncing IPTV provider: ${providerConfig.name}")

        // For Xtream Codes providers, verify account is active before syncing
        // Apply rate limiting to prevent excessive API calls (shared across all services)
        // Use atomic check-and-record to prevent race conditions in parallel processing
        if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            if (iptvLoginRateLimitService.shouldProceedWithLoginCall(providerConfig.name)) {
                // This thread won the race - proceed with verification call
                val isAccountActive = xtreamCodesClient.verifyAccount(providerConfig)
                if (!isAccountActive) {
                    logger.error("Account verification failed for provider ${providerConfig.name}. Skipping sync.")
                    return
                }
            } else {
                // Rate limited - another thread already made the call or it was made recently
                val timeSinceLastCall = iptvLoginRateLimitService.getTimeSinceLastCall(providerConfig.name)
                logger.debug("Skipping IPTV provider login call for ${providerConfig.name} (rate limited, last call was ${timeSinceLastCall}ms ago)")
            }
        }

        try {
            // Mark sync as in progress for all endpoints
            markSyncInProgress(providerConfig.name, providerConfig.type)
            val contentItems = when (providerConfig.type) {
                io.skjaere.debridav.iptv.IptvProvider.M3U -> {
                    // Check hash first for M3U
                    val hashCheckResult = checkM3uHashChanged(providerConfig)
                    if (!hashCheckResult.shouldSync) {
                        logger.info("Content hash unchanged for provider ${providerConfig.name}, skipping sync")
                        // Mark sync as completed since hash check was successful
                        markSyncCompleted(providerConfig.name, providerConfig.type)
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
                // Mark sync as completed since all endpoints were checked successfully
                markSyncCompleted(providerConfig.name, providerConfig.type)
                return
            }
            
            // For M3U, sync categories from group-titles
            if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.M3U) {
                syncM3uCategories(providerConfig.name, contentItems)
            }
            
            // Filter content based on type
            // For M3U, filter VOD content only (live streams are not supported for M3U)
            // For Xtream Codes, separate VOD/Series from Live content
            val filtered = contentItems.filter { item ->
                when {
                    // M3U: Only VOD content
                    item.categoryType == "m3u" && item.categoryId != null -> {
                        val categoryName = item.categoryId // For M3U, categoryId is the group-title
                        categoryName.contains("VOD", ignoreCase = true) ||
                        categoryName.contains("Movies", ignoreCase = true) ||
                        categoryName.contains("Series", ignoreCase = true) ||
                        categoryName.contains("TV Shows", ignoreCase = true)
                    }
                    // Xtream Codes: Include VOD, Series, and Live (Live is handled separately with filtering)
                    item.categoryType in listOf("vod", "series", "live") -> true
                    else -> false
                }
            }
            if (filtered.isNotEmpty()) {
                syncContentToDatabase(providerConfig.name, filtered)
            }

            // Hashes are updated during sync for Xtream Codes, or here for M3U
            if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.M3U) {
                val hashCheckResult = checkM3uHashChanged(providerConfig)
                hashCheckResult.changedEndpoints.forEach { (endpointType, hash) ->
                    val responseSize = hashCheckResult.endpointSizes[endpointType]
                    updateSyncHash(providerConfig.name, endpointType, hash, responseSize)
                }
                if (hashCheckResult.changedEndpoints.isNotEmpty()) {
                    logger.info("Successfully synced and updated hashes for provider ${providerConfig.name}: ${hashCheckResult.changedEndpoints.keys}")
                }
            } else if (providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                // For Xtream Codes, hashes are updated during sync
                // Completion is logged in syncXtreamCodesContent if changes were made
                // If no changes, it's already logged there
            }
            
            // Mark sync as completed
            markSyncCompleted(providerConfig.name, providerConfig.type)
            
            // Check if sync completed successfully for this provider before cleaning up
            if (isProviderSyncCompleted(providerConfig.name, providerConfig.type)) {
                // Cleanup inactive content items for this provider that aren't linked to files
                try {
                    val deletedCount = iptvContentService.deleteInactiveContentWithoutFilesForProvider(providerConfig.name)
                    if (deletedCount > 0) {
                        logger.info("Cleaned up $deletedCount inactive IPTV content items and their metadata for provider ${providerConfig.name} after successful sync")
                    }
                } catch (e: Exception) {
                    logger.error("Error during cleanup of inactive IPTV content items for provider ${providerConfig.name}", e)
                    // Don't fail the sync if cleanup fails
                }
                
                // Cleanup inactive Movies and Series categories and content
                try {
                    val (deletedCategories, deletedContent) = iptvContentService.deleteInactiveMoviesAndSeriesCategoriesAndContentForProvider(providerConfig.name)
                    if (deletedCategories > 0 || deletedContent > 0) {
                        logger.info("Cleaned up $deletedCategories inactive Movies/Series categories and $deletedContent content items for provider ${providerConfig.name} after successful sync")
                    }
                } catch (e: Exception) {
                    logger.error("Error during cleanup of inactive Movies/Series categories and content for provider ${providerConfig.name}", e)
                    // Don't fail the sync if cleanup fails
                }
                
                // Sync live channels (now just logs paths if logging is enabled)
                if (isLiveSyncEnabled(providerConfig) && providerConfig.type == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
                    try {
                        liveChannelSyncService.syncLiveChannelsToVfs(providerConfig.name)
                    } catch (e: Exception) {
                        logger.error("Error logging live channel paths for provider ${providerConfig.name}", e)
                    }
                }
            } else {
                logger.warn("Skipping cleanup for provider ${providerConfig.name} - sync did not complete successfully")
            }
        } catch (e: Exception) {
            logger.error("Sync failed for provider ${providerConfig.name}, hash not updated. Will retry on next sync.", e)
            // Mark sync as failed
            markSyncFailed(providerConfig.name, providerConfig.type)
            throw e // Re-throw to be caught by outer try-catch
        }
    }
    
    /**
     * Checks if a provider's sync completed successfully by verifying all endpoints have COMPLETED status.
     */
    private fun isProviderSyncCompleted(providerName: String, providerType: io.skjaere.debridav.iptv.IptvProvider): Boolean {
        val baseEndpoints = when (providerType) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> listOf("m3u")
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> listOf("vod_categories", "vod_streams", "series_categories", "series_streams")
        }
        
        // Add live endpoints if live is enabled for this provider
        val providerConfig = iptvConfigurationService.getProviderConfigurations().find { it.name == providerName }
        val endpoints = if (providerConfig != null && isLiveSyncEnabled(providerConfig) && providerType == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            baseEndpoints + listOf("live_categories", "live_streams")
        } else {
            baseEndpoints
        }
        
        // Check if any endpoint is not completed
        val notCompleted = iptvSyncHashRepository.findByProviderNameAndSyncStatusNotCompleted(providerName)
            .filter { it.endpointType in endpoints }
        
        if (notCompleted.isNotEmpty()) {
            logger.debug("Provider $providerName has ${notCompleted.size} endpoint(s) that did not complete successfully: ${notCompleted.map { "${it.endpointType}=${it.syncStatus}" }}")
            return false
        }
        
        // Verify all expected endpoints exist and are completed
        val allEndpointsCompleted = endpoints.all { endpointType ->
            val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerName, endpointType)
            hashEntity?.syncStatus == "COMPLETED"
        }
        
        return allEndpointsCompleted
    }
    
    /**
     * Marks sync as in progress for all endpoints of a provider
     */
    private fun markSyncInProgress(providerName: String, providerType: io.skjaere.debridav.iptv.IptvProvider) {
        val baseEndpoints = when (providerType) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> listOf("m3u")
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> listOf("vod_categories", "vod_streams", "series_categories", "series_streams")
        }
        
        // Add live endpoints if live is enabled for this provider
        val providerConfig = iptvConfigurationService.getProviderConfigurations().find { it.name == providerName }
        val endpoints = if (providerConfig != null && isLiveSyncEnabled(providerConfig) && providerType == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            baseEndpoints + listOf("live_categories", "live_streams")
        } else {
            baseEndpoints
        }
        
        val now = Instant.now()
        endpoints.forEach { endpointType ->
            val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerName, endpointType)
                ?: IptvSyncHashEntity().apply {
                    this.providerName = providerName
                    this.endpointType = endpointType
                    // Initialize with a placeholder hash if entity doesn't exist yet
                    // The actual hash will be set during sync
                    this.contentHash = ""
                    this.lastChecked = Instant.now()
                }
            
            hashEntity.syncStatus = "IN_PROGRESS"
            hashEntity.syncStartedAt = now
            iptvSyncHashRepository.save(hashEntity)
        }
    }
    
    /**
     * Marks sync as completed for all endpoints of a provider
     */
    private fun markSyncCompleted(providerName: String, providerType: io.skjaere.debridav.iptv.IptvProvider) {
        val baseEndpoints = when (providerType) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> listOf("m3u")
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> listOf("vod_categories", "vod_streams", "series_categories", "series_streams")
        }
        
        // Add live endpoints if live is enabled for this provider
        val providerConfig = iptvConfigurationService.getProviderConfigurations().find { it.name == providerName }
        val endpoints = if (providerConfig != null && isLiveSyncEnabled(providerConfig) && providerType == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            baseEndpoints + listOf("live_categories", "live_streams")
        } else {
            baseEndpoints
        }
        
        endpoints.forEach { endpointType ->
            val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerName, endpointType)
            if (hashEntity != null) {
                hashEntity.syncStatus = "COMPLETED"
                hashEntity.syncStartedAt = null
                iptvSyncHashRepository.save(hashEntity)
            }
        }
    }
    
    /**
     * Marks sync as failed for all endpoints of a provider
     */
    private fun markSyncFailed(providerName: String, providerType: io.skjaere.debridav.iptv.IptvProvider) {
        val baseEndpoints = when (providerType) {
            io.skjaere.debridav.iptv.IptvProvider.M3U -> listOf("m3u")
            io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES -> listOf("vod_categories", "vod_streams", "series_categories", "series_streams")
        }
        
        // Add live endpoints if live is enabled for this provider
        val providerConfig = iptvConfigurationService.getProviderConfigurations().find { it.name == providerName }
        val endpoints = if (providerConfig != null && isLiveSyncEnabled(providerConfig) && providerType == io.skjaere.debridav.iptv.IptvProvider.XTREAM_CODES) {
            baseEndpoints + listOf("live_categories", "live_streams")
        } else {
            baseEndpoints
        }
        
        endpoints.forEach { endpointType ->
            val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerName, endpointType)
            if (hashEntity != null) {
                hashEntity.syncStatus = "FAILED"
                hashEntity.syncStartedAt = null
                iptvSyncHashRepository.save(hashEntity)
            }
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
                val response: HttpResponse = httpClient.get(providerConfig.m3uUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                }
                if (response.status == HttpStatusCode.OK) {
                    val body = response.body<String>()
                    
                    // Save response if configured
                    // If saveResponse returns false (empty response), log warning but continue with sync
                    if (responseFileService.shouldSaveResponses()) {
                        val saved = responseFileService.saveResponse(providerConfig, "m3u", body)
                        if (!saved) {
                            logger.warn("M3U playlist response was empty or significantly smaller than cached for provider ${providerConfig.name}, but continuing with sync")
                        }
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
                    // If saveResponse returns false (empty response), log warning but continue with sync
                    if (responseFileService.shouldSaveResponses()) {
                        val saved = responseFileService.saveResponse(providerConfig, "m3u", fileContent)
                        if (!saved) {
                            logger.warn("M3U playlist file content was empty or significantly smaller than cached for provider ${providerConfig.name}, but continuing with sync")
                        }
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
        val endpointType: String? = null, // For backward compatibility with M3U
        val endpointSizes: Map<String, Long> = emptyMap() // endpointType -> responseBodySize
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
                val response: HttpResponse = httpClient.get(providerConfig.m3uUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, iptvConfigurationProperties.userAgent)
                    }
                }
                if (response.status == HttpStatusCode.OK) {
                    val body = response.body<String>()
                    
                    // Don't save here - save only after hash check confirms we will sync
                    // This prevents saving bad/empty responses when provider is down
                    body
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
        val contentSize = content.length.toLong()
        val storedHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, "m3u")
        
        return if (storedHash?.contentHash == currentHash) {
            // Hash unchanged, just update last checked timestamp
            storedHash.lastChecked = Instant.now()
            iptvSyncHashRepository.save(storedHash)
            HashCheckResult(shouldSync = false, changedEndpoints = emptyMap(), endpointType = "m3u", endpointSizes = emptyMap())
        } else {
            // Hash changed or doesn't exist - we will sync
            // Save response to file now that we know we'll import it
            if (responseFileService.shouldSaveResponses()) {
                val saved = responseFileService.saveResponse(providerConfig, "m3u", content)
                if (!saved) {
                    logger.warn("M3U playlist response for provider ${providerConfig.name} was empty or significantly smaller than cached, but hash changed - will sync anyway")
                }
            }
            // Hash changed or doesn't exist, need to sync (but don't update hash yet)
            HashCheckResult(shouldSync = true, changedEndpoints = mapOf("m3u" to currentHash), endpointType = "m3u", endpointSizes = mapOf("m3u" to contentSize))
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
            "live_categories" -> "get_live_categories"
            "live_streams" -> "get_live_streams"
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
            // getSingleEndpointResponse returns null if response was empty and skipped
            val fetchedBody = xtreamCodesClient.getSingleEndpointResponse(providerConfig, endpointType)
            if (fetchedBody == null) {
                // Response was empty and saveResponse skipped it, treat as unchanged
                logger.info("Endpoint $endpointType returned empty response for provider ${providerConfig.name}, skipping sync (keeping cached data)")
                val storedHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
                if (storedHash != null) {
                    storedHash.lastChecked = Instant.now()
                    iptvSyncHashRepository.save(storedHash)
                }
                return null to null
            }
            fetchedBody
        }
        
        val currentHash = IptvHashUtil.computeHash(responseBody)
        val storedHash = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
        
        if (storedHash?.contentHash != currentHash) {
            // Hash changed or doesn't exist - we will sync
            // Save response to file now that we know we'll import it
            if (responseFileService.shouldSaveResponses()) {
                val saved = responseFileService.saveResponse(providerConfig, endpointType, responseBody)
                if (!saved) {
                    logger.warn("Response for endpoint $endpointType from provider ${providerConfig.name} was empty or significantly smaller than cached, but hash changed - will sync anyway")
                }
            }
            logger.info("Endpoint $endpointType changed for provider ${providerConfig.name}, will sync")
            return responseBody to currentHash
        } else {
            // Hash unchanged, just update last checked timestamp
            // But return the response body so it can be reused (e.g., when processing streams that need categories)
            logger.info("Endpoint $endpointType unchanged for provider ${providerConfig.name}, skipping sync")
            storedHash.lastChecked = Instant.now()
            iptvSyncHashRepository.save(storedHash)
            return responseBody to null // Return body for reuse, null hash indicates unchanged
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
            
            // Parse categories from in-memory body (reuse cached body even if unchanged)
            val vodCategories = if (vodCategoriesBody != null) {
                val categoriesList = xtreamCodesClient.parseVodCategoriesFromBody(vodCategoriesBody)
                // Only sync categories if they changed (hash is not null)
                if (vodCategoriesHash != null) {
                    syncCategories(providerConfig.name, "vod", categoriesList.map { it.category_id.toString() to it.category_name })
                }
                categoriesList
            } else {
                // Fallback: if body is null (shouldn't happen), fetch them normally
                xtreamCodesClient.getVodCategoriesAsObjects(providerConfig)
            }
            
            // Process streams using in-memory body if available
            val vodContent = xtreamCodesClient.getVodContent(providerConfig, vodCategories, vodStreamsBody)
            allContent.addAll(vodContent)
            
            // Update hashes after successful processing
            if (vodCategoriesHash != null) {
                val categoriesSize = vodCategoriesBody?.length?.toLong()
                updateSyncHash(providerConfig.name, "vod_categories", vodCategoriesHash, categoriesSize)
            }
            if (vodStreamsHash != null) {
                val streamsSize = vodStreamsBody?.length?.toLong()
                updateSyncHash(providerConfig.name, "vod_streams", vodStreamsHash, streamsSize)
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
            
            // Parse categories from in-memory body (reuse cached body even if unchanged)
            val seriesCategories = if (seriesCategoriesBody != null) {
                val categoriesList = xtreamCodesClient.parseSeriesCategoriesFromBody(seriesCategoriesBody)
                // Only sync categories if they changed (hash is not null)
                if (seriesCategoriesHash != null) {
                    syncCategories(providerConfig.name, "series", categoriesList.map { it.category_id.toString() to it.category_name })
                }
                categoriesList
            } else {
                // Fallback: if body is null (shouldn't happen), fetch them normally
                xtreamCodesClient.getSeriesCategoriesAsObjects(providerConfig)
            }
            
            // Process streams using in-memory body if available
            val seriesContent = xtreamCodesClient.getSeriesContent(providerConfig, seriesCategories, seriesStreamsBody)
            allContent.addAll(seriesContent)
            
            // Update hashes after successful processing
            if (seriesCategoriesHash != null) {
                val categoriesSize = seriesCategoriesBody?.length?.toLong()
                updateSyncHash(providerConfig.name, "series_categories", seriesCategoriesHash, categoriesSize)
            }
            if (seriesStreamsHash != null) {
                val streamsSize = seriesStreamsBody?.length?.toLong()
                updateSyncHash(providerConfig.name, "series_streams", seriesStreamsHash, streamsSize)
            }
        }
        
        // Process Live group: categories + streams (only if live is enabled for this provider)
        if (isLiveSyncEnabled(providerConfig)) {
            // Step 7: Fetch and check live categories hash
            val (liveCategoriesBody, liveCategoriesHash) = checkSingleEndpointHash(providerConfig, "live_categories")
            
            // Step 8: Always check live streams hash (streams can change independently)
            val (liveStreamsBody, liveStreamsHash) = checkSingleEndpointHash(providerConfig, "live_streams")
            
            // Step 9: Process live group if either changed
            if (liveCategoriesBody != null || liveStreamsBody != null) {
                hasChanges = true
                logger.debug("Syncing live content for provider ${providerConfig.name}")
                
                // Parse categories from in-memory body (reuse cached body even if unchanged)
                val liveCategories = if (liveCategoriesBody != null) {
                    val categoriesList = xtreamCodesClient.parseLiveCategoriesFromBody(liveCategoriesBody)
                    // Apply database filtering to categories - only sync categories that are not excluded
                    val filteredCategories = categoriesList.filter { category ->
                        liveChannelDatabaseFilterService.shouldIncludeCategoryForDatabase(
                            categoryName = category.category_name,
                            categoryId = category.category_id.toString(),
                            config = providerConfig
                        )
                    }
                    // Only sync categories if they changed (hash is not null) and after filtering
                    if (liveCategoriesHash != null) {
                        syncCategories(providerConfig.name, "live", filteredCategories.map { it.category_id.toString() to it.category_name })
                    }
                    // Return filtered categories for stream processing
                    filteredCategories
                } else {
                    // Fallback: if body is null (shouldn't happen), fetch them normally
                    val categoriesList = xtreamCodesClient.getLiveCategoriesAsObjects(providerConfig)
                    // Apply filtering to fallback categories too
                    categoriesList.filter { category ->
                        liveChannelDatabaseFilterService.shouldIncludeCategoryForDatabase(
                            categoryName = category.category_name,
                            categoryId = category.category_id.toString(),
                            config = providerConfig
                        )
                    }
                }
                
                // Process streams using in-memory body if available
                val liveContent = syncLiveContent(providerConfig, liveCategories, liveStreamsBody)
                allContent.addAll(liveContent)
                
                // Update hashes after successful processing
                if (liveCategoriesHash != null) {
                    val categoriesSize = liveCategoriesBody?.length?.toLong()
                    updateSyncHash(providerConfig.name, "live_categories", liveCategoriesHash, categoriesSize)
                }
                if (liveStreamsHash != null) {
                    val streamsSize = liveStreamsBody?.length?.toLong()
                    updateSyncHash(providerConfig.name, "live_streams", liveStreamsHash, streamsSize)
                }
            }
        }
        
        if (!hasChanges) {
            logger.info("Content hash unchanged for provider ${providerConfig.name}, skipping sync")
        } else {
            logger.info("Successfully synced provider ${providerConfig.name} (${allContent.size} items)")
        }
        
        return allContent
    }

    /**
     * Syncs live content with database filtering applied.
     * Returns filtered list of live content items that should be synced to database.
     */
    private suspend fun syncLiveContent(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        categories: List<io.skjaere.debridav.iptv.client.XtreamCodesClient.XtreamCategory>,
        preFetchedStreamsBody: String?
    ): List<io.skjaere.debridav.iptv.model.IptvContentItem> {
        // Get all live content from provider
        val allLiveContent = xtreamCodesClient.getLiveContent(providerConfig, categories, preFetchedStreamsBody)
        
        // Apply database filtering (exclude only)
        val categoryMap = categories.associateBy { it.category_id.toString() }
        
        return allLiveContent.filter { item ->
            val categoryIdStr = item.categoryId
            val categoryName = categoryIdStr?.let { categoryMap[it]?.category_name } ?: ""
            
            // First check if category should be included
            val includeCategory = if (categoryIdStr != null) {
                liveChannelDatabaseFilterService.shouldIncludeCategoryForDatabase(
                    categoryName = categoryName,
                    categoryId = categoryIdStr,
                    config = providerConfig
                )
            } else {
                true // If no category, include by default
            }
            
            if (!includeCategory) {
                // Category excluded, exclude all channels in it
                false
            } else {
                // Category included, check channel exclusion
                liveChannelDatabaseFilterService.shouldIncludeChannelForDatabase(
                    channelName = item.title,
                    categoryName = categoryName,
                    categoryId = categoryIdStr ?: "",
                    config = providerConfig
                )
            }
        }
    }

    /**
     * Syncs live content only (independent sync, not part of main sync)
     * @param forceSync If true, bypasses sync interval check and forces sync regardless of last sync time
     */
    private suspend fun syncLiveContentOnly(
        providerConfig: io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration,
        forceSync: Boolean = false
    ) {
        logger.info("Syncing live content only for provider: ${providerConfig.name}${if (forceSync) " (forced)" else ""}")
        
        // Check for interrupted syncs for live endpoints
        val interruptedSyncs = iptvSyncHashRepository.findByProviderNameAndSyncStatusInProgress(providerConfig.name)
            .filter { it.endpointType in listOf("live_categories", "live_streams") }
        if (interruptedSyncs.isNotEmpty()) {
            logger.info("Provider ${providerConfig.name} has ${interruptedSyncs.size} interrupted live sync(s), will resync immediately")
        } else if (!forceSync) {
            // Check per-provider timing for live sync (skip if forceSync is true)
            val liveSyncInterval = iptvConfigurationProperties.liveSyncInterval
            if (liveSyncInterval != null) {
                val mostRecentLiveSync = iptvSyncHashRepository.findMostRecentLastCheckedByProvider(providerConfig.name)
                    ?.let { lastChecked ->
                        // Check if any live endpoint was synced recently
                        val liveEndpoints = iptvSyncHashRepository.findByProviderName(providerConfig.name)
                            .filter { it.endpointType in listOf("live_categories", "live_streams") }
                            .filter { it.endpointType in listOf("live_categories", "live_streams") && it.lastChecked != null }
                            .maxOfOrNull { it.lastChecked!! }
                        liveEndpoints ?: lastChecked
                    }
                
                if (mostRecentLiveSync != null) {
                    val timeSinceLastSync = Duration.between(mostRecentLiveSync, Instant.now())
                    if (timeSinceLastSync < liveSyncInterval) {
                        val timeUntilNextSync = liveSyncInterval.minus(timeSinceLastSync)
                        logger.info("Skipping live sync for provider ${providerConfig.name} - only ${formatDuration(timeSinceLastSync)} since last sync. Next sync in ${formatDuration(timeUntilNextSync)}")
                        return
                    }
                }
            }
        } else {
            logger.info("Provider ${providerConfig.name} live sync forced via API, bypassing time interval check")
        }
        
        // Mark live sync as in progress
        val now = Instant.now()
        listOf("live_categories", "live_streams").forEach { endpointType ->
            val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
                ?: IptvSyncHashEntity().apply {
                    this.providerName = providerConfig.name
                    this.endpointType = endpointType
                    this.contentHash = ""
                    this.lastChecked = Instant.now()
                }
            
            hashEntity.syncStatus = "IN_PROGRESS"
            hashEntity.syncStartedAt = now
            iptvSyncHashRepository.save(hashEntity)
        }
        
        try {
            // Fetch and check live categories hash
            val (liveCategoriesBody, liveCategoriesHash) = checkSingleEndpointHash(providerConfig, "live_categories")
            
            // Fetch and check live streams hash
            val (liveStreamsBody, liveStreamsHash) = checkSingleEndpointHash(providerConfig, "live_streams")
            
            // Process live group if either changed, or if forceSync is true (force sync even if hash unchanged)
            val shouldSync = liveCategoriesBody != null || liveStreamsBody != null || forceSync
            
            if (shouldSync) {
                logger.debug("Syncing live content for provider ${providerConfig.name}${if (forceSync && liveCategoriesHash == null && liveStreamsHash == null) " (forced, hash unchanged)" else ""}")
                
                // Parse categories from in-memory body (body is returned even if hash unchanged, for reuse)
                val liveCategories = if (liveCategoriesBody != null) {
                    val categoriesList = xtreamCodesClient.parseLiveCategoriesFromBody(liveCategoriesBody)
                    // Apply database filtering to categories - only sync categories that are not excluded
                    val filteredCategories = categoriesList.filter { category ->
                        liveChannelDatabaseFilterService.shouldIncludeCategoryForDatabase(
                            categoryName = category.category_name,
                            categoryId = category.category_id.toString(),
                            config = providerConfig
                        )
                    }
                    // Sync categories if they changed (hash is not null) or if forceSync, and after filtering
                    if (liveCategoriesHash != null || forceSync) {
                        syncCategories(providerConfig.name, "live", filteredCategories.map { it.category_id.toString() to it.category_name })
                    }
                    // Return filtered categories for stream processing
                    filteredCategories
                } else {
                    // Fallback: fetch if body is null (shouldn't happen normally)
                    val categoriesList = xtreamCodesClient.getLiveCategoriesAsObjects(providerConfig)
                    // Apply filtering to fallback categories too
                    categoriesList.filter { category ->
                        liveChannelDatabaseFilterService.shouldIncludeCategoryForDatabase(
                            categoryName = category.category_name,
                            categoryId = category.category_id.toString(),
                            config = providerConfig
                        )
                    }
                }
                
                // Process streams with database filtering
                val liveContent = syncLiveContent(providerConfig, liveCategories, liveStreamsBody)
                
                if (liveContent.isNotEmpty()) {
                    syncContentToDatabase(providerConfig.name, liveContent)
                    
                    // Update existing live content URLs to use the correct extension
                    updateExistingLiveContentUrls(providerConfig.name, providerConfig.liveChannelExtension)
                }
                
                // Update hashes after successful processing (only if hash changed)
                if (liveCategoriesHash != null) {
                    val categoriesSize = liveCategoriesBody?.length?.toLong()
                    updateSyncHash(providerConfig.name, "live_categories", liveCategoriesHash, categoriesSize)
                }
                if (liveStreamsHash != null) {
                    val streamsSize = liveStreamsBody?.length?.toLong()
                    updateSyncHash(providerConfig.name, "live_streams", liveStreamsHash, streamsSize)
                }
                
                logger.info("Successfully synced live content for provider ${providerConfig.name} (${liveContent.size} items)")
            } else {
                logger.info("Live content hash unchanged for provider ${providerConfig.name}, skipping database sync")
            }
            
            // Cleanup inactive live categories and their associated streams after sync
            try {
                val (deletedCategories, deletedStreams) = iptvContentService.deleteInactiveLiveCategoriesAndStreamsForProvider(providerConfig.name)
                if (deletedCategories > 0 || deletedStreams > 0) {
                    logger.info("Cleaned up $deletedCategories inactive live categories and $deletedStreams associated live streams for provider ${providerConfig.name} after sync")
                }
            } catch (e: Exception) {
                logger.error("Error during cleanup of inactive live categories and streams for provider ${providerConfig.name}", e)
                // Don't fail the sync if cleanup fails
            }
            
            // Sync live channels (now just logs paths if logging is enabled)
            if (isLiveSyncEnabled(providerConfig)) {
                try {
                    liveChannelSyncService.syncLiveChannelsToVfs(providerConfig.name)
                } catch (e: Exception) {
                    logger.error("Error logging live channel paths for provider ${providerConfig.name}", e)
                }
            }
            
            // Mark live sync as completed
            listOf("live_categories", "live_streams").forEach { endpointType ->
                val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
                if (hashEntity != null) {
                    hashEntity.syncStatus = "COMPLETED"
                    hashEntity.syncStartedAt = null
                    iptvSyncHashRepository.save(hashEntity)
                }
            }
        } catch (e: Exception) {
            logger.error("Live sync failed for provider ${providerConfig.name}, hash not updated. Will retry on next sync.", e)
            // Mark live sync as failed
            listOf("live_categories", "live_streams").forEach { endpointType ->
                val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerConfig.name, endpointType)
                if (hashEntity != null) {
                    hashEntity.syncStatus = "FAILED"
                    hashEntity.syncStartedAt = null
                    iptvSyncHashRepository.save(hashEntity)
                }
            }
            throw e
        }
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

    private fun updateSyncHash(providerName: String, endpointType: String, newHash: String, responseBodySize: Long? = null) {
        val hashEntity = iptvSyncHashRepository.findByProviderNameAndEndpointType(providerName, endpointType)
            ?: IptvSyncHashEntity().apply {
                this.providerName = providerName
                this.endpointType = endpointType
            }
        
        hashEntity.contentHash = newHash
        hashEntity.lastChecked = Instant.now()
        hashEntity.syncStatus = "COMPLETED"
        hashEntity.syncStartedAt = null
        
        // Update file size from response body size (never from saved files)
        if (responseBodySize != null) {
            hashEntity.fileSize = responseBodySize
        }
        
        iptvSyncHashRepository.save(hashEntity)
    }

    private fun syncContentToDatabase(
        providerName: String,
        contentItems: List<io.skjaere.debridav.iptv.model.IptvContentItem>
    ) {
        val now = Instant.now()
        
        // Determine the content type being synced (all items should be the same type)
        val contentTypeBeingSynced = contentItems.firstOrNull()?.type
            ?: throw IllegalArgumentException("Cannot sync empty content list")
        
        // Get provider config for URL extension updates (for live content)
        val providerConfig = iptvConfigurationService.getProviderConfigurations()
            .find { it.name == providerName }
        
        // Query all content for this provider (including inactive) to avoid duplicate key violations
        // Note: We need ALL content (not filtered by type) because the unique constraint is on (provider_name, content_id)
        // The same content_id cannot exist twice for the same provider, regardless of content_type
        val allExistingContent = iptvContentRepository.findByProviderName(providerName)
        
        // Create a map of ALL existing content by contentId (across all types) to check for duplicates
        val allExistingMap = allExistingContent.associateBy { it.contentId }.toMutableMap()
        
        // Filter to only existing content of the same type being synced (for inactive marking)
        // This prevents marking other content types (e.g., MOVIES/SERIES) as inactive when syncing LIVE
        val existingContentOfType = allExistingContent.filter { it.contentType == contentTypeBeingSynced }
        val existingMapOfType = existingContentOfType.associateBy { it.contentId }.toMutableMap()
        
        // Deduplicate contentItems by id to avoid processing the same item twice
        val uniqueContentItems = contentItems.distinctBy { it.id }
        val incomingIds = uniqueContentItems.map { it.id }.toSet()
        
        // Mark content as inactive if it's no longer available in the provider's source
        // Only mark items of the same content type being synced (e.g., only LIVE items when syncing LIVE)
        // This only affects providers that are currently being synced (in iptv.providers list)
        // Providers removed from the config are not synced, so their content remains unchanged
        val inactiveEntities = existingMapOfType.values.filter { !incomingIds.contains(it.contentId) }
        if (inactiveEntities.isNotEmpty()) {
            inactiveEntities.forEach { existing ->
                existing.isActive = false
                existing.lastSynced = now
            }
            iptvContentRepository.saveAll(inactiveEntities)
            logger.debug("Marked ${inactiveEntities.size} items as inactive for provider $providerName")
        }
        
        // Get category map for linking
        val categoryMap = mutableMapOf<String, IptvCategoryEntity>()
        if (uniqueContentItems.any { it.categoryId != null && it.categoryType != null }) {
            val allCategories = iptvCategoryRepository.findByProviderName(providerName)
            categoryMap.putAll(allCategories.map { "${it.categoryType}:${it.categoryId}" to it })
        }
        
        // Prepare all entities for batch save
        val entitiesToSave = mutableListOf<IptvContentEntity>()
        uniqueContentItems.forEach { item ->
            // Check if entity already exists in database (across ALL types, not just current type)
            // This is necessary because the unique constraint is on (provider_name, content_id) without content_type
            // If the same content_id exists with a different type, we need to update it, not create a new one
            val entity = allExistingMap[item.id] ?: IptvContentEntity().apply {
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
            entity.normalizedTitle = iptvContentService.normalizeTitle(item.title)
            
            // For live content, ensure URL uses the configured extension
            val urlToStore = if (item.type == ContentType.LIVE && providerConfig != null) {
                updateLiveUrlExtension(
                    tokenizedUrl = item.url,
                    configuredExtension = providerConfig.liveChannelExtension,
                    providerName = providerName,
                    channelTitle = item.title,
                    categoryName = category?.categoryName
                )
            } else {
                item.url
            }
            entity.url = urlToStore
            
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
            
            entitiesToSave.add(entity)
        }
        
        // Batch save all entities at once
        if (entitiesToSave.isNotEmpty()) {
            val savedEntities = iptvContentRepository.saveAll(entitiesToSave)
            // Update both maps with saved entities
            savedEntities.forEach { saved ->
                allExistingMap[saved.contentId] = saved
                existingMapOfType[saved.contentId] = saved
            }
            logger.info("Synced ${entitiesToSave.size} items for provider $providerName")
        }
    }
    
    /**
     * Sanitizes a file name by removing invalid file system characters and converting to ASCII-only.
     * Handles Unicode characters including subscripts/superscripts by normalizing and replacing non-ASCII with underscores.
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName
            // Normalize Unicode characters (decompose, then remove combining marks)
            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            // Replace non-ASCII characters with underscores
            .replace(Regex("[^\\x00-\\x7F]"), "_")
            // Replace invalid file system characters with underscores
            .replace(Regex("[<>:\"/\\|?*]"), "_")
            // Replace spaces with underscores
            .replace(Regex("\\s+"), "_")
            // Consolidate multiple consecutive underscores into a single underscore
            .replace(Regex("_+"), "_")
            // Trim leading and trailing underscores
            .trim('_')
    }

    /**
     * Updates the extension in a live content URL to use the {ext} token.
     * URLs are in format: {BASE_URL}/live/{USERNAME}/{PASSWORD}/{stream_id}.{extension}
     * This replaces the extension part with {ext} token, which will be resolved at runtime.
     * Handles both tokenized URLs (with {BASE_URL}, {USERNAME}, {PASSWORD}) and resolved URLs.
     * 
     * Note: File paths (like /live/provider/category/channel.ts) don't need pattern replacement
     * and will be returned as-is without warnings.
     */
    private fun updateLiveUrlExtension(
        tokenizedUrl: String,
        configuredExtension: String,
        providerName: String? = null,
        channelTitle: String? = null,
        categoryName: String? = null
    ): String {
        // Pattern to match: /live/{anything}/{anything}/{stream_id}.{old_extension}
        // This handles both tokenized URLs ({BASE_URL}/live/{USERNAME}/...) and resolved URLs
        // The pattern matches: /live/ followed by two path segments, then stream_id, then extension
        val xtreamCodesPattern = Regex("""(/live/[^/]+/[^/]+/\d+)\.([a-zA-Z0-9]+)(\?.*)?$""")
        
        // Check if this is a file path (not an Xtream Codes URL)
        // File paths have format: /live/{provider}/{category}/{channel}.ts where channel is not numeric
        val filePathPattern = Regex("""^/live/[^/]+/[^/]+/[^/]+\.([a-zA-Z0-9]+)(\?.*)?$""")
        val isFilePath = filePathPattern.matches(tokenizedUrl) && !xtreamCodesPattern.containsMatchIn(tokenizedUrl)
        
        return if (xtreamCodesPattern.containsMatchIn(tokenizedUrl)) {
            // This is an Xtream Codes URL - update the extension
            xtreamCodesPattern.replace(tokenizedUrl) { matchResult ->
                val beforeExtension = matchResult.groupValues[1]
                val queryParams = matchResult.groupValues.getOrNull(3) ?: ""
                // Replace extension with {ext} token instead of configured extension
                "$beforeExtension.{ext}$queryParams"
            }
        } else if (isFilePath) {
            // This is a file path - return as-is without any warnings
            tokenizedUrl
        } else {
            // Pattern doesn't match and it's not a file path
            // This might be a malformed Xtream Codes URL, so log a warning
            // Only log if the URL looks like it should be an Xtream Codes URL (contains tokens or looks like a URL)
            val looksLikeXtreamUrl = tokenizedUrl.contains("{BASE_URL}") || 
                                    tokenizedUrl.contains("{USERNAME}") || 
                                    tokenizedUrl.contains("{PASSWORD}") ||
                                    tokenizedUrl.contains("://") ||
                                    (tokenizedUrl.startsWith("/live/") && tokenizedUrl.contains("/"))
            
            if (looksLikeXtreamUrl) {
                logger.debug("Could not update extension in live URL (pattern didn't match): $tokenizedUrl")
            }
            tokenizedUrl
        }
    }
    
    /**
     * Updates existing live content URLs in the database to use the configured extension.
     * This ensures that URLs that were previously synced with a different extension are updated.
     */
    private fun updateExistingLiveContentUrls(providerName: String, configuredExtension: String) {
        val existingLiveContent = iptvContentRepository.findByProviderName(providerName)
            .filter { it.contentType == ContentType.LIVE && it.isActive }
        
        if (existingLiveContent.isEmpty()) {
            return
        }
        
        var updatedCount = 0
        val contentToUpdate = mutableListOf<IptvContentEntity>()
        
        existingLiveContent.forEach { content ->
            val category = content.category
            val updatedUrl = updateLiveUrlExtension(
                tokenizedUrl = content.url,
                configuredExtension = configuredExtension,
                providerName = providerName,
                channelTitle = content.title,
                categoryName = category?.categoryName
            )
            if (updatedUrl != content.url) {
                content.url = updatedUrl
                contentToUpdate.add(content)
                updatedCount++
            }
        }
        
        if (contentToUpdate.isNotEmpty()) {
            iptvContentRepository.saveAll(contentToUpdate)
            logger.debug("Updated $updatedCount existing live content URLs to use extension '$configuredExtension' for provider $providerName")
        }
    }
    
    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds % 60
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}

