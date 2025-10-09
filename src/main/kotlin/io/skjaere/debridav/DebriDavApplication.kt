package io.skjaere.debridav

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import org.springframework.context.annotation.Bean

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
            
            logger.info("=== End Configuration Properties ===")
        }
    }
}

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DebriDavApplication>(*args)
}
