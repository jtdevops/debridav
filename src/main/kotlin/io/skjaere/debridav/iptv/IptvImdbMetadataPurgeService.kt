package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class IptvImdbMetadataPurgeService(
    private val iptvImdbMetadataRepository: IptvImdbMetadataRepository,
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(IptvImdbMetadataPurgeService::class.java)

    /**
     * Purges IMDB metadata cache entries that have not been accessed within the TTL period
     * Runs on a schedule defined by imdbMetadataPurgeInterval
     */
    @Scheduled(
        fixedDelayString = "#{T(java.time.Duration).parse(@environment.getProperty('iptv.imdb-metadata-purge-interval', 'PT24H')).toMillis()}"
    )
    fun purgeExpiredMetadata() {
        if (!iptvConfigurationProperties.enabled) {
            return
        }
        
        val cutoffTime = Instant.now().minus(iptvConfigurationProperties.imdbMetadataCacheTtl)
        val countBefore = iptvImdbMetadataRepository.countByLastAccessedBefore(cutoffTime)
        
        if (countBefore > 0) {
            val days = iptvConfigurationProperties.imdbMetadataCacheTtl.toDays()
            logger.info("Purging $countBefore expired IMDB metadata cache entries (older than $days days)")
            val deletedCount = iptvImdbMetadataRepository.deleteByLastAccessedBefore(cutoffTime)
            logger.info("Successfully purged $deletedCount expired IMDB metadata cache entries")
        } else {
            logger.debug("No expired IMDB metadata cache entries to purge")
        }
    }
}

