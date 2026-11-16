package io.skjaere.debridav.torrent

import com.fasterxml.jackson.annotation.JsonProperty
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.configuration.RuntimeConfigurationService
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.fs.LocalEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity
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
import java.util.concurrent.atomic.AtomicReference

@RestController
class QBittorrentEmulationController(
    private val torrentService: TorrentService,
    private val resourceLoader: ResourceLoader,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val categoryService: CategoryService,
    private val torrentToMagnetConverter: TorrentToMagnetConverter,
    private val runtimeConfigurationService: RuntimeConfigurationService
) {
    companion object {
        /** WebAPI protocol version (qBittorrent 4.6.x era). */
        const val API_VERSION = "2.9.3"

        /** App version string HolyArrs reads from GET /api/v2/app/version. */
        const val APP_VERSION = "v4.6.7"
    }

    private val logger = LoggerFactory.getLogger(QBittorrentEmulationController::class.java)

    /** Last hash set returned by sync/maindata, used to compute removals. */
    private val lastSyncHashes = AtomicReference<Set<String>>(emptySet())

    @GetMapping("/api/v2/app/webapiVersion")
    fun webapiVersion(): String = API_VERSION

    /**
     * HolyArrs (and other clients) call this first on Test/Health.
     * Returns plain text like real qBittorrent, e.g. `v4.6.7`.
     */
    @GetMapping("/api/v2/app/version", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun appVersion(): String = APP_VERSION

    /**
     * No-op login for clients that send credentials. HolyArrs skips this when
     * username/password are empty; others may still POST here.
     */
    @RequestMapping(
        path = ["/api/v2/auth/login"],
        method = [RequestMethod.POST],
        produces = [MediaType.TEXT_PLAIN_VALUE]
    )
    fun login(
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) password: String?
    ): ResponseEntity<String> {
        logger.debug("qBittorrent auth/login (emulated) for user='{}'", username ?: "")
        return ResponseEntity.ok("Ok.")
    }

    @GetMapping("/api/v2/torrents/categories")
    fun categories(): Map<String, QBittorrentCategoryResponse> {
        // Absolute save path matching app/preferences save_path so HolyArrs
        // and other clients can map folders without a relative/absolute mismatch.
        val defaultSave = defaultSavePath()
        return categoryService.getAllCategories().associate { category ->
            val name = category.name!!
            name to QBittorrentCategoryResponse(name = name, savePath = defaultSave)
        }
    }

    @RequestMapping(
        path = ["/api/v2/torrents/createCategory"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun createCategory(@RequestParam category: String): ResponseEntity<String> {
        if (categoryService.findByName(category) != null) {
            // Real qBittorrent answers 409 when the category already exists.
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Category name already exists")
        }
        categoryService.createCategory(category)
        return ResponseEntity.ok("")
    }

    @GetMapping("/api/v2/app/preferences")
    fun preferences(): String {
        return resourceLoader
            .getResource("classpath:qbittorrent_properties_response.json")
            .getContentAsString(Charsets.UTF_8)
            .replace(
                "%DOWNLOAD_DIR%",
                defaultSavePath()
            )
    }

    /**
     * Incremental torrent feed used by HolyArrs to watch downloads.
     * rid=0 → full snapshot; later rid → new torrents + removals.
     */
    @GetMapping("/api/v2/sync/maindata")
    fun syncMaindata(@RequestParam(defaultValue = "0") rid: Long): SyncMaindataResponse {
        val torrentMap = torrentInfoList(category = null, hashes = null)
            .associateBy { it.hash.lowercase() }
        val currentHashes = torrentMap.keys
        val nextRid = rid + 1

        if (rid == 0L) {
            lastSyncHashes.set(currentHashes)
            return SyncMaindataResponse(
                rid = nextRid,
                fullUpdate = true,
                torrents = torrentMap,
                torrentsRemoved = emptyList(),
                serverState = serverState()
            )
        }

        val previous = lastSyncHashes.getAndSet(currentHashes)
        val added = currentHashes - previous
        val removed = previous - currentHashes
        return SyncMaindataResponse(
            rid = nextRid,
            fullUpdate = false,
            torrents = torrentMap.filterKeys { it in added },
            torrentsRemoved = removed.toList(),
            serverState = serverState()
        )
    }

    /** No-op controls HolyArrs may call; torrents here are already complete. */
    @RequestMapping(
        path = [
            "/api/v2/torrents/stop",
            "/api/v2/torrents/pause",
            "/api/v2/torrents/start",
            "/api/v2/torrents/resume",
            "/api/v2/torrents/recheck",
            "/api/v2/torrents/reannounce"
        ],
        method = [RequestMethod.POST]
    )
    fun noopTorrentControl(@RequestParam(required = false) hashes: String?): ResponseEntity<String> {
        logger.debug("qBittorrent control no-op hashes='{}'", hashes)
        return ResponseEntity.ok("")
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

    data class SyncMaindataResponse(
        val rid: Long,
        @JsonProperty("full_update")
        val fullUpdate: Boolean,
        val torrents: Map<String, TorrentsInfoResponse> = emptyMap(),
        @JsonProperty("torrents_removed")
        val torrentsRemoved: List<String> = emptyList(),
        @JsonProperty("server_state")
        val serverState: Map<String, Any> = emptyMap()
    )

    @GetMapping("/api/v2/torrents/info")
    fun torrentsInfo(requestParams: TorrentsInfoRequestParams): List<TorrentsInfoResponse> {
        return torrentInfoList(requestParams.category, requestParams.hashes)
    }

    private fun torrentInfoList(category: String?, hashes: String?): List<TorrentsInfoResponse> {
        val debugSuffix = runtimeConfigurationService.getEffectiveValue(
            "debugArrTorrentInfoContentPathSuffix",
            debridavConfigurationProperties.debugArrTorrentInfoContentPathSuffix
        )
        val mountPath = debridavConfigurationProperties.mountPath
        val downloadRoot = defaultSavePath()
        val hashFilter = parseHashList(hashes)

        val torrents = when {
            !hashFilter.isNullOrEmpty() -> hashFilter.mapNotNull { torrentService.getTorrentByHashString(it) }
            !category.isNullOrBlank() -> torrentService.getTorrentsByCategory(category)
            else -> torrentService.getAllLiveTorrents()
        }

        return torrents
            .filter { it.status == Status.LIVE }
            .map { TorrentsInfoResponse.ofTorrent(it, mountPath, downloadRoot, debugSuffix) }
    }

    private fun defaultSavePath(): String =
        "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}"

    private fun serverState(): Map<String, Any> = mapOf(
        "connection_status" to "connected",
        "dl_info_speed" to 0,
        "up_info_speed" to 0,
        "dl_rate_limit" to 0,
        "up_rate_limit" to 0,
        "free_space_on_disk" to 1_000_000_000_000L,
        "use_alt_speed_limits" to false
    )

    /** qBittorrent sends one or more hashes separated by `|`. */
    private fun parseHashList(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
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
                val originalPath = when (torrentFile) {
                    is RemotelyCachedEntity ->
                        torrentFile.contents?.originalPath ?: torrentFile.name!!
                    is LocalEntity -> torrentFile.name!!
                    else -> torrentFile.name ?: "unknown"
                }
                TorrentFilesResponse(
                    0,
                    originalPath,
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
        
        logger.debug("Add torrent request received - category='{}', hasUrls={}, hasTorrents={}, fullQueryString='{}'", 
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
                // Get torrent details for logging
                val magnet = urls?.let { TorrentMagnet(it) } ?: torrents?.let { 
                    torrentToMagnetConverter.convertTorrentToMagnet(it.bytes) 
                }
                val torrentName = magnet?.let { TorrentService.getNameFromMagnet(it) } ?: "unknown"
                val torrentHash = magnet?.let { TorrentService.getHashFromMagnet(it) }
                val fileCount = torrentHash?.let { hash ->
                    torrentService.getTorrentByHash(hash)?.files?.size
                } ?: 0
                
                logger.info("Torrent added successfully: name='{}', category='{}', files={}", 
                    torrentName, category, fileCount)
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
        
        logger.debug("Add torrent request received (form-urlencoded) - category='{}', urlLength={}, fullQueryString='{}'", 
            request.category, request.urls.length, httpRequest.queryString)
        logger.debug("Request URI: {}, Method: {}, RemoteAddr: {}", httpRequest.requestURI, httpRequest.method, remoteInfo)
        logger.debug("Torrent URL(s): {}", truncatedUrl)
        
        // Log all request parameters for debugging
        httpRequest.parameterMap.forEach { (key, values) ->
            logger.debug("Request parameter: {} = {}", key, values.joinToString(", "))
        }
        
        val success = torrentService.addMagnet(request.category, TorrentMagnet(request.urls))
        
        return if (success) {
            // Get torrent details for logging
            val magnet = TorrentMagnet(request.urls)
            val torrentName = TorrentService.getNameFromMagnet(magnet) ?: "unknown"
            val torrentHash = TorrentService.getHashFromMagnet(magnet)
            val fileCount = torrentHash?.let { hash ->
                torrentService.getTorrentByHash(hash)?.files?.size
            } ?: 0
            
            logger.info("Torrent added successfully: name='{}', category='{}', files={}", 
                torrentName, request.category, fileCount)
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
        path = ["/api/v2/torrents/delete"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun deleteTorrents(
        @RequestParam hashes: String,
        @RequestParam(required = false) deleteFiles: Boolean?,
        request: HttpServletRequest
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

        val hashList = parseHashList(hashes).orEmpty()
        
        logger.debug("Delete torrent request received - hashes={}, fullQueryString='{}'", 
            hashList.size, request.queryString)
        logger.debug("Request URI: {}, Method: {}, RemoteAddr: {}", request.requestURI, request.method, remoteInfo)
        
        // Log all request parameters for debugging
        request.parameterMap.forEach { (key, values) ->
            logger.debug("Request parameter: {} = {}", key, values.joinToString(", "))
        }
        
        val deletedHashes = mutableListOf<String>()
        hashList.forEach { hash ->
            try {
                // Get torrent details before deletion for enhanced logging
                // Use getTorrentByHashString so legacy short IPTV hashes still delete
                val torrent = torrentService.getTorrentByHashString(hash)
                if (torrent != null) {
                    val torrentName = torrent.name ?: "unknown"
                    val fileCount = torrent.files.size
                    val fileDetails = torrent.files.mapNotNull { file ->
                        val fileName = file.name ?: "unknown"
                        val vfsPath = file.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
                        "$vfsPath (id: ${file.id}, size: ${file.size ?: 0} bytes)"
                    }
                    
                    logger.info(
                        "Deleting torrent: hash=$hash, name='$torrentName', files=$fileCount, deleteFiles=$deleteFiles"
                    )
                    if (fileDetails.isNotEmpty()) {
                        logger.info("Linked files: ${fileDetails.joinToString("; ")}")
                    }

                    torrentService.deleteTorrentByHash(hash, deleteFiles)
                    deletedHashes.add(hash)

                    // Log completion with details (using values captured before deletion)
                    logger.info("Deleted torrent: hash=$hash, name='$torrentName', processed $fileCount linked file(s)")
                } else {
                    logger.info("Deleting torrent: hash=$hash (not found in database)")
                    torrentService.deleteTorrentByHash(hash, deleteFiles)
                    deletedHashes.add(hash)
                    logger.info("Deleted torrent: hash=$hash")
                }
            } catch (e: Exception) {
                logger.error("Failed to delete torrent with hash: $hash", e)
            }
        }

        if (deletedHashes.isNotEmpty()) {
            logger.info("Deleted {} torrent(s) via qBittorrent API: {}", deletedHashes.size, deletedHashes.joinToString(", "))
        }
        if (deletedHashes.size < hashList.size) {
            logger.warn("Some torrents failed to delete. Requested: {}, Deleted: {}", hashList.size, deletedHashes.size)
        }
        return ResponseEntity.ok("Ok.")
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
