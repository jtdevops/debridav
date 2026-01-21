package io.skjaere.debridav.webdav.folder

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "debridav.webdav-folder-mapping")
data class WebDavFolderMappingProperties(
    val enabled: Boolean = false,
    val providers: String? = null, // Comma-separated list
    val mappings: String? = null, // Comma-separated list of mappings
    val syncInterval: Duration = Duration.ofHours(1),
    // Comma-separated list of file extensions to sync (e.g., "mkv,mp4,avi,srt,sub")
    // If empty, syncs all files
    val allowedExtensions: String = "mkv,mp4,avi,mov,m4v,mpg,mpeg,wmv,flv,webm,ts,m2ts,srt,sub,ass,ssa,vtt,idx,sup",
    // Comma-separated list of provider names to enable root folder logging for
    // Example: "premiumize,real_debrid,mywebdav"
    val logRootFolders: String? = null
) {
    private val allowedExtensionsList: Set<String> by lazy {
        if (allowedExtensions.isBlank()) {
            emptySet()
        } else {
            allowedExtensions.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    /**
     * Check if a file should be synced based on its extension
     */
    fun shouldSyncFile(fileName: String): Boolean {
        if (allowedExtensionsList.isEmpty()) {
            return true // Sync all files if no extensions specified
        }
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return extension in allowedExtensionsList
    }

    /**
     * Get list of provider names (as strings, not DebridProvider enum)
     */
    fun getProvidersList(): List<String> {
        if (providers.isNullOrBlank()) {
            return emptyList()
        }
        return providers.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Get list of provider names that should have root folder logging enabled
     */
    fun getLogRootFoldersList(): List<String> {
        if (logRootFolders.isNullOrBlank()) {
            return emptyList()
        }
        return logRootFolders.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun getMappingsList(): List<WebDavFolderMapping> {
        return parseMappings(mappings)
    }
    
    companion object {
        fun parseMappings(mappingsString: String?): List<WebDavFolderMapping> {
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

        private fun parseMapping(mappingStr: String): WebDavFolderMapping {
            // Format: provider:externalPath=internalPath
            // Example: premiumize:/Downloads/TV=/pm_tv
            // Example: mywebdav:/media=/custom_media

            val parts = mappingStr.split(":")
            require(parts.size >= 2) {
                "Invalid mapping format. Expected: provider:externalPath=internalPath"
            }

            val providerName = parts[0].trim()
            require(providerName.isNotBlank()) {
                "Provider name cannot be blank"
            }

            // Reconstruct the path part (may contain colons)
            val pathPart = parts.drop(1).joinToString(":")
            val pathParts = pathPart.split("=")
            require(pathParts.size == 2) {
                "Invalid mapping format. Expected: provider:externalPath=internalPath"
            }

            val externalPath = pathParts[0].trim()
            val internalPath = pathParts[1].trim()

            require(externalPath.isNotBlank()) {
                "External path cannot be blank"
            }
            require(internalPath.startsWith("/")) {
                "Internal path must start with '/'"
            }

            return WebDavFolderMapping(
                providerName = providerName,
                externalPath = externalPath,
                internalPath = internalPath
            )
        }
    }
}
