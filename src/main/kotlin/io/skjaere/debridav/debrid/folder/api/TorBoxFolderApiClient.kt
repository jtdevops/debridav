package io.skjaere.debridav.debrid.folder.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.skjaere.debridav.debrid.client.torbox.TorBoxConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class TorBoxFolderApiClient(
    private val torBoxConfiguration: TorBoxConfigurationProperties,
    private val httpClient: HttpClient
) : DebridFolderApiClient {
    private val logger = LoggerFactory.getLogger(TorBoxFolderApiClient::class.java)

    override suspend fun listFolders(parentPath: String?): List<DebridFolder> {
        // TorBox API folder listing - implementation depends on TorBox API
        logger.warn("TorBox folder listing not yet implemented")
        return emptyList()
    }

    override suspend fun listFiles(folderPath: String): List<DebridFile> {
        // TorBox API file listing - implementation depends on TorBox API
        logger.warn("TorBox file listing not yet implemented")
        return emptyList()
    }

    override suspend fun getFileInfo(filePath: String, fileId: String): DebridFile? {
        logger.warn("TorBox getFileInfo not yet implemented")
        return null
    }

    override suspend fun getDownloadLink(filePath: String, fileId: String): String? {
        logger.warn("TorBox getDownloadLink not yet implemented")
        return null
    }
}
