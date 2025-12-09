package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface IptvCategoryRepository : JpaRepository<IptvCategoryEntity, Long> {
    fun findByProviderNameAndCategoryIdAndCategoryType(
        providerName: String,
        categoryId: String,
        categoryType: String
    ): IptvCategoryEntity?
    
    fun findByProviderNameAndCategoryType(
        providerName: String,
        categoryType: String
    ): List<IptvCategoryEntity>
    
    fun findByProviderName(providerName: String): List<IptvCategoryEntity>
    
    @Query("SELECT c FROM IptvCategoryEntity c WHERE c.providerName = :providerName AND c.categoryType = :categoryType AND c.isActive = true")
    fun findActiveByProviderNameAndCategoryType(
        @Param("providerName") providerName: String,
        @Param("categoryType") categoryType: String
    ): List<IptvCategoryEntity>
    
    fun deleteByProviderName(providerName: String)
}

