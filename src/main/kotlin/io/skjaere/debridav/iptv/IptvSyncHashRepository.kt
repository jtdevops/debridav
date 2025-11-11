package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface IptvSyncHashRepository : JpaRepository<IptvSyncHashEntity, Long> {
    fun findByProviderNameAndEndpointType(providerName: String, endpointType: String): IptvSyncHashEntity?
    
    fun findByProviderName(providerName: String): List<IptvSyncHashEntity>
    
    fun deleteByProviderName(providerName: String)
}

