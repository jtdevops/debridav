package io.skjaere.debridav.iptv

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.iptv.model.SeriesInfo
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(
    name = "iptv_content",
    indexes = [
        Index(name = "idx_iptv_content_normalized_title", columnList = "normalized_title"),
        Index(name = "idx_iptv_content_provider_content_id", columnList = "provider_name,content_id"),
        Index(name = "idx_iptv_content_type_active", columnList = "content_type,is_active")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_iptv_content_provider_id", columnNames = ["provider_name", "content_id"])
    ]
)
open class IptvContentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    
    @Column(name = "provider_name", nullable = false)
    open var providerName: String = ""
    
    @Column(name = "content_id", nullable = false, length = 512)
    open var contentId: String = ""
    
    @Column(name = "title", nullable = false, length = 1024)
    open var title: String = ""
    
    @Column(name = "normalized_title", nullable = false, length = 1024)
    open var normalizedTitle: String = ""
    
    @Column(name = "url", nullable = false, length = 2048)
    open var url: String = "" // tokenized URL
    
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    open var contentType: ContentType = ContentType.MOVIE
    
    @Column(name = "category", length = 255)
    open var category: String? = null
    
    @Type(JsonBinaryType::class)
    @Column(name = "series_info", columnDefinition = "jsonb")
    open var seriesInfo: SeriesInfo? = null
    
    @Type(JsonBinaryType::class)
    @Column(name = "metadata", columnDefinition = "jsonb")
    open var metadata: Map<String, String> = emptyMap()
    
    @Column(name = "last_synced", nullable = false)
    open var lastSynced: Instant = Instant.now()
    
    @Column(name = "is_active", nullable = false)
    open var isActive: Boolean = true
}

