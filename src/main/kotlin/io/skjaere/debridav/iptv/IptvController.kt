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
}

