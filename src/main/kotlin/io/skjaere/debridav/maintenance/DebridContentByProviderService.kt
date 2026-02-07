package io.skjaere.debridav.maintenance

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
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
     * @param verbose If true, returns full file details. If false, returns only path.
     * @return Map structure: provider -> (for IPTV: iptvProvider -> files, for others: files)
     */
    fun listContentByProvider(verbose: Boolean = false): Map<String, Any> {
        logger.debug("Listing content by provider")
        val allEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
        
        if (verbose) {
            // Group entities by provider with full FileEntry objects
            val nonIptvProviderMap = mutableMapOf<String, MutableList<FileEntry>>()
            val iptvProviderMap = mutableMapOf<String, MutableList<FileEntry>>()
            
            allEntities.forEach { entity ->
                val contents = entity.contents ?: return@forEach
                val fileEntry = createFileEntry(entity)
                
                when (contents) {
                    is DebridIptvContent -> {
                        val iptvProviderName = contents.iptvProviderName ?: "unknown"
                        iptvProviderMap.getOrPut(iptvProviderName) { mutableListOf() }
                            .add(fileEntry)
                    }
                    else -> {
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
            
            // Sort files by path within each provider
            nonIptvProviderMap.forEach { (_, files) ->
                files.sortBy { it.path }
            }
            iptvProviderMap.forEach { (_, files) ->
                files.sortBy { it.path }
            }
            
            // Build result structure with sorted providers
            val result = mutableMapOf<String, Any>()
            
            nonIptvProviderMap.toSortedMap().forEach { (providerName, files) ->
                result[providerName] = mapOf(
                    "count" to files.size,
                    "files" to files
                )
            }
            
            if (iptvProviderMap.isNotEmpty()) {
                val iptvProviders = mutableMapOf<String, Any>()
                iptvProviderMap.toSortedMap().forEach { (iptvProviderName, files) ->
                    iptvProviders[iptvProviderName] = mapOf(
                        "count" to files.size,
                        "files" to files
                    )
                }
                result[DebridProvider.IPTV.name] = iptvProviders
            }
            
            logger.debug("Found content for ${result.size} providers")
            return result
        } else {
            // Non-verbose: group paths as strings
            val nonIptvProviderMap = mutableMapOf<String, MutableList<String>>()
            val iptvProviderMap = mutableMapOf<String, MutableList<String>>()
            
            allEntities.forEach { entity ->
                val contents = entity.contents ?: return@forEach
                val path = getFilePath(entity)
                
                when (contents) {
                    is DebridIptvContent -> {
                        val iptvProviderName = contents.iptvProviderName ?: "unknown"
                        iptvProviderMap.getOrPut(iptvProviderName) { mutableListOf() }
                            .add(path)
                    }
                    else -> {
                        val providers = contents.debridLinks
                            .mapNotNull { it.provider }
                            .distinct()
                        
                        providers.forEach { provider ->
                            val providerKey = provider.name
                            nonIptvProviderMap.getOrPut(providerKey) { mutableListOf() }
                                .add(path)
                        }
                    }
                }
            }
            
            // Sort paths within each provider
            nonIptvProviderMap.forEach { (_, paths) ->
                paths.sort()
            }
            iptvProviderMap.forEach { (_, paths) ->
                paths.sort()
            }
            
            // Build result structure with sorted providers
            val result = mutableMapOf<String, Any>()
            
            nonIptvProviderMap.toSortedMap().forEach { (providerName, paths) ->
                result[providerName] = mapOf(
                    "count" to paths.size,
                    "files" to paths
                )
            }
            
            if (iptvProviderMap.isNotEmpty()) {
                val iptvProviders = mutableMapOf<String, Any>()
                iptvProviderMap.toSortedMap().forEach { (iptvProviderName, paths) ->
                    iptvProviders[iptvProviderName] = mapOf(
                        "count" to paths.size,
                        "files" to paths
                    )
                }
                result[DebridProvider.IPTV.name] = iptvProviders
            }
            
            logger.debug("Found content for ${result.size} providers")
            return result
        }
    }
    
    /**
     * Lists all IPTV content grouped by iptvProvider.
     * 
     * @param verbose If true, returns full file details. If false, returns only path.
     * @return Map structure: iptvProvider -> files
     */
    fun listAllIptvContent(verbose: Boolean = false): Map<String, Any> {
        logger.debug("Listing all IPTV content")
        val allEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
        
        if (verbose) {
            val iptvProviderMap = mutableMapOf<String, MutableList<FileEntry>>()
            
            allEntities.forEach { entity ->
                val contents = entity.contents ?: return@forEach
                
                if (contents is DebridIptvContent) {
                    val iptvProviderName = contents.iptvProviderName ?: "unknown"
                    val fileEntry = createFileEntry(entity)
                    iptvProviderMap.getOrPut(iptvProviderName) { mutableListOf() }
                        .add(fileEntry)
                }
            }
            
            // Sort files by path within each iptvProvider
            iptvProviderMap.forEach { (_, files) ->
                files.sortBy { it.path }
            }
            
            // Build result structure with sorted iptvProviders
            val result = mutableMapOf<String, Any>()
            iptvProviderMap.toSortedMap().forEach { (iptvProviderName, files) ->
                result[iptvProviderName] = mapOf(
                    "count" to files.size,
                    "files" to files
                )
            }
            
            logger.debug("Found IPTV content for ${result.size} providers")
            return result
        } else {
            val iptvProviderMap = mutableMapOf<String, MutableList<String>>()
            
            allEntities.forEach { entity ->
                val contents = entity.contents ?: return@forEach
                
                if (contents is DebridIptvContent) {
                    val iptvProviderName = contents.iptvProviderName ?: "unknown"
                    val path = getFilePath(entity)
                    iptvProviderMap.getOrPut(iptvProviderName) { mutableListOf() }
                        .add(path)
                }
            }
            
            // Sort paths within each iptvProvider
            iptvProviderMap.forEach { (_, paths) ->
                paths.sort()
            }
            
            // Build result structure with sorted iptvProviders
            val result = mutableMapOf<String, Any>()
            iptvProviderMap.toSortedMap().forEach { (iptvProviderName, paths) ->
                result[iptvProviderName] = mapOf(
                    "count" to paths.size,
                    "files" to paths
                )
            }
            
            logger.debug("Found IPTV content for ${result.size} providers")
            return result
        }
    }
    
    /**
     * Lists content for a specific provider.
     * For non-IPTV providers, returns the files list directly.
     * For IPTV, requires iptvProvider and returns files for that specific IPTV provider.
     * 
     * @param provider The provider name (e.g. PREMIUMIZE, REAL_DEBRID, IPTV)
     * @param iptvProvider The IPTV provider name (required if provider is IPTV, ignored otherwise)
     * @param verbose If true, returns full file details. If false, returns only path.
     * @return Map with count and files, or null if provider not found or invalid
     */
    fun listContentByProvider(provider: String, iptvProvider: String? = null, verbose: Boolean = false): Map<String, Any>? {
        logger.debug("Listing content for provider: $provider${iptvProvider?.let { ", IPTV provider: $it" } ?: ""}")
        val allEntities = debridFileContentsRepository.findAllRemotelyCachedEntities()
        
        if (verbose) {
            val files = mutableListOf<FileEntry>()
            
            allEntities.forEach { entity ->
                val contents = entity.contents ?: return@forEach
                val fileEntry = createFileEntry(entity)
                
                when (contents) {
                    is DebridIptvContent -> {
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
                        if (contents.debridLinks.any { it.provider?.name == provider }) {
                            files.add(fileEntry)
                        }
                    }
                }
            }
            
            if (files.isEmpty() && iptvProvider == null && provider != DebridProvider.IPTV.name) {
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
                val iptvProviderExists = allEntities.any { entity ->
                    val contents = entity.contents
                    contents is DebridIptvContent && contents.iptvProviderName == iptvProvider
                }
                if (!iptvProviderExists) {
                    logger.debug("IPTV provider $iptvProvider not found")
                    return null
                }
            }
            
            // Sort files by path
            files.sortBy { it.path }
            
            return mapOf(
                "count" to files.size,
                "files" to files
            )
        } else {
            val paths = mutableListOf<String>()
            
            allEntities.forEach { entity ->
                val contents = entity.contents ?: return@forEach
                val path = getFilePath(entity)
                
                when (contents) {
                    is DebridIptvContent -> {
                        if (provider != DebridProvider.IPTV.name) {
                            return@forEach
                        }
                        if (iptvProvider == null) {
                            return@forEach
                        }
                        if (contents.iptvProviderName == iptvProvider) {
                            paths.add(path)
                        }
                    }
                    else -> {
                        if (contents.debridLinks.any { it.provider?.name == provider }) {
                            paths.add(path)
                        }
                    }
                }
            }
            
            if (paths.isEmpty() && iptvProvider == null && provider != DebridProvider.IPTV.name) {
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
            
            if (paths.isEmpty() && provider == DebridProvider.IPTV.name && iptvProvider != null) {
                val iptvProviderExists = allEntities.any { entity ->
                    val contents = entity.contents
                    contents is DebridIptvContent && contents.iptvProviderName == iptvProvider
                }
                if (!iptvProviderExists) {
                    logger.debug("IPTV provider $iptvProvider not found")
                    return null
                }
            }
            
            // Sort paths
            paths.sort()
            
            return mapOf(
                "count" to paths.size,
                "files" to paths
            )
        }
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
     * Creates a FileEntry from a RemotelyCachedEntity with full details.
     */
    private fun createFileEntry(entity: RemotelyCachedEntity): FileEntry {
        return FileEntry(
            id = entity.id,
            name = entity.name,
            path = getFilePath(entity),
            size = entity.size,
            lastModified = entity.lastModified,
            hash = entity.hash,
            mimeType = entity.mimeType
        )
    }
    
    /**
     * Gets the file path from a RemotelyCachedEntity.
     */
    private fun getFilePath(entity: RemotelyCachedEntity): String {
        return if (entity.directory != null && entity.name != null) {
            val dirPath = entity.directory!!.fileSystemPath() ?: ""
            if (dirPath.endsWith("/")) {
                "$dirPath${entity.name}"
            } else {
                "$dirPath/${entity.name}"
            }
        } else {
            entity.name ?: ""
        }
    }
    
    /**
     * Data class representing a file entry in the response.
     * When verbose=false, only path is populated.
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
