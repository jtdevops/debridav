package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.configuration.HostnameDetectionService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.core.env.Environment
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Instant
import java.util.*

/**
 * Virtual STRM file resource that generates STRM file content dynamically.
 * STRM files are text files containing paths to the original media files.
 */
class StrmFileResource(
    private val originalFile: DbEntity,
    private val originalFilePath: String,
    fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val serverProperties: ServerProperties? = null,
    private val environment: Environment? = null,
    private val hostnameDetectionService: HostnameDetectionService? = null
) : AbstractResource(fileService, originalFile), GetableResource {

    private val logger = LoggerFactory.getLogger(StrmFileResource::class.java)

    // Compute content dynamically (proxy URLs are generated when refresh is enabled)
    private fun getStrmContent(): String = computeStrmContent()
    
    private fun getStrmContentBytes(): ByteArray = getStrmContent().toByteArray(Charsets.UTF_8)

    /**
     * Computes the content to write in the STRM file.
     * If external URL mode is enabled and available, returns the external URL.
     * Otherwise, returns the VFS path (with optional prefix).
     * 
     * Priority order:
     * 1. Proxy URLs (if DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS is enabled for provider)
     * 2. Direct external URLs (if DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS is enabled for provider)
     * 3. VFS path (default)
     */
    private fun computeStrmContent(): String {
        val fileName = originalFile.name ?: "unknown"
        val filePath = originalFilePath
        
        // Determine the provider if this is a RemotelyCachedEntity
        val provider = if (originalFile is RemotelyCachedEntity) {
            determineProvider(originalFile)
        } else {
            null
        }
        
        if (originalFile is RemotelyCachedEntity) {
            // Check if proxy URLs are enabled (takes priority - proxy URLs implicitly enable external URLs)
            val shouldUseProxy = provider != null && 
                provider != DebridProvider.IPTV &&
                debridavConfigurationProperties.shouldUseProxyUrlForStrm(provider)
            
            if (shouldUseProxy) {
                // Generate proxy URL (proxy URLs are external URLs, so this takes priority)
                val proxyUrl = generateProxyUrl(originalFile)
                if (proxyUrl != null) {
                    StrmFileAccessLogger.logContentComputed(filePath, provider?.toString(), "proxy", proxyUrl)
                    return proxyUrl
                }
            }
            
            // Check if we should use direct external URL (only if proxy is not enabled)
            if (debridavConfigurationProperties.shouldUseExternalUrlForStrm(provider)) {
                // Use direct external URL
                val externalUrl = getExternalUrl(originalFile)
                if (externalUrl != null) {
                    StrmFileAccessLogger.logContentComputed(filePath, provider?.toString(), "external", externalUrl)
                    return externalUrl
                }
            }
        }
        // Fall back to VFS path
        val vfsPath = debridavConfigurationProperties.getStrmContentPath(originalFilePath)
        StrmFileAccessLogger.logContentComputed(filePath, provider?.toString(), "VFS", vfsPath)
        return vfsPath
    }

    /**
     * Determines the provider for a RemotelyCachedEntity.
     * @param file The remotely cached entity
     * @return The provider, or null if unable to determine
     */
    private fun determineProvider(file: RemotelyCachedEntity): DebridProvider? {
        // Reload the entity to ensure contents are loaded
        val reloadedFile = fileService.reloadRemotelyCachedEntity(file) ?: return null
        val contents = reloadedFile.contents ?: return null
        
        // Check if it's IPTV content
        if (contents is DebridIptvContent) {
            return DebridProvider.IPTV
        }
        
        // Try to get provider from debridLinks
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        return cachedFile?.provider
    }

    /**
     * Gets the external URL from a RemotelyCachedEntity if available.
     * This returns the direct external URL without refresh checks (refresh is handled by proxy).
     * @param file The remotely cached entity
     * @return The external URL, or null if not available
     */
    private fun getExternalUrl(file: RemotelyCachedEntity): String? {
        // Reload the entity within a transaction to ensure contents and debridLinks are loaded
        val reloadedFile = fileService.reloadRemotelyCachedEntity(file) ?: return null
        val contents = reloadedFile.contents ?: return null
        
        // Try to get a CachedFile link first (for debrid providers)
        val cachedFile = contents.debridLinks.firstOrNull { it is CachedFile } as? CachedFile
        if (cachedFile?.link != null) {
            return cachedFile.link
        }
        
        // Try to get an IptvFile link (for IPTV content)
        if (contents is DebridIptvContent) {
            val iptvFile = contents.debridLinks.firstOrNull { it is IptvFile } as? IptvFile
            val tokenizedUrl = iptvFile?.link
            if (tokenizedUrl != null) {
                // Resolve IPTV template URL if needed
                return if (tokenizedUrl.startsWith("{IPTV_TEMPLATE_URL}")) {
                    try {
                        val template = contents.iptvUrlTemplate
                        if (template != null) {
                            // Access baseUrl - may throw LazyInitializationException if outside session
                            tokenizedUrl.replace("{IPTV_TEMPLATE_URL}", template.baseUrl)
                        } else {
                            // Template missing, return null to fall back to VFS path
                            null
                        }
                    } catch (e: org.hibernate.LazyInitializationException) {
                        // Template is lazy-loaded and we're outside a Hibernate session
                        // Fall back to VFS path
                        null
                    }
                } else {
                    // Full URL already stored
                    tokenizedUrl
                }
            }
        }
        
        return null
    }

    /**
     * Generates a proxy URL for the file that will redirect to the external URL after checking/refreshing it.
     * @param file The remotely cached entity
     * @return The proxy URL, or null if file ID or filename is not available
     */
    private fun generateProxyUrl(file: RemotelyCachedEntity): String? {
        val fileId = file.id ?: return null
        val fileName = file.name ?: return null
        
        // Get base URL from config or construct from detected hostname
        val baseUrl = debridavConfigurationProperties.strmProxyBaseUrl
            ?: run {
                // Get hostname from detection service (checks HOSTNAME env var first, then network detection)
                val hostname = hostnameDetectionService?.getHostname()
                    ?: throw IllegalStateException(
                        "STRM proxy requires either DEBRIDAV_STRM_PROXY_BASE_URL to be set or hostname detection to succeed. " +
                        "Hostname detection failed (checked HOSTNAME environment variable and network detection)."
                    )
                // Default port is 8080 (the server port)
                "http://$hostname:8080"
            }
        
        // Build proxy URL: {baseUrl}/strm-proxy/{fileId}/{filename}
        // URL encode the filename to handle special characters
        val encodedFileName = java.net.URLEncoder.encode(fileName, Charsets.UTF_8)
        return "$baseUrl/strm-proxy/$fileId/$encodedFileName"
    }

    override fun getUniqueId(): String {
        return "strm_${originalFile.id}"
    }

    override fun getName(): String {
        val originalFileName = originalFile.name ?: ""
        return debridavConfigurationProperties.getStrmFileName(originalFileName)
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.ofEpochMilli(originalFile.lastModified ?: System.currentTimeMillis()))
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        // Compute content dynamically (proxy URLs are generated when refresh is enabled)
        val strmContentBytes = getStrmContentBytes()
        val fileName = originalFile.name ?: "unknown"
        val filePath = originalFilePath
        
        if (range != null && range.start != null && range.finish != null) {
            // Handle range request
            val start = range.start.toInt().coerceAtLeast(0)
            val end = range.finish.toInt().coerceAtMost(strmContentBytes.size - 1)
            if (start <= end && start < strmContentBytes.size) {
                val contentLength = (end - start + 1).coerceAtMost(strmContentBytes.size - start)
                StrmFileAccessLogger.logContentSent(filePath, "$start-$end", contentLength)
                out.write(strmContentBytes, start, contentLength)
            }
        } else {
            // Write full content
            StrmFileAccessLogger.logContentSent(filePath, null, strmContentBytes.size)
            out.write(strmContentBytes)
        }
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String? {
        return "text/plain"
    }

    override fun getContentLength(): Long {
        val strmContentBytes = getStrmContentBytes()
        val length = strmContentBytes.size.toLong()
        val fileName = originalFile.name ?: "unknown"
        val filePath = originalFilePath
        StrmFileAccessLogger.logContentLengthQueried(filePath, length)
        return length
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli(originalFile.lastModified ?: System.currentTimeMillis()))
    }
}

