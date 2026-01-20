package io.skjaere.debridav.debrid.folder.webdav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('premiumize')}")
class PremiumizeWebDavClient(
    private val premiumizeConfiguration: PremiumizeConfigurationProperties,
    private val httpClient: HttpClient
) : DebridWebDavClient {
    private val logger = LoggerFactory.getLogger(PremiumizeWebDavClient::class.java)

    // Note: Premiumize WebDAV endpoint - verify actual endpoint with Premiumize documentation
    private val webdavBaseUrl = "https://webdav.premiumize.me"

    override suspend fun listFiles(folderPath: String): List<WebDavFile> {
        return try {
            val url = "$webdavBaseUrl${normalizePath(folderPath)}"
            logger.debug("Listing files from Premiumize WebDAV: $url")

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

            val response = httpClient.request(url) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, "Basic ${getBasicAuth()}")
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                setBody(propfindBody)
            }

            val responseBody = response.body<String>()
            logger.debug("Premiumize WebDAV response status: ${response.status}")
            logger.trace("Premiumize WebDAV response body: $responseBody")

            if (response.status.value == 207) { // Multi-Status
                val files = parseWebDavResponse(responseBody)
                logger.debug("Parsed ${files.size} files from Premiumize WebDAV response")
                files.forEach { file ->
                    logger.debug("  - ${file.name} (${if (file.isDirectory) "directory" else "file"}, path: ${file.path})")
                }
                files
            } else {
                logger.warn("Unexpected response status from Premiumize WebDAV: ${response.status}")
                logger.warn("Response body: $responseBody")
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Error listing files from Premiumize WebDAV", e)
            emptyList()
        }
    }

    override suspend fun getFileInfo(filePath: String): WebDavFile? {
        return try {
            val url = "$webdavBaseUrl${normalizePath(filePath)}"
            logger.debug("Getting file info from Premiumize WebDAV: $url")

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

            val response = httpClient.request(url) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, "Basic ${getBasicAuth()}")
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                setBody(propfindBody)
            }

            if (response.status.value == 207) {
                parseFileInfo(response.body<String>(), filePath)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Error getting file info from Premiumize WebDAV", e)
            null
        }
    }

    override suspend fun getDownloadLink(filePath: String): String? {
        // For WebDAV, the download link is typically the WebDAV URL itself
        return "$webdavBaseUrl${normalizePath(filePath)}"
    }

    private fun getBasicAuth(): String {
        // Basic auth: base64(username:password)
        // Premiumize WebDAV requires separate credentials from the REST API
        val username = premiumizeConfiguration.webdavUsername
        val password = premiumizeConfiguration.webdavPassword

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            logger.warn(
                "Premiumize WebDAV credentials not configured. " +
                "Set PREMIUMIZE_WEBDAV_USERNAME and PREMIUMIZE_WEBDAV_PASSWORD environment variables."
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
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xmlResponse.toByteArray()))

            // Try different namespace prefixes that WebDAV servers might use
            val responses = findElements(doc, "response")
            logger.debug("Found ${responses.size} response elements in WebDAV XML")

            for (response in responses) {
                val href = findElementText(response, "href") ?: continue
                val propstat = findElement(response, "propstat") ?: continue
                val prop = findElement(propstat, "prop") ?: continue

                val displayName = findElementText(prop, "displayname")
                val contentLength = findElementText(prop, "getcontentlength")?.toLongOrNull() ?: 0
                val contentType = findElementText(prop, "getcontenttype")
                val lastModified = findElementText(prop, "getlastmodified")
                val resourceType = findElement(prop, "resourcetype")
                val isCollection = resourceType?.let { 
                    findElement(it, "collection") != null 
                } ?: false

                val name = displayName?.takeIf { it.isNotBlank() } 
                    ?: java.net.URLDecoder.decode(href.substringAfterLast("/"), "UTF-8")
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

    /**
     * Find elements by local name, ignoring namespace prefix.
     * Handles both "D:response" and "response" formats.
     */
    private fun findElements(parent: org.w3c.dom.Node, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                if (child.localName == localName || child.tagName.endsWith(":$localName") || child.tagName == localName) {
                    result.add(child)
                }
                // Recursively search in child elements
                result.addAll(findElements(child, localName))
            }
        }
        return result
    }

    /**
     * Find first element by local name within a parent element.
     */
    private fun findElement(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                if (child.localName == localName || child.tagName.endsWith(":$localName") || child.tagName == localName) {
                    return child
                }
            }
        }
        return null
    }

    /**
     * Find element text by local name within a parent element.
     */
    private fun findElementText(parent: Element, localName: String): String? {
        return findElement(parent, localName)?.textContent
    }

    private fun parseFileInfo(xmlResponse: String, filePath: String): WebDavFile? {
        // Similar parsing logic for single file
        val files = parseWebDavResponse(xmlResponse)
        return files.firstOrNull { it.path == normalizePath(filePath) }
    }

    private fun parseLastModified(lastModifiedStr: String): Instant? {
        return try {
            // WebDAV uses RFC 1123 format: "Wed, 21 Oct 2015 07:28:00 GMT"
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedStr, java.time.Instant::from)
        } catch (e: Exception) {
            null
        }
    }
}
