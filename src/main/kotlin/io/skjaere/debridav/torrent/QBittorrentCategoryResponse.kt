package io.skjaere.debridav.torrent

/**
 * qBittorrent WebUI shape for one category under `/api/v2/torrents/categories`.
 * Real qBittorrent uses camelCase `savePath` (not `downloadPath` / `id`).
 */
data class QBittorrentCategoryResponse(
    val name: String,
    val savePath: String = ""
)
