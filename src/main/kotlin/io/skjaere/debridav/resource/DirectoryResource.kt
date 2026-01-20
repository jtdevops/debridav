package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.resource.CollectionResource
import io.milton.resource.DeletableResource
import io.milton.resource.MakeCollectionableResource
import io.milton.resource.MoveableResource
import io.milton.resource.PutableResource
import io.milton.resource.Resource
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.webdav.folder.WebDavFolderMappingRepository
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.LocalContentsService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import jakarta.servlet.http.HttpServletRequest
import java.io.InputStream
import java.net.InetAddress
import java.time.Instant
import java.util.*

class DirectoryResource(
    val directory: DbDirectory,
    //private val directoryChildren: List<Resource>,
    val resourceFactory: StreamableResourceFactory,
    private val localContentsService: LocalContentsService,
    fileService: DatabaseFileService,
    private val arrRequestDetector: ArrRequestDetector,
    private val webDavFolderMappingRepository: WebDavFolderMappingRepository?,
    private val webDavSyncedFileRepository: io.skjaere.debridav.webdav.folder.WebDavSyncedFileRepository?
) : AbstractResource(fileService, directory), MakeCollectionableResource, MoveableResource, PutableResource,
    DeletableResource {
    
    private val logger = LoggerFactory.getLogger(DirectoryResource::class.java)

    var directoryChildren: MutableList<Resource>? = null

    override fun getUniqueId(): String {
        return directory.id!!.toString()
    }

    override fun getName(): String {
        return directory.name ?: "/"
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
                directory.lastModified!!
            )
        )
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(directory)
    }

    /*override fun moveTo(rDest: CollectionResource, name: String) {
        fileService.moveResource(directory, (rDest as DirectoryResource).directory.path!!, name)
    }*/

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli(directory.lastModified!!))
    }

    override fun child(childName: String?): Resource? {
        return children.firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> {
        val children = directoryChildren ?: getChildren(directory).toMutableList()
        
        // If this is the root directory and STRM is enabled, add STRM folders
        val directoryPath = directory.fileSystemPath()
        if (directoryPath == "/" && resourceFactory.debridavConfigurationProperties.isStrmEnabled()) {
            val strmMappings = resourceFactory.debridavConfigurationProperties.parseStrmFolderMappings()
            
            strmMappings.forEach { (originalFolder, strmFolder) ->
                // Check if the original folder exists
                val originalPath = "/$originalFolder"
                val originalDir = fileService.getFileAtPath(originalPath)
                
                if (originalDir is DbDirectory) {
                    // Create a STRM directory resource for this folder
                    val strmPath = "/$strmFolder"
                    val strmDirResource = StrmDirectoryResource(
                        originalDir,
                        strmPath,
                        resourceFactory,
                        localContentsService,
                        fileService,
                        resourceFactory.debridavConfigurationProperties
                    )
                    children.add(strmDirResource)
                }
            }
        }
        
        // If this is the root directory and WebDAV folder mapping is enabled, add mapped folders
        if (directoryPath == "/" && webDavFolderMappingRepository != null && webDavSyncedFileRepository != null) {
            val mappings = webDavFolderMappingRepository.findByEnabled(true)
            
            mappings.forEach { mapping ->
                val internalPath = mapping.internalPath ?: return@forEach
                // Only add mappings that are at the root level (no subdirectories)
                val pathParts = internalPath.removePrefix("/").split("/")
                if (pathParts.size == 1) {
                    // This is a root-level mapping
                    val folderDirResource = WebDavDirectoryResource(
                        mapping,
                        resourceFactory,
                        fileService,
                        webDavSyncedFileRepository
                    )
                    children.add(folderDirResource)
                }
            }
        }
        
        return children
    }

    override fun createNew(newName: String, inputStream: InputStream, length: Long?, contentType: String?): Resource {
        // Extract HTTP request information for logging
        var sourceIpAddress: String? = null
        var sourceHostname: String? = null
        var sourceInfo: String? = null
        
        try {
            val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            val httpRequest = requestAttributes?.request
            
            if (httpRequest != null) {
                sourceIpAddress = httpRequest.remoteAddr
                    ?: httpRequest.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                    ?: httpRequest.getHeader("X-Real-IP")
                
                if (sourceIpAddress != null && sourceIpAddress != "unknown") {
                    try {
                        sourceHostname = InetAddress.getByName(sourceIpAddress).hostName
                    } catch (e: Exception) {
                        // If hostname resolution fails, leave it null
                    }
                }
                
                sourceInfo = if (sourceHostname != null && sourceHostname != sourceIpAddress) {
                    "$sourceIpAddress/$sourceHostname"
                } else {
                    sourceIpAddress
                }
            }
        } catch (e: Exception) {
            logger.debug("Could not extract request information for file write logging", e)
        }
        
        // Log all PUT attempts at WARN level for auditing purposes
        logger.warn(
            "VFS_FILE_WRITE_ATTEMPT: filename='{}', source_ip={}, source_hostname={}, source_info={}, size={}",
            newName,
            sourceIpAddress ?: "unknown",
            sourceHostname ?: "unknown",
            sourceInfo ?: "unknown",
            length ?: "unknown"
        )
        
        val createdFile = fileService.createLocalFile(
            "${directory.fileSystemPath()}/$newName",
            inputStream,
            length
        )
        directoryChildren?.add(toResource(createdFile)!!)
        return FileResource(createdFile, fileService, localContentsService)
    }

    override fun createCollection(newName: String?): CollectionResource {
        return DirectoryResource(
            fileService.createDirectory("${directory.fileSystemPath()}/$newName/"),
            resourceFactory,
            localContentsService,
            fileService,
            arrRequestDetector,
            webDavFolderMappingRepository,
            webDavSyncedFileRepository
        )
    }


    private fun getChildren(directory: DbDirectory): List<Resource> = runBlocking {
        fileService.getChildren(directory)
            .toList()
            .map { async { toResource(it) } }
            .awaitAll()
            .filterNotNull()

    }

    private fun toResource(file: DbEntity): Resource? {
        return if (file is DbDirectory)
            DirectoryResource(file, resourceFactory, localContentsService, fileService, arrRequestDetector, webDavFolderMappingRepository, webDavSyncedFileRepository)
        else resourceFactory.toFileResource(file)
    }
}
