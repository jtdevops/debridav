package io.skjaere.debridav.fs

import io.ipfs.multibase.Base58
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.torrent.TorrentRepository
import jakarta.persistence.EntityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.lang.StringUtils
import org.hibernate.engine.jdbc.BlobProxy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Instant

private const val ROOT_NODE = "ROOT"
private const val MEGABYTE = 1024 * 1024

@Service
class DatabaseFileService(
    private val debridFileRepository: DebridFileContentsRepository,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val torrentRepository: TorrentRepository,
    private val usenetRepository: UsenetRepository,
    private val fileChunkCachingService: FileChunkCachingService,
    private val entityManager: EntityManager,
) {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val lock = Mutex()
    private val defaultDirectories = listOf("/", "/downloads", "/tv", "/movies", "/tv_strm", "/movies_strm")

    init {
        defaultDirectories.forEach {
            if (debridFileRepository.getDirectoryByPath(it.pathToLtree()) == null) {
                createDirectory(it)
            }
        }
    }

    @Transactional
    fun createDebridFile(
        path: String, hash: String, debridFileContents: DebridFileContents
    ): RemotelyCachedEntity = runBlocking {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val name = path.substringAfterLast("/")

        // Overwrite file if it exists
        debridFileRepository.findByDirectoryAndName(directory, name)?.let {
            it as? RemotelyCachedEntity ?: error("type ${it.javaClass.simpleName} exists at path $path")
            when (it.contents) {
                is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(it)
                is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(it)
            }
            fileChunkCachingService.deleteChunksForFile(it)
            debridFileRepository.deleteDbEntityByHash(it.hash!!) // TODO: why doesn't debridFileRepository.delete() work?
        }
        val fileEntity = RemotelyCachedEntity()
        fileEntity.name = path.substringAfterLast("/")
        fileEntity.lastModified = Instant.now().toEpochMilli()
        fileEntity.size = debridFileContents.size
        fileEntity.mimeType = debridFileContents.mimeType
        fileEntity.directory = directory
        fileEntity.contents = debridFileContents
        fileEntity.hash = hash

        debridFileRepository.getByHash("asd")
        logger.debug("Creating ${directory.path}/${fileEntity.name}")
        fileEntity
    }

    @Transactional
    fun saveDbEntity(dbItem: DbEntity) {
        when (dbItem) {
            is RemotelyCachedEntity -> {
                debridFileRepository.save(dbItem)
            }

            else -> error("Cant write DebridFileContents to ${dbItem.javaClass.simpleName}")
        }
    }

    @Transactional
    fun writeDebridFileContentsToFile(dbItem: DbEntity, debridFileContents: DebridFileContents) {
        when (dbItem) {
            is RemotelyCachedEntity -> {
                dbItem.contents = debridFileContents
                debridFileRepository.save(dbItem)
            }

            else -> error("Cant write DebridFileContents to ${dbItem.javaClass.simpleName}")
        }
    }

    @Transactional
    fun writeContentsToLocalFile(dbItem: LocalEntity, contents: InputStream, size: Long) {
        if (size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
            && debridavConfigurationProperties.localEntityMaxSizeMb != 0
        ) {
            throw IllegalArgumentException(
                "Size: ${size / MEGABYTE} MB is greater than set maximum: " +
                        "${debridavConfigurationProperties.localEntityMaxSizeMb}"
            )
        }
        dbItem.blob!!.localContents = BlobProxy.generateProxy(contents, size)
        debridFileRepository.save(dbItem)
    }

    @Transactional
    fun moveResource(dbItem: DbEntity, destination: String, name: String) {
        when (dbItem) {
            is RemotelyCachedEntity -> moveFile(destination, dbItem, name)
            is LocalEntity -> moveFile(destination, dbItem, name)
            is DbDirectory -> {
                dbItem.name = name
                debridFileRepository.save(dbItem)
                if (directoriesHaveSameParent(dbItem.fileSystemPath()!!, destination)) {
                    debridFileRepository.renameDirectory(
                        dbItem.path!!, Base58.encode(name.encodeToByteArray()), name
                    )
                } else {
                    debridFileRepository.moveDirectory(
                        dbItem, destination.pathToLtree()

                    )
                }
            }
        }
    }

    @Transactional
    fun moveFile(
        destination: String, dbFile: DbEntity, name: String
    ) {
        if (dbFile is DbDirectory) error("entity is directory")
        val destinationDirectory = getOrCreateDirectory(destination)
        dbFile.directory = destinationDirectory
        dbFile.name = name
        debridFileRepository.save(dbFile)
    }

    @Transactional
    fun deleteFile(file: DbEntity) {
        when (file) {
            is RemotelyCachedEntity -> deleteRemotelyCachedEntity(file)
            is DbDirectory -> debridFileRepository.delete(file)
            is LocalEntity -> {
                deleteLargeObjectForLocalEntity(file)
                debridFileRepository.delete(file)
            }
        }
    }

    private fun deleteLargeObjectForLocalEntity(file: LocalEntity) {
        entityManager.createNativeQuery(
            """
            SELECT lo_unlink(b.loid) from (
                select distinct local_contents as loid from blob b
                where b.id=${file.blob!!.id}
            ) as b
           
        """.trimMargin()
        ).resultList
    }

    private fun deleteRemotelyCachedEntity(file: RemotelyCachedEntity) {
        when (file.contents) {
            is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(file)
            is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(file)
        }
        fileChunkCachingService.deleteChunksForFile(file)
        debridFileRepository.delete(file)
    }

    @Transactional
    fun handleNoLongerCachedFile(debridFile: RemotelyCachedEntity) {
        when (debridFile.contents) {
            is DebridCachedTorrentContent -> {
                torrentRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    if (it is RemotelyCachedEntity) {
                        fileChunkCachingService.deleteChunksForFile(it)
                    }
                    debridFileRepository.delete(it)
                }
            }

            is DebridCachedUsenetReleaseContent -> {
                usenetRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    if (it is RemotelyCachedEntity) {
                        fileChunkCachingService.deleteChunksForFile(it)
                    }
                    debridFileRepository.delete(it)
                }
            }
        }

    }

    @Transactional
    fun createLocalFile(path: String, inputStream: InputStream, size: Long?): LocalEntity {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val localFile = LocalEntity()

        if (size == null) {
            val bytes = inputStream.readAllBytes()
            if (bytes.size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
            ) {
                throw IllegalArgumentException(
                    "Size: ${bytes.size.times(MEGABYTE)} MB is greater than set maximum: " +
                            "${debridavConfigurationProperties.localEntityMaxSizeMb}"
                )
            }
            val streamSize = bytes.size.toLong()
            localFile.size = streamSize
            localFile.blob = Blob(BlobProxy.generateProxy(bytes.inputStream(), streamSize), streamSize)
        } else {
            if (size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
            ) {
                throw IllegalArgumentException(
                    "Size: ${size / MEGABYTE} MB is greater than set maximum: " +
                            "${debridavConfigurationProperties.localEntityMaxSizeMb}"
                )
            }
            localFile.size = size
            localFile.blob = Blob(BlobProxy.generateProxy(inputStream, size), size)
        }
        localFile.name = path.substringAfterLast("/")
        localFile.directory = directory
        localFile.lastModified = System.currentTimeMillis()

        return debridFileRepository.save(localFile)
    }


    fun getFileAtPath(path: String): DbEntity? {
        // Handle virtual strm paths (only if feature is enabled)
        if (debridavConfigurationProperties.enableStrmFolders &&
            (path.startsWith("/tv_strm") || path.startsWith("/movies_strm"))) {
            return handleVirtualStrmPath(path)
        }

        // Regular file/directory lookup
        val directory = debridFileRepository.getDirectoryByPath(path.pathToLtree()) ?:
                       debridFileRepository.getDirectoryByPath(path.getDirectoryFromPath().pathToLtree())

        return directory?.let { dir ->
            debridFileRepository.findByDirectoryAndName(dir, path.substringAfterLast("/"))
        }
    }
    
    private fun handleVirtualStrmPath(path: String): DbEntity? {
        // Double-check that STRM folders are enabled (defensive programming)
        if (!debridavConfigurationProperties.enableStrmFolders) {
            return null
        }

        // Convert strm path to original path
        val originalPath = path.replace("/tv_strm", "/tv").replace("/movies_strm", "/movies")

        return when {
            // If this is a directory request, return a virtual strm directory
            path.endsWith("/") || !path.contains(".") -> VirtualStrmDirectory(originalPath)

            // If this is a file request, check if the original file exists and is a video
            else -> {
                val originalFile = debridFileRepository.getDirectoryByPath(originalPath.getDirectoryFromPath().pathToLtree())
                    ?.let { directory ->
                        debridFileRepository.findByDirectoryAndName(directory, originalPath.substringAfterLast("/"))
                    }

                if (originalFile != null && isVideoFile(originalFile.name)) {
                    VirtualStrmFile(originalFile, originalPath)
                } else {
                    null
                }
            }
        }
    }
    
    private fun isVideoFile(filename: String?): Boolean {
        if (filename == null) return false
        val videoExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg")
        return videoExtensions.any { filename.lowercase().endsWith(it) }
    }

    @Transactional
    fun createDirectory(path: String): DbDirectory {
        return getOrCreateDirectory(if (path != "/") StringUtils.removeEnd(path, "/") else path)
    }

    @Transactional
    suspend fun getChildren(directory: DbDirectory): List<DbEntity> = withContext(Dispatchers.IO) {
        // Handle virtual strm directories
        if (directory is VirtualStrmDirectory) {
            return@withContext getVirtualStrmChildren(directory)
        }
        
        // Handle root directory - add virtual strm directories (only if feature is enabled)
        if (directory.path == "ROOT" || directory.fileSystemPath() == "/") {
            val regularChildren1 = debridFileRepository.getChildrenByDirectory(directory)
            val regularChildren2 = debridFileRepository.getByDirectory(directory)
            val regularChildren = regularChildren1 + regularChildren2

            val virtualStrmDirs = if (debridavConfigurationProperties.enableStrmFolders) {
                listOf(
                    VirtualStrmDirectory("/tv"),
                    VirtualStrmDirectory("/movies")
                )
            } else {
                emptyList()
            }

            return@withContext regularChildren + virtualStrmDirs
        }
        
        listOf(
            async { debridFileRepository.getChildrenByDirectory(directory) },
            async { debridFileRepository.getByDirectory(directory) }).awaitAll().flatten()
    }
    
    private suspend fun getVirtualStrmChildren(virtualDir: VirtualStrmDirectory): List<DbEntity> {
        // Double-check that STRM folders are enabled (defensive programming)
        if (!debridavConfigurationProperties.enableStrmFolders) {
            return emptyList()
        }

        val originalPath = virtualDir.originalPath ?: return emptyList()

        // Get the original directory
        val originalDirectory = debridFileRepository.getDirectoryByPath(originalPath.pathToLtree())
            ?: return emptyList()

        // Get children from original directory
        val children1 = debridFileRepository.getChildrenByDirectory(originalDirectory)
        val children2 = debridFileRepository.getByDirectory(originalDirectory)
        val originalChildren = children1 + children2

        // Filter and convert to virtual strm entities
        return originalChildren.mapNotNull { child ->
            when {
                child is DbDirectory -> {
                    // Create virtual strm directory for subdirectories
                    val childPath = "${originalPath}/${child.name}"
                    VirtualStrmDirectory(childPath)
                }
                isVideoFile(child.name) -> {
                    // Only create virtual strm files for video files
                    val childPath = "${originalPath}/${child.name}"
                    VirtualStrmFile(child, childPath)
                }
                else -> null
            }
        }
    }

    @Transactional
    fun getOrCreateDirectory(path: String): DbDirectory = runBlocking {
        lock.withLock {
            getDirectoryTreePaths(path).map {
                val directoryEntity = debridFileRepository.getDirectoryByPath(it.pathToLtree())
                if (directoryEntity == null) {
                    val newDirectoryEntity = DbDirectory()
                    newDirectoryEntity.path = it.pathToLtree()
                    newDirectoryEntity.name = if (it != "/") it.substringAfterLast("/") else null
                    newDirectoryEntity.lastModified = Instant.now().toEpochMilli()
                    debridFileRepository.save(newDirectoryEntity)
                } else directoryEntity
            }.last()
        }
    }


    private fun getDirectoryTreePaths(path: String): List<String> {
        val tree = path.split("/").toMutableList()

        return tree.fold(mutableListOf()) { acc, part ->
            if (acc.isEmpty()) {
                acc.add("/")
            } else if (acc.last() == "/") {
                acc.add("/$part")
            } else {
                acc.add("${acc.last()}/$part")
            }
            acc
        }
    }

    private fun String.pathToLtree(): String {
        return if (this == "/") ROOT_NODE else {
            this.split("/").filter { it.isNotBlank() }
                .joinToString(separator = ".") { Base58.encode(it.encodeToByteArray()) }.let { "$ROOT_NODE.$it" }
        }
    }

    private fun String.getDirectoryFromPath(): String {
        return if (this == "/") {
            "/"
        } else this.substringBeforeLast("/").let {
            if (it.isBlank()) return "/"
            it
        }
    }

    private fun directoriesHaveSameParent(first: String, second: String): Boolean {
        return first.getDirectoryFromPath() == second
    }
}
