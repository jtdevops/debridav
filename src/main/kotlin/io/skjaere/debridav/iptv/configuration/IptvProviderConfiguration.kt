package io.skjaere.debridav.iptv.configuration

import io.skjaere.debridav.iptv.IptvProvider

data class IptvProviderConfiguration(
    val name: String,
    val type: IptvProvider,
    val m3uUrl: String? = null,
    val m3uFilePath: String? = null,
    val xtreamBaseUrl: String? = null,
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    val priority: Int = 1,
    val syncEnabled: Boolean = true,
    val useLocalResponses: Boolean? = null,
    val liveSyncEnabled: Boolean = false, // Per-provider live sync toggle (default: false, opt-in per provider)
    val liveChannelExtension: String = "ts", // File extension for live channels (default: "ts", can be "m3u8", "ts", etc.)
    // Database filtering (exclude only - controls what gets synced to database)
    val liveDbCategoryExclude: List<String>? = null,
    val liveDbCategoryExcludeIndex: List<String>? = null,
    val liveDbChannelExclude: List<String>? = null,
    val liveDbChannelExcludeIndex: List<String>? = null,
    // VFS filtering (include/exclude - controls what appears in /live folder from database)
    val liveCategoryInclude: List<String>? = null,
    val liveCategoryIncludeIndex: List<String>? = null,
    val liveCategoryExclude: List<String>? = null,
    val liveCategoryExcludeIndex: List<String>? = null,
    val liveChannelInclude: List<String>? = null,
    val liveChannelIncludeIndex: List<String>? = null,
    val liveChannelExclude: List<String>? = null,
    val liveChannelExcludeIndex: List<String>? = null
) {
    init {
        when (type) {
            IptvProvider.M3U -> {
                require(m3uUrl != null || m3uFilePath != null) {
                    "IPTV provider $name (M3U) must have either m3u-url or m3u-file-path configured"
                }
            }
            IptvProvider.XTREAM_CODES -> {
                require(xtreamBaseUrl != null && xtreamUsername != null && xtreamPassword != null) {
                    "IPTV provider $name (Xtream Codes) must have xtream-base-url, xtream-username, and xtream-password configured"
                }
            }
        }
    }
}

