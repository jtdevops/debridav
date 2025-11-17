package io.skjaere.debridav.torrent

import com.fasterxml.jackson.annotation.JsonProperty
import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.TorrentMagnet
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class QBittorrentEmulationController(
    private val torrentService: TorrentService,
    private val resourceLoader: ResourceLoader,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val categoryService: CategoryService
) {
    companion object {
        const val API_VERSION = "2.9.3"
    }

    private val logger = LoggerFactory.getLogger(QBittorrentEmulationController::class.java)

    @GetMapping("/api/v2/app/webapiVersion")
    fun version(): String = API_VERSION

    @GetMapping("/api/v2/torrents/categories")
    fun categories(): Map<String, Category> {
        return categoryService.getAllCategories().associateBy { it.name!! }
    }

    @RequestMapping(
        path = ["api/v2/torrents/createCategory"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    private fun createCategory(@RequestParam category: String): Category {
        return categoryService.createCategory(category)
    }

    @GetMapping("api/v2/app/preferences")
    fun preferences(): String {
        return resourceLoader
            .getResource("classpath:qbittorrent_properties_response.json")
            .getContentAsString(Charsets.UTF_8)
            .replace(
                "%DOWNLOAD_DIR%",
                "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}"
            )
    }

    @GetMapping("/version/api")
    fun versionTwo(): ResponseEntity<String> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Not found")
    }

    data class TorrentsInfoRequestParams(
        val filter: String?,
        val category: String?,
        val tag: String?,
        val sort: String?,
        val reverse: Boolean?,
        val limit: Int?,
        val offset: Int?,
        val hashes: String?
    )

    @GetMapping("/api/v2/torrents/info")
    fun torrentsInfo(requestParams: TorrentsInfoRequestParams): List<TorrentsInfoResponse> {
        val category = requestParams.category ?: ""
        return torrentService
            .getTorrentsByCategory(category)
            //.filter { it.files?.firstOrNull()?.originalPath != null }
            .map {
                TorrentsInfoResponse.ofTorrent(it, debridavConfigurationProperties.mountPath, debridavConfigurationProperties.debugArrTorrentInfoContentPathSuffix)
            }
    }

    @GetMapping("/api/v2/torrents/properties")
    fun torrentsProperties(@RequestParam hash: TorrentHash): TorrentPropertiesResponse? {
        return torrentService.getTorrentByHash(hash)?.let {
            TorrentPropertiesResponse.ofTorrent(it)
        }
    }

    @Suppress("MagicNumber")
    @GetMapping("/api/v2/torrents/files")
    fun torrentFiles(@RequestParam hash: TorrentHash): List<TorrentFilesResponse>? {
        return torrentService.getTorrentByHash(hash)?.let {
            it.files.map { torrentFile ->
                TorrentFilesResponse(
                    0,
                    torrentFile.contents!!.originalPath!!,
                    torrentFile.size!!.toInt(),
                    100,
                    1,
                    true,
                    pieceRange = listOf(1, 100),
                    availability = 1.0.toFloat()
                )
            }
        }
    }

    @RequestMapping(
        path = ["/api/v2/torrents/add"],
        method = [RequestMethod.POST],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun addTorrent(
        @RequestPart urls: String?,
        @RequestPart torrents: MultipartFile?,
        @RequestPart category: String,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        // Resolve hostname from IP address
        val remoteAddr = request.remoteAddr
        val remoteInfo = try {
            val hostname = java.net.InetAddress.getByName(remoteAddr).hostName
            if (hostname != remoteAddr) {
                "$remoteAddr/$hostname"
            } else {
                remoteAddr
            }
        } catch (e: Exception) {
            remoteAddr
        }
        
        logger.info("Add torrent request received - category='{}', hasUrls={}, hasTorrents={}, fullQueryString='{}'", 
            category, urls != null, torrents != null, request.queryString)
        logger.debug("Request URI: {}, Method: {}, RemoteAddr: {}", request.requestURI, request.method, remoteInfo)
        
        // Log request parameters
        request.parameterMap.forEach { (key, values) ->
            logger.debug("Request parameter: {} = {}", key, values.joinToString(", "))
        }
        
        // Log URLs if present (truncate if too long)
        urls?.let {
            val truncatedUrl = if (it.length > 200) "${it.take(200)}..." else it
            logger.info("Adding torrent from URL(s): {}", truncatedUrl)
        }
        
        // Log torrent file info if present
        torrents?.let {
            logger.info("Adding torrent from file: name='{}', size={} bytes, contentType='{}'", 
                it.originalFilename, it.size, it.contentType)
        }
        
        val result = urls?.let {
            torrentService.addMagnet(category, TorrentMagnet(it))
        } ?: run {
            torrents?.let {
                torrentService.addTorrent(category, it)
            }
        }
        
        val responseStatus = when (result) {
            null -> {
                logger.warn("Add torrent request rejected: Request body must contain either urls or torrents")
                ResponseEntity.badRequest().body("Request body must contain either urls or torrents")
            }
            true -> {
                logger.info("Successfully added torrent to category '{}'", category)
                ResponseEntity.ok("ok")
            }
            false -> {
                logger.warn("Failed to add torrent to category '{}'", category)
                ResponseEntity.unprocessableEntity().build()
            }
        }
        
        return responseStatus
    }

    @RequestMapping(
        path = ["/api/v2/torrents/add"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun addTorrentFile(
        request: AddTorrentRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<String> {
        // Resolve hostname from IP address
        val remoteAddr = httpRequest.remoteAddr
        val remoteInfo = try {
            val hostname = java.net.InetAddress.getByName(remoteAddr).hostName
            if (hostname != remoteAddr) {
                "$remoteAddr/$hostname"
            } else {
                remoteAddr
            }
        } catch (e: Exception) {
            remoteAddr
        }
        
        // Truncate URL if too long for logging
        val truncatedUrl = if (request.urls.length > 200) "${request.urls.take(200)}..." else request.urls
        
        logger.info("Add torrent request received (form-urlencoded) - category='{}', urlLength={}, fullQueryString='{}'", 
            request.category, request.urls.length, httpRequest.queryString)
        logger.debug("Request URI: {}, Method: {}, RemoteAddr: {}", httpRequest.requestURI, httpRequest.method, remoteInfo)
        logger.debug("Torrent URL(s): {}", truncatedUrl)
        
        // Log all request parameters for debugging
        httpRequest.parameterMap.forEach { (key, values) ->
            logger.debug("Request parameter: {} = {}", key, values.joinToString(", "))
        }
        
        val success = torrentService.addMagnet(request.category, TorrentMagnet(request.urls))
        
        return if (success) {
            logger.info("Successfully added torrent to category '{}'", request.category)
            ResponseEntity.ok("")
        } else {
            logger.warn("Failed to add torrent to category '{}'", request.category)
            ResponseEntity.unprocessableEntity().build()
        }
    }

    data class AddTorrentRequest(
        val urls: String,
        val category: String
    )

    @RequestMapping(
        path = ["api/v2/torrents/delete"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun deleteTorrents(
        @RequestParam hashes: List<String>
    ): ResponseEntity<String> {
        hashes.forEach {
            torrentService.deleteTorrentByHash(it)
        }

        return ResponseEntity.ok("ok")
    }

    data class TorrentFilesResponse(
        val index: Int,
        val name: String,
        val size: Int,
        val progress: Int,
        val priority: Int,
        @JsonProperty("is_seed")
        val isSeed: Boolean,
        @JsonProperty("piece_range")
        val pieceRange: List<Int>,
        val availability: Float
    )
}
