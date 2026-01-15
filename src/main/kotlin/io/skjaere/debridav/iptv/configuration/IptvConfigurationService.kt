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
            val liveSyncEnabled = environment.getProperty("iptv.provider.$providerName.live.sync-enabled", Boolean::class.java, false)
            val liveChannelExtension = environment.getProperty("iptv.provider.$providerName.live.channel-extension", "ts")

            // Load live filtering configuration
            val liveDbCategoryExclude = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.db.category.exclude")
            val liveDbCategoryExcludeIndex = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.db.category.exclude-index")
            val liveDbChannelExclude = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.db.channel.exclude")
            val liveDbChannelExcludeIndex = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.db.channel.exclude-index")
            
            val liveCategoryInclude = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.category.include")
            val liveCategoryIncludeIndex = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.category.include-index")
            val liveCategoryExclude = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.category.exclude")
            val liveCategoryExcludeIndex = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.category.exclude-index")
            val liveChannelInclude = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.channel.include")
            val liveChannelIncludeIndex = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.channel.include-index")
            val liveChannelExclude = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.channel.exclude")
            val liveChannelExcludeIndex = combineIndexedAndCommaSeparated(environment, "iptv.provider.$providerName.live.channel.exclude-index")

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
                        useLocalResponses = useLocalResponses,
                        liveSyncEnabled = liveSyncEnabled,
                        liveChannelExtension = liveChannelExtension,
                        liveDbCategoryExclude = liveDbCategoryExclude,
                        liveDbCategoryExcludeIndex = liveDbCategoryExcludeIndex,
                        liveDbChannelExclude = liveDbChannelExclude,
                        liveDbChannelExcludeIndex = liveDbChannelExcludeIndex,
                        liveCategoryInclude = liveCategoryInclude,
                        liveCategoryIncludeIndex = liveCategoryIncludeIndex,
                        liveCategoryExclude = liveCategoryExclude,
                        liveCategoryExcludeIndex = liveCategoryExcludeIndex,
                        liveChannelInclude = liveChannelInclude,
                        liveChannelIncludeIndex = liveChannelIncludeIndex,
                        liveChannelExclude = liveChannelExclude,
                        liveChannelExcludeIndex = liveChannelExcludeIndex
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
                        useLocalResponses = useLocalResponses,
                        liveSyncEnabled = liveSyncEnabled,
                        liveChannelExtension = liveChannelExtension,
                        liveDbCategoryExclude = liveDbCategoryExclude,
                        liveDbCategoryExcludeIndex = liveDbCategoryExcludeIndex,
                        liveDbChannelExclude = liveDbChannelExclude,
                        liveDbChannelExcludeIndex = liveDbChannelExcludeIndex,
                        liveCategoryInclude = liveCategoryInclude,
                        liveCategoryIncludeIndex = liveCategoryIncludeIndex,
                        liveCategoryExclude = liveCategoryExclude,
                        liveCategoryExcludeIndex = liveCategoryExcludeIndex,
                        liveChannelInclude = liveChannelInclude,
                        liveChannelIncludeIndex = liveChannelIncludeIndex,
                        liveChannelExclude = liveChannelExclude,
                        liveChannelExcludeIndex = liveChannelExcludeIndex
                    )
                }
            }
        }.sortedBy { it.priority }
    }

    /**
     * Combines indexed and comma-separated list properties.
     * Indexed entries are added first (to preserve order), followed by comma-separated entries.
     * Similar to how languagePrefixes work.
     */
    private fun combineIndexedAndCommaSeparated(environment: Environment, baseProperty: String): List<String>? {
        val combined = mutableListOf<String>()
        
        // Load indexed properties (e.g., property-index[0], property-index[1], etc.)
        var index = 0
        while (true) {
            val indexedValue = environment.getProperty("$baseProperty[$index]")
            if (indexedValue == null) {
                break
            }
            combined.add(indexedValue)
            index++
        }
        
        // Load comma-separated property
        val commaSeparated = environment.getProperty(baseProperty)
        if (commaSeparated != null) {
            // Split by comma and add each item
            commaSeparated.split(",").forEach { item ->
                val trimmed = item.trim()
                if (trimmed.isNotEmpty()) {
                    combined.add(trimmed)
                }
            }
        }
        
        return if (combined.isEmpty()) null else combined
    }
}

