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
    name = "iptv_sync_hash",
    indexes = [
        Index(name = "idx_iptv_sync_hash_provider_endpoint", columnList = "provider_name,endpoint_type")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_iptv_sync_hash_provider_endpoint", columnNames = ["provider_name", "endpoint_type"])
    ]
)
open class IptvSyncHashEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    
    @Column(name = "provider_name", nullable = false, length = 255)
    open var providerName: String = ""
    
    @Column(name = "endpoint_type", nullable = false, length = 50)
    open var endpointType: String = "" // e.g., "vod_streams", "series_streams", "m3u", "combined"
    
    @Column(name = "content_hash", nullable = false, length = 64) // SHA-256 produces 64 hex characters
    open var contentHash: String = ""
    
    @Column(name = "last_checked", nullable = false)
    open var lastChecked: Instant = Instant.now()
}

