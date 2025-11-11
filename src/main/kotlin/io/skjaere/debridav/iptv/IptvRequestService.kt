package io.skjaere.debridav.iptv

import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.iptv.model.ContentType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class IptvRequestService(
    private val iptvContentRepository: IptvContentRepository,
    private val iptvContentService: IptvContentService,
    private val databaseFileService: DatabaseFileService,
    private val categoryService: CategoryService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(IptvRequestService::class.java)

    @Transactional
    fun addIptvContent(contentId: String, providerName: String, category: String): Boolean {
        logger.info("Adding IPTV content: contentId=$contentId, provider=$providerName, category=$category")
        
        val iptvContent = iptvContentRepository.findByProviderNameAndContentId(providerName, contentId)
            ?: run {
                logger.warn("IPTV content not found: provider=$providerName, contentId=$contentId")
                return false
            }
        
        if (!iptvContent.isActive) {
            logger.warn("IPTV content is inactive: provider=$providerName, contentId=$contentId")
            return false
        }
        
        // Resolve tokenized URL to actual URL
        val resolvedUrl = try {
            iptvContentService.resolveIptvUrl(iptvContent.url, providerName)
        } catch (e: Exception) {
            logger.error("Failed to resolve IPTV URL for provider $providerName", e)
            return false
        }
        
        // Create DebridIptvContent entity
        val debridIptvContent = DebridIptvContent(
            originalPath = iptvContent.title,
            size = null, // IPTV streams may not have known size
            modified = Instant.now().toEpochMilli(),
            iptvUrl = resolvedUrl,
            iptvProviderName = providerName,
            iptvContentId = contentId,
            mimeType = determineMimeType(iptvContent.title),
            debridLinks = mutableListOf()
        )
        
        // Create IptvFile link
        val iptvFile = IptvFile(
            path = iptvContent.title,
            size = 0L, // IPTV streams may not have known size
            mimeType = debridIptvContent.mimeType ?: "video/mp4",
            link = resolvedUrl,
            params = emptyMap(),
            lastChecked = Instant.now().toEpochMilli()
        )
        
        debridIptvContent.debridLinks.add(iptvFile)
        
        // Determine file path - use category if provided, otherwise use default
        val categoryPath = categoryService.findByName(category)?.downloadPath 
            ?: debridavConfigurationProperties.downloadPath
        val filePath = "$categoryPath/${sanitizeFileName(iptvContent.title)}"
        
        // Generate hash from content ID
        val hash = "${providerName}_${contentId}".hashCode().toString()
        
        // Create virtual file
        try {
            databaseFileService.createDebridFile(filePath, hash, debridIptvContent)
            logger.info("Successfully created IPTV virtual file: $filePath")
            return true
        } catch (e: Exception) {
            logger.error("Failed to create IPTV virtual file: $filePath", e)
            return false
        }
    }
    
    fun searchIptvContent(query: String, contentType: ContentType?): List<IptvSearchResult> {
        val results = iptvContentService.searchContent(query, contentType)
        return results.map { entity ->
            IptvSearchResult(
                contentId = entity.contentId,
                providerName = entity.providerName,
                title = entity.title,
                contentType = entity.contentType,
                category = entity.category?.categoryName
            )
        }
    }
    
    private fun determineMimeType(title: String): String {
        return when {
            title.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            title.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            title.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            title.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            else -> "video/mp4" // Default
        }
    }
    
    private fun sanitizeFileName(fileName: String): String {
        // Remove invalid file system characters
        return fileName
            .replace(Regex("[<>:\"/\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    data class IptvSearchResult(
        val contentId: String,
        val providerName: String,
        val title: String,
        val contentType: ContentType,
        val category: String?
    )
}

