package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.model.ContentType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface IptvContentRepository : JpaRepository<IptvContentEntity, Long> {
    fun findByProviderNameAndContentId(providerName: String, contentId: String): IptvContentEntity?
    
    fun findByProviderName(providerName: String): List<IptvContentEntity>
    
    @Query("SELECT e FROM IptvContentEntity e WHERE e.normalizedTitle LIKE %:query% AND e.isActive = true")
    fun findByNormalizedTitleContaining(@Param("query") query: String): List<IptvContentEntity>
    
    fun findByContentTypeAndIsActive(contentType: ContentType, isActive: Boolean): List<IptvContentEntity>
    
    @Query("SELECT e FROM IptvContentEntity e WHERE e.normalizedTitle LIKE %:query% AND e.contentType = :contentType AND e.isActive = true")
    fun findByNormalizedTitleContainingAndContentType(
        @Param("query") query: String,
        @Param("contentType") contentType: ContentType
    ): List<IptvContentEntity>
}

