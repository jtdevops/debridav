package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.http.http11.auth.DigestResponse
import io.milton.resource.CollectionResource
import io.milton.resource.DigestResource
import io.milton.resource.PropFindableResource
import io.milton.resource.Resource
import io.skjaere.debridav.debrid.folder.DebridFolderMappingEntity
import io.skjaere.debridav.debrid.folder.DebridSyncedFileEntity
import io.skjaere.debridav.debrid.folder.DebridSyncedFileRepository
import io.skjaere.debridav.fs.DatabaseFileService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * Virtual directory resource for mapped debrid folders.
 * Lists synced files from the database and creates virtual directory structure.
 */
class DebridFolderDirectoryResource(
    private val mapping: DebridFolderMappingEntity,
    private val resourceFactory: StreamableResourceFactory,
    private val fileService: DatabaseFileService,
    private val syncedFileRepository: DebridSyncedFileRepository
) : DigestResource, PropFindableResource, CollectionResource {
    private val logger = LoggerFactory.getLogger(DebridFolderDirectoryResource::class.java)

    override fun getUniqueId(): String {
        return "debrid_folder_mapping_${mapping.id}"
    }

    override fun getName(): String {
        return mapping.internalPath?.removePrefix("/")?.split("/")?.lastOrNull() ?: mapping.internalPath ?: "unknown"
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(mapping.lastSynced ?: Instant.now())
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(mapping.createdAt ?: Instant.now())
    }

    override fun authenticate(user: String?, requestedPassword: String?): Any? {
        return null
    }

    override fun authenticate(digestRequest: DigestResponse?): Any? {
        return null
    }

    override fun child(childName: String?): Resource? {
        return getChildren().firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> = runBlocking {
        val internalPath = mapping.internalPath ?: return@runBlocking emptyList()
        
        // Get all synced files for this mapping that are not deleted
        val syncedFiles = syncedFileRepository.findByFolderMappingAndIsDeleted(mapping, false)
        
        // Group files by directory structure
        val children = mutableListOf<Resource>()
        val directoryMap = mutableMapOf<String, MutableList<DebridSyncedFileEntity>>()
        
        syncedFiles.forEach { syncedFile ->
            val vfsPath = syncedFile.vfsPath ?: return@forEach
            val relativePath = vfsPath.removePrefix(internalPath).removePrefix("/")
            
            if (relativePath.contains("/")) {
                // File is in a subdirectory
                val dirPath = relativePath.substringBeforeLast("/")
                directoryMap.getOrPut(dirPath) { mutableListOf() }.add(syncedFile)
            } else {
                // File is directly in this directory
                val fileEntity = fileService.getFileAtPath(vfsPath)
                if (fileEntity != null) {
                    resourceFactory.toFileResource(fileEntity)?.let { children.add(it) }
                }
            }
        }
        
        // Create subdirectories
        directoryMap.forEach { (dirName, files) ->
            val dirPath = if (internalPath.endsWith("/")) {
                "$internalPath$dirName"
            } else {
                "$internalPath/$dirName"
            }
            
            // Create a virtual directory resource for this subdirectory
            val dirResource = DebridFolderSubdirectoryResource(
                dirPath,
                dirName,
                files,
                resourceFactory,
                fileService,
                syncedFileRepository
            )
            children.add(dirResource)
        }
        
        children
    }
}

/**
 * Virtual subdirectory resource for nested folders within mapped debrid folders.
 */
class DebridFolderSubdirectoryResource(
    private val fullPath: String,
    private val dirName: String,
    private val syncedFiles: List<DebridSyncedFileEntity>,
    private val resourceFactory: StreamableResourceFactory,
    private val fileService: DatabaseFileService,
    private val syncedFileRepository: DebridSyncedFileRepository
) : DigestResource, PropFindableResource, CollectionResource {
    private val logger = LoggerFactory.getLogger(DebridFolderSubdirectoryResource::class.java)

    override fun getUniqueId(): String {
        return "debrid_folder_subdir_${fullPath.hashCode()}"
    }

    override fun getName(): String {
        return dirName
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.now())
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.now())
    }

    override fun authenticate(user: String?, requestedPassword: String?): Any? {
        return null
    }

    override fun authenticate(digestRequest: DigestResponse?): Any? {
        return null
    }

    override fun child(childName: String?): Resource? {
        return getChildren().firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> = runBlocking {
        val children = mutableListOf<Resource>()
        val directoryMap = mutableMapOf<String, MutableList<DebridSyncedFileEntity>>()
        
        syncedFiles.forEach { syncedFile ->
            val vfsPath = syncedFile.vfsPath ?: return@forEach
            val relativePath = vfsPath.removePrefix(fullPath).removePrefix("/")
            
            if (relativePath.contains("/")) {
                // File is in a nested subdirectory
                val nestedDirName = relativePath.substringBeforeLast("/")
                directoryMap.getOrPut(nestedDirName) { mutableListOf() }.add(syncedFile)
            } else {
                // File is directly in this directory
                val fileEntity = fileService.getFileAtPath(vfsPath)
                if (fileEntity != null) {
                    resourceFactory.toFileResource(fileEntity)?.let { children.add(it) }
                }
            }
        }
        
        // Create nested subdirectories
        directoryMap.forEach { (nestedDirName, files) ->
            val nestedPath = if (fullPath.endsWith("/")) {
                "$fullPath$nestedDirName"
            } else {
                "$fullPath/$nestedDirName"
            }
            
            val nestedDirResource = DebridFolderSubdirectoryResource(
                nestedPath,
                nestedDirName,
                files,
                resourceFactory,
                fileService,
                syncedFileRepository
            )
            children.add(nestedDirResource)
        }
        
        children
    }
}
