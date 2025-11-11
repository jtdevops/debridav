package io.skjaere.debridav.iptv.configuration

import io.skjaere.debridav.iptv.IptvProvider
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

@Service
class IptvConfigurationService(
    private val environment: Environment,
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    fun getProviderConfigurations(): List<IptvProviderConfiguration> {
        if (!iptvConfigurationProperties.enabled || iptvConfigurationProperties.providers.isEmpty()) {
            return emptyList()
        }

        return iptvConfigurationProperties.providers.mapNotNull { providerName ->
            val typeStr = environment.getProperty("iptv.provider.$providerName.type") ?: return@mapNotNull null
            val type = try {
                IptvProvider.valueOf(typeStr.uppercase())
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }

            val priority = environment.getProperty("iptv.provider.$providerName.priority", Int::class.java, 1)
            val syncEnabled = environment.getProperty("iptv.provider.$providerName.sync-enabled", Boolean::class.java, true)
            val useLocalResponses = environment.getProperty("iptv.provider.$providerName.use-local-responses", Boolean::class.java)

            when (type) {
                IptvProvider.M3U -> {
                    val m3uUrl = environment.getProperty("iptv.provider.$providerName.m3u-url")
                    val m3uFilePath = environment.getProperty("iptv.provider.$providerName.m3u-file-path")
                    if (m3uUrl == null && m3uFilePath == null) {
                        return@mapNotNull null
                    }
                    IptvProviderConfiguration(
                        name = providerName,
                        type = type,
                        m3uUrl = m3uUrl,
                        m3uFilePath = m3uFilePath,
                        priority = priority,
                        syncEnabled = syncEnabled,
                        useLocalResponses = useLocalResponses
                    )
                }
                IptvProvider.XTREAM_CODES -> {
                    val baseUrl = environment.getProperty("iptv.provider.$providerName.xtream-base-url")
                    val username = environment.getProperty("iptv.provider.$providerName.xtream-username")
                    val password = environment.getProperty("iptv.provider.$providerName.xtream-password")
                    if (baseUrl == null || username == null || password == null) {
                        return@mapNotNull null
                    }
                    IptvProviderConfiguration(
                        name = providerName,
                        type = type,
                        xtreamBaseUrl = baseUrl,
                        xtreamUsername = username,
                        xtreamPassword = password,
                        priority = priority,
                        syncEnabled = syncEnabled,
                        useLocalResponses = useLocalResponses
                    )
                }
            }
        }.sortedBy { it.priority }
    }
}

