package io.skjaere.debridav.debrid.folder

import io.skjaere.debridav.debrid.DebridProvider
import java.time.Duration

data class DebridFolderMapping(
    val provider: DebridProvider,
    val externalPath: String,
    val internalPath: String,
    val syncMethod: SyncMethod,
    val syncInterval: Duration? = null
) {
    init {
        require(internalPath.startsWith("/")) {
            "Internal path must start with '/'"
        }
        require(externalPath.isNotBlank()) {
            "External path cannot be blank"
        }
    }
}
