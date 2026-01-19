package io.skjaere.debridav.debrid.folder.sync

import io.skjaere.debridav.debrid.folder.DebridFolderMappingEntity
import io.skjaere.debridav.debrid.folder.DebridSyncedFileEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FileMappingService {
    private val logger = LoggerFactory.getLogger(FileMappingService::class.java)

    /**
     * Maps a provider file path to VFS path based on the folder mapping
     */
    fun mapToVfsPath(mapping: DebridFolderMappingEntity, providerFilePath: String): String {
        val externalPath = mapping.externalPath ?: ""
        val internalPath = mapping.internalPath ?: ""

        // Remove the external path prefix from provider file path
        val relativePath = if (providerFilePath.startsWith(externalPath)) {
            providerFilePath.removePrefix(externalPath).removePrefix("/")
        } else {
            providerFilePath.removePrefix("/")
        }

        // Combine internal path with relative path
        val vfsPath = if (internalPath.endsWith("/")) {
            "$internalPath$relativePath"
        } else {
            "$internalPath/$relativePath"
        }

        return vfsPath.replace("//", "/")
    }

    /**
     * Gets the VFS file name from the provider file path
     */
    fun getVfsFileName(providerFilePath: String): String {
        return providerFilePath.substringAfterLast("/")
    }

    /**
     * Generates a unique VFS path if there's a conflict
     */
    fun generateUniqueVfsPath(baseVfsPath: String, existingPaths: Set<String>): String {
        var uniquePath = baseVfsPath
        var counter = 1

        while (existingPaths.contains(uniquePath)) {
            val pathWithoutExt = baseVfsPath.substringBeforeLast(".")
            val ext = baseVfsPath.substringAfterLast(".", "")
            uniquePath = if (ext.isNotEmpty() && ext != baseVfsPath) {
                "$pathWithoutExt ($counter).$ext"
            } else {
                "$baseVfsPath ($counter)"
            }
            counter++
        }

        return uniquePath
    }

    /**
     * Updates VFS file name if file was renamed in VFS
     */
    fun updateVfsFileName(
        syncedFile: DebridSyncedFileEntity,
        newVfsFileName: String
    ): DebridSyncedFileEntity {
        syncedFile.vfsFileName = newVfsFileName
        return syncedFile
    }

    /**
     * Checks if a file path matches the mapping's external path
     */
    fun matchesMapping(mapping: DebridFolderMappingEntity, providerFilePath: String): Boolean {
        val externalPath = mapping.externalPath ?: ""
        return providerFilePath.startsWith(externalPath) || 
               providerFilePath.startsWith("/$externalPath")
    }
}
