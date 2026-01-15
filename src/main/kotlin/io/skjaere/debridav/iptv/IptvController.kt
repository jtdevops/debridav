package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/iptv")
class IptvController(
    private val iptvSyncService: IptvSyncService,
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvContentService: IptvContentService
) {
    private val logger = LoggerFactory.getLogger(IptvController::class.java)

    @PostMapping("/sync")
    fun triggerSync(): ResponseEntity<Map<String, String>> {
        logger.info("Manual IPTV sync triggered")
        iptvSyncService.syncIptvContent(forceSync = true)
        return ResponseEntity.ok(mapOf("status" to "sync triggered"))
    }

    @PostMapping("/live/sync")
    fun triggerLiveSync(): ResponseEntity<Map<String, String>> {
        if (!iptvConfigurationProperties.liveEnabled) {
            return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "IPTV Live is disabled"))
        }
        logger.info("Manual IPTV live sync triggered")
        // Trigger live-only sync for all providers (force sync to bypass interval check)
        iptvSyncService.syncLiveContentOnly(forceSync = true)
        return ResponseEntity.ok(mapOf("status" to "live sync triggered"))
    }

    @GetMapping("/config")
    fun getStatus(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "enabled" to iptvConfigurationProperties.enabled,
            "syncInterval" to iptvConfigurationProperties.syncInterval.toString(),
            "initialSyncDelay" to iptvConfigurationProperties.initialSyncDelay.toString(),
            "providers" to iptvConfigurationProperties.providers.size
        ))
    }

    @GetMapping("/content")
    fun listContent(): ResponseEntity<Map<String, Any>> {
        // TODO: Implement content listing if needed
        return ResponseEntity.ok(mapOf("message" to "Content listing not yet implemented"))
    }

    @DeleteMapping("/provider/{providerName}")
    fun deleteProvider(@PathVariable providerName: String): ResponseEntity<Map<String, Any>> {
        logger.info("Deleting IPTV provider content: $providerName")
        val deletedCount = iptvContentService.deleteProviderContent(providerName)
        return ResponseEntity.ok(mapOf(
            "status" to "deleted",
            "provider" to providerName,
            "deletedCount" to deletedCount
        ))
    }
    
    @GetMapping("/inactive/files")
    fun getInactiveContentWithFiles(): ResponseEntity<Map<String, Any>> {
        logger.info("Listing files linked to inactive IPTV content")
        val linkedContent = iptvContentService.findFilesLinkedToInactiveContent()
        
        val result = linkedContent.map { (content, files) ->
            mapOf(
                "iptvContent" to mapOf(
                    "id" to content.id,
                    "providerName" to content.providerName,
                    "contentId" to content.contentId,
                    "title" to content.title,
                    "contentType" to content.contentType.name,
                    "isActive" to content.isActive,
                    "lastSynced" to content.lastSynced.toString()
                ),
                "files" to files.map { file ->
                    val fullPath = if (file.directory != null && file.name != null) {
                        val dirPath = file.directory!!.fileSystemPath() ?: ""
                        if (dirPath.endsWith("/")) {
                            "$dirPath${file.name}"
                        } else {
                            "$dirPath/${file.name}"
                        }
                    } else {
                        file.name ?: ""
                    }
                    mapOf(
                        "id" to file.id,
                        "name" to file.name,
                        "path" to fullPath,
                        "hash" to file.hash,
                        "size" to file.size,
                        "mimeType" to file.mimeType
                    )
                },
                "fileCount" to files.size
            )
        }
        
        return ResponseEntity.ok(mapOf(
            "inactiveContentWithFiles" to result,
            "totalInactiveItems" to linkedContent.size,
            "totalFiles" to linkedContent.values.sumOf { it.size }
        ))
    }
    
    @DeleteMapping("/inactive/without-files")
    fun deleteInactiveContentWithoutFiles(): ResponseEntity<Map<String, Any>> {
        logger.info("Deleting inactive IPTV content items without linked files")
        val deletedCount = iptvContentService.deleteInactiveContentWithoutFiles()
        return ResponseEntity.ok(mapOf(
            "status" to "deleted",
            "deletedCount" to deletedCount
        ))
    }
}

