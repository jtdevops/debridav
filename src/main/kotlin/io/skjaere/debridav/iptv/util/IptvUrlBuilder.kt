package io.skjaere.debridav.iptv.util

import io.skjaere.debridav.iptv.client.XtreamCodesClient
import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration

/**
 * Utility functions for building IPTV URLs.
 */
object IptvUrlBuilder {
    /**
     * Builds episode URL for Xtream Codes provider.
     * Format: {baseUrl}/series/{username}/{password}/{episode_id}.{extension}
     */
    fun buildEpisodeUrl(
        providerConfig: IptvProviderConfiguration,
        episode: XtreamCodesClient.XtreamSeriesEpisode
    ): String? {
        val baseUrl = providerConfig.xtreamBaseUrl ?: return null
        val username = providerConfig.xtreamUsername ?: return null
        val password = providerConfig.xtreamPassword ?: return null
        val extension = episode.container_extension ?: "mp4"
        return "$baseUrl/series/$username/$password/${episode.id}.$extension"
    }
    
    /**
     * Builds movie URL for Xtream Codes provider.
     * Format: {baseUrl}/movie/{username}/{password}/{vodId}.{extension}
     * 
     * @param providerConfig The IPTV provider configuration
     * @param vodId The movie VOD ID
     * @param extension Optional file extension (e.g., "mp4", "mkv", "avi"). If not provided, defaults to "mp4".
     *                 Unlike episodes which have container_extension in their metadata, movies don't have this field,
     *                 so the extension must be determined from the URL or defaulted.
     */
    fun buildMovieUrl(
        providerConfig: IptvProviderConfiguration,
        vodId: String,
        extension: String? = null
    ): String? {
        val baseUrl = providerConfig.xtreamBaseUrl ?: return null
        val username = providerConfig.xtreamUsername ?: return null
        val password = providerConfig.xtreamPassword ?: return null
        val fileExtension = extension ?: "mp4" // Default to mp4 if not provided
        return "$baseUrl/movie/$username/$password/$vodId.$fileExtension"
    }
    
    /**
     * Extracts the media file extension from an IPTV URL.
     * URLs are typically in format: {BASE_URL}/movie/{USERNAME}/{PASSWORD}/401804493.mkv
     * or: {BASE_URL}/series/{USERNAME}/{PASSWORD}/12345.mp4
     * 
     * @param url The resolved IPTV URL
     * @return The file extension (without the dot), or null if not found
     */
    fun extractMediaExtensionFromUrl(url: String): String? {
        val urlWithoutQuery = url.substringBefore("?")
        val lastDotIndex = urlWithoutQuery.lastIndexOf(".")
        if (lastDotIndex == -1 || lastDotIndex == urlWithoutQuery.length - 1) {
            return null
        }
        
        val extension = urlWithoutQuery.substring(lastDotIndex + 1)
        // Validate it's a reasonable extension (alphanumeric, 2-5 chars)
        return if (extension.matches(Regex("[a-zA-Z0-9]{2,5}"))) {
            extension.lowercase()
        } else {
            null
        }
    }
}
