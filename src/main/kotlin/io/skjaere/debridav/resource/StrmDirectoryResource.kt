package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.resource.CollectionResource
import io.milton.resource.Resource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.LocalContentsService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.*

/**
 * Virtual STRM directory resource that mirrors an original directory structure
 * but replaces media files with STRM files.
 */
class StrmDirectoryResource(
    private val originalDirectory: DbDirectory,
    private val strmPath: String,
    private val resourceFactory: StreamableResourceFactory,
    private val localContentsService: LocalContentsService,
    fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) : AbstractResource(fileService, originalDirectory), CollectionResource {

    override fun getUniqueId(): String {
        return "strm_dir_${originalDirectory.id}"
    }

    override fun getName(): String {
        // Extract the STRM folder name from the path
        return strmPath.removePrefix("/").split("/").lastOrNull() ?: strmPath
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(
            Instant.ofEpochMilli(
                originalDirectory.lastModified ?: System.currentTimeMillis()
            )
        )
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli(originalDirectory.lastModified ?: System.currentTimeMillis()))
    }

    override fun child(childName: String?): Resource? {
        return getChildren().firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> = runBlocking {
        // Get children from the original directory
        fileService.getChildren(originalDirectory)
            .toList()
            .map { async { toStrmResource(it) } }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Converts an original DbEntity to a STRM resource (either STRM directory or STRM file).
     */
    private fun toStrmResource(entity: DbEntity): Resource? {
        return when (entity) {
            is DbDirectory -> {
                // For subdirectories, create nested STRM directories
                val originalPath = entity.fileSystemPath() ?: return null
                val originalBasePath = originalDirectory.fileSystemPath() ?: return null
                
                // Calculate the relative path from the original directory
                val relativePath = originalPath.removePrefix(originalBasePath).removePrefix("/")
                val nestedStrmPath = if (relativePath.isNotEmpty()) {
                    "$strmPath/$relativePath"
                } else {
                    strmPath
                }
                
                StrmDirectoryResource(
                    entity,
                    nestedStrmPath,
                    resourceFactory,
                    localContentsService,
                    fileService,
                    debridavConfigurationProperties
                )
            }
            else -> {
                // For files, check if they should be converted to STRM
                val fileName = entity.name ?: return null
                if (debridavConfigurationProperties.shouldCreateStrmFile(fileName)) {
                    // Convert media files to STRM files
                    val originalPath = originalDirectory.fileSystemPath() ?: return null
                    val fullOriginalPath = "$originalPath/$fileName"
                    StrmFileResource(
                        entity,
                        fullOriginalPath,
                        fileService,
                        debridavConfigurationProperties
                    )
                } else {
                    // Non-media files (like .srt subtitles) should appear as regular files
                    // without the .strm extension
                    resourceFactory.toFileResource(entity)
                }
            }
        }
    }
}

