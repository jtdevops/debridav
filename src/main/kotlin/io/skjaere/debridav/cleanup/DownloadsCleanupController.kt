package io.skjaere.debridav.cleanup

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/cleanup")
class DownloadsCleanupController(
    private val downloadsCleanupService: DownloadsCleanupService
) {
    @GetMapping("/downloads/abandoned")
    fun findAbandonedFiles(
        @RequestParam(defaultValue = "6") cleanupAgeHours: Long,
        @RequestParam(defaultValue = "1") arrCleanupAgeHours: Long,
        @RequestParam arrCategories: List<String>? = null
    ): ResponseEntity<AbandonedFilesResponse> {
        // Use dynamic ARR categories if not specified
        val arrCategoriesList = arrCategories ?: downloadsCleanupService.getArrCategories()
        
        // Find files not linked to ARR categories
        val arrAbandonedFiles = downloadsCleanupService.findAbandonedFilesNotLinkedToArr(
            arrCleanupAgeHours, arrCategoriesList
        )
        
        // Find all abandoned files
        val allAbandonedFiles = downloadsCleanupService.findAbandonedFiles(cleanupAgeHours)
        
        // Combine and deduplicate
        val abandonedFiles = (arrAbandonedFiles + allAbandonedFiles).distinctBy { it.id }
        
        val totalSize = abandonedFiles.sumOf { it.size ?: 0L }
        
        return ResponseEntity.ok(
            AbandonedFilesResponse(
                count = abandonedFiles.size,
                totalSizeBytes = totalSize,
                arrNotLinkedCount = arrAbandonedFiles.size,
                files = abandonedFiles.map { file ->
                    AbandonedFileInfo(
                        id = file.id,
                        name = file.name,
                        path = file.directory?.let { dir ->
                            dir.fileSystemPath()?.let { "$it/${file.name}" } ?: file.name
                        } ?: file.name,
                        size = file.size,
                        lastModified = file.lastModified,
                        linkedToArr = file.id !in arrAbandonedFiles.map { it.id }.toSet()
                    )
                }
            )
        )
    }

    @PostMapping("/downloads/cleanup")
    fun cleanupAbandonedFiles(
        @RequestParam(defaultValue = "6") cleanupAgeHours: Long,
        @RequestParam(defaultValue = "1") arrCleanupAgeHours: Long,
        @RequestParam arrCategories: List<String>? = null,
        @RequestParam(defaultValue = "false") dryRun: Boolean
    ): ResponseEntity<CleanupResponse> {
        // Use dynamic ARR categories if not specified
        val arrCategoriesList = arrCategories ?: downloadsCleanupService.getArrCategories()
        val result = downloadsCleanupService.cleanupAbandonedFiles(
            cleanupAgeHours, arrCleanupAgeHours, arrCategoriesList, dryRun
        )
        
        return ResponseEntity.ok(
            CleanupResponse(
                deletedCount = result.deletedCount,
                totalSizeBytes = result.totalSizeBytes,
                totalSizeMB = result.totalSizeMB,
                deletedDirectoriesCount = result.deletedDirectoriesCount,
                errors = result.errors,
                dryRun = result.dryRun
            )
        )
    }

    data class AbandonedFilesResponse(
        val count: Int,
        val totalSizeBytes: Long,
        val arrNotLinkedCount: Int,
        val files: List<AbandonedFileInfo>
    )

    data class AbandonedFileInfo(
        val id: Long?,
        val name: String?,
        val path: String?,
        val size: Long?,
        val lastModified: Long?,
        val linkedToArr: Boolean = false
    )

    data class CleanupResponse(
        val deletedCount: Int,
        val totalSizeBytes: Long,
        val totalSizeMB: Double,
        val deletedDirectoriesCount: Int,
        val errors: List<String>,
        val dryRun: Boolean
    )

    @GetMapping("/downloads/diagnose")
    fun diagnoseFileCleanupStatus(
        @RequestParam filePath: String
    ): ResponseEntity<String> {
        val diagnostic = downloadsCleanupService.diagnoseFileCleanupStatus(filePath)
        return ResponseEntity.ok(diagnostic)
    }
}

