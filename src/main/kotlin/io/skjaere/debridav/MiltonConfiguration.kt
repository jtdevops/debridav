package io.skjaere.debridav

import io.milton.config.HttpManagerBuilder
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.configuration.HostnameDetectionService
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.webdav.folder.WebDavFolderMappingRepository
import io.skjaere.debridav.webdav.folder.WebDavSyncedFileRepository
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.resource.ArrRequestDetector
import io.skjaere.debridav.resource.StreamableResourceFactory
import io.skjaere.debridav.stream.StreamingService
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class MiltonConfiguration {

    @Bean("milton.http.manager")
    fun httpManagerBuilder(
        resourceFactory: StreamableResourceFactory
    ): HttpManagerBuilder {
        val builder = HttpManagerBuilder()
        builder.resourceFactory = resourceFactory
        return builder
    }

    @Bean
    fun resourceFactory(
        fileService: DatabaseFileService,
        debridService: DebridLinkService,
        streamingService: StreamingService,
        debridavConfigurationProperties: DebridavConfigurationProperties,
        localContentsService: LocalContentsService,
        arrRequestDetector: ArrRequestDetector,
        serverProperties: ServerProperties,
        environment: Environment,
        hostnameDetectionService: HostnameDetectionService,
        webDavFolderMappingRepository: WebDavFolderMappingRepository?,
        webDavSyncedFileRepository: WebDavSyncedFileRepository?
    ): StreamableResourceFactory = StreamableResourceFactory(
        fileService,
        debridService,
        streamingService,
        debridavConfigurationProperties,
        localContentsService,
        arrRequestDetector,
        serverProperties,
        environment,
        hostnameDetectionService,
        webDavFolderMappingRepository,
        webDavSyncedFileRepository
    )
}
