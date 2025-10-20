package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.VirtualStrmFile
import java.io.OutputStream
import java.time.Instant
import java.util.*

class StrmFileResource(
    val strmFile: VirtualStrmFile,
    fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) : AbstractResource(fileService, strmFile), GetableResource, DeletableResource {

    override fun getUniqueId(): String {
        return strmFile.id!!.toString()
    }

    override fun getName(): String {
        return strmFile.name!!
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.ofEpochMilli(strmFile.lastModified!!))
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        // Virtual files cannot be deleted
    }

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        val strmContent = generateStrmContent()
        out.write(strmContent.toByteArray())
    }

    private fun generateStrmContent(): String {
        val mountPath = debridavConfigurationProperties.strmRootMountPath 
            ?: debridavConfigurationProperties.mountPath
        return "$mountPath${strmFile.originalPath}"
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String {
        return "text/plain"
    }

    override fun getContentLength(): Long {
        return generateStrmContent().toByteArray().size.toLong()
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return modifiedDate
    }
}
