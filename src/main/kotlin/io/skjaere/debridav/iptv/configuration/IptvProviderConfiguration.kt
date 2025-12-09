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
    val useLocalResponses: Boolean? = null
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

