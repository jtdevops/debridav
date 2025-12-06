package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.model.ContentType
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "iptv_url_template",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_iptv_url_template_provider_base",
            columnNames = ["provider_name", "base_url"]
        )
    ],
    indexes = [
        Index(name = "idx_iptv_url_template_provider", columnList = "provider_name")
    ]
)
open class IptvUrlTemplateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "iptv_url_template_seq")
    @SequenceGenerator(name = "iptv_url_template_seq", sequenceName = "iptv_url_template_seq", allocationSize = 50)
    open var id: Long? = null
    
    @Column(name = "provider_name", nullable = false, length = 255)
    open var providerName: String = ""
    
    @Column(name = "base_url", nullable = false, length = 2048)
    open var baseUrl: String = ""
    
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = true, length = 50)
    open var contentType: ContentType? = null
    
    @Column(name = "last_updated", nullable = false)
    open var lastUpdated: Instant = Instant.now()
}

