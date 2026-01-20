package io.skjaere.debridav.debrid.folder.sync

import io.skjaere.debridav.debrid.folder.DebridFolderMappingProperties
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "debridav.debrid-folder-mapping",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class DebridFolderSyncScheduler(
    private val syncService: DebridFolderSyncService,
    private val folderMappingProperties: DebridFolderMappingProperties
) {
    private val logger = LoggerFactory.getLogger(DebridFolderSyncScheduler::class.java)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs an initial sync immediately after the application is ready.
     * This runs asynchronously so it doesn't block application startup.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun runInitialSync() {
        if (!folderMappingProperties.enabled) {
            return
        }

        logger.info("Starting initial debrid folder sync on application startup")
        syncScope.launch {
            try {
                syncService.syncAllMappings()
                logger.info("Initial debrid folder sync completed")
            } catch (e: Exception) {
                logger.error("Error in initial debrid folder sync", e)
            }
        }
    }

    /**
     * Scheduled sync that runs at the configured interval.
     * The initial delay matches the sync interval since the initial sync
     * is handled separately by [runInitialSync].
     */
    @Scheduled(
        initialDelayString = "\${debridav.debrid-folder-mapping.sync-interval:PT1H}",
        fixedRateString = "\${debridav.debrid-folder-mapping.sync-interval:PT1H}"
    )
    fun syncTask() {
        if (folderMappingProperties.enabled) {
            logger.debug("Starting scheduled folder sync")
            runBlocking {
                try {
                    syncService.syncAllMappings()
                } catch (e: Exception) {
                    logger.error("Error in scheduled folder sync", e)
                }
            }
        }
    }

    @PreDestroy
    fun cleanup() {
        syncScope.cancel()
    }
}
