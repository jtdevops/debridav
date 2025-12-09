package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
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
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) : AbstractResource(fileService, originalFile), GetableResource {

    // Lazy initialization to avoid Hibernate LazyInitializationException during directory listing
    private val strmContent: String by lazy { computeStrmContent() }
    private val strmContentBytes: ByteArray by lazy { strmContent.toByteArray(Charsets.UTF_8) }

    /**
     * Computes the content to write in the STRM file.
     * If external URL mode is enabled and available, returns the external URL.
     * Otherwise, returns the VFS path (with optional prefix).
     */
    private fun computeStrmContent(): String {
        if (originalFile is RemotelyCachedEntity) {
            // Determine the provider for provider-specific configuration checks
            val provider = determineProvider(originalFile)
            
            // Check if we should use external URL (with provider-specific check if configured)
            if (debridavConfigurationProperties.shouldUseExternalUrlForStrm(provider)) {
                val externalUrl = getExternalUrl(originalFile)
                if (externalUrl != null) {
                    return externalUrl
                }
            }
        }
        // Fall back to VFS path
        return debridavConfigurationProperties.getStrmContentPath(originalFilePath)
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
        if (range != null && range.start != null && range.finish != null) {
            // Handle range request
            val start = range.start.toInt().coerceAtLeast(0)
            val end = range.finish.toInt().coerceAtMost(strmContentBytes.size - 1)
            if (start <= end && start < strmContentBytes.size) {
                out.write(strmContentBytes, start, (end - start + 1).coerceAtMost(strmContentBytes.size - start))
            }
        } else {
            // Write full content
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
        return strmContentBytes.size.toLong()
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli(originalFile.lastModified ?: System.currentTimeMillis()))
    }
}

