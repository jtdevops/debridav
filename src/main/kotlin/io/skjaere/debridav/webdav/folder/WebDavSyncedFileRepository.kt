package io.skjaere.debridav.webdav.folder

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface WebDavSyncedFileRepository : JpaRepository<WebDavSyncedFileEntity, Long> {
    fun findByFolderMapping(folderMapping: WebDavFolderMappingEntity): List<WebDavSyncedFileEntity>
    fun findByFolderMappingAndIsDeleted(
        folderMapping: WebDavFolderMappingEntity,
        isDeleted: Boolean
    ): List<WebDavSyncedFileEntity>
    fun findByFolderMappingAndProviderFileId(
        folderMapping: WebDavFolderMappingEntity,
        providerFileId: String
    ): WebDavSyncedFileEntity?
    fun findByVfsPath(vfsPath: String): List<WebDavSyncedFileEntity>
    fun findByVfsPathAndIsDeleted(vfsPath: String, isDeleted: Boolean): List<WebDavSyncedFileEntity>

    @Modifying
    @Transactional
    @Query("UPDATE WebDavSyncedFileEntity e SET e.isDeleted = true WHERE e.folderMapping = :folderMapping AND e.providerFileId NOT IN :providerFileIds")
    fun markAsDeletedForMissingFiles(
        @Param("folderMapping") folderMapping: WebDavFolderMappingEntity,
        @Param("providerFileIds") providerFileIds: List<String>
    )
}
