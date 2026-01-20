package io.skjaere.debridav.debrid.folder.webdav

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.folder.DebridFolderMappingEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WebDavFolderService(
    private val webDavClients: List<DebridWebDavClient>
) {
    private val logger = LoggerFactory.getLogger(WebDavFolderService::class.java)
    private val clientCache = ConcurrentHashMap<DebridProvider, DebridWebDavClient>()

    init {
        // Initialize client cache
        webDavClients.forEach { client ->
            when (client) {
                is PremiumizeWebDavClient -> clientCache[DebridProvider.PREMIUMIZE] = client
                is RealDebridWebDavClient -> clientCache[DebridProvider.REAL_DEBRID] = client
                is TorBoxWebDavClient -> clientCache[DebridProvider.TORBOX] = client
            }
        }
    }

    suspend fun listFiles(mapping: DebridFolderMappingEntity): List<WebDavFile> {
        val client = clientCache[mapping.provider]
        return if (client != null) {
            try {
                val basePath = mapping.externalPath ?: ""
                logger.debug("Listing files recursively from WebDAV path: $basePath")
                val allFiles = listFilesRecursively(client, basePath)
                logger.info("Found ${allFiles.size} files (including ${allFiles.count { it.isDirectory }} directories) in $basePath")
                allFiles
            } catch (e: Exception) {
                logger.error("Error listing files from WebDAV for mapping ${mapping.id}", e)
                emptyList()
            }
        } else {
            logger.warn("No WebDAV client found for provider: ${mapping.provider}")
            emptyList()
        }
    }

    /**
     * Recursively list all files in a directory and its subdirectories.
     */
    private suspend fun listFilesRecursively(
        client: DebridWebDavClient,
        path: String,
        maxDepth: Int = 10,
        currentDepth: Int = 0
    ): List<WebDavFile> {
        if (currentDepth >= maxDepth) {
            logger.warn("Max depth ($maxDepth) reached while listing WebDAV files at path: $path")
            return emptyList()
        }

        val files = client.listFiles(path)
        val result = mutableListOf<WebDavFile>()

        // Filter out the parent directory itself (WebDAV often returns the requested folder as first entry)
        val filteredFiles = files.filter { file ->
            val normalizedFilePath = file.path.trimEnd('/')
            val normalizedBasePath = path.trimEnd('/')
            normalizedFilePath != normalizedBasePath && normalizedFilePath.isNotBlank()
        }

        logger.debug("Found ${filteredFiles.size} items at depth $currentDepth in path: $path")

        for (file in filteredFiles) {
            result.add(file)

            // If it's a directory, recursively list its contents
            if (file.isDirectory) {
                logger.debug("Recursing into directory: ${file.path}")
                val subFiles = listFilesRecursively(client, file.path, maxDepth, currentDepth + 1)
                result.addAll(subFiles)
            }
        }

        return result
    }

    suspend fun getFileInfo(mapping: DebridFolderMappingEntity, filePath: String): WebDavFile? {
        val client = clientCache[mapping.provider]
        return if (client != null) {
            try {
                val fullPath = "${mapping.externalPath}/${filePath}".replace("//", "/")
                client.getFileInfo(fullPath)
            } catch (e: Exception) {
                logger.error("Error getting file info from WebDAV for mapping ${mapping.id}", e)
                null
            }
        } else {
            null
        }
    }

    suspend fun getDownloadLink(mapping: DebridFolderMappingEntity, filePath: String): String? {
        val client = clientCache[mapping.provider]
        return if (client != null) {
            try {
                val fullPath = "${mapping.externalPath}/${filePath}".replace("//", "/")
                client.getDownloadLink(fullPath)
            } catch (e: Exception) {
                logger.error("Error getting download link from WebDAV for mapping ${mapping.id}", e)
                null
            }
        } else {
            null
        }
    }

    fun isProviderSupported(provider: DebridProvider): Boolean {
        return clientCache.containsKey(provider)
    }
}
