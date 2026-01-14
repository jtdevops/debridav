package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.util.ByteFormatUtil
import java.net.InetAddress
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.IptvFile
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.UnknownDebridLinkError
import io.skjaere.debridav.stream.StreamResult
import io.skjaere.debridav.stream.StreamingService
import io.skjaere.debridav.stream.HttpRequestInfo
import io.skjaere.debridav.util.VideoFileExtensions
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
            
            var currentCachedFile = debridService.getCachedFileCached(file)
            if (currentCachedFile != null) {
                logger.debug("streaming: {} range {} from {}", currentCachedFile.path, range, currentCachedFile.provider)

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
                    // Check if this is IPTV content - IPTV links don't need refreshing
                    val isIptvContent = file.contents is DebridIptvContent || currentCachedFile.provider == DebridProvider.IPTV
                    
                    if (isIptvContent) {
                        logger.debug("Streaming failed for IPTV file ${currentCachedFile.path}, skipping link refresh - IPTV links don't use debrid clients")
                        // For IPTV content, preserve the valid IptvFile and don't replace it with error types
                        // IPTV links are stable and don't change, so we shouldn't update them at all
                        val existingLinks = file.contents!!.debridLinks
                        val hasValidIptvFile = existingLinks.any { it is IptvFile }
                        
                        if (!hasValidIptvFile) {
                            // Only update if there's no valid IptvFile (shouldn't happen normally)
                            logger.warn("No valid IptvFile found for IPTV content, updating with error status")
                            val updatedDebridLink = mapResultToDebridFile(result, currentCachedFile)
                            file.contents!!.replaceOrAddDebridLink(updatedDebridLink)
                            fileService.saveDbEntity(file)
                        } else {
                            // IPTV links are stable - don't update them at all, even on streaming errors
                            logger.debug("Preserved IptvFile for IPTV content despite streaming error - IPTV links remain unchanged")
                        }
                    } else {
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
                if (file.isNoLongerCached(debridavConfigurationProperties.debridClients)
                    && debridavConfigurationProperties.shouldDeleteNonWorkingFiles
                ) {
                    fileService.handleNoLongerCachedFile(file)
                }

                logger.info("No working link found for ${debridFileContents.originalPath}")
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
            val fileName = file.name ?: "unknown"
            val fullPath = file.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
            
            // Check if IPTV provider should bypass local video serving
            val iptvProviderName = if (file.contents is io.skjaere.debridav.fs.DebridIptvContent) {
                (file.contents as io.skjaere.debridav.fs.DebridIptvContent).iptvProviderName
            } else {
                null
            }
            val shouldBypass = debridavConfigurationProperties.shouldBypassLocalVideoForIptvProvider(iptvProviderName)
            
            if (shouldBypass) {
                // When bypass is configured, use database content type (don't use local video files)
                val mimeType = file.mimeType ?: "video/mp4"
                logger.debug("LOCAL_VIDEO_BYPASS_CONTENT_TYPE_EXTERNAL: file={}, iptvProvider={}, mimeType={} (from external IPTV provider, bypass enabled)", 
                    fileName, iptvProviderName, mimeType)
                return mimeType
            }
            
            // Check if the file path matches the configured regex pattern
            if (debridavConfigurationProperties.shouldServeLocalVideoForPath(fullPath)) {
                // Get MIME type from the local video file
                val localVideoPath = debridavConfigurationProperties.getLocalVideoFilePath(fileName)
                if (localVideoPath != null) {
                    val localVideoFile = java.io.File(localVideoPath)
                    if (localVideoFile.exists() && localVideoFile.isFile) {
                        val localFileName = localVideoFile.name.lowercase()
                        return when {
                            localFileName.endsWith(".mp4") -> "video/mp4"
                            localFileName.endsWith(".avi") -> "video/x-msvideo"
                            localFileName.endsWith(".mkv") -> "video/x-matroska"
                            localFileName.endsWith(".mov") -> "video/quicktime"
                            localFileName.endsWith(".wmv") -> "video/x-ms-wmv"
                            localFileName.endsWith(".flv") -> "video/x-flv"
                            localFileName.endsWith(".webm") -> "video/webm"
                            localFileName.endsWith(".m4v") -> "video/x-m4v"
                            else -> "video/mp4" // fallback
                        }
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
            val fileName = file.name ?: "unknown"
            val fullPath = file.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
            
            // Check if IPTV provider should bypass local video serving
            val iptvProviderName = if (file.contents is io.skjaere.debridav.fs.DebridIptvContent) {
                (file.contents as io.skjaere.debridav.fs.DebridIptvContent).iptvProviderName
            } else {
                null
            }
            val shouldBypass = debridavConfigurationProperties.shouldBypassLocalVideoForIptvProvider(iptvProviderName)
            
            if (shouldBypass) {
                // When bypass is configured, use external file size directly (don't use local video files)
                val externalFileSize = file.contents!!.size!!.toLong()
                logger.debug("LOCAL_VIDEO_BYPASS_CONTENT_LENGTH_EXTERNAL: file={}, iptvProvider={}, size={} bytes (from external IPTV provider, bypass enabled)", 
                    fileName, iptvProviderName, externalFileSize)
                return externalFileSize
            }
            
            // Check if the file path matches the configured regex pattern
            if (debridavConfigurationProperties.shouldServeLocalVideoForPath(fullPath)) {
                // Only apply local video serving to media files, not subtitles or other files
                if (isMediaFile(fileName)) {
                    // Get the external file size to check against the minimum size threshold
                    val externalFileSize = file.contents!!.size!!.toLong()
                    
                    // Check if the file is large enough to use local video serving
                    if (debridavConfigurationProperties.shouldUseLocalVideoForSize(externalFileSize)) {
                        logger.debug("LOCAL_VIDEO_PATH_MATCHED: file={}, fullPath={}, regex={}, isMediaFile=true, externalSize={} bytes, minSizeKb={}", 
                            fileName, fullPath, debridavConfigurationProperties.rcloneArrsLocalVideoPathRegex, 
                            externalFileSize, debridavConfigurationProperties.rcloneArrsLocalVideoMinSizeKb)
                        
                        // Get file size from the local video file
                        val localVideoPath = debridavConfigurationProperties.getLocalVideoFilePath(fileName)
                        if (localVideoPath != null) {
                            val localVideoFile = java.io.File(localVideoPath)
                            if (localVideoFile.exists() && localVideoFile.isFile) {
                                val localFileSize = localVideoFile.length()
                                logger.debug("LOCAL_VIDEO_CONTENT_LENGTH: file={}, localPath={}, size={} bytes", 
                                    fileName, localVideoPath, localFileSize)
                                return localFileSize
                            } else {
                                logger.warn("LOCAL_VIDEO_FILE_NOT_FOUND: file={}, localPath={}, exists={}, isFile={}", 
                                    fileName, localVideoPath, localVideoFile.exists(), localVideoFile.isFile)
                            }
                        } else {
                            logger.warn("LOCAL_VIDEO_PATH_NULL: file={}, no local video path found", fileName)
                        }
                    } else {
                        logger.debug("LOCAL_VIDEO_PATH_MATCHED_BUT_TOO_SMALL: file={}, fullPath={}, regex={}, isMediaFile=true, externalSize={} bytes, minSizeKb={}", 
                            fileName, fullPath, debridavConfigurationProperties.rcloneArrsLocalVideoPathRegex, 
                            externalFileSize, debridavConfigurationProperties.rcloneArrsLocalVideoMinSizeKb)
                    }
                } else {
                    logger.debug("LOCAL_VIDEO_PATH_MATCHED_BUT_NOT_MEDIA: file={}, fullPath={}, regex={}, isMediaFile=false", 
                        fileName, fullPath, debridavConfigurationProperties.rcloneArrsLocalVideoPathRegex)
                }
            } else {
                logger.debug("LOCAL_VIDEO_PATH_NOT_MATCHED: file={}, fullPath={}, regex={}", 
                    fileName, fullPath, debridavConfigurationProperties.rcloneArrsLocalVideoPathRegex)
            }
        }
        
        // Fallback to external file size
        val externalFileSize = file.contents!!.size!!.toLong()
        val workingDebridFile = file.contents!!.debridLinks.firstOrNull { it is CachedFile }
        // Determine provider label for logging - use IPTV for IPTV content, otherwise use debrid provider
        val providerLabel = if (file.contents is DebridIptvContent || workingDebridFile?.provider == DebridProvider.IPTV) {
            DebridProvider.IPTV.toString()
        } else {
            workingDebridFile?.provider?.toString() ?: "null"
        }
        logger.trace("EXTERNAL_FILE_CONTENT_LENGTH: file={}, size={} bytes, provider={}", 
            file.name ?: "unknown", externalFileSize, providerLabel)
        return externalFileSize
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }
    
    /**
     * Checks if the given filename is a media file based on its extension.
     * Only media files should use local video serving, not subtitles or other files.
     */
    private fun isMediaFile(fileName: String): Boolean {
        return VideoFileExtensions.isVideoFile(fileName)
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

        logger.debug("INCOMING_RANGE_REQUEST: file={}, range={}-{}, size={} ({}), source_ip={}, headers_count={}",
            file.name ?: "unknown", range?.start, range?.finish, ByteFormatUtil.byteCountToDisplaySize(requestedSize), requestedSize, sourceInfo, httpHeaders.size)

        return HttpRequestInfo(httpHeaders, sourceIpAddress, sourceHostname)
    }
}
