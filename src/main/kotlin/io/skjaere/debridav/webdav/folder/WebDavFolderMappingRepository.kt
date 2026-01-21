package io.skjaere.debridav.webdav.folder

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WebDavFolderMappingRepository : JpaRepository<WebDavFolderMappingEntity, Long> {
    fun findByProviderName(providerName: String): List<WebDavFolderMappingEntity>
    fun findByEnabled(enabled: Boolean): List<WebDavFolderMappingEntity>
    fun findByInternalPath(internalPath: String): WebDavFolderMappingEntity?
    fun findByProviderNameAndExternalPathAndInternalPath(
        providerName: String,
        externalPath: String,
        internalPath: String
    ): WebDavFolderMappingEntity?
}
