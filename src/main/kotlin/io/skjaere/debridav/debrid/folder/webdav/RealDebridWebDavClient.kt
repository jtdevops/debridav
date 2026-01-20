package io.skjaere.debridav.debrid.folder.webdav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
class RealDebridWebDavClient(
    private val realDebridConfiguration: RealDebridConfigurationProperties,
    private val httpClient: HttpClient
) : DebridWebDavClient {
    private val logger = LoggerFactory.getLogger(RealDebridWebDavClient::class.java)

    // Note: Real-Debrid WebDAV endpoint - verify actual endpoint with Real-Debrid documentation
    private val webdavBaseUrl = "https://dav.real-debrid.com"

    override suspend fun listFiles(folderPath: String): List<WebDavFile> {
        return try {
            val url = "$webdavBaseUrl${normalizePath(folderPath)}"
            logger.debug("Listing files from Real-Debrid WebDAV: $url")

            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getcontenttype/>
    <D:getlastmodified/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""

            val response = httpClient.post(url) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, "Basic ${getBasicAuth()}")
                header("Depth", "1")
                contentType(io.ktor.http.ContentType.Application.Xml)
                setBody(propfindBody)
            }

            if (response.status.value == 207) { // Multi-Status
                parseWebDavResponse(response.body<String>())
            } else {
                logger.warn("Unexpected response status from Real-Debrid WebDAV: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Error listing files from Real-Debrid WebDAV", e)
            emptyList()
        }
    }

    override suspend fun getFileInfo(filePath: String): WebDavFile? {
        return try {
            val url = "$webdavBaseUrl${normalizePath(filePath)}"
            logger.debug("Getting file info from Real-Debrid WebDAV: $url")

            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getcontenttype/>
    <D:getlastmodified/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""

            val response = httpClient.post(url) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, "Basic ${getBasicAuth()}")
                header("Depth", "0")
                contentType(io.ktor.http.ContentType.Application.Xml)
                setBody(propfindBody)
            }

            if (response.status.value == 207) {
                parseFileInfo(response.body<String>(), filePath)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Error getting file info from Real-Debrid WebDAV", e)
            null
        }
    }

    override suspend fun getDownloadLink(filePath: String): String? {
        return "$webdavBaseUrl${normalizePath(filePath)}"
    }

    private fun getBasicAuth(): String {
        // Basic auth: base64(username:password)
        // Real-Debrid WebDAV requires separate credentials from the REST API
        val username = realDebridConfiguration.webdavUsername
        val password = realDebridConfiguration.webdavPassword

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            logger.warn(
                "Real-Debrid WebDAV credentials not configured. " +
                "Set REAL_DEBRID_WEBDAV_USERNAME and REAL_DEBRID_WEBDAV_PASSWORD environment variables."
            )
        }

        val credentials = "${username ?: ""}:${password ?: ""}"
        return java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    private fun normalizePath(path: String): String {
        var normalized = path.trim()
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.endsWith("/") && normalized != "/") {
            normalized = normalized.removeSuffix("/")
        }
        return normalized
    }

    private fun parseWebDavResponse(xmlResponse: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xmlResponse.toByteArray()))

            val responses = doc.getElementsByTagName("D:response")
            for (i in 0 until responses.length) {
                val response = responses.item(i) as Element
                val href = response.getElementsByTagName("D:href").item(0)?.textContent ?: continue
                val propstat = response.getElementsByTagName("D:propstat").item(0) as? Element ?: continue
                val prop = propstat.getElementsByTagName("D:prop").item(0) as? Element ?: continue

                val displayName = prop.getElementsByTagName("D:displayname").item(0)?.textContent
                val contentLength = prop.getElementsByTagName("D:getcontentlength").item(0)?.textContent?.toLongOrNull() ?: 0
                val contentType = prop.getElementsByTagName("D:getcontenttype").item(0)?.textContent
                val lastModified = prop.getElementsByTagName("D:getlastmodified").item(0)?.textContent
                val isCollection = prop.getElementsByTagName("D:resourcetype").item(0)
                    ?.let { (it as Element).getElementsByTagName("D:collection").length > 0 } ?: false

                val name = displayName ?: href.substringAfterLast("/")
                val path = href.removePrefix(webdavBaseUrl)

                files.add(
                    WebDavFile(
                        path = path,
                        name = name,
                        size = contentLength,
                        mimeType = contentType,
                        isDirectory = isCollection,
                        lastModified = lastModified?.let { parseLastModified(it) },
                        downloadLink = if (!isCollection) "$webdavBaseUrl$path" else null
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error parsing WebDAV response", e)
        }
        return files
    }

    private fun parseFileInfo(xmlResponse: String, filePath: String): WebDavFile? {
        val files = parseWebDavResponse(xmlResponse)
        return files.firstOrNull { it.path == normalizePath(filePath) }
    }

    private fun parseLastModified(lastModifiedStr: String): Instant? {
        return try {
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedStr, java.time.Instant::from)
        } catch (e: Exception) {
            null
        }
    }
}
