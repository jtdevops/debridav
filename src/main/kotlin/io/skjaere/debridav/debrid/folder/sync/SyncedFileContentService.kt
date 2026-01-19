package io.skjaere.debridav.debrid.folder.sync

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.folder.DebridSyncedFileEntity
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridFileContents
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SyncedFileContentService {
    private val logger = LoggerFactory.getLogger(SyncedFileContentService::class.java)

    /**
     * Creates DebridFileContents for a synced file
     */
    fun createDebridFileContents(
        syncedFile: DebridSyncedFileEntity,
        provider: DebridProvider
    ): DebridFileContents {
        val cachedFile = CachedFile(
            path = syncedFile.providerFilePath ?: "",
            size = syncedFile.fileSize ?: 0L,
            mimeType = syncedFile.mimeType ?: "application/octet-stream",
            link = syncedFile.providerLink ?: "",
            params = mapOf("synced_file_id" to (syncedFile.id?.toString() ?: "")),
            lastChecked = syncedFile.lastChecked?.toEpochMilli() ?: Instant.now().toEpochMilli(),
            provider = provider
        )

        // Use DebridCachedTorrentContent for synced files
        // We don't have a magnet, so we'll use the file path as identifier
        val contents = DebridCachedTorrentContent(
            originalPath = syncedFile.vfsPath,
            size = syncedFile.fileSize,
            modified = syncedFile.lastChecked?.toEpochMilli() ?: Instant.now().toEpochMilli(),
            magnet = null, // Synced files don't have magnets
            mimeType = syncedFile.mimeType,
            debridLinks = mutableListOf(cachedFile)
        )

        return contents
    }

    /**
     * Updates DebridFileContents with new link information
     */
    fun updateDebridFileContents(
        contents: DebridFileContents,
        syncedFile: DebridSyncedFileEntity,
        provider: DebridProvider
    ): DebridFileContents {
        val cachedFile = contents.debridLinks
            .firstOrNull { it is CachedFile && it.provider == provider } as? CachedFile

        if (cachedFile != null) {
            // Update existing link
            cachedFile.link = syncedFile.providerLink
            cachedFile.lastChecked = syncedFile.lastChecked?.toEpochMilli() ?: Instant.now().toEpochMilli()
            cachedFile.size = syncedFile.fileSize ?: cachedFile.size
            cachedFile.mimeType = syncedFile.mimeType ?: cachedFile.mimeType
        } else {
            // Add new link
            val newCachedFile = CachedFile(
                path = syncedFile.providerFilePath ?: "",
                size = syncedFile.fileSize ?: 0L,
                mimeType = syncedFile.mimeType ?: "application/octet-stream",
                link = syncedFile.providerLink ?: "",
                params = mapOf("synced_file_id" to (syncedFile.id?.toString() ?: "")),
                lastChecked = syncedFile.lastChecked?.toEpochMilli() ?: Instant.now().toEpochMilli(),
                provider = provider
            )
            contents.debridLinks.add(newCachedFile)
        }

        // Update file contents metadata
        contents.size = syncedFile.fileSize ?: contents.size
        contents.mimeType = syncedFile.mimeType ?: contents.mimeType
        contents.modified = syncedFile.lastChecked?.toEpochMilli() ?: contents.modified

        return contents
    }
}
