package io.skjaere.debridav.iptv.util

/**
 * Chooses the clean show/movie name used in IPTV magnet / Torznab titles.
 *
 * Prefer OMDB ([preferredDisplayTitle]), then the Arr search query, and only then
 * the raw IPTV catalog title (which often includes country tags like "(US)" that
 * break HolyArrs / Arr title matching).
 */
object IptvMagnetTitle {
    private val IMDB_ID = Regex("^tt\\d{7,8}$", RegexOption.IGNORE_CASE)

    fun resolveBaseTitle(
        preferredDisplayTitle: String?,
        searchTitle: String?,
        iptvCatalogTitle: String,
    ): String {
        preferredDisplayTitle?.takeIf { it.isNotBlank() }?.let { return it }
        searchTitle
            ?.takeIf { it.isNotBlank() && !IMDB_ID.matches(it.trim()) }
            ?.let { return it }
        return iptvCatalogTitle
    }
}
