package io.skjaere.debridav.debrid.folder

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface DebridSyncedFileRepository : JpaRepository<DebridSyncedFileEntity, Long> {
    fun findByFolderMapping(folderMapping: DebridFolderMappingEntity): List<DebridSyncedFileEntity>
    fun findByFolderMappingAndIsDeleted(
        folderMapping: DebridFolderMappingEntity,
        isDeleted: Boolean
    ): List<DebridSyncedFileEntity>
    fun findByFolderMappingAndProviderFileId(
        folderMapping: DebridFolderMappingEntity,
        providerFileId: String
    ): DebridSyncedFileEntity?
    fun findByVfsPath(vfsPath: String): List<DebridSyncedFileEntity>
    fun findByVfsPathAndIsDeleted(vfsPath: String, isDeleted: Boolean): List<DebridSyncedFileEntity>

    @Modifying
    @Transactional
    @Query("UPDATE DebridSyncedFileEntity e SET e.isDeleted = true WHERE e.folderMapping = :folderMapping AND e.providerFileId NOT IN :providerFileIds")
    fun markAsDeletedForMissingFiles(
        @Param("folderMapping") folderMapping: DebridFolderMappingEntity,
        @Param("providerFileIds") providerFileIds: List<String>
    )
}
