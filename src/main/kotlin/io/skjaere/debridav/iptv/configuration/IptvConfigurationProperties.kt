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
    val searchFuzziness: Double = 0.8,
    val responseSaveFolder: String? = null,
    val useLocalResponses: Boolean = false,
    val seriesMetadataCacheTtl: Duration = Duration.ofDays(7), // Default: 7 days
    val seriesMetadataPurgeInterval: Duration = Duration.ofHours(24), // Default: purge check every 24 hours
    val languagePrefixes: List<String> = emptyList(), // Language prefixes to try when searching (e.g., "EN - ", "EN| ", "EN | ")
    val userAgent: String = "TiviMate/5.0.3", // User-Agent string for IPTV media requests (default: TiviMate)
    val includeProviderInMagnetTitle: Boolean = false, // Include provider name in magnet title after -IPTV (e.g., -IPTV-mega-NL) for debugging
    val maxSearchResults: Int = 0, // Maximum number of search results to return (0 = unlimited, default: unlimited)
    val redirectHandlingMode: RedirectHandlingMode = RedirectHandlingMode.AUTOMATIC, // How to handle HTTP redirects: AUTOMATIC (use Ktor's HttpRedirect plugin) or MANUAL (preserve Range headers)
    val loginRateLimit: Duration = Duration.ofMinutes(1) // Rate limit for IPTV provider login/credential verification calls (default: 1 minute)
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

