package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.http.http11.auth.DigestResponse
import io.milton.resource.CollectionResource
import io.milton.resource.DigestResource
import io.milton.resource.PropFindableResource
import io.milton.resource.Resource
import io.skjaere.debridav.webdav.folder.WebDavFolderMappingEntity
import io.skjaere.debridav.webdav.folder.WebDavSyncedFileEntity
import io.skjaere.debridav.webdav.folder.WebDavSyncedFileRepository
import io.skjaere.debridav.fs.DatabaseFileService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * Virtual directory resource for mapped WebDAV folders.
 * Handles both root-level mapped directories and nested subdirectories.
 * 
 * For root directories: pass mapping parameter, syncedFiles will be queried from DB
 * For subdirectories: pass fullPath, dirName, and pre-filtered syncedFiles
 */
class WebDavDirectoryResource private constructor(
    private val fullPath: String,
    private val dirName: String,
    private val mapping: WebDavFolderMappingEntity?,
    private val syncedFiles: List<WebDavSyncedFileEntity>?,
    private val resourceFactory: StreamableResourceFactory,
    private val fileService: DatabaseFileService,
    private val syncedFileRepository: WebDavSyncedFileRepository
) : DigestResource, PropFindableResource, CollectionResource {

    private val logger = LoggerFactory.getLogger(WebDavDirectoryResource::class.java)

    /**
     * Create a root-level WebDAV folder directory resource.
     * Files will be queried from the database based on the mapping.
     */
    constructor(
        mapping: WebDavFolderMappingEntity,
        resourceFactory: StreamableResourceFactory,
        fileService: DatabaseFileService,
        syncedFileRepository: WebDavSyncedFileRepository
    ) : this(
        fullPath = mapping.internalPath ?: "/",
        dirName = mapping.internalPath?.removePrefix("/")?.split("/")?.lastOrNull() 
            ?: mapping.internalPath ?: "unknown",
        mapping = mapping,
        syncedFiles = null, // Will be queried from DB
        resourceFactory = resourceFactory,
        fileService = fileService,
        syncedFileRepository = syncedFileRepository
    )

    /**
     * Create a subdirectory resource within a WebDAV folder.
     * Uses pre-filtered syncedFiles to avoid repeated DB queries.
     */
    constructor(
        fullPath: String,
        dirName: String,
        syncedFiles: List<WebDavSyncedFileEntity>,
        resourceFactory: StreamableResourceFactory,
        fileService: DatabaseFileService,
        syncedFileRepository: WebDavSyncedFileRepository
    ) : this(
        fullPath = fullPath,
        dirName = dirName,
        mapping = null,
        syncedFiles = syncedFiles,
        resourceFactory = resourceFactory,
        fileService = fileService,
        syncedFileRepository = syncedFileRepository
    )

    /**
     * Get the full VFS path for this WebDAV folder directory.
     * Used for move operations to determine the destination path.
     */
    fun getFullPath(): String = fullPath

    /**
     * Check if this is a root-level mapped directory (has mapping) or a subdirectory.
     */
    fun isRootDirectory(): Boolean = mapping != null

    override fun getUniqueId(): String {
        return if (mapping != null) {
            "webdav_folder_mapping_${mapping.id}"
        } else {
            "webdav_folder_subdir_${fullPath.hashCode()}"
        }
    }

    override fun getName(): String = dirName

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean = true

    override fun getRealm(): String = "realm"

    override fun getModifiedDate(): Date {
        return if (mapping != null) {
            Date.from(mapping.lastSynced ?: Instant.now())
        } else {
            Date.from(Instant.now())
        }
    }

    override fun checkRedirect(request: Request?): String? = null

    override fun isDigestAllowed(): Boolean = true

    override fun getCreateDate(): Date {
        return if (mapping != null) {
            Date.from(mapping.createdAt ?: Instant.now())
        } else {
            Date.from(Instant.now())
        }
    }

    override fun authenticate(user: String?, requestedPassword: String?): Any? = null

    override fun authenticate(digestRequest: DigestResponse?): Any? = null

    override fun child(childName: String?): Resource? {
        return getChildren().firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> = runBlocking {
        val children = mutableListOf<Resource>()
        val directoryMap = mutableMapOf<String, MutableList<WebDavSyncedFileEntity>>()

        // Get synced files - either from DB (root) or from pre-filtered list (subdirectory)
        val files = if (mapping != null) {
            syncedFileRepository.findByFolderMappingAndIsDeleted(mapping, false)
        } else {
            syncedFiles ?: emptyList()
        }

        files.forEach { syncedFile ->
            val vfsPath = syncedFile.vfsPath ?: return@forEach
            val vfsFileName = syncedFile.vfsFileName ?: return@forEach

            // Construct full file path from vfsPath (directory) + vfsFileName
            val fullFilePath = if (vfsPath.endsWith("/")) {
                "$vfsPath$vfsFileName"
            } else {
                "$vfsPath/$vfsFileName"
            }

            val relativePath = fullFilePath.removePrefix(fullPath).removePrefix("/")

            if (relativePath.contains("/")) {
                // File is in a subdirectory - get the first directory level
                val subDirName = relativePath.substringBefore("/")
                directoryMap.getOrPut(subDirName) { mutableListOf() }.add(syncedFile)
            } else {
                // File is directly in this directory
                val fileEntity = fileService.getFileAtPath(fullFilePath)
                if (fileEntity != null) {
                    resourceFactory.toFileResource(fileEntity)?.let { children.add(it) }
                } else {
                    logger.warn("File not found at path: {}", fullFilePath)
                }
            }
        }

        // Create subdirectories
        directoryMap.forEach { (subDirName, subDirFiles) ->
            val subDirPath = if (fullPath.endsWith("/")) {
                "$fullPath$subDirName"
            } else {
                "$fullPath/$subDirName"
            }

            // Create a subdirectory resource (recursive structure)
            val subDirResource = WebDavDirectoryResource(
                subDirPath,
                subDirName,
                subDirFiles,
                resourceFactory,
                fileService,
                syncedFileRepository
            )
            children.add(subDirResource)
        }

        children
    }
}
