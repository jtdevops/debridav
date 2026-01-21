package io.skjaere.debridav.webdav.folder

import java.time.Duration

data class WebDavFolderMapping(
    val providerName: String,
    val externalPath: String,
    val internalPath: String,
    val syncInterval: Duration? = null
) {
    init {
        require(internalPath.startsWith("/")) {
            "Internal path must start with '/'"
        }
        require(externalPath.isNotBlank()) {
            "External path cannot be blank"
        }
    }
    
    /**
     * Check if this is a built-in provider
     */
    fun isBuiltInProvider(): Boolean {
        val normalized = providerName.lowercase().trim()
        return normalized in setOf("premiumize", "real_debrid", "realdebrid", "torbox")
    }
}
