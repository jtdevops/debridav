package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface IptvImdbMetadataRepository : JpaRepository<IptvImdbMetadataEntity, Long> {
    fun findByImdbId(imdbId: String): IptvImdbMetadataEntity?
    
    @Modifying
    @Query("UPDATE IptvImdbMetadataEntity e SET e.lastAccessed = :now WHERE e.imdbId = :imdbId")
    fun updateLastAccessed(@Param("imdbId") imdbId: String, @Param("now") now: Instant): Int
    
    @Modifying
    @Query("DELETE FROM IptvImdbMetadataEntity e WHERE e.lastAccessed < :cutoffTime")
    fun deleteByLastAccessedBefore(@Param("cutoffTime") cutoffTime: Instant): Int
    
    @Query("SELECT COUNT(e) FROM IptvImdbMetadataEntity e WHERE e.lastAccessed < :cutoffTime")
    fun countByLastAccessedBefore(@Param("cutoffTime") cutoffTime: Instant): Long
}

