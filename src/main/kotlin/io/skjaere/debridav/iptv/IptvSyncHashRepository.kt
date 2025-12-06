package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface IptvSyncHashRepository : JpaRepository<IptvSyncHashEntity, Long> {
    fun findByProviderNameAndEndpointType(providerName: String, endpointType: String): IptvSyncHashEntity?
    
    fun findByProviderName(providerName: String): List<IptvSyncHashEntity>
    
    fun deleteByProviderName(providerName: String)
    
    @Query("SELECT MAX(h.lastChecked) FROM IptvSyncHashEntity h")
    fun findMostRecentLastChecked(): Instant?
    
    @Query("SELECT MAX(h.lastChecked) FROM IptvSyncHashEntity h WHERE h.providerName = :providerName")
    fun findMostRecentLastCheckedByProvider(providerName: String): Instant?
    
    @Query("SELECT h FROM IptvSyncHashEntity h WHERE h.syncStatus = 'IN_PROGRESS'")
    fun findBySyncStatusInProgress(): List<IptvSyncHashEntity>
    
    @Query("SELECT h FROM IptvSyncHashEntity h WHERE h.providerName = :providerName AND h.syncStatus = 'IN_PROGRESS'")
    fun findByProviderNameAndSyncStatusInProgress(providerName: String): List<IptvSyncHashEntity>
    
    @Query("SELECT h FROM IptvSyncHashEntity h WHERE h.providerName = :providerName AND h.syncStatus = 'FAILED'")
    fun findByProviderNameAndSyncStatusFailed(providerName: String): List<IptvSyncHashEntity>
    
    @Query("SELECT h FROM IptvSyncHashEntity h WHERE h.providerName = :providerName AND h.syncStatus != 'COMPLETED'")
    fun findByProviderNameAndSyncStatusNotCompleted(providerName: String): List<IptvSyncHashEntity>
}

