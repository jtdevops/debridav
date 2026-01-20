package io.skjaere.debridav.resource

import io.milton.common.Path
import io.milton.http.ResourceFactory
import io.milton.http.exceptions.BadRequestException
import io.milton.http.exceptions.NotAuthorizedException
import io.milton.resource.Resource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.configuration.HostnameDetectionService
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.debrid.folder.DebridFolderMappingRepository
import io.skjaere.debridav.debrid.folder.DebridSyncedFileRepository
import io.skjaere.debridav.fs.LocalEntity
import kotlinx.coroutines.runBlocking
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.stream.StreamingService
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.core.env.Environment
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class StreamableResourceFactory(
    private val fileService: DatabaseFileService,
    internal val debridService: DebridLinkService,
    private val streamingService: StreamingService,
    internal val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val localContentsService: LocalContentsService,
    private val arrRequestDetector: ArrRequestDetector,
    internal val serverProperties: ServerProperties,
    internal val environment: Environment,
    internal val hostnameDetectionService: HostnameDetectionService,
    private val debridFolderMappingRepository: DebridFolderMappingRepository?,
    private val debridSyncedFileRepository: DebridSyncedFileRepository?
) : ResourceFactory {
    private val logger = LoggerFactory.getLogger(StreamableResourceFactory::class.java)

    @Throws(NotAuthorizedException::class, BadRequestException::class)
    override fun getResource(host: String?, url: String): Resource? {
        val path: Path = Path.path(url)
        return find(path)
    }

    @Throws(NotAuthorizedException::class, BadRequestException::class)
    private fun find(path: Path): Resource? {
        val actualPath = if (path.isRoot) "/" else path.toPath()
        return getResourceAtPath(actualPath)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun getResourceAtPath(path: String): Resource? {
        return try {
            // Check if this is a STRM path
            if (debridavConfigurationProperties.isStrmEnabled() && debridavConfigurationProperties.isStrmPath(path)) {
                // Get the original path from the STRM path
                var originalPath = debridavConfigurationProperties.getOriginalPathFromStrm(path)
                    ?: return null
                
                // If the path ends with .strm, we need to find the original file
                // by removing .strm and trying to find a matching file
                if (path.endsWith(".strm")) {
                    // This is a STRM file request - need to find the original file
                    val strmFileName = path.substringAfterLast("/")
                    val strmDirPath = path.substringBeforeLast("/")
                    val originalDirPath = debridavConfigurationProperties.getOriginalPathFromStrm(strmDirPath)
                        ?: return null
                    
                    // Try to find the original file by checking files in the directory
                    val originalDir = fileService.getFileAtPath(originalDirPath) as? DbDirectory
                        ?: return null
                    
                    // Get all files in the directory and find one that would generate this STRM file
                    val children = kotlinx.coroutines.runBlocking { fileService.getChildren(originalDir) }
                    val originalFile = children.firstOrNull { file ->
                        val fileName = file.name ?: return@firstOrNull false
                        // Determine provider for provider-based filtering
                        val provider = determineProvider(file)
                        if (debridavConfigurationProperties.shouldCreateStrmFile(fileName, provider)) {
                            val strmFileNameForFile = debridavConfigurationProperties.getStrmFileName(fileName)
                            strmFileNameForFile == strmFileName
                        } else {
                            false
                        }
                    } ?: return null
                    
                    val fullOriginalPath = "$originalDirPath/${originalFile.name}"
                    return StrmFileResource(
                        originalFile,
                        fullOriginalPath,
                        fileService,
                        debridavConfigurationProperties,
                        serverProperties,
                        environment,
                        hostnameDetectionService
                    )
                } else {
                    // This could be a STRM directory request or a non-STRM file within a STRM directory
                    val originalEntity = fileService.getFileAtPath(originalPath) ?: return null
                    
                    if (originalEntity is DbDirectory) {
                        return StrmDirectoryResource(
                            originalEntity,
                            path,
                            this,
                            localContentsService,
                            fileService,
                            debridavConfigurationProperties
                        )
                    } else {
                        // This is a non-STRM file within a STRM directory path (e.g., subtitle.srt)
                        // Return the regular file resource so it appears in the STRM folder
                        return toFileResource(originalEntity)
                    }
                }
            }
            
            // Check if this is a mapped debrid folder path
            if (debridFolderMappingRepository != null && debridSyncedFileRepository != null) {
                val mappings = debridFolderMappingRepository.findByEnabled(true)
                val matchingMapping = mappings.firstOrNull { mapping ->
                    val internalPath = mapping.internalPath ?: ""
                    path == internalPath || path.startsWith("$internalPath/")
                }
                
                if (matchingMapping != null) {
                    // This is a mapped folder path
                    val internalPath = matchingMapping.internalPath ?: ""
                    if (path == internalPath) {
                        // Root of mapped folder - return directory resource
                        return DebridFolderDirectoryResource(
                            matchingMapping,
                            this,
                            fileService,
                            debridSyncedFileRepository
                        )
                    } else {
                        // Subdirectory or file within mapped folder
                        val relativePath = path.removePrefix(internalPath).removePrefix("/")
                        val syncedFiles = kotlinx.coroutines.runBlocking {
                            debridSyncedFileRepository.findByFolderMappingAndIsDeleted(matchingMapping, false)
                        }
                        
                        // Check if it's a file by constructing full path from vfsPath + vfsFileName
                        val matchingFile = syncedFiles.firstOrNull { syncedFile ->
                            val vfsPath = syncedFile.vfsPath ?: return@firstOrNull false
                            val vfsFileName = syncedFile.vfsFileName ?: return@firstOrNull false
                            val fullFilePath = if (vfsPath.endsWith("/")) {
                                "$vfsPath$vfsFileName"
                            } else {
                                "$vfsPath/$vfsFileName"
                            }
                            fullFilePath == path
                        }
                        
                        if (matchingFile != null) {
                            // Return the actual VFS file
                            val fileEntity = fileService.getFileAtPath(path)
                            return fileEntity?.let { toFileResource(it) }
                        } else {
                            // Check if it's a subdirectory by looking for files whose full path starts with this path
                            val subdirFiles = syncedFiles.filter { syncedFile ->
                                val vfsPath = syncedFile.vfsPath ?: return@filter false
                                val vfsFileName = syncedFile.vfsFileName ?: return@filter false
                                val fullFilePath = if (vfsPath.endsWith("/")) {
                                    "$vfsPath$vfsFileName"
                                } else {
                                    "$vfsPath/$vfsFileName"
                                }
                                fullFilePath.startsWith("$path/")
                            }
                            
                            if (subdirFiles.isNotEmpty()) {
                                val dirName = path.substringAfterLast("/")
                                return DebridFolderSubdirectoryResource(
                                    path,
                                    dirName,
                                    subdirFiles,
                                    this,
                                    fileService,
                                    debridSyncedFileRepository
                                )
                            }
                        }
                    }
                }
            }
            
            // Not a STRM path or mapped folder path, handle normally
            fileService.getFileAtPath(path)
                ?.let {
                    if (it is DbDirectory) {
                        toDirectoryResource(it)
                    } else {
                        toFileResource(it)
                    }
                }

        } catch (e: Exception) {
            logger.error("could not load item at path: $path", e)
            null
        }
    }

    fun toDirectoryResource(dbItem: DbEntity): DirectoryResource {
        if (dbItem !is DbDirectory) {
            error("Not a directory")
        }
        return DirectoryResource(
            dbItem, 
            this, 
            localContentsService, 
            fileService, 
            arrRequestDetector,
            debridFolderMappingRepository,
            debridSyncedFileRepository
        )
    }

    fun toFileResource(dbItem: DbEntity): Resource? {
        return when (dbItem) {
            is DbDirectory -> error("Provided file is a directory")
            is RemotelyCachedEntity -> DebridFileResource(
                file = dbItem,
                fileService = fileService,
                streamingService = streamingService,
                debridService = debridService,
                debridavConfigurationProperties = debridavConfigurationProperties,
                arrRequestDetector = arrRequestDetector
            )

            is LocalEntity -> FileResource(dbItem, fileService, localContentsService)
            else -> error("Unknown dbItemType type: ${dbItem::class.simpleName}")
        }
    }

    /**
     * Determines the provider for a DbEntity.
     * @param entity The entity to check
     * @return The provider, or null if unable to determine or not a remotely cached entity
     */
    private fun determineProvider(entity: DbEntity): DebridProvider? {
        if (entity !is RemotelyCachedEntity) {
            return null // Local files don't have providers
        }
        
        // Reload the entity to ensure contents are loaded
        val reloadedFile = fileService.reloadRemotelyCachedEntity(entity) ?: return null
        val contents = reloadedFile.contents ?: return null
        
        // Check if it's IPTV content
        if (contents is DebridIptvContent) {
            return DebridProvider.IPTV
        }
        
        // Try to get provider from debridLinks
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        return cachedFile?.provider
    }
}
