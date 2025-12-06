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
    name = "iptv_imdb_metadata",
    indexes = [
        jakarta.persistence.Index(name = "idx_iptv_imdb_metadata_imdb_id", columnList = "imdb_id"),
        jakarta.persistence.Index(name = "idx_iptv_imdb_metadata_last_accessed", columnList = "last_accessed")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_iptv_imdb_metadata_imdb_id", columnNames = ["imdb_id"])
    ]
)
open class IptvImdbMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    
    @Column(name = "imdb_id", nullable = false, length = 32, unique = true)
    open var imdbId: String = ""
    
    @Column(name = "title", nullable = false, length = 1024)
    open var title: String = ""
    
    @Column(name = "start_year", nullable = true)
    open var startYear: Int? = null
    
    @Column(name = "end_year", nullable = true)
    open var endYear: Int? = null
    
    @Column(name = "response_json", columnDefinition = "TEXT", nullable = false)
    open var responseJson: String = ""
    
    @Column(name = "last_accessed", nullable = false)
    open var lastAccessed: Instant = Instant.now()
    
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

