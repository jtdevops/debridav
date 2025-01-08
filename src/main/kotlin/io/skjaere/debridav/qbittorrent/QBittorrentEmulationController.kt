package io.skjaere.debridav.qbittorrent

import com.fasterxml.jackson.annotation.JsonProperty
import io.skjaere.debridav.configuration.DebridavConfiguration
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

@RestController("/qbittorrent")
class QBittorrentEmulationController(
    private val cachedContentTorrentService: CachedContentTorrentService,
    private val resourceLoader: ResourceLoader,
    private val debridavConfiguration: DebridavConfiguration
) {
    companion object {
        const val API_VERSION = "2.9.3"
    }

    private val logger = LoggerFactory.getLogger(QBittorrentEmulationController::class.java)

    @GetMapping("/api/v2/app/webapiVersion")
    fun version(): String = API_VERSION

    @GetMapping("/api/v2/torrents/categories")
    fun categories(): Map<String, Category> {
        return cachedContentTorrentService.getCategories().associateBy { it.name!! }
    }

    @RequestMapping(
        path = ["api/v2/torrents/createCategory"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    private fun createCategory(@RequestParam category: String): Category {
        return cachedContentTorrentService.createCategory(category)
    }

    @GetMapping("api/v2/app/preferences")
    fun preferences(): String {
        return resourceLoader
            .getResource("classpath:qbittorrent_properties_response.json")
            .getContentAsString(Charsets.UTF_8)
            .replace("%DOWNLOAD_DIR%", debridavConfiguration.mountPath)
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
        return cachedContentTorrentService
            .getTorrentsByCategory(requestParams.category!!)
            .filter { it.files?.firstOrNull()?.path != null }
            .map {
                TorrentsInfoResponse.ofTorrent(it, debridavConfiguration.mountPath)
            }
    }

    @GetMapping("/api/v2/torrents/properties")
    fun torrentsProperties(@RequestParam hash: String): TorrentPropertiesResponse? {
        return cachedContentTorrentService.getTorrentByHash(hash)?.let {
            TorrentPropertiesResponse.ofTorrent(it)
        }
    }

    @Suppress("MagicNumber")
    @GetMapping("/api/v2/torrents/files")
    fun torrentFiles(@RequestParam hash: String): List<TorrentFilesResponse>? {
        return cachedContentTorrentService.getTorrentByHash(hash)?.let {
            it.files?.map { torrentFile ->
                TorrentFilesResponse(
                    0,
                    torrentFile.fileName!!,
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
    fun addMagnetLink(
        @RequestPart urls: String,
        @RequestPart category: String
    ): ResponseEntity<String> {
        cachedContentTorrentService.addTorrent(category, urls)
        return ResponseEntity.ok("")
    }

    @RequestMapping(
        path = ["/api/v2/torrents/add"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun addTorrentFile(
        request: AddTorrentRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<String> {
        cachedContentTorrentService.addTorrent(request.category, request.urls)
        return ResponseEntity.ok("")
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
            cachedContentTorrentService.deleteTorrentByHash(it)
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
