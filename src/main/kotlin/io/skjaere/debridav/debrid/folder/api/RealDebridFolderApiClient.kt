package io.skjaere.debridav.debrid.folder.api

import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownload
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
class RealDebridFolderApiClient(
    private val realDebridConfiguration: RealDebridConfigurationProperties,
    private val httpClient: HttpClient,
    private val realDebridRateLimiter: RateLimiter
) : DebridFolderApiClient {
    private val logger = LoggerFactory.getLogger(RealDebridFolderApiClient::class.java)

    override suspend fun listFolders(parentPath: String?): List<DebridFolder> {
        // Real-Debrid downloads API doesn't support folder listing
        // We'll use listFiles and extract folder structure from file paths
        return emptyList()
    }

    override suspend fun listFiles(folderPath: String): List<DebridFile> {
        return try {
            val allDownloads = getAllDownloads()
            // Filter downloads that match the folder path
            allDownloads
                .filter { download ->
                    // Extract folder from download filename/path
                    // Real-Debrid downloads don't have explicit folder structure,
                    // so we'll match based on filename patterns or use a different approach
                    matchesFolderPath(download, folderPath)
                }
                .map { download ->
                    DebridFile(
                        id = download.id,
                        path = download.filename, // Real-Debrid uses filename as path
                        name = download.filename.substringAfterLast("/"),
                        size = download.fileSize,
                        mimeType = download.mimeType,
                        downloadLink = download.download,
                        lastModified = null // Real-Debrid downloads API doesn't provide last modified
                    )
                }
        } catch (e: Exception) {
            logger.error("Error listing files from Real-Debrid API", e)
            emptyList()
        }
    }

    override suspend fun getFileInfo(filePath: String, fileId: String): DebridFile? {
        return try {
            val downloads = getAllDownloads()
            downloads
                .firstOrNull { it.id == fileId }
                ?.let { download ->
                    DebridFile(
                        id = download.id,
                        path = download.filename,
                        name = download.filename.substringAfterLast("/"),
                        size = download.fileSize,
                        mimeType = download.mimeType,
                        downloadLink = download.download,
                        lastModified = null
                    )
                }
        } catch (e: Exception) {
            logger.error("Error getting file info from Real-Debrid API", e)
            null
        }
    }

    override suspend fun getDownloadLink(filePath: String, fileId: String): String? {
        return try {
            val downloads = getAllDownloads()
            downloads.firstOrNull { it.id == fileId }?.download
        } catch (e: Exception) {
            logger.error("Error getting download link from Real-Debrid API", e)
            null
        }
    }

    private suspend fun getAllDownloads(): List<RealDebridDownload> {
        var offset = 0
        val bulkSize = 100
        val downloads = mutableListOf<RealDebridDownload>()
        var bulk: List<RealDebridDownload>

        do {
            bulk = getDownloadsWithOffset(offset, bulkSize)
            downloads.addAll(bulk)
            offset += bulkSize
        } while (bulk.size == bulkSize)

        return downloads
    }

    private suspend fun getDownloadsWithOffset(offset: Int, numItems: Int): List<RealDebridDownload> {
        val resp = realDebridRateLimiter.executeSuspendFunction {
            httpClient.get("${realDebridConfiguration.baseUrl}/downloads") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfiguration.apiKey)
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                url {
                    parameters.append("limit", numItems.toString())
                    if (offset > 0) parameters.append("offset", offset.toString())
                }
            }
        }
        if (resp.status.value == 204) return emptyList()
        return resp.body()
    }

    private fun matchesFolderPath(download: RealDebridDownload, folderPath: String): Boolean {
        // Real-Debrid downloads API returns files with filenames
        // We need to match based on filename patterns or use a different strategy
        // For now, we'll match if the filename contains the folder path
        val normalizedFolderPath = folderPath.trim().removePrefix("/").removeSuffix("/")
        val filename = download.filename

        // If folder path is empty or root, match all files
        if (normalizedFolderPath.isBlank() || normalizedFolderPath == "/") {
            return true
        }

        // Check if filename starts with folder path
        return filename.startsWith(normalizedFolderPath) || filename.contains("/$normalizedFolderPath/")
    }
}
