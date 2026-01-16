package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.resource.CollectionResource
import io.milton.resource.DeletableResource
import io.milton.resource.MakeCollectionableResource
import io.milton.resource.MoveableResource
import io.milton.resource.PutableResource
import io.milton.resource.Resource
import io.ipfs.multibase.Base58
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.iptv.LiveChannelFileService
import io.skjaere.debridav.iptv.VirtualLiveFile
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
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
    private val liveChannelFileService: LiveChannelFileService? = null,
    private val iptvConfigurationService: IptvConfigurationService? = null
) : AbstractResource(fileService, directory), MakeCollectionableResource, MoveableResource, PutableResource,
    DeletableResource {
    
    private val logger = LoggerFactory.getLogger(DirectoryResource::class.java)

    var directoryChildren: MutableList<Resource>? = null

    override fun getUniqueId(): String {
        // For virtual directories (like /live folders), id may be null
        // Use path-based ID for virtual directories, database ID for real directories
        return directory.id?.toString() ?: "virtual_dir_${directory.path?.hashCode() ?: directory.name?.hashCode() ?: 0}"
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
                directory.lastModified ?: System.currentTimeMillis()
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
        return Date.from(Instant.ofEpochMilli(directory.lastModified ?: System.currentTimeMillis()))
    }

    override fun child(childName: String?): Resource? {
        return children.firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> {
        val directoryPath = directory.fileSystemPath()
        
        // Check if this is a /live path that should use runtime generation
        if (directoryPath != null && directoryPath.startsWith("/live") && liveChannelFileService != null) {
            return getLiveChildren(directoryPath)
        }
        
        // Normal directory listing from database
        val children = directoryChildren ?: getChildren(directory).toMutableList()
        
        // Filter out hidden folders (e.g., /live when IPTV Live is disabled)
        val filteredChildren = children.filter { resource ->
            val resourcePath = when {
                resource is DirectoryResource -> resource.directory.fileSystemPath()
                else -> null
            }
            resourcePath?.let { resourceFactory.isFolderVisible(it) } ?: true
        }.toMutableList()
        
        // Special handling for /live directory and provider folders: flat presentation and sorting
        val processedChildren = if (directoryPath != null && (directoryPath == "/live" || (directoryPath.startsWith("/live/") && directoryPath.split("/").size == 3))) {
            // /live directory or provider folder (e.g., /live/provider1)
            handleLiveDirectoryPresentation(filteredChildren, directoryPath).toMutableList()
        } else {
            filteredChildren
        }
        
        // If this is the root directory and STRM is enabled, add STRM folders
        if (directoryPath == "/" && resourceFactory.debridavConfigurationProperties.isStrmEnabled()) {
            val strmMappings = resourceFactory.debridavConfigurationProperties.parseStrmFolderMappings()
            
            strmMappings.forEach { (originalFolder, strmFolder) ->
                // Check if the original folder exists and is visible
                val originalPath = "/$originalFolder"
                if (!resourceFactory.isFolderVisible(originalPath)) {
                    return@forEach // Skip STRM folder creation if original folder is hidden
                }
                
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
                    processedChildren.add(strmDirResource)
                }
            }
        }
        
        return processedChildren
    }
    
    /**
     * Handles special presentation logic for /live directory and provider folders:
     * - Flat categories: Hide category folders, show channels directly under provider folders
     * - Flat providers: Hide provider folders, show their contents directly under /live
     * - Sorting: Alphabetical or provider order
     */
    private fun handleLiveDirectoryPresentation(children: List<Resource>, directoryPath: String): List<Resource> {
        // Get IPTV config from resourceFactory (may be null if IPTV not configured)
        val iptvConfig = resourceFactory.iptvConfig ?: return children
        
        val flatCategories = iptvConfig.liveFlatCategories
        val flatProviders = iptvConfig.liveFlatProviders
        
        // Check if we're in /live (root) or a provider folder (e.g., /live/provider1)
        val isLiveRoot = directoryPath == "/live"
        val isProviderFolder = directoryPath.startsWith("/live/") && directoryPath.split("/").size == 3
        
        // If we're in /live root and flatProviders is enabled, flatten provider folders
        if (isLiveRoot && flatProviders) {
            val flatChildren = mutableListOf<Resource>()
            
            children.forEach { resource ->
                if (resource is DirectoryResource) {
                    // This is a provider folder - get its contents
                    val providerChildren = resource.getChildren()
                    
                    if (flatCategories) {
                        // Both options enabled: collect all channels from all providers/categories
                        providerChildren.forEach { categoryResource ->
                            if (categoryResource is DirectoryResource) {
                                // This is a category folder - collect its files
                                val categoryChildren = categoryResource.getChildren()
                                flatChildren.addAll(categoryChildren)
                            } else {
                                // Direct file in provider folder (shouldn't happen normally, but handle it)
                                flatChildren.add(categoryResource)
                            }
                        }
                    } else {
                        // Only flatProviders: show category folders directly under /live
                        flatChildren.addAll(providerChildren)
                    }
                } else {
                    // Direct file in /live (shouldn't happen normally, but handle it)
                    flatChildren.add(resource)
                }
            }
            
            // Apply sorting
            return if (iptvConfig.liveSortAlphabetically) {
                flatChildren.sortedBy { it.name }
            } else {
                flatChildren // Provider order (default)
            }
        }
        // If we're in a provider folder and flatCategories is enabled, flatten category folders
        else if (isProviderFolder && flatCategories) {
            val flatChildren = mutableListOf<Resource>()
            
            children.forEach { resource ->
                if (resource is DirectoryResource) {
                    // This is a category folder - collect its files
                    val categoryChildren = resource.getChildren()
                    flatChildren.addAll(categoryChildren)
                } else {
                    // Direct file in provider folder (shouldn't happen normally, but handle it)
                    flatChildren.add(resource)
                }
            }
            
            // Apply sorting
            return if (iptvConfig.liveSortAlphabetically) {
                flatChildren.sortedBy { it.name }
            } else {
                flatChildren // Provider order (default)
            }
        }
        // Neither flat option enabled or not applicable - show normal structure
        else {
            // Apply sorting to folders and files
            return if (iptvConfig.liveSortAlphabetically) {
                children.sortedBy { it.name }
            } else {
                children // Provider order (default)
            }
        }
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
            liveChannelFileService,
            iptvConfigurationService
        )
    }


    private fun getChildren(directory: DbDirectory): List<Resource> = runBlocking {
        fileService.getChildren(directory)
            .toList()
            .map { async { toResource(it) } }
            .awaitAll()
            .filterNotNull()

    }

    /**
     * Gets children for /live paths using runtime generation
     */
    private fun getLiveChildren(directoryPath: String): List<Resource> {
        val pathParts = directoryPath.split("/").filter { it.isNotEmpty() }
        
        return when {
            directoryPath == "/live" -> {
                // Root /live directory - list providers
                val providers = liveChannelFileService!!.getLiveProviders()
                providers.map { providerName ->
                    val providerPath = "/live/$providerName"
                    DirectoryResource(
                        createVirtualDbDirectory(providerPath, providerName),
                        resourceFactory,
                        localContentsService,
                        fileService,
                        arrRequestDetector,
                        liveChannelFileService,
                        iptvConfigurationService
                    )
                }
            }
            pathParts.size == 2 && pathParts[0] == "live" -> {
                // /live/{provider} - list categories or channels (depending on flatCategories)
                val providerName = pathParts[1]
                val iptvConfig = resourceFactory.iptvConfig
                val flatCategories = iptvConfig?.liveFlatCategories == true
                
                if (flatCategories) {
                    // Show channels directly (no category folders)
                    val channels = liveChannelFileService!!.getLiveChannels(providerName)
                    channels.map { channel ->
                        val category = channel.category ?: return@map null
                        val sanitizedProviderName = sanitizeFileName(providerName)
                        val sanitizedCategoryName = sanitizeFileName(category.categoryName)
                        val sanitizedChannelName = sanitizeFileName(channel.title)
                        val filePath = "/live/$sanitizedProviderName/$sanitizedCategoryName/$sanitizedChannelName.ts"
                        val fileName = "$sanitizedChannelName.ts"
                        
                        val virtualFile = VirtualLiveFile(filePath, fileName) {
                            liveChannelFileService!!.createVirtualRemotelyCachedEntity(
                                channel,
                                filePath
                            )
                        }
                        resourceFactory.toFileResource(virtualFile.getEntity())
                    }.filterNotNull()
                } else {
                    // Show category folders
                    val categories = liveChannelFileService!!.getLiveCategories(providerName)
                    categories.map { categoryName ->
                        val categoryPath = "/live/$providerName/$categoryName"
                        DirectoryResource(
                            createVirtualDbDirectory(categoryPath, categoryName),
                            resourceFactory,
                            localContentsService,
                            fileService,
                            arrRequestDetector,
                            liveChannelFileService,
                            iptvConfigurationService
                        )
                    }
                }
            }
            pathParts.size == 3 && pathParts[0] == "live" -> {
                // /live/{provider}/{category} - list channels
                val providerName = pathParts[1]
                val categoryName = pathParts[2]
                val channels = liveChannelFileService!!.getLiveChannels(providerName, categoryName)
                
                channels.map { channel ->
                    val sanitizedProviderName = sanitizeFileName(providerName)
                    val sanitizedCategoryName = sanitizeFileName(categoryName)
                    val sanitizedChannelName = sanitizeFileName(channel.title)
                    val filePath = "/live/$sanitizedProviderName/$sanitizedCategoryName/$sanitizedChannelName.ts"
                    val fileName = "$sanitizedChannelName.ts"
                    
                    val virtualFile = VirtualLiveFile(filePath, fileName) {
                        liveChannelFileService!!.createVirtualRemotelyCachedEntity(
                            channel,
                            filePath
                        )
                    }
                    resourceFactory.toFileResource(virtualFile.getEntity())
                }.filterNotNull()
            }
            else -> {
                emptyList()
            }
        }
    }
    
    /**
     * Creates a virtual DbDirectory for runtime-generated live directories
     */
    private fun createVirtualDbDirectory(path: String, name: String): DbDirectory {
        val virtualDir = DbDirectory()
        virtualDir.name = name
        virtualDir.lastModified = Instant.now().toEpochMilli()
        // Convert path to ltree format using Base58 encoding
        virtualDir.path = if (path == "/") "ROOT" else {
            path.split("/").filter { it.isNotBlank() }
                .joinToString(separator = ".") { Base58.encode(it.encodeToByteArray()) }
                .let { "ROOT.$it" }
        }
        return virtualDir
    }
    
    /**
     * Sanitizes a file name by removing invalid file system characters
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[<>:\"/\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun toResource(file: DbEntity): Resource? {
        return if (file is DbDirectory)
            DirectoryResource(file, resourceFactory, localContentsService, fileService, arrRequestDetector, liveChannelFileService, iptvConfigurationService)
        else resourceFactory.toFileResource(file)
    }
}
