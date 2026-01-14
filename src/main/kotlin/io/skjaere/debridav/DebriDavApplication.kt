package io.skjaere.debridav

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.iptv.configuration.IptvConfigurationService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@SpringBootApplication
class DebriDavApplication : SpringBootServletInitializer() {
    private val logger = LoggerFactory.getLogger(DebriDavApplication::class.java)

    @Bean
    fun logConfigurationProperties(
        debridavConfigurationProperties: DebridavConfigurationProperties
    ): CommandLineRunner {
        return CommandLineRunner {
            logger.info("=== Debridav Configuration Properties ===")
            logger.info("Root Path: {}", debridavConfigurationProperties.rootPath)
            logger.info("Download Path: {}", debridavConfigurationProperties.downloadPath)
            logger.info("Mount Path: {}", debridavConfigurationProperties.mountPath)
            logger.info("Debrid Clients: {}", debridavConfigurationProperties.debridClients)
            logger.info("Connect Timeout: {}ms", debridavConfigurationProperties.connectTimeoutMilliseconds)
            logger.info("Read Timeout: {}ms", debridavConfigurationProperties.readTimeoutMilliseconds)
            
            // Caching and buffering properties
            logger.info("Enable Chunk Caching: {}", debridavConfigurationProperties.enableChunkCaching)
            logger.info("Enable In-Memory Buffering: {}", debridavConfigurationProperties.enableInMemoryBuffering)
            logger.info(
                "Chunk Caching Size Threshold: {} bytes", 
                debridavConfigurationProperties.chunkCachingSizeThreshold
            )
            logger.info(
                "Chunk Caching Grace Period: {}", 
                debridavConfigurationProperties.chunkCachingGracePeriod
            )
            logger.info("Cache Max Size: {} GB", debridavConfigurationProperties.cacheMaxSizeGb)
            logger.info("Local Entity Max Size: {} MB", debridavConfigurationProperties.localEntityMaxSizeMb)
            
            // Other important properties
            logger.info(
                "Enable File Import on Startup: {}", 
                debridavConfigurationProperties.enableFileImportOnStartup
            )
            logger.info(
                "Should Delete Non-Working Files: {}", 
                debridavConfigurationProperties.shouldDeleteNonWorkingFiles
            )
            logger.info("Torrent Lifetime: {}", debridavConfigurationProperties.torrentLifetime)
            logger.info("Default Categories: {}", debridavConfigurationProperties.defaultCategories)
            
            // Byte range chunking control
            logger.info(
                "Disable Byte Range Request Chunking: {}", 
                debridavConfigurationProperties.disableByteRangeRequestChunking
            )
            
            // Downloads cleanup configuration
            logger.info(
                "Enable Downloads Cleanup Time-Based: {}", 
                debridavConfigurationProperties.enableDownloadsCleanupTimeBased
            )
            if (debridavConfigurationProperties.enableDownloadsCleanupTimeBased) {
                logger.info(
                    "Downloads Cleanup Time-Based Threshold: {} minutes", 
                    debridavConfigurationProperties.downloadsCleanupTimeBasedThresholdMinutes
                )
            } else {
                logger.info("Downloads Cleanup Mode: Immediate (no age check)")
            }
            
            logger.info("=== End Configuration Properties ===")
        }
    }

    @Bean
    fun logMaintenanceEndpoints(
        serverProperties: ServerProperties,
        environment: Environment,
        iptvConfigurationService: IptvConfigurationService
    ): CommandLineRunner {
        return CommandLineRunner {
            // Check DEBRIDAV_PORT environment variable first (for Docker containers)
            val port = environment.getProperty("DEBRIDAV_PORT", Int::class.java)
                ?: environment.getProperty("server.port", Int::class.java)
                ?: serverProperties.port
                ?: 8080
            val contextPath = serverProperties.servlet?.contextPath ?: ""
            val baseUrl = "http://localhost:$port$contextPath"
            
            logger.info("")
            logger.info("=== Maintenance Endpoints ===")
            logger.info("Base URL: $baseUrl")
            logger.info("")
            logger.info("Usage Examples:")
            logger.info("  GET:    curl $baseUrl/actuator/health")
            logger.info("  POST:   curl -X POST $baseUrl/api/iptv/sync")
            logger.info("  DELETE: curl -X DELETE $baseUrl/api/iptv/provider/{providerName}")
            logger.info("  POST:   curl -X POST $baseUrl/actuator/loggers/ROOT -H 'Content-Type: application/json' -d '{\"configuredLevel\":\"DEBUG\"}'")
            logger.info("  POST:   curl -X POST $baseUrl/actuator/configuration -H 'Content-Type: application/json' -d '{\"debugArrTorrentInfoContentPathSuffix\":\"__DEBUG_TESTING\"}'")
            logger.info("")
            logger.info("Actuator Endpoints:")
            logger.info("  GET     $baseUrl/actuator/health")
            logger.info("  GET     $baseUrl/actuator/health/liveness")
            logger.info("  GET     $baseUrl/actuator/health/readiness")
            logger.info("  GET     $baseUrl/actuator/prometheus")
            logger.info("  GET     $baseUrl/actuator/realdebrid")
            logger.info("  DELETE  $baseUrl/actuator/cache")
            logger.info("  GET     $baseUrl/actuator/loggers")
            logger.info("          (List all loggers and their levels)")
            logger.info("  GET     $baseUrl/actuator/loggers/{loggerName}")
            logger.info("          (Get logger level for specific logger, e.g., /actuator/loggers/io.skjaere.debridav)")
            logger.info("  POST    $baseUrl/actuator/loggers/{loggerName}")
            logger.info("          (Set logger level: curl -X POST $baseUrl/actuator/loggers/ROOT -H 'Content-Type: application/json' -d '{\"configuredLevel\":\"DEBUG\"}')")
            logger.info("  GET     $baseUrl/actuator/configuration")
            logger.info("          (Get current runtime configuration overrides)")
            logger.info("  POST    $baseUrl/actuator/configuration")
            logger.info("          (Set runtime config: curl -X POST $baseUrl/actuator/configuration -H 'Content-Type: application/json' -d '{\"debugArrTorrentInfoContentPathSuffix\":\"__DEBUG_TESTING\"}')")
            logger.info("  DELETE  $baseUrl/actuator/configuration")
            logger.info("          (Clear all runtime configuration overrides)")
            
            // Check if streaming download tracking is enabled
            val enableTracking = environment.getProperty("debridav.enable-streaming-download-tracking", Boolean::class.java, false)
            if (enableTracking) {
                logger.info("  GET     $baseUrl/actuator/streaming-download-tracking")
                logger.info("  DELETE  $baseUrl/actuator/streaming-download-tracking")
            }
            
            logger.info("")
            logger.info("IPTV Management Endpoints:")
            logger.info("  POST    $baseUrl/api/iptv/sync")
            logger.info("  POST    $baseUrl/api/iptv/live/sync")
            logger.info("          (Manually trigger live content sync only)")
            logger.info("  GET     $baseUrl/api/iptv/config")
            logger.info("  GET     $baseUrl/api/iptv/content")
            logger.info("  DELETE  $baseUrl/api/iptv/provider/{providerName}")
            
            // Get configured IPTV providers and show examples for each
            val configuredProviders = iptvConfigurationService.getProviderConfigurations()
            if (configuredProviders.isNotEmpty()) {
                configuredProviders.forEach { provider ->
                    logger.info("          (DELETE $baseUrl/api/iptv/provider/${provider.name})")
                }
            } else {
                logger.info("          (No IPTV providers configured)")
            }
            logger.info("  GET     $baseUrl/api/iptv/inactive/files")
            logger.info("          (List files linked to inactive IPTV content)")
            logger.info("  DELETE  $baseUrl/api/iptv/inactive/without-files")
            logger.info("          (Delete inactive IPTV content items not linked to files)")
            logger.info("")
            logger.info("Downloads Cleanup Endpoints:")
            logger.info("  GET     $baseUrl/api/cleanup/downloads/abandoned?cleanupAgeHours=6&arrCleanupAgeHours=1")
            logger.info("          (Files not linked to radarr/sonarr torrents use shorter arrCleanupAgeHours threshold)")
            logger.info("  POST    $baseUrl/api/cleanup/downloads/cleanup?cleanupAgeHours=6&arrCleanupAgeHours=1&dryRun=false")
            logger.info("          (Files not linked to radarr/sonarr torrents are cleaned up after arrCleanupAgeHours)")
            logger.info("  GET     $baseUrl/api/cleanup/downloads/diagnose?filePath=/downloads/folder-name")
            logger.info("          (Diagnose why a specific file/folder isn't being cleaned up)")
            logger.info("")
            logger.info("=== End Maintenance Endpoints ===")
            logger.info("")
        }
    }
}

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DebriDavApplication>(*args)
}
