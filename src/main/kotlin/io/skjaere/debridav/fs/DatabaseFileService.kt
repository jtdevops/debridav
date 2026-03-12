package io.skjaere.debridav.fs

import io.ipfs.multibase.Base58
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.IptvFile
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.TransactionDefinition
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
    private val transactionManager: PlatformTransactionManager,
    private val httpClient: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val lock = Mutex()
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val transactionTemplateRequiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val defaultDirectories = listOf("/", "/downloads", "/tv", "/movies")

    init {
        defaultDirectories.forEach {
            if (debridFileRepository.getDirectoryByPath(it.pathToLtree()) == null) {
                createDirectory(it)
            }
        }
    }

    @Transactional
    fun createIptvFile(
        path: String, hash: String, debridIptvContent: io.skjaere.debridav.fs.DebridIptvContent
    ): RemotelyCachedEntity = runBlocking {
        createDebridFile(path, hash, debridIptvContent)
    }

    @Transactional
    fun createDebridFile(
        path: String, hash: String, debridFileContents: DebridFileContents
    ): RemotelyCachedEntity = createDebridFile(path, hash, debridFileContents, skipLocalEntityConversion = false)

    /**
     * Creates a debrid file entry in the database.
     * For whitelisted files (e.g. subtitles), the HTTP download runs outside any transaction
     * so the DB connection is not held during the download (avoids pool exhaustion when
     * Radarr adds many videos with subtitles in parallel).
     *
     * @param skipLocalEntityConversion If true, skips the LocalEntity conversion even if the file
     *        extension is whitelisted. Useful for folder mapping sync where we want to keep files
     *        as RemotelyCachedEntity pointing to the provider's WebDAV URL.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun createDebridFile(
        path: String, hash: String, debridFileContents: DebridFileContents, skipLocalEntityConversion: Boolean
    ): RemotelyCachedEntity = runBlocking {
        val name = path.substringAfterLast("/")
        val shouldStoreAsLocal = !skipLocalEntityConversion &&
            debridavConfigurationProperties.shouldAlwaysStoreAsLocalEntity(name)
        val hasDownloadableLink = shouldStoreAsLocal && when {
            debridFileContents is DebridIptvContent -> {
                val iptvFile = debridFileContents.debridLinks.firstOrNull { it is IptvFile } as? IptvFile
                iptvFile?.link != null
            }
            else -> {
                val cachedFile = debridFileContents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
                cachedFile?.link != null
            }
        }

        if (shouldStoreAsLocal && hasDownloadableLink) {
            // Setup in short tx, then download (no tx), then save (short tx).
            // This avoids holding a DB connection during the HTTP download.
            val directory = transactionTemplateRequiresNew.execute<DbDirectory?> {
                val dir = getOrCreateDirectory(path.substringBeforeLast("/"))
                debridFileRepository.findByDirectoryAndName(dir, name)?.let { existingFile ->
                    when (existingFile) {
                        is RemotelyCachedEntity -> {
                            when (existingFile.contents) {
                                is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(existingFile)
                                is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(existingFile)
                                is io.skjaere.debridav.fs.DebridIptvContent ->
                                    debridFileRepository.unlinkFileFromTorrents(existingFile)
                            }
                            fileChunkCachingService.deleteChunksForFile(existingFile)
                            debridFileRepository.deleteDbEntityByHash(existingFile.hash!!)
                        }
                        is LocalEntity -> {
                            deleteLargeObjectForLocalEntity(existingFile)
                            debridFileRepository.delete(existingFile)
                        }
                    }
                }
                dir
            } ?: return@runBlocking transactionTemplate.execute { createRemotelyCachedEntity(path, hash, debridFileContents) }!!

            try {
                logger.debug("File {} has whitelisted extension, downloading and storing as LocalEntity", name)
                downloadAndStoreAsLocalEntity(path, debridFileContents)
                val fileEntity = RemotelyCachedEntity()
                fileEntity.name = name
                fileEntity.lastModified = Instant.now().toEpochMilli()
                fileEntity.size = debridFileContents.size
                fileEntity.mimeType = debridFileContents.mimeType
                fileEntity.directory = directory
                fileEntity.contents = debridFileContents
                fileEntity.hash = hash
                return@runBlocking fileEntity
            } catch (e: Exception) {
                logger.warn("Failed to download whitelisted file {}, falling back to RemotelyCachedEntity: {}", name, e.message)
            }
        } else if (shouldStoreAsLocal) {
            logger.debug("File {} has whitelisted extension but no download link available yet, creating RemotelyCachedEntity", name)
        }

        return@runBlocking transactionTemplate.execute { createRemotelyCachedEntity(path, hash, debridFileContents) }!!
    }

    private fun createRemotelyCachedEntity(path: String, hash: String, debridFileContents: DebridFileContents): RemotelyCachedEntity {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val name = path.substringAfterLast("/")
        debridFileRepository.findByDirectoryAndName(directory, name)?.let {
            it as? RemotelyCachedEntity ?: error("type ${it.javaClass.simpleName} exists at path $path")
            when (it.contents) {
                is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(it)
                is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(it)
                is io.skjaere.debridav.fs.DebridIptvContent -> debridFileRepository.unlinkFileFromTorrents(it)
            }
            fileChunkCachingService.deleteChunksForFile(it)
            debridFileRepository.deleteDbEntityByHash(it.hash!!)
        }
        val fileEntity = RemotelyCachedEntity()
        fileEntity.name = path.substringAfterLast("/")
        fileEntity.lastModified = Instant.now().toEpochMilli()
        fileEntity.size = debridFileContents.size
        fileEntity.mimeType = debridFileContents.mimeType
        fileEntity.directory = directory
        fileEntity.contents = debridFileContents
        fileEntity.hash = hash
        logger.debug("Creating ${directory.path}/${fileEntity.name}")
        return debridFileRepository.save(fileEntity)
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
        val fileName = dbItem.name ?: ""
        val shouldBypassSizeCheck = debridavConfigurationProperties.shouldAlwaysStoreAsLocalEntity(fileName)
        
        if (!shouldBypassSizeCheck && size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
            && debridavConfigurationProperties.localEntityMaxSizeMb != 0
        ) {
            throw IllegalArgumentException(
                "Size: ${size / MEGABYTE} MB is greater than set maximum: " +
                        "${debridavConfigurationProperties.localEntityMaxSizeMb}"
            )
        }
        // Merge the entity to attach it to the current session and initialize lazy properties
        val mergedEntity = entityManager.merge(dbItem) as LocalEntity
        // Ensure blob is initialized by accessing it
        val blob = mergedEntity.blob
        if (blob == null) {
            // Create a new blob if it doesn't exist
            mergedEntity.blob = Blob(BlobProxy.generateProxy(contents, size), size)
        } else {
            blob.localContents = BlobProxy.generateProxy(contents, size)
        }
        debridFileRepository.save(mergedEntity)
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
            is DbDirectory -> deleteDirectory(file)
            is LocalEntity -> {
                deleteLargeObjectForLocalEntity(file)
                debridFileRepository.delete(file)
            }
        }
    }

    /**
     * Deletes a directory and all its path-descendants (subdirectories and files).
     * This ensures ltree hierarchy integrity: we never delete a parent while leaving
     * descendants with paths like ROOT.A.B.C when ROOT.A.B no longer exists.
     *
     * Order: 1) Delete files in tree, 2) Delete child directories by path, 3) Delete directory.
     */
    @Transactional
    fun deleteDirectory(directory: DbDirectory) {
        val path = directory.path ?: return
        val dirId = directory.id ?: return

        // Skip if already deleted (e.g. was a child of a parent we just deleted)
        if (!debridFileRepository.findById(dirId).isPresent) {
            return
        }

        // 1. Delete all files in this directory tree
        debridFileRepository.findFilesInDirectoryTree(path).forEach { file ->
            try {
                deleteFile(file)
            } catch (e: Exception) {
                logger.warn("Error deleting file {} in directory tree {}", file.id, path, e)
            }
        }

        // 2. Delete all path-descendant directories (children and deeper)
        debridFileRepository.deleteChildDirectoriesByPath(path)

        // 3. Delete the directory itself
        debridFileRepository.delete(directory)
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
            is io.skjaere.debridav.fs.DebridIptvContent -> {
                // IPTV content can also be linked to torrents, so unlink it before deletion
                debridFileRepository.unlinkFileFromTorrents(file)
            }
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
        val fileName = path.substringAfterLast("/")
        val shouldBypassSizeCheck = debridavConfigurationProperties.shouldAlwaysStoreAsLocalEntity(fileName)

        if (size == null) {
            val bytes = inputStream.readAllBytes()
            if (!shouldBypassSizeCheck && bytes.size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
            ) {
                throw IllegalArgumentException(
                    "Size: ${bytes.size / MEGABYTE} MB is greater than set maximum: " +
                            "${debridavConfigurationProperties.localEntityMaxSizeMb}"
                )
            }
            val streamSize = bytes.size.toLong()
            localFile.size = streamSize
            localFile.blob = Blob(BlobProxy.generateProxy(bytes.inputStream(), streamSize), streamSize)
        } else {
            if (!shouldBypassSizeCheck && size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
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
        localFile.name = fileName
        localFile.directory = directory
        localFile.lastModified = System.currentTimeMillis()

        return debridFileRepository.save(localFile)
    }

    /**
     * Downloads content from a debrid file and stores it as LocalEntity.
     * Intentionally not @Transactional so the DB connection is not held during the HTTP download.
     *
     * @param path The file path where the LocalEntity should be created
     * @param debridFileContents The DebridFileContents containing the download URL
     * @param authHeader Optional authentication header (e.g., "Basic ..." or "Bearer ...") for WebDAV or other authenticated sources
     * @return The created LocalEntity with downloaded content
     * @throws Exception If download fails, the exception is logged and rethrown
     */
    suspend fun downloadAndStoreAsLocalEntity(
        path: String,
        debridFileContents: DebridFileContents,
        authHeader: String? = null
    ): LocalEntity = withContext(Dispatchers.IO) {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val fileName = path.substringAfterLast("/")

        // Extract download URL from debrid links
        val downloadUrl = when {
            // For IPTV content, resolve template URL if needed
            debridFileContents is DebridIptvContent -> {
                val iptvFile = debridFileContents.debridLinks.firstOrNull { it is IptvFile } as? IptvFile
                val tokenizedUrl = iptvFile?.link
                if (tokenizedUrl != null) {
                    if (tokenizedUrl.startsWith("{IPTV_TEMPLATE_URL}")) {
                        val template = debridFileContents.iptvUrlTemplate
                        if (template != null) {
                            tokenizedUrl.replace("{IPTV_TEMPLATE_URL}", template.baseUrl)
                        } else {
                            throw IllegalStateException("IPTV URL template is missing for content: ${debridFileContents.iptvContentId}")
                        }
                    } else {
                        tokenizedUrl
                    }
                } else {
                    throw IllegalStateException("IptvFile.link is missing for IPTV content")
                }
            }
            // For debrid providers, find CachedFile with link
            else -> {
                val cachedFile = debridFileContents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
                cachedFile?.link ?: throw IllegalStateException("No download URL available in debrid links")
            }
        }

        logger.debug("Downloading content from {} for file {}", urlDecode(downloadUrl), fileName)

        val bytes = try {
            if (authHeader != null) {
                httpClient.get(downloadUrl) {
                    header(io.ktor.http.HttpHeaders.Authorization, authHeader)
                }.body<ByteArray>()
            } else {
                httpClient.get(downloadUrl).body<ByteArray>()
            }
        } catch (e: Exception) {
            logger.error("Failed to download content from {} for file {}: {}", urlDecode(downloadUrl), fileName, e.message, e)
            throw e
        }
        val contentSize = bytes.size.toLong()

        // Bypass size check for whitelisted extensions
        val shouldBypassSizeCheck = debridavConfigurationProperties.shouldAlwaysStoreAsLocalEntity(fileName)
        if (!shouldBypassSizeCheck && contentSize / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
            && debridavConfigurationProperties.localEntityMaxSizeMb != 0
        ) {
            throw IllegalArgumentException(
                "Downloaded file size: ${contentSize / MEGABYTE} MB is greater than set maximum: " +
                        "${debridavConfigurationProperties.localEntityMaxSizeMb}"
            )
        }

        logger.info("Successfully downloaded and stored {} as LocalEntity (size: {} bytes)", fileName, contentSize)
        saveDownloadedLocalEntity(directory, fileName, bytes, contentSize, debridFileContents.mimeType)
    }

    /**
     * Persists a downloaded file as LocalEntity. Short transaction for write only.
     */
    @Transactional
    private fun saveDownloadedLocalEntity(
        directory: DbDirectory,
        fileName: String,
        bytes: ByteArray,
        contentSize: Long,
        mimeType: String?
    ): LocalEntity {
        val localFile = LocalEntity()
        localFile.name = fileName
        localFile.directory = directory
        localFile.lastModified = System.currentTimeMillis()
        localFile.size = contentSize
        localFile.mimeType = mimeType
        localFile.blob = Blob(BlobProxy.generateProxy(bytes.inputStream(), contentSize), contentSize)
        return debridFileRepository.save(localFile)
    }

    fun getFileAtPath(path: String): DbEntity? {
        return debridFileRepository.getDirectoryByPath(path.pathToLtree()) ?: debridFileRepository.getDirectoryByPath(
            path.getDirectoryFromPath().pathToLtree()
        )?.let { directory ->
            return debridFileRepository.findByDirectoryAndName(directory, path.substringAfterLast("/"))
        }

    }

    /**
     * Get a file by its database ID.
     * Used to find files that may have been renamed by the user.
     */
    fun getFileById(id: Long): DbEntity? {
        return debridFileRepository.findById(id).orElse(null)
    }

    @Transactional
    fun createDirectory(path: String): DbDirectory {
        return getOrCreateDirectory(if (path != "/") StringUtils.removeEnd(path, "/") else path)
    }

    @Transactional
    suspend fun getChildren(directory: DbDirectory): List<DbEntity> = withContext(Dispatchers.IO) {
        listOf(
            async { debridFileRepository.getChildrenByDirectory(directory) },
            async { debridFileRepository.getByDirectory(directory) }).awaitAll().flatten()
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

    /**
     * Reloads a RemotelyCachedEntity with its contents and debridLinks within a transaction.
     * This is useful when accessing lazy-loaded properties outside of a Hibernate session.
     * @param entity The entity to reload
     * @return The reloaded entity with contents loaded, or null if the entity doesn't exist
     */
    fun reloadRemotelyCachedEntity(entity: RemotelyCachedEntity): RemotelyCachedEntity? {
        return transactionTemplate.execute<RemotelyCachedEntity?> {
            val mergedEntity = entityManager.merge(entity) as? RemotelyCachedEntity
            // Force initialization of contents and debridLinks by accessing them
            val contents = mergedEntity?.contents
            contents?.debridLinks?.size
            // Also initialize iptvUrlTemplate if this is IPTV content
            if (contents is io.skjaere.debridav.fs.DebridIptvContent) {
                contents.iptvUrlTemplate?.baseUrl
            }
            mergedEntity
        }
    }

    /**
     * URL decode a path for logging purposes, handling URL-encoded characters like %20, %5b, etc.
     * Returns the original string if decoding fails.
     */
    private fun urlDecode(url: String?): String {
        if (url == null) return "null"
        return try {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            url // Return original if decoding fails
        }
    }
}
