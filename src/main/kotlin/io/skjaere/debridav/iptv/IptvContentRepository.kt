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
    
    /**
     * Finds entities where the normalized title contains the query as a whole phrase/word.
     * Uses word boundary matching to prevent partial matches (e.g., "twister" won't match "twisters").
     * 
     * For single-word queries like "twister", matches:
     * - "twister" (exact match)
     * - "twister " (followed by space)
     * - " twister " (surrounded by spaces)
     * - " twister" (preceded by space)
     * 
     * For multi-word queries like "spider man", matches:
     * - "spider man" (exact match)
     * - "spider man " (followed by space)
     * - " spider man " (surrounded by spaces)
     * - " spider man" (preceded by space)
     */
    @Query("""
        SELECT e FROM IptvContentEntity e 
        WHERE e.isActive = true 
        AND (
            e.normalizedTitle = :query 
            OR e.normalizedTitle LIKE CONCAT(:query, ' %')
            OR e.normalizedTitle LIKE CONCAT('% ', :query, ' %')
            OR e.normalizedTitle LIKE CONCAT('% ', :query)
        )
    """)
    fun findByNormalizedTitleWordBoundary(@Param("query") query: String): List<IptvContentEntity>
    
    /**
     * Finds entities where the normalized title contains the query as a whole phrase/word, filtered by content type.
     * Uses word boundary matching to prevent partial matches.
     */
    @Query("""
        SELECT e FROM IptvContentEntity e 
        WHERE e.isActive = true 
        AND e.contentType = :contentType
        AND (
            e.normalizedTitle = :query 
            OR e.normalizedTitle LIKE CONCAT(:query, ' %')
            OR e.normalizedTitle LIKE CONCAT('% ', :query, ' %')
            OR e.normalizedTitle LIKE CONCAT('% ', :query)
        )
    """)
    fun findByNormalizedTitleWordBoundaryAndContentType(
        @Param("query") query: String,
        @Param("contentType") contentType: ContentType
    ): List<IptvContentEntity>
    
    fun findByIsActive(isActive: Boolean): List<IptvContentEntity>
    
    @Query("SELECT e FROM IptvContentEntity e WHERE e.isActive = false")
    fun findInactiveContent(): List<IptvContentEntity>
}

