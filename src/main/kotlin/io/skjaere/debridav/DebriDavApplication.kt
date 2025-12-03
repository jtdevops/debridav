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
            logger.info("Actuator Endpoints:")
            logger.info("  GET     $baseUrl/actuator/health")
            logger.info("          curl $baseUrl/actuator/health")
            logger.info("")
            logger.info("  GET     $baseUrl/actuator/health/liveness")
            logger.info("          curl $baseUrl/actuator/health/liveness")
            logger.info("")
            logger.info("  GET     $baseUrl/actuator/health/readiness")
            logger.info("          curl $baseUrl/actuator/health/readiness")
            logger.info("")
            logger.info("  GET     $baseUrl/actuator/prometheus")
            logger.info("          curl $baseUrl/actuator/prometheus")
            logger.info("")
            logger.info("  GET     $baseUrl/actuator/realdebrid")
            logger.info("          curl $baseUrl/actuator/realdebrid")
            logger.info("")
            logger.info("  DELETE  $baseUrl/actuator/cache")
            logger.info("          curl -X DELETE $baseUrl/actuator/cache")
            
            // Check if streaming download tracking is enabled
            val enableTracking = environment.getProperty("debridav.enable-streaming-download-tracking", Boolean::class.java, false)
            if (enableTracking) {
                logger.info("")
                logger.info("  GET     $baseUrl/actuator/streaming-download-tracking")
                logger.info("          curl $baseUrl/actuator/streaming-download-tracking")
            }
            
            logger.info("")
            logger.info("IPTV Management Endpoints:")
            logger.info("  POST    $baseUrl/api/iptv/sync")
            logger.info("          curl -X POST $baseUrl/api/iptv/sync")
            logger.info("")
            logger.info("  GET     $baseUrl/api/iptv/config")
            logger.info("          curl $baseUrl/api/iptv/config")
            logger.info("")
            logger.info("  GET     $baseUrl/api/iptv/content")
            logger.info("          curl $baseUrl/api/iptv/content")
            logger.info("")
            logger.info("  DELETE  $baseUrl/api/iptv/provider/{providerName}")
            
            // Get configured IPTV providers and show curl examples for each
            val configuredProviders = iptvConfigurationService.getProviderConfigurations()
            if (configuredProviders.isNotEmpty()) {
                configuredProviders.forEach { provider ->
                    logger.info("          curl -X DELETE $baseUrl/api/iptv/provider/${provider.name}")
                }
            } else {
                logger.info("          curl -X DELETE $baseUrl/api/iptv/provider/{providerName}")
                logger.info("          (No IPTV providers configured)")
            }
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
