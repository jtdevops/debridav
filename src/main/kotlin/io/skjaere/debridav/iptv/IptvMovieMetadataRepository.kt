package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface IptvMovieMetadataRepository : JpaRepository<IptvMovieMetadataEntity, Long> {
    fun findByProviderNameAndMovieId(providerName: String, movieId: String): IptvMovieMetadataEntity?
    
    @Modifying
    @Query("DELETE FROM IptvMovieMetadataEntity e WHERE e.lastAccessed < :cutoffTime")
    fun deleteByLastAccessedBefore(@Param("cutoffTime") cutoffTime: Instant): Int
    
    @Query("SELECT COUNT(e) FROM IptvMovieMetadataEntity e WHERE e.lastAccessed < :cutoffTime")
    fun countByLastAccessedBefore(@Param("cutoffTime") cutoffTime: Instant): Long
    
    @Modifying
    @Query("DELETE FROM IptvMovieMetadataEntity e WHERE e.providerName = :providerName")
    fun deleteByProviderName(@Param("providerName") providerName: String): Int
    
    @Query("SELECT COUNT(e) FROM IptvMovieMetadataEntity e WHERE e.providerName = :providerName")
    fun countByProviderName(@Param("providerName") providerName: String): Long
}

