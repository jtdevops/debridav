package io.skjaere.debridav.debrid.folder

import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DebridFolderMappingRepository : JpaRepository<DebridFolderMappingEntity, Long> {
    fun findByProvider(provider: DebridProvider): List<DebridFolderMappingEntity>
    fun findByEnabled(enabled: Boolean): List<DebridFolderMappingEntity>
    fun findByInternalPath(internalPath: String): DebridFolderMappingEntity?
    fun findByProviderAndExternalPathAndInternalPath(
        provider: DebridProvider,
        externalPath: String,
        internalPath: String
    ): DebridFolderMappingEntity?
}
