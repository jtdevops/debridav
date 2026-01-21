package io.skjaere.debridav.webdav.folder.webdav

import io.skjaere.debridav.webdav.folder.WebDavFolderMappingEntity
import io.skjaere.debridav.webdav.folder.WebDavProviderConfigurationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Service
@ConditionalOnProperty(
    prefix = "debridav.webdav-folder-mapping",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class WebDavFolderService(
    private val genericWebDavClient: GenericWebDavClient,
    private val providerConfigService: WebDavProviderConfigurationService
) {
    private val logger = LoggerFactory.getLogger(WebDavFolderService::class.java)

    suspend fun listFiles(mapping: WebDavFolderMappingEntity): List<WebDavFile> {
        val providerName = mapping.providerName ?: return emptyList()
        val config = providerConfigService.getConfiguration(providerName)
        
        return if (config != null && config.hasCredentials()) {
            try {
                val basePath = mapping.externalPath ?: ""
                logger.trace("Listing files recursively from WebDAV path: ${urlDecode(basePath)}")
                val allFiles = listFilesRecursively(config, basePath)
                logger.info("Found ${allFiles.size} files (including ${allFiles.count { it.isDirectory }} directories) in ${urlDecode(basePath)}")
                allFiles
            } catch (e: Exception) {
                logger.error("Error listing files from WebDAV for mapping ${mapping.id}", e)
                emptyList()
            }
        } else {
            logger.warn("No WebDAV configuration found for provider: $providerName")
            emptyList()
        }
    }

    /**
     * Recursively list all files in a directory and its subdirectories.
     */
    private suspend fun listFilesRecursively(
        config: io.skjaere.debridav.webdav.folder.WebDavProviderConfiguration,
        path: String,
        maxDepth: Int = 10,
        currentDepth: Int = 0
    ): List<WebDavFile> {
        if (currentDepth >= maxDepth) {
            logger.warn("Max depth ($maxDepth) reached while listing WebDAV files at path: $path")
            return emptyList()
        }

        val files = genericWebDavClient.listFiles(config, path)
        val result = mutableListOf<WebDavFile>()

        // Filter out the parent directory itself (WebDAV often returns the requested folder as first entry)
        val filteredFiles = files.filter { file ->
            val normalizedFilePath = file.path.trimEnd('/')
            val normalizedBasePath = path.trimEnd('/')
            normalizedFilePath != normalizedBasePath && normalizedFilePath.isNotBlank()
        }

        logger.trace("Found ${filteredFiles.size} items at depth $currentDepth in path: ${urlDecode(path)}")

        for (file in filteredFiles) {
            result.add(file)

            // If it's a directory, recursively list its contents
            if (file.isDirectory) {
                logger.trace("Recursing into directory: ${urlDecode(file.path)}")
                val subFiles = listFilesRecursively(config, file.path, maxDepth, currentDepth + 1)
                result.addAll(subFiles)
            }
        }

        return result
    }

    suspend fun getFileInfo(mapping: WebDavFolderMappingEntity, filePath: String): WebDavFile? {
        val providerName = mapping.providerName ?: return null
        val config = providerConfigService.getConfiguration(providerName)
        
        return if (config != null && config.hasCredentials()) {
            try {
                val fullPath = "${mapping.externalPath}/${filePath}".replace("//", "/")
                genericWebDavClient.getFileInfo(config, fullPath)
            } catch (e: Exception) {
                logger.error("Error getting file info from WebDAV for mapping ${mapping.id}", e)
                null
            }
        } else {
            null
        }
    }

    suspend fun getDownloadLink(mapping: WebDavFolderMappingEntity, filePath: String): String? {
        val providerName = mapping.providerName ?: return null
        val config = providerConfigService.getConfiguration(providerName)
        
        return if (config != null && config.hasCredentials()) {
            try {
                val fullPath = "${mapping.externalPath}/${filePath}".replace("//", "/")
                genericWebDavClient.getDownloadLink(config, fullPath)
            } catch (e: Exception) {
                logger.error("Error getting download link from WebDAV for mapping ${mapping.id}", e)
                null
            }
        } else {
            null
        }
    }

    fun isProviderSupported(providerName: String): Boolean {
        val config = providerConfigService.getConfiguration(providerName)
        return config != null && config.hasCredentials()
    }

    /**
     * Lists and logs root folders available on the WebDAV server for a provider.
     * This is useful for discovering available folders when configuring mappings.
     */
    suspend fun logRootFolders(providerName: String) {
        val config = providerConfigService.getConfiguration(providerName)
        
        if (config == null || !config.hasCredentials()) {
            logger.warn("Cannot log root folders for provider '$providerName': no configuration or credentials found")
            return
        }

        try {
            logger.info("Listing root folders for WebDAV provider: $providerName (${config.url})")
            val rootFiles = genericWebDavClient.listFiles(config, "/")
            
            // Filter to only directories
            val rootFolders = rootFiles.filter { it.isDirectory }
                .map { it.path.trimEnd('/') }
                .filter { it.isNotBlank() && it != "/" }
                .sorted()

            if (rootFolders.isEmpty()) {
                logger.info("No root folders found for WebDAV provider '$providerName'")
            } else {
                logger.info("Available root folders for WebDAV provider '$providerName' (${rootFolders.size} total):")
                rootFolders.forEach { folder ->
                    logger.info("  - ${urlDecode(folder)}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error listing root folders for WebDAV provider '$providerName'", e)
        }
    }

    /**
     * URL decode a path for logging purposes, handling URL-encoded characters like %20, %5b, etc.
     * Returns the original path if decoding fails, or "null" if path is null.
     */
    private fun urlDecode(path: String?): String {
        if (path == null) return "null"
        return try {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            path // Return original path if decoding fails
        }
    }
}
