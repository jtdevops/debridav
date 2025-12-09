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
    name = "iptv_series_metadata",
    indexes = [
        jakarta.persistence.Index(name = "idx_iptv_series_metadata_provider_series", columnList = "provider_name,series_id"),
        jakarta.persistence.Index(name = "idx_iptv_series_metadata_last_accessed", columnList = "last_accessed")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_iptv_series_metadata_provider_series", columnNames = ["provider_name", "series_id"])
    ]
)
open class IptvSeriesMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    
    @Column(name = "provider_name", nullable = false, length = 255)
    open var providerName: String = ""
    
    @Column(name = "series_id", nullable = false, length = 512)
    open var seriesId: String = ""
    
    @Column(name = "response_json", columnDefinition = "TEXT", nullable = false)
    open var responseJson: String = ""
    
    @Column(name = "last_accessed", nullable = false)
    open var lastAccessed: Instant = Instant.now()
    
    @Column(name = "last_fetch", nullable = false)
    open var lastFetch: Instant = Instant.now()
    
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

