package io.skjaere.debridav.webdav.folder.webdav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.skjaere.debridav.webdav.folder.WebDavProviderConfiguration
import io.skjaere.debridav.webdav.folder.WebDavAuthType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Generic WebDAV client that works with any WebDAV provider configuration
 * Supports both Basic and Bearer token authentication
 */
@Component
class GenericWebDavClient(
    private val httpClient: HttpClient
) {
    private val logger = LoggerFactory.getLogger(GenericWebDavClient::class.java)

    /**
     * Lists files in the specified folder path
     */
    suspend fun listFiles(config: WebDavProviderConfiguration, folderPath: String): List<WebDavFile> {
        return try {
            val url = "${config.url}${normalizePath(folderPath)}"
            logger.debug("Listing files from WebDAV provider '${config.name}': $url")

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
                header(HttpHeaders.Authorization, getAuthHeader(config))
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                setBody(propfindBody)
            }

            val responseBody = response.body<String>()
            logger.debug("WebDAV provider '${config.name}' response status: ${response.status}")
            logger.trace("WebDAV provider '${config.name}' response body: $responseBody")

            if (response.status.value == 207) { // Multi-Status
                val files = parseWebDavResponse(responseBody, config.url)
                logger.debug("Parsed ${files.size} files from WebDAV provider '${config.name}'")
                files.forEach { file ->
                    logger.debug("  - ${file.name} (${if (file.isDirectory) "directory" else "file"}, path: ${file.path})")
                }
                files
            } else {
                logger.warn("Unexpected response status from WebDAV provider '${config.name}': ${response.status}")
                logger.warn("Response body: $responseBody")
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Error listing files from WebDAV provider '${config.name}'", e)
            emptyList()
        }
    }

    /**
     * Gets file information for a specific file
     */
    suspend fun getFileInfo(config: WebDavProviderConfiguration, filePath: String): WebDavFile? {
        return try {
            val url = "${config.url}${normalizePath(filePath)}"
            logger.debug("Getting file info from WebDAV provider '${config.name}': $url")

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
                header(HttpHeaders.Authorization, getAuthHeader(config))
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                setBody(propfindBody)
            }

            if (response.status.value == 207) {
                parseFileInfo(response.body<String>(), filePath, config.url)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Error getting file info from WebDAV provider '${config.name}'", e)
            null
        }
    }

    /**
     * Gets a download/streaming link for a file
     */
    fun getDownloadLink(config: WebDavProviderConfiguration, filePath: String): String {
        return "${config.url}${normalizePath(filePath)}"
    }

    private fun getAuthHeader(config: WebDavProviderConfiguration): String {
        return when (config.authType) {
            WebDavAuthType.BASIC -> {
                val credentials = "${config.username ?: ""}:${config.password ?: ""}"
                "Basic ${java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())}"
            }
            WebDavAuthType.BEARER -> {
                "Bearer ${config.bearerToken ?: ""}"
            }
        }
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

    private fun parseWebDavResponse(xmlResponse: String, baseUrl: String): List<WebDavFile> {
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
                    ?: URLDecoder.decode(href.substringAfterLast("/"), "UTF-8")
                val path = href.removePrefix(baseUrl)

                files.add(
                    WebDavFile(
                        path = path,
                        name = name,
                        size = contentLength,
                        mimeType = contentType,
                        isDirectory = isCollection,
                        lastModified = lastModified?.let { parseLastModified(it) },
                        downloadLink = if (!isCollection) "$baseUrl$path" else null
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

    private fun parseFileInfo(xmlResponse: String, filePath: String, baseUrl: String): WebDavFile? {
        val files = parseWebDavResponse(xmlResponse, baseUrl)
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
