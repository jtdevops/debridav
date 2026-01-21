package io.skjaere.debridav.webdav.folder

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "webdav_folder_mapping",
    indexes = [
        Index(name = "idx_webdav_folder_mapping_provider_name", columnList = "provider_name"),
        Index(name = "idx_webdav_folder_mapping_enabled", columnList = "enabled"),
        Index(name = "idx_webdav_folder_mapping_internal_path", columnList = "internal_path")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "unique_mapping",
            columnNames = ["provider_name", "external_path", "internal_path"]
        )
    ]
)
class WebDavFolderMappingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "provider_name", nullable = false, length = 100)
    var providerName: String? = null

    @Column(name = "external_path", nullable = false, length = 2048)
    var externalPath: String? = null

    @Column(name = "internal_path", nullable = false, length = 2048)
    var internalPath: String? = null

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    @Column(name = "last_synced")
    var lastSynced: Instant? = null

    @Column(name = "sync_interval", length = 50)
    var syncInterval: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
