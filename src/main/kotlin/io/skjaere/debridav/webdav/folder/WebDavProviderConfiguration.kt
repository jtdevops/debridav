package io.skjaere.debridav.webdav.folder

import java.time.Duration

/**
 * Authentication type for WebDAV providers
 */
enum class WebDavAuthType {
    BASIC,      // Basic auth with username/password
    BEARER      // Bearer token auth
}

/**
 * Configuration for a WebDAV provider (built-in or custom)
 */
data class WebDavProviderConfiguration(
    val name: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
    val authType: WebDavAuthType = WebDavAuthType.BASIC,
    val syncInterval: Duration? = null,
    val isBuiltIn: Boolean = false
) {
    init {
        require(url.isNotBlank()) {
            "WebDAV provider $name must have a URL configured"
        }
        when (authType) {
            WebDavAuthType.BASIC -> require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
                "WebDAV provider $name (Basic auth) must have username and password configured"
            }
            WebDavAuthType.BEARER -> require(!bearerToken.isNullOrBlank()) {
                "WebDAV provider $name (Bearer auth) must have bearer token configured"
            }
        }
    }
    
    /**
     * Check if this provider has credentials configured
     */
    fun hasCredentials(): Boolean {
        return when (authType) {
            WebDavAuthType.BASIC -> !username.isNullOrBlank() && !password.isNullOrBlank()
            WebDavAuthType.BEARER -> !bearerToken.isNullOrBlank()
        }
    }
}
