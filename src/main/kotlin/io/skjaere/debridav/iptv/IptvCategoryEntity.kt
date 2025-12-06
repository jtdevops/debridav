package io.skjaere.debridav.iptv

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "iptv_category",
    indexes = [
        Index(name = "idx_iptv_category_provider_type", columnList = "provider_name,category_type"),
        Index(name = "idx_iptv_category_provider_id", columnList = "provider_name,category_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_iptv_category_provider_id", columnNames = ["provider_name", "category_id", "category_type"])
    ]
)
open class IptvCategoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    
    @Column(name = "provider_name", nullable = false, length = 255)
    open var providerName: String = ""
    
    @Column(name = "category_id", nullable = false, length = 50)
    open var categoryId: String = "" // The ID from the IPTV provider API
    
    @Column(name = "category_name", nullable = false, length = 255)
    open var categoryName: String = ""
    
    @Column(name = "category_type", nullable = false, length = 50)
    open var categoryType: String = "" // "vod" or "series"
    
    @Column(name = "last_synced", nullable = false)
    open var lastSynced: Instant = Instant.now()
    
    @Column(name = "is_active", nullable = false)
    open var isActive: Boolean = true
}

