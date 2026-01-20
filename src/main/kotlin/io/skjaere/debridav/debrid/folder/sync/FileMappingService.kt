package io.skjaere.debridav.debrid.folder.sync

import io.skjaere.debridav.debrid.folder.DebridFolderMappingEntity
import io.skjaere.debridav.debrid.folder.DebridSyncedFileEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Service
class FileMappingService {
    private val logger = LoggerFactory.getLogger(FileMappingService::class.java)

    /**
     * Maps a provider file path to VFS path based on the folder mapping
     */
    fun mapToVfsPath(mapping: DebridFolderMappingEntity, providerFilePath: String): String {
        val externalPath = mapping.externalPath ?: ""
        val internalPath = mapping.internalPath ?: ""

        // URL decode the provider file path (WebDAV returns URL-encoded paths)
        val decodedProviderPath = urlDecode(providerFilePath)
        val decodedExternalPath = urlDecode(externalPath)

        // Remove the external path prefix from provider file path
        val relativePath = if (decodedProviderPath.startsWith(decodedExternalPath)) {
            decodedProviderPath.removePrefix(decodedExternalPath).removePrefix("/")
        } else {
            decodedProviderPath.removePrefix("/")
        }

        // Get just the directory part (without the filename)
        val relativeDir = relativePath.substringBeforeLast("/", "")

        // Combine internal path with relative directory
        val vfsPath = if (relativeDir.isEmpty()) {
            internalPath
        } else if (internalPath.endsWith("/")) {
            "$internalPath$relativeDir"
        } else {
            "$internalPath/$relativeDir"
        }

        val result = vfsPath.replace("//", "/")
        logger.debug("Mapped provider path '{}' to VFS path '{}'", providerFilePath, result)
        return result
    }

    /**
     * Gets the VFS file name from the provider file path
     */
    fun getVfsFileName(providerFilePath: String): String {
        // URL decode the path first
        val decoded = urlDecode(providerFilePath)
        return decoded.substringAfterLast("/")
    }

    /**
     * URL decode a path, handling URL-encoded characters like %20, %5b, etc.
     */
    private fun urlDecode(path: String): String {
        return try {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            logger.warn("Failed to URL decode path: {}", path)
            path
        }
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
