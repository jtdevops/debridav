package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface IptvSeriesMetadataRepository : JpaRepository<IptvSeriesMetadataEntity, Long> {
    fun findByProviderNameAndSeriesId(providerName: String, seriesId: String): IptvSeriesMetadataEntity?
    
    @Modifying
    @Query("DELETE FROM IptvSeriesMetadataEntity e WHERE e.lastAccessed < :cutoffTime")
    fun deleteByLastAccessedBefore(@Param("cutoffTime") cutoffTime: Instant): Int
    
    @Query("SELECT COUNT(e) FROM IptvSeriesMetadataEntity e WHERE e.lastAccessed < :cutoffTime")
    fun countByLastAccessedBefore(@Param("cutoffTime") cutoffTime: Instant): Long
    
    @Modifying
    @Query("DELETE FROM IptvSeriesMetadataEntity e WHERE e.providerName = :providerName")
    fun deleteByProviderName(@Param("providerName") providerName: String): Int
    
    @Query("SELECT COUNT(e) FROM IptvSeriesMetadataEntity e WHERE e.providerName = :providerName")
    fun countByProviderName(@Param("providerName") providerName: String): Long
}

