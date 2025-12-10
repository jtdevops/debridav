package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class IptvSeriesMetadataPurgeService(
    private val iptvSeriesMetadataRepository: IptvSeriesMetadataRepository,
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(IptvSeriesMetadataPurgeService::class.java)

    /**
     * Purges series metadata cache entries that have not been accessed within the TTL period
     * Runs on a schedule defined by seriesMetadataPurgeInterval
     */
    @Scheduled(
        fixedDelayString = "#{T(java.time.Duration).parse(@environment.getProperty('iptv.series-metadata-purge-interval', 'PT24H')).toMillis()}"
    )
    @Transactional
    fun purgeExpiredMetadata() {
        if (!iptvConfigurationProperties.enabled) {
            return
        }
        
        val cutoffTime = Instant.now().minus(iptvConfigurationProperties.seriesMetadataCacheTtl)
        val countBefore = iptvSeriesMetadataRepository.countByLastAccessedBefore(cutoffTime)
        
        if (countBefore > 0) {
            logger.info("Purging $countBefore expired series metadata cache entries (older than ${iptvConfigurationProperties.seriesMetadataCacheTtl.toDays()} days)")
            val deletedCount = iptvSeriesMetadataRepository.deleteByLastAccessedBefore(cutoffTime)
            logger.info("Successfully purged $deletedCount expired series metadata cache entries")
        } else {
            logger.debug("No expired series metadata cache entries to purge")
        }
    }
}

