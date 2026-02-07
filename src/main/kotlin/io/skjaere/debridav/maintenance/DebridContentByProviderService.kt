package io.skjaere.debridav.maintenance

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.fs.DatabaseFileService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for listing and deleting debrid content grouped by provider.
 */
@Service
class DebridContentByProviderService(
    private val debridFileContentsRepository: DebridFileContentsRepository,
    private val databaseFileService: DatabaseFileService
) {
    private val logger = LoggerFactory.getLogger(DebridContentByProviderService::class.java)
    
    /**
     * Lists all debrid content grouped by provider.
     * For non-IPTV providers, content is grouped directly by provider.
     * For IPTV, content is further grouped by iptv provider name.
     * 
     * @return Map structure: provider -> (for IPTV: iptvProvider -> files, for others: files)
     */
    fun listContentByProvider(): Map<String, Any> {
        logger.debug("Listing content by provider")
        val allEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
        
        // Group entities by provider
        // For non-IPTV: provider -> list of files
        // For IPTV: IPTV -> iptvProvider -> list of files
        val nonIptvProviderMap = mutableMapOf<String, MutableList<FileEntry>>()
        val iptvProviderMap = mutableMapOf<String, MutableList<FileEntry>>()
        
        allEntities.forEach { entity ->
            val contents = entity.contents ?: return@forEach
            val fileEntry = createFileEntry(entity)
            
            when (contents) {
                is DebridIptvContent -> {
                    // IPTV content: group by iptvProviderName
                    val iptvProviderName = contents.iptvProviderName ?: "unknown"
                    iptvProviderMap.getOrPut(iptvProviderName) { mutableListOf() }
                        .add(fileEntry)
                }
                else -> {
                    // Non-IPTV content: group by providers from debridLinks
                    val providers = contents.debridLinks
                        .mapNotNull { it.provider }
                        .distinct()
                    
                    providers.forEach { provider ->
                        val providerKey = provider.name
                        nonIptvProviderMap.getOrPut(providerKey) { mutableListOf() }
                            .add(fileEntry)
                    }
                }
            }
        }
        
        // Build result structure
        val result = mutableMapOf<String, Any>()
        
        // Add non-IPTV providers
        nonIptvProviderMap.forEach { (providerName, files) ->
            result[providerName] = mapOf(
                "count" to files.size,
                "files" to files
            )
        }
        
        // Add IPTV provider with nested structure
        if (iptvProviderMap.isNotEmpty()) {
            val iptvProviders = mutableMapOf<String, Any>()
            iptvProviderMap.forEach { (iptvProviderName, files) ->
                iptvProviders[iptvProviderName] = mapOf(
                    "count" to files.size,
                    "files" to files
                )
            }
            result[DebridProvider.IPTV.name] = iptvProviders
        }
        
        logger.debug("Found content for ${result.size} providers")
        return result
    }
    
    /**
     * Lists content for a specific provider.
     * For non-IPTV providers, returns the files list directly.
     * For IPTV, requires iptvProvider and returns files for that specific IPTV provider.
     * 
     * @param provider The provider name (e.g. PREMIUMIZE, REAL_DEBRID, IPTV)
     * @param iptvProvider The IPTV provider name (required if provider is IPTV, ignored otherwise)
     * @return Map with count and files, or null if provider not found or invalid
     */
    fun listContentByProvider(provider: String, iptvProvider: String? = null): Map<String, Any>? {
        logger.debug("Listing content for provider: $provider${iptvProvider?.let { ", IPTV provider: $it" } ?: ""}")
        val allEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
        
        val files = mutableListOf<FileEntry>()
        
        allEntities.forEach { entity ->
            val contents = entity.contents ?: return@forEach
            val fileEntry = createFileEntry(entity)
            
            when (contents) {
                is DebridIptvContent -> {
                    // IPTV content: must match provider and iptvProvider
                    if (provider != DebridProvider.IPTV.name) {
                        return@forEach
                    }
                    if (iptvProvider == null) {
                        return@forEach
                    }
                    if (contents.iptvProviderName == iptvProvider) {
                        files.add(fileEntry)
                    }
                }
                else -> {
                    // Non-IPTV content: check if any debridLink has the matching provider
                    if (contents.debridLinks.any { it.provider?.name == provider }) {
                        files.add(fileEntry)
                    }
                }
            }
        }
        
        if (files.isEmpty() && iptvProvider == null && provider != DebridProvider.IPTV.name) {
            // Check if provider exists at all (for non-IPTV)
            val providerExists = allEntities.any { entity ->
                val contents = entity.contents
                contents != null && contents !is DebridIptvContent && 
                contents.debridLinks.any { it.provider?.name == provider }
            }
            if (!providerExists) {
                logger.debug("Provider $provider not found")
                return null
            }
        }
        
        if (files.isEmpty() && provider == DebridProvider.IPTV.name && iptvProvider != null) {
            // Check if IPTV provider exists
            val iptvProviderExists = allEntities.any { entity ->
                val contents = entity.contents
                contents is DebridIptvContent && contents.iptvProviderName == iptvProvider
            }
            if (!iptvProviderExists) {
                logger.debug("IPTV provider $iptvProvider not found")
                return null
            }
        }
        
        return mapOf(
            "count" to files.size,
            "files" to files
        )
    }
    
    /**
     * Deletes all files for a given provider.
     * For IPTV, iptvProvider must be specified.
     * 
     * @param provider The provider name (e.g. PREMIUMIZE, REAL_DEBRID, IPTV)
     * @param iptvProvider The IPTV provider name (required if provider is IPTV, ignored otherwise)
     * @return Number of files deleted
     */
    @Transactional
    fun deleteByProvider(provider: String, iptvProvider: String? = null): Int {
        logger.info("Deleting files for provider: $provider${iptvProvider?.let { ", IPTV provider: $it" } ?: ""}")
        
        val allEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
        
        // Filter entities that belong to the specified provider
        val entitiesToDelete = allEntities.filter { entity ->
            val contents = entity.contents ?: return@filter false
            
            when (contents) {
                is DebridIptvContent -> {
                    // IPTV: must match provider and iptvProvider
                    if (provider != DebridProvider.IPTV.name) {
                        return@filter false
                    }
                    if (iptvProvider == null) {
                        logger.warn("IPTV provider requires iptvProvider parameter")
                        return@filter false
                    }
                    contents.iptvProviderName == iptvProvider
                }
                else -> {
                    // Non-IPTV: check if any debridLink has the matching provider
                    contents.debridLinks.any { it.provider?.name == provider }
                }
            }
        }
        
        logger.info("Found ${entitiesToDelete.size} files to delete for provider $provider${iptvProvider?.let { "/$it" } ?: ""}")
        
        // Delete each entity
        entitiesToDelete.forEach { entity ->
            try {
                databaseFileService.deleteFile(entity)
            } catch (e: Exception) {
                logger.error("Error deleting file ${entity.id}: ${entity.name}", e)
            }
        }
        
        logger.info("Deleted ${entitiesToDelete.size} files for provider $provider${iptvProvider?.let { "/$it" } ?: ""}")
        return entitiesToDelete.size
    }
    
    /**
     * Creates a FileEntry from a RemotelyCachedEntity.
     */
    private fun createFileEntry(entity: RemotelyCachedEntity): FileEntry {
        val fullPath = if (entity.directory != null && entity.name != null) {
            val dirPath = entity.directory!!.fileSystemPath() ?: ""
            if (dirPath.endsWith("/")) {
                "$dirPath${entity.name}"
            } else {
                "$dirPath/${entity.name}"
            }
        } else {
            entity.name ?: ""
        }
        
        return FileEntry(
            id = entity.id,
            name = entity.name,
            path = fullPath,
            size = entity.size,
            lastModified = entity.lastModified,
            hash = entity.hash,
            mimeType = entity.mimeType
        )
    }
    
    /**
     * Data class representing a file entry in the response.
     */
    data class FileEntry(
        val id: Long?,
        val name: String?,
        val path: String,
        val size: Long?,
        val lastModified: Long?,
        val hash: String?,
        val mimeType: String?
    )
}
