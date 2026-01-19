package io.skjaere.debridav.debrid.folder

import io.skjaere.debridav.debrid.DebridProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "debrid_folder_mapping",
    indexes = [
        Index(name = "idx_debrid_folder_mapping_provider", columnList = "provider"),
        Index(name = "idx_debrid_folder_mapping_enabled", columnList = "enabled"),
        Index(name = "idx_debrid_folder_mapping_internal_path", columnList = "internal_path")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "unique_mapping",
            columnNames = ["provider", "external_path", "internal_path"]
        )
    ]
)
class DebridFolderMappingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 50)
    var provider: DebridProvider? = null

    @Column(name = "external_path", nullable = false, length = 2048)
    var externalPath: String? = null

    @Column(name = "internal_path", nullable = false, length = 2048)
    var internalPath: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_method", nullable = false, length = 20)
    var syncMethod: SyncMethod? = null

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
