package io.skjaere.debridav.debrid.folder.sync

import io.skjaere.debridav.debrid.folder.DebridFolderMappingProperties
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

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
}
