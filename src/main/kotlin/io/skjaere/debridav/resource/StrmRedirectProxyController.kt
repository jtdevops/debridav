package io.skjaere.debridav.resource

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Redirect proxy controller for STRM external URLs.
 * This controller handles redirect requests for external URLs in STRM files,
 * checking if URLs are expired and refreshing them before redirecting.
 */
@RestController
@RequestMapping("/strm-proxy")
class StrmRedirectProxyController(
    private val fileService: DatabaseFileService,
    private val debridLinkService: DebridLinkService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val debridFileRepository: DebridFileContentsRepository
) {
    private val logger = LoggerFactory.getLogger(StrmRedirectProxyController::class.java)

    /**
     * Handles redirect requests for STRM external URLs.
     * The path should contain the file ID and filename encoded in the URL.
     * Format: /strm-proxy/{fileId}/{filename}
     * 
     * @param fileId The ID of the file to redirect to
     * @param filename The filename (for compatibility, may include extension)
     * @param request The HTTP request
     * @return Redirect response to the active external URL
     */
    @GetMapping("/{fileId}/{filename}")
    fun redirectToExternalUrl(
        @PathVariable fileId: Long,
        @PathVariable filename: String,
        request: HttpServletRequest
    ): ResponseEntity<Void> {
        val proxyUrl = "${request.scheme}://${request.serverName}:${request.serverPort}${request.requestURI}"
        logger.debug("STRM proxy: Received request for proxy URL: $proxyUrl (fileId: $fileId, filename: $filename)")
        
        return try {
            // Load the file entity by ID
            val dbEntity = debridFileRepository.findById(fileId).orElse(null)
            val file = dbEntity as? RemotelyCachedEntity
            if (file == null) {
                logger.warn("STRM proxy: File not found for ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
            }

            // Reload to ensure contents are loaded
            val reloadedFile = fileService.reloadRemotelyCachedEntity(file)
            if (reloadedFile == null) {
                logger.warn("STRM proxy: Could not reload file for ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
            }

            val contents = reloadedFile.contents ?: run {
                logger.warn("STRM proxy: File has no contents for ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
            }

            // Determine the provider
            val provider = determineProvider(reloadedFile, contents)
            if (provider == null) {
                logger.warn("STRM proxy: Could not determine provider for file ID: $fileId")
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
            }

            // Check if proxy URLs are enabled for this provider (proxy URLs always refresh)
            val shouldUseProxy = debridavConfigurationProperties.shouldUseProxyUrlForStrm(provider)
            
            // Get the external URL (with refresh check if proxy is enabled)
            val externalUrl = if (shouldUseProxy && provider != DebridProvider.IPTV) {
                getExternalUrlWithRefresh(reloadedFile, contents, provider)
            } else {
                getExternalUrl(reloadedFile, contents)
            }

            if (externalUrl == null) {
                logger.warn("STRM proxy: No external URL available for file ID: $fileId")
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
            }

            logger.debug("STRM proxy: Final URL returned: $externalUrl (fileId: $fileId, provider: $provider, file: ${reloadedFile.name})")
            
            // Redirect to the external URL
            ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .header("Location", externalUrl)
                .build()
        } catch (e: Exception) {
            logger.error("STRM proxy: Error processing redirect for file ID: $fileId", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Determines the provider for a RemotelyCachedEntity.
     */
    private fun determineProvider(file: RemotelyCachedEntity, contents: io.skjaere.debridav.fs.DebridFileContents): DebridProvider? {
        // Check if it's IPTV content
        if (contents is DebridIptvContent) {
            return DebridProvider.IPTV
        }
        
        // Try to get provider from debridLinks
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        return cachedFile?.provider
    }

    /**
     * Gets the external URL from a RemotelyCachedEntity with refresh check.
     */
    private fun getExternalUrlWithRefresh(
        file: RemotelyCachedEntity,
        contents: io.skjaere.debridav.fs.DebridFileContents,
        provider: DebridProvider
    ): String? = runBlocking {
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        val originalUrl = cachedFile?.link
        if (originalUrl == null) {
            return@runBlocking null
        }

        logger.debug("STRM proxy: Initial URL from database: $originalUrl (fileId: ${file.id}, provider: $provider)")

        try {
            // Check if link is alive using the cache
            val cacheKey = io.skjaere.debridav.debrid.DebridLinkService.LinkLivenessCacheKey(
                provider.toString(),
                cachedFile
            )
            logger.debug("STRM proxy: Verifying URL is active: $originalUrl")
            val isAlive = debridLinkService.isLinkAliveCache.get(cacheKey)
            
            val finalUrl = if (!isAlive) {
                logger.info("STRM proxy: External URL expired for ${file.name} from $provider, refreshing...")
                // Refresh the link
                val refreshedLink = debridLinkService.refreshLinkOnError(file, cachedFile)
                if (refreshedLink != null) {
                    logger.info("STRM proxy: Successfully refreshed external URL for ${file.name} from $provider")
                    logger.debug("STRM proxy: Refreshed URL returned: ${refreshedLink.link}")
                    refreshedLink.link
                } else {
                    logger.warn("STRM proxy: Failed to refresh external URL for ${file.name} from $provider, using original URL")
                    logger.debug("STRM proxy: Using original URL (refresh failed): $originalUrl")
                    originalUrl
                }
            } else {
                // URL is still alive, use it
                logger.debug("STRM proxy: URL verified active, using original URL: $originalUrl")
                originalUrl
            }
            
            return@runBlocking finalUrl
        } catch (e: Exception) {
            logger.warn("STRM proxy: Error checking/refreshing external URL for ${file.name} from $provider: ${e.message}, using original URL", e)
            logger.debug("STRM proxy: Using original URL (error occurred): $originalUrl")
            originalUrl
        }
    }

    /**
     * Gets the external URL from a RemotelyCachedEntity without refresh check.
     */
    private fun getExternalUrl(
        file: RemotelyCachedEntity,
        contents: io.skjaere.debridav.fs.DebridFileContents
    ): String? {
        // Try to get a CachedFile link first (for debrid providers)
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        if (cachedFile?.link != null) {
            return cachedFile.link
        }
        
        // Try to get an IptvFile link (for IPTV content)
        if (contents is DebridIptvContent) {
            val iptvFile = contents.debridLinks.firstOrNull { it is io.skjaere.debridav.fs.IptvFile } as? io.skjaere.debridav.fs.IptvFile
            val tokenizedUrl = iptvFile?.link
            if (tokenizedUrl != null) {
                // Resolve IPTV template URL if needed
                return if (tokenizedUrl.startsWith("{IPTV_TEMPLATE_URL}")) {
                    try {
                        val template = contents.iptvUrlTemplate
                        if (template != null) {
                            tokenizedUrl.replace("{IPTV_TEMPLATE_URL}", template.baseUrl)
                        } else {
                            null
                        }
                    } catch (e: org.hibernate.LazyInitializationException) {
                        null
                    }
                } else {
                    tokenizedUrl
                }
            }
        }
        
        return null
    }
}

