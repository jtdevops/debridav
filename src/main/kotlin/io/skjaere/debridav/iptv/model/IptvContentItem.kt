package io.skjaere.debridav.iptv.model

data class IptvContentItem(
    val id: String,
    val title: String,
    val url: String, // tokenized URL
    val category: String,
    val type: ContentType,
    val episodeInfo: EpisodeInfo? = null
)

