package io.skjaere.debridav.webdav.folder

import io.skjaere.debridav.webdav.folder.sync.WebDavFolderSyncService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/webdav-folder-mapping")
@ConditionalOnProperty(
    prefix = "debridav.webdav-folder-mapping",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class WebDavFolderMappingController(
    private val folderMappingRepository: WebDavFolderMappingRepository,
    private val syncService: WebDavFolderSyncService
) {
    private val logger = LoggerFactory.getLogger(WebDavFolderMappingController::class.java)

    @PostMapping("/sync")
    fun syncAll(): ResponseEntity<Map<String, String>> {
        return try {
            runBlocking {
                syncService.syncAllMappings()
            }
            ResponseEntity.ok(mapOf("status" to "success", "message" to "Sync completed"))
        } catch (e: Exception) {
            logger.error("Error syncing all mappings", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    @PostMapping("/provider/{providerName}/sync")
    fun syncByProvider(@PathVariable providerName: String): ResponseEntity<Map<String, Any>> {
        return try {
            val mappings = folderMappingRepository.findByProviderName(providerName)
            if (mappings.isEmpty()) {
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(mapOf(
                        "status" to "error",
                        "message" to "No mappings found for provider: $providerName"
                    ))
            } else {
                runBlocking {
                    mappings.forEach { mapping ->
                        syncService.syncMapping(mapping)
                    }
                }
                ResponseEntity.ok(mapOf(
                    "status" to "success",
                    "message" to "Sync completed for ${mappings.size} mapping(s)",
                    "syncedMappings" to mappings.size
                ))
            }
        } catch (e: Exception) {
            logger.error("Error syncing mappings for provider $providerName", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    @PostMapping("/{id}/sync")
    fun syncMapping(@PathVariable id: Long): ResponseEntity<Map<String, String>> {
        return try {
            val mapping = folderMappingRepository.findById(id).orElse(null)
            if (mapping == null) {
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(mapOf("status" to "error", "message" to "Mapping not found"))
            } else {
                runBlocking {
                    syncService.syncMapping(mapping)
                }
                ResponseEntity.ok(mapOf("status" to "success", "message" to "Sync completed"))
            }
        } catch (e: Exception) {
            logger.error("Error syncing mapping $id", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    @GetMapping
    fun listMappings(): ResponseEntity<List<WebDavFolderMappingEntity>> {
        return ResponseEntity.ok(folderMappingRepository.findAll())
    }

    @GetMapping("/{id}")
    fun getMapping(@PathVariable id: Long): ResponseEntity<WebDavFolderMappingEntity> {
        val mapping = folderMappingRepository.findById(id).orElse(null)
        return if (mapping != null) {
            ResponseEntity.ok(mapping)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping
    fun createMapping(@RequestBody mapping: WebDavFolderMappingEntity): ResponseEntity<WebDavFolderMappingEntity> {
        mapping.createdAt = Instant.now()
        mapping.updatedAt = Instant.now()
        val saved = folderMappingRepository.save(mapping)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    @PutMapping("/{id}")
    fun updateMapping(
        @PathVariable id: Long,
        @RequestBody mapping: WebDavFolderMappingEntity
    ): ResponseEntity<WebDavFolderMappingEntity> {
        val existing = folderMappingRepository.findById(id).orElse(null)
        return if (existing != null) {
            existing.providerName = mapping.providerName
            existing.externalPath = mapping.externalPath
            existing.internalPath = mapping.internalPath
            existing.enabled = mapping.enabled
            existing.syncInterval = mapping.syncInterval
            existing.updatedAt = Instant.now()
            val saved = folderMappingRepository.save(existing)
            ResponseEntity.ok(saved)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteMapping(@PathVariable id: Long): ResponseEntity<Map<String, String>> {
        return if (folderMappingRepository.existsById(id)) {
            folderMappingRepository.deleteById(id)
            ResponseEntity.ok(mapOf("status" to "success", "message" to "Mapping deleted"))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
