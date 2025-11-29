package io.skjaere.debridav.iptv

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface IptvUrlTemplateRepository : JpaRepository<IptvUrlTemplateEntity, Long> {
    fun findByProviderNameAndBaseUrl(providerName: String, baseUrl: String): IptvUrlTemplateEntity?
    fun findByProviderName(providerName: String): List<IptvUrlTemplateEntity>
}

