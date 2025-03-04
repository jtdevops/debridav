package io.skjaere.debridav.fs

import io.ipfs.multibase.Base58
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.torrent.TorrentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.lang.StringUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Instant

private const val ROOT_NODE = "ROOT"

@Service
class DatabaseFileService(
    private val debridFileRepository: DebridFileContentsRepository,
    private val torrentRepository: TorrentRepository,
    private val usenetRepository: UsenetRepository
) {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val lock = Mutex()
    private val defaultDirectories = listOf("/", "/downloads", "/tv", "/movies")

    init {
        defaultDirectories.forEach {
            if (debridFileRepository.getDirectoryByPath(it.pathToLtree()) == null) {
                createDirectory(it)
            }
        }
    }

    fun createDebridFile(
        path: String,
        hash: String,
        debridFileContents: DebridFileContents
    ): RemotelyCachedEntity = runBlocking {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val name = path.substringAfterLast("/")

        // Overwrite file if it exists
        val fileEntity = debridFileRepository.findByDirectoryAndName(directory, name)?.let {
            it as? RemotelyCachedEntity ?: error("type ${it.javaClass.simpleName} exists at path $path")
            debridFileRepository.unlinkFileFromTorrents(it)
            debridFileRepository.unlinkFileFromUsenet(it)
            it
        } ?: RemotelyCachedEntity()

        fileEntity.name = path.substringAfterLast("/")
        fileEntity.lastModified = Instant.now().toEpochMilli()
        fileEntity.size = debridFileContents.size
        fileEntity.mimeType = debridFileContents.mimeType
        fileEntity.directory = directory
        fileEntity.contents = debridFileContents
        fileEntity.hash = hash

        logger.debug("Creating ${directory.path}/${fileEntity.name}")
        fileEntity
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
    fun writeContentsToLocalFile(dbItem: LocalEntity, contents: ByteArray) {
        dbItem.blob!!.localContents = contents
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
                        dbItem.path!!,
                        Base58.encode(name.encodeToByteArray()),
                        name
                    )
                } else {
                    debridFileRepository.moveDirectory(
                        dbItem,
                        destination.pathToLtree()

                    )
                }
            }
        }
    }

    private fun moveFile(
        destination: String,
        dbFile: DbEntity,
        name: String
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
            is DbDirectory -> debridFileRepository.delete(file)//deleteDirectory(file)
            is LocalEntity -> debridFileRepository.delete(file)
        }
    }

    private fun deleteRemotelyCachedEntity(file: RemotelyCachedEntity) {
        when (file.contents) {
            is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(file)
            is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(file)
        }
        debridFileRepository.delete(file)
    }

    @Transactional
    fun handleNoLongerCachedFile(debridFile: RemotelyCachedEntity) {
        when (debridFile.contents) {
            is DebridCachedTorrentContent -> {
                torrentRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    debridFileRepository.delete(it)
                }
            }

            is DebridCachedUsenetReleaseContent -> {
                usenetRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    debridFileRepository.delete(it)
                }
            }
        }

    }

    @Transactional
    fun createLocalFile(path: String, inputStream: InputStream): LocalEntity {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val localFile = LocalEntity()
        val bytes = inputStream.readBytes()
        val blob = Blob(bytes)

        localFile.name = path.substringAfterLast("/")
        localFile.directory = directory
        localFile.lastModified = System.currentTimeMillis()
        localFile.size = bytes.size.toLong()
        localFile.blob = blob

        return debridFileRepository.save(localFile)
    }


    fun getFileAtPath(path: String): DbEntity? {
        return debridFileRepository.getDirectoryByPath(path.pathToLtree())
            ?: debridFileRepository.getDirectoryByPath(path.getDirectoryFromPath().pathToLtree())
                ?.let { directory ->
                    return debridFileRepository.findByDirectoryAndName(directory, path.substringAfterLast("/"))
                }

    }

    @Transactional
    fun createDirectory(path: String): DbDirectory {
        return getOrCreateDirectory(if (path != "/") StringUtils.removeEnd(path, "/") else path)
    }

    @Transactional
    suspend fun getChildren(directory: DbDirectory): List<DbEntity> = withContext(Dispatchers.IO) {
        listOf(
            async { debridFileRepository.getChildrenByDirectory(directory) },
            async { debridFileRepository.getByDirectory(directory) }
        ).awaitAll()
            .flatten()
    }

    @Transactional
    fun getOrCreateDirectory(path: String): DbDirectory = runBlocking {
        lock.withLock {
            getDirectoryTreePaths(path)
                .map {
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
            this.split("/")
                .filter { it.isNotBlank() }
                .joinToString(separator = ".") { Base58.encode(it.encodeToByteArray()) }
                .let { "$ROOT_NODE.$it" }
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
