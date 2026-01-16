package io.skjaere.debridav.iptv

import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity
import java.time.Instant

/**
 * Virtual directory entity for live channels that doesn't persist to database.
 * Represents /live/{provider} or /live/{provider}/{category} directories.
 */
class VirtualLiveDirectory(
    val directoryPath: String,
    val directoryName: String
) : DbEntity() {
    init {
        this.name = directoryName
        this.lastModified = Instant.now().toEpochMilli()
        this.size = null
        this.mimeType = null
        this.directory = null // Will be set when needed
    }
    
    /**
     * Returns the file system path for this directory
     */
    fun fileSystemPath(): String = directoryPath
}

/**
 * Virtual file entity for live channels that wraps a RemotelyCachedEntity.
 * The RemotelyCachedEntity is created on-demand when the file is accessed.
 * This class doesn't extend RemotelyCachedEntity to avoid JPA entity issues.
 */
class VirtualLiveFile(
    val filePath: String,
    val fileName: String,
    private val createEntity: () -> RemotelyCachedEntity
) {
    private var _entity: RemotelyCachedEntity? = null
    
    /**
     * Gets or creates the underlying RemotelyCachedEntity
     */
    fun getEntity(): RemotelyCachedEntity {
        if (_entity == null) {
            _entity = createEntity()
        }
        return _entity!!
    }
    
    /**
     * Returns the file system path for this file
     */
    fun fileSystemPath(): String = filePath
}
