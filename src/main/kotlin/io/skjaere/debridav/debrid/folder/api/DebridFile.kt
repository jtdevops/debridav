package io.skjaere.debridav.debrid.folder.api

import java.time.Instant

data class DebridFile(
    val id: String, // Provider-specific file identifier/UUID
    val path: String, // Full path in provider
    val name: String, // File name
    val size: Long,
    val mimeType: String?,
    val downloadLink: String?,
    val lastModified: Instant?
)

data class DebridFolder(
    val id: String?,
    val path: String,
    val name: String
)
