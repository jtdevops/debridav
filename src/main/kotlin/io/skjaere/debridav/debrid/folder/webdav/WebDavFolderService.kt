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
                client.listFiles(mapping.externalPath ?: "")
            } catch (e: Exception) {
                logger.error("Error listing files from WebDAV for mapping ${mapping.id}", e)
                emptyList()
            }
        } else {
            logger.warn("No WebDAV client found for provider: ${mapping.provider}")
            emptyList()
        }
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
