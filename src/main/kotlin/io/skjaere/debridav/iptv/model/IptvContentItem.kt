package io.skjaere.debridav.iptv.model

data class IptvContentItem(
    val id: String,
    val title: String,
    val url: String, // tokenized URL
    val categoryId: String?, // Category ID from provider (null if no category)
    val categoryType: String?, // "vod" or "series" (null if no category)
    val type: ContentType,
    val episodeInfo: EpisodeInfo? = null
)

