package io.skjaere.debridav.debrid.folder.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('premiumize')}")
class PremiumizeFolderApiClient(
    private val premiumizeConfiguration: PremiumizeConfigurationProperties,
    private val httpClient: HttpClient
) : DebridFolderApiClient {
    private val logger = LoggerFactory.getLogger(PremiumizeFolderApiClient::class.java)

    override suspend fun listFolders(parentPath: String?): List<DebridFolder> {
        // Premiumize API folder listing - implementation depends on Premiumize API
        // For now, return empty list as Premiumize may not support folder listing
        logger.warn("Premiumize folder listing not yet implemented")
        return emptyList()
    }

    override suspend fun listFiles(folderPath: String): List<DebridFile> {
        // Premiumize API file listing - implementation depends on Premiumize API
        // Premiumize may use cloud storage API endpoints
        logger.warn("Premiumize file listing not yet implemented")
        return emptyList()
    }

    override suspend fun getFileInfo(filePath: String, fileId: String): DebridFile? {
        logger.warn("Premiumize getFileInfo not yet implemented")
        return null
    }

    override suspend fun getDownloadLink(filePath: String, fileId: String): String? {
        logger.warn("Premiumize getDownloadLink not yet implemented")
        return null
    }
}
