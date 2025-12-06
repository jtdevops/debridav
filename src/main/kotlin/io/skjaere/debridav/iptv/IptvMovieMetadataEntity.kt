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
    name = "iptv_movie_metadata",
    indexes = [
        jakarta.persistence.Index(name = "idx_iptv_movie_metadata_provider_movie", columnList = "provider_name,movie_id"),
        jakarta.persistence.Index(name = "idx_iptv_movie_metadata_last_accessed", columnList = "last_accessed")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_iptv_movie_metadata_provider_movie", columnNames = ["provider_name", "movie_id"])
    ]
)
open class IptvMovieMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    
    @Column(name = "provider_name", nullable = false, length = 255)
    open var providerName: String = ""
    
    @Column(name = "movie_id", nullable = false, length = 512)
    open var movieId: String = ""
    
    @Column(name = "response_json", columnDefinition = "TEXT", nullable = false)
    open var responseJson: String = ""
    
    @Column(name = "last_accessed", nullable = false)
    open var lastAccessed: Instant = Instant.now()
    
    @Column(name = "last_fetch", nullable = false)
    open var lastFetch: Instant = Instant.now()
    
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

