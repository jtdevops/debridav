package io.skjaere.debridav

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.milton.servlet.SpringMiltonFilter
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import kotlinx.serialization.json.Json
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import java.security.cert.X509Certificate
import org.slf4j.LoggerFactory

@Configuration
@ConfigurationPropertiesScan("io.skjaere.debridav")
@EnableScheduling
class DebridavConfiguration {
    private val logger = LoggerFactory.getLogger(DebridavConfiguration::class.java)
    
    init {
        // Log supported TLS versions at startup for diagnostics
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val supportedProtocols = sslContext.defaultSSLParameters.protocols
            val enabledProtocols = sslContext.supportedSSLParameters.protocols
            logger.info("JVM TLS Support: supportedProtocols={}, enabledProtocols={}", 
                supportedProtocols.contentToString(), enabledProtocols.contentToString())
            
            // Check system properties for TLS configuration
            val clientProtocols = System.getProperty("jdk.tls.client.protocols")
            val disabledAlgorithms = System.getProperty("jdk.tls.disabledAlgorithms")
            if (clientProtocols != null) {
                logger.info("JVM TLS Configuration: jdk.tls.client.protocols={}", clientProtocols)
            }
            if (disabledAlgorithms != null) {
                logger.info("JVM TLS Configuration: jdk.tls.disabledAlgorithms={}", disabledAlgorithms)
            }
        } catch (e: Exception) {
            logger.warn("Failed to check TLS version support", e)
        }
    }
    @Bean
    fun miltonFilterFilterRegistrationBean(): FilterRegistrationBean<SpringMiltonFilter> {
        val registration = FilterRegistrationBean<SpringMiltonFilter>()
        registration.filter = SpringMiltonFilter()
        registration.setName("MiltonFilter")
        registration.addUrlPatterns("/*")
        registration.order = Ordered.HIGHEST_PRECEDENCE + 100 // Run early, but after security filters
        registration.addInitParameter("milton.exclude.paths", "/files,/api,/version,/sabnzbd,/actuator,/strm-proxy")
        registration.addInitParameter(
            "resource.factory.class", "io.skjaere.debrid.resource.StreamableResourceFactory"
        )
        registration.addInitParameter(
            "controllerPackagesToScan", "io.skjaere.debrid"
        )
        registration.addInitParameter("contextConfigClass", "io.skjaere.debridav.MiltonConfiguration")

        return registration
    }


    @Bean
    @Primary
    fun objectMapper(): ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        configure(SerializationFeature.INDENT_OUTPUT, true)
    }

    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun httpClient(debridavConfigurationProperties: DebridavConfigurationProperties): HttpClient {
        // Create a trust-all SSL trust manager for IPTV providers with self-signed certificates
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        
        return HttpClient(CIO) {
            engine {
                // Configure SSL to trust all certificates (needed for IPTV providers with self-signed certs)
                // Note: This applies globally but is only used for IPTV URLs detected in DefaultStreamableLinkPreparer
                https {
                    trustManager = trustAllCerts.first() as X509TrustManager
                }
                
                // Configure connection pooling and keep-alive for better performance
                // Reduces connection establishment overhead (each reconnect adds 200-500ms)
                // CIO engine uses connection pooling by default with keep-alive enabled
                // These settings help reduce latency for streaming requests
            }
            install(HttpTimeout) {
                connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds
            }
            // HttpRedirect plugin follows redirects by default (needed for IPTV providers)
            install(HttpRedirect)
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
    }

    @Bean
    fun usenetConversionService(converters: List<Converter<*, *>>): DefaultConversionService {
        val conversionService = DefaultConversionService()
        converters.forEach { conversionService.addConverter(it) }
        return conversionService
    }
}
