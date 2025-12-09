package io.skjaere.debridav.iptv.metadata

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "iptv.metadata")
data class MetadataConfigurationProperties(
    /**
     * OMDB API key (optional). Get a free key from http://www.omdbapi.com/apikey.aspx
     * If not provided, IMDB ID lookups will be skipped and the system will fall back to query string searches.
     */
    val omdbApiKey: String = "",
    
    /**
     * OMDB API base URL
     */
    val omdbBaseUrl: String = "https://www.omdbapi.com"
)

