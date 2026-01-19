package io.skjaere.debridav.debrid.folder

import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "debridav.debrid-folder-mapping")
data class DebridFolderMappingProperties(
    val enabled: Boolean = false,
    val providers: String? = null, // Comma-separated list
    val mappings: String? = null, // Comma-separated list of mappings
    val syncInterval: Duration = Duration.ofHours(1)
) {
    fun getProvidersList(): List<DebridProvider> {
        if (providers.isNullOrBlank()) {
            return emptyList()
        }
        return providers.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { providerStr ->
                try {
                    when (providerStr.lowercase()) {
                        "premiumize" -> DebridProvider.PREMIUMIZE
                        "real_debrid", "realdebrid" -> DebridProvider.REAL_DEBRID
                        "torbox" -> DebridProvider.TORBOX
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
            }
    }

    fun getMappingsList(): List<DebridFolderMapping> {
        return parseMappings(mappings)
    }
    companion object {
        fun parseMappings(mappingsString: String?): List<DebridFolderMapping> {
            if (mappingsString.isNullOrBlank()) {
                return emptyList()
            }

            return mappingsString.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { mappingStr ->
                    try {
                        parseMapping(mappingStr)
                    } catch (e: Exception) {
                        null
                    }
                }
        }

        private fun parseMapping(mappingStr: String): DebridFolderMapping {
            // Format: provider:externalPath=internalPath:method
            // Example: premiumize:/Downloads/TV=/pm_tv:webdav

            val parts = mappingStr.split(":")
            require(parts.size >= 3) {
                "Invalid mapping format. Expected: provider:externalPath=internalPath:method"
            }

            val providerStr = parts[0].trim()
            val provider = try {
                when (providerStr.lowercase()) {
                    "premiumize" -> DebridProvider.PREMIUMIZE
                    "real_debrid", "realdebrid" -> DebridProvider.REAL_DEBRID
                    "torbox" -> DebridProvider.TORBOX
                    else -> throw IllegalArgumentException("Unknown provider: $providerStr")
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid provider: $providerStr", e)
            }

            // Reconstruct the path part (may contain colons)
            val pathAndMethod = parts.drop(1).joinToString(":")
            val pathMethodParts = pathAndMethod.split("=")
            require(pathMethodParts.size == 2) {
                "Invalid mapping format. Expected: provider:externalPath=internalPath:method"
            }

            val externalPath = pathMethodParts[0].trim()
            val internalPathAndMethod = pathMethodParts[1].trim()

            // Split internal path and method
            val lastColonIndex = internalPathAndMethod.lastIndexOf(":")
            require(lastColonIndex > 0) {
                "Method is required. Format: provider:externalPath=internalPath:method"
            }

            val internalPath = internalPathAndMethod.substring(0, lastColonIndex).trim()
            val methodStr = internalPathAndMethod.substring(lastColonIndex + 1).trim()

            val syncMethod = when (methodStr.lowercase()) {
                "webdav" -> SyncMethod.WEBDAV
                "api" -> SyncMethod.API_SYNC
                else -> throw IllegalArgumentException("Invalid sync method: $methodStr. Must be 'webdav' or 'api'")
            }

            return DebridFolderMapping(
                provider = provider,
                externalPath = externalPath,
                internalPath = internalPath,
                syncMethod = syncMethod
            )
        }
    }
}
