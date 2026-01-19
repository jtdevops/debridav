package io.skjaere.debridav.debrid.folder

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "debrid_synced_file",
    indexes = [
        Index(name = "idx_debrid_synced_file_folder_mapping_id", columnList = "folder_mapping_id"),
        Index(name = "idx_debrid_synced_file_provider_file_id", columnList = "provider_file_id"),
        Index(name = "idx_debrid_synced_file_vfs_path", columnList = "vfs_path"),
        Index(name = "idx_debrid_synced_file_is_deleted", columnList = "is_deleted")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "unique_synced_file",
            columnNames = ["folder_mapping_id", "provider_file_id"]
        )
    ]
)
class DebridSyncedFileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne
    @JoinColumn(
        name = "folder_mapping_id",
        nullable = false,
        foreignKey = ForeignKey(name = "fk_debrid_synced_file_folder_mapping")
    )
    var folderMapping: DebridFolderMappingEntity? = null

    @Column(name = "provider_file_id", nullable = false, length = 512)
    var providerFileId: String? = null

    @Column(name = "provider_file_path", nullable = false, length = 2048)
    var providerFilePath: String? = null

    @Column(name = "vfs_path", nullable = false, length = 2048)
    var vfsPath: String? = null

    @Column(name = "vfs_file_name", length = 512)
    var vfsFileName: String? = null

    @Column(name = "file_size")
    var fileSize: Long? = null

    @Column(name = "mime_type", length = 255)
    var mimeType: String? = null

    @Column(name = "provider_link", length = 2048)
    var providerLink: String? = null

    @Column(name = "last_checked")
    var lastChecked: Instant? = null

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
