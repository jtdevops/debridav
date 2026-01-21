package io.skjaere.debridav.webdav.folder.webdav

import java.time.Instant

data class WebDavFile(
    val path: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val isDirectory: Boolean,
    val lastModified: Instant?,
    val downloadLink: String?
)
