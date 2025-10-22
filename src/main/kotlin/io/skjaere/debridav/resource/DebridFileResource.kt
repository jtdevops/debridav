package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import org.apache.commons.io.FileUtils
import java.net.InetAddress
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.UnknownDebridLinkError
import io.skjaere.debridav.stream.StreamResult
import io.skjaere.debridav.stream.StreamingService
import io.skjaere.debridav.stream.HttpRequestInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Instant
import java.util.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class DebridFileResource(
    val file: RemotelyCachedEntity,
    fileService: DatabaseFileService,
    private val streamingService: StreamingService,
    private val debridService: DebridLinkService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val arrRequestDetector: ArrRequestDetector
) : AbstractResource(fileService, file as DbEntity), GetableResource, DeletableResource {
    private val debridFileContents: DebridFileContents = (dbItem as RemotelyCachedEntity).contents!!
    private val logger = LoggerFactory.getLogger(DebridClient::class.java)

    override fun getUniqueId(): String {
        return dbItem.id!!.toString()
    }

    override fun getName(): String {
        return dbItem.name!!.replace(".debridfile", "")
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.ofEpochMilli(dbItem.lastModified!!))
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(dbItem)
    }

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) = runBlocking<Unit> {
        out.use { outputStream ->
            // Track incoming byte range request and HTTP headers
            val httpRequestInfo = if (debridavConfigurationProperties.enableStreamingDownloadTracking) {
                extractHttpRequestInfo(range, file)
            } else {
                HttpRequestInfo()
            }
            
            if (debridavConfigurationProperties.enableReactiveLinkRefresh) {
                // New reactive behavior: only refresh links when streaming fails
                logger.info("Using reactive link refresh for ${file.name}")

                var currentCachedFile = debridService.getCachedFileCached(file)
                if (currentCachedFile != null) {
                    logger.info("streaming: {} range {} from {}", currentCachedFile.path, range, currentCachedFile.provider)

                    val result = try {
                        streamingService.streamContents(
                            currentCachedFile,
                            range,
                            outputStream,
                            file,
                            httpRequestInfo
                        )
                    } catch (_: CancellationException) {
                        this.coroutineContext.cancelChildren()
                        StreamResult.OK
                    }

                    if (result != StreamResult.OK) {
                        // Try to refresh the failed link and retry once
                        logger.info("Streaming failed for ${currentCachedFile.path}, attempting to refresh link and retry")
                        val refreshedLink = runBlocking { debridService.refreshLinkOnError(file, currentCachedFile) }

                        if (refreshedLink != null) {
                            logger.info("Retrying streaming with refreshed link for ${refreshedLink.path}")
                            val retryResult = try {
                                streamingService.streamContents(
                                    refreshedLink,
                                    range,
                                    outputStream,
                                    file,
                                    httpRequestInfo
                                )
                            } catch (_: CancellationException) {
                                this.coroutineContext.cancelChildren()
                                StreamResult.OK
                            }

                            if (retryResult == StreamResult.OK) {
                                logger.info("Successfully retried streaming with refreshed link for ${refreshedLink.path}")
                            } else {
                                // If retry also failed, update with the error status
                                val updatedDebridLink = mapResultToDebridFile(retryResult, refreshedLink)
                                file.contents!!.replaceOrAddDebridLink(updatedDebridLink)
                                fileService.saveDbEntity(file)
                            }
                        } else {
                            // If link refresh failed, update with the original error
                            val updatedDebridLink = mapResultToDebridFile(result, currentCachedFile)
                            file.contents!!.replaceOrAddDebridLink(updatedDebridLink)
                            fileService.saveDbEntity(file)
                        }
                    }
                }
            } else {
                // Original behavior: proactively refresh links before streaming
                debridService.refreshLinksProactively(file)

                debridService.getCachedFileCached(file)
                    ?.let { cachedFile ->
                        logger.info("streaming: {} range {} from {}", cachedFile.path, range, cachedFile.provider)
                        val result = try {
                            streamingService.streamContents(
                                cachedFile,
                                range,
                                outputStream,
                                file,
                                httpRequestInfo
                            )
                        } catch (_: CancellationException) {
                            this.coroutineContext.cancelChildren()
                            StreamResult.OK
                        }
                        if (result != StreamResult.OK) {
                            val updatedDebridLink = mapResultToDebridFile(result, cachedFile)
                            file.contents!!.replaceOrAddDebridLink(updatedDebridLink)
                            fileService.saveDbEntity(file)
                        }
                    } ?: run {
                        if (file.isNoLongerCached(debridavConfigurationProperties.debridClients)
                            && debridavConfigurationProperties.shouldDeleteNonWorkingFiles
                        ) {
                            fileService.handleNoLongerCachedFile(file)
                        }

                        logger.info("No working link found for ${debridFileContents.originalPath}")
                    }
            }
        }
    }

    private fun mapResultToDebridFile(
        result: StreamResult,
        cachedFile: CachedFile
    ): DebridFile = when (result) {
        StreamResult.DEAD_LINK -> MissingFile(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.IO_ERROR -> NetworkError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.PROVIDER_ERROR -> ProviderError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.CLIENT_ERROR -> ClientError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.UNKNOWN_ERROR -> UnknownDebridLinkError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.OK -> error("how?")
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String {
        // Check if we should serve local video for ARR requests
        if (arrRequestDetector.isArrRequest()) {
            // Get MIME type from the local video file
            val localVideoPath = debridavConfigurationProperties.rcloneArrsLocalVideoFilePath
            if (localVideoPath != null) {
                val localVideoFile = java.io.File(localVideoPath)
                if (localVideoFile.exists() && localVideoFile.isFile) {
                    val fileName = localVideoFile.name.lowercase()
                    return when {
                        fileName.endsWith(".mp4") -> "video/mp4"
                        fileName.endsWith(".avi") -> "video/x-msvideo"
                        fileName.endsWith(".mkv") -> "video/x-matroska"
                        fileName.endsWith(".mov") -> "video/quicktime"
                        fileName.endsWith(".wmv") -> "video/x-ms-wmv"
                        fileName.endsWith(".flv") -> "video/x-flv"
                        fileName.endsWith(".webm") -> "video/webm"
                        fileName.endsWith(".m4v") -> "video/x-m4v"
                        else -> "video/mp4" // fallback
                    }
                }
            }
            return "video/mp4" // fallback
        }
        return "video/mp4"
    }

    override fun getContentLength(): Long {
        // Check if we should serve local video for ARR requests
        if (arrRequestDetector.isArrRequest()) {
            // Get file size from the local video file
            val localVideoPath = debridavConfigurationProperties.rcloneArrsLocalVideoFilePath
            if (localVideoPath != null) {
                val localVideoFile = java.io.File(localVideoPath)
                if (localVideoFile.exists() && localVideoFile.isFile) {
                    return localVideoFile.length()
                }
            }
            return 0L // fallback
        }
        return file.contents!!.size!!.toLong()
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return modifiedDate
    }

    private fun extractHttpRequestInfo(range: Range?, file: RemotelyCachedEntity): HttpRequestInfo {
        val httpHeaders = mutableMapOf<String, String>()
        var sourceIpAddress: String? = null
        var sourceHostname: String? = null

        val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        requestAttributes?.request?.let { httpRequest ->
            // Extract HTTP headers as Map
            httpRequest.headerNames?.toList()?.forEach { headerName ->
                httpRequest.getHeaders(headerName)?.toList()?.forEach { headerValue ->
                    httpHeaders[headerName] = headerValue
                }
            }

            // Get source IP address
            sourceIpAddress = httpRequest.remoteAddr
                ?: httpRequest.getHeader("X-Forwarded-For")?.split(",")?.first()?.trim()
                ?: httpRequest.getHeader("X-Real-IP")
                ?: "unknown"

            // Try to resolve hostname from IP address
            if (sourceIpAddress != "unknown") {
                try {
                    sourceHostname = InetAddress.getByName(sourceIpAddress).hostName
                } catch (e: Exception) {
                    // If hostname resolution fails, leave it null
                }
            }
        }

        val requestedSize = if (range != null) (range.finish - range.start + 1) else file.contents?.size ?: 0L

        val sourceInfo = if (sourceHostname != null && sourceHostname != sourceIpAddress) {
            "$sourceIpAddress/$sourceHostname"
        } else {
            sourceIpAddress
        }

        logger.info("INCOMING_RANGE_REQUEST: file={}, range={}-{}, size={} ({}), source_ip={}, headers_count={}",
            file.name ?: "unknown", range?.start, range?.finish, FileUtils.byteCountToDisplaySize(requestedSize), requestedSize, sourceInfo, httpHeaders.size)

        return HttpRequestInfo(httpHeaders, sourceIpAddress, sourceHostname)
    }
}
