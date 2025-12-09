package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
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

    private val strmContent: String = debridavConfigurationProperties.getStrmContentPath(originalFilePath)
    private val strmContentBytes: ByteArray = strmContent.toByteArray(Charsets.UTF_8)

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

