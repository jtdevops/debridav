package io.skjaere.debridav.iptv

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import io.skjaere.debridav.iptv.client.XtreamSeriesEpisode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Type
import java.time.Instant

/**
 * Storage DTO for episode information (compatible with Jackson/JsonBinaryType)
 */
data class EpisodeStorageDto(
    val id: String,
    val title: String,
    val containerExtension: String? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    fun toXtreamSeriesEpisode(): XtreamSeriesEpisode {
        return XtreamSeriesEpisode(
            id = id,
            title = title,
            container_extension = containerExtension,
            info = null, // Info not stored in cache to reduce size
            season = season,
            episode = episode
        )
    }
    
    companion object {
        fun fromXtreamSeriesEpisode(ep: XtreamSeriesEpisode): EpisodeStorageDto {
            return EpisodeStorageDto(
                id = ep.id,
                title = ep.title,
                containerExtension = ep.container_extension,
                season = ep.season,
                episode = ep.episode
            )
        }
    }
}

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
    
    @Type(JsonBinaryType::class)
    @Column(name = "episodes", columnDefinition = "jsonb", nullable = false)
    open var episodes: List<EpisodeStorageDto> = emptyList()
    
    @Column(name = "last_accessed", nullable = false)
    open var lastAccessed: Instant = Instant.now()
    
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
    
    /**
     * Helper method to get episodes as XtreamSeriesEpisode list
     */
    fun getEpisodesAsXtreamSeriesEpisode(): List<XtreamSeriesEpisode> {
        return episodes.map { it.toXtreamSeriesEpisode() }
    }
    
    /**
     * Helper method to set episodes from XtreamSeriesEpisode list
     */
    fun setEpisodesFromXtreamSeriesEpisode(episodesList: List<XtreamSeriesEpisode>) {
        episodes = episodesList.map { EpisodeStorageDto.fromXtreamSeriesEpisode(it) }
    }
}

