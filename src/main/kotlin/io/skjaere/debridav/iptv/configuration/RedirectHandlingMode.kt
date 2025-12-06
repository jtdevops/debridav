package io.skjaere.debridav.iptv.configuration

/**
 * Enumeration for redirect handling modes.
 * 
 * - AUTOMATIC: Use Ktor's HttpRedirect plugin to automatically follow redirects (default behavior)
 * - MANUAL: Manually handle redirects to ensure Range headers are preserved
 */
enum class RedirectHandlingMode {
    AUTOMATIC,
    MANUAL
}

