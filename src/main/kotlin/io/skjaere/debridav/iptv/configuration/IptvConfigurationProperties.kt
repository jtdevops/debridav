package io.skjaere.debridav.iptv.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "iptv")
data class IptvConfigurationProperties(
    val enabled: Boolean = false,
    val syncInterval: Duration = Duration.ofHours(24),
    val initialSyncDelay: Duration = Duration.ofSeconds(30),
    val providers: List<String> = emptyList(),
    val filterVodOnly: Boolean = true,
    val searchFuzziness: Double = 0.8
) {
    init {
        // Validate searchFuzziness regardless of enabled state
        require(searchFuzziness in 0.0..1.0) {
            "IPTV search fuzziness must be between 0.0 and 1.0"
        }
        // Note: It's valid to have IPTV enabled with no providers - 
        // the sync service will simply skip processing in that case
    }
}

