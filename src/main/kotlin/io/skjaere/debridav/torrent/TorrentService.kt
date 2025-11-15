package io.skjaere.debridav.torrent

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.iptv.IptvContentRepository
import io.skjaere.debridav.iptv.IptvRequestService
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.util.VideoFileExtensions
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.net.URLDecoder
import java.time.Instant
import java.util.*


@Service
@Suppress("LongParameterList")
class TorrentService(
    private val debridService: DebridCachedContentService,
    private val fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val torrentRepository: TorrentRepository,
    private val categoryService: CategoryService,
    private val arrService: ArrService,
    private val torrentToMagnetConverter: TorrentToMagnetConverter,
    private val iptvRequestService: IptvRequestService,
    private val debridFileRepository: DebridFileContentsRepository,
    private val iptvContentRepository: IptvContentRepository
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)

    @Transactional
    fun addTorrent(category: String, torrent: MultipartFile): Boolean {
        return addMagnet(
            category, torrentToMagnetConverter.convertTorrentToMagnet(torrent.bytes)
        )
    }

    @Transactional
    fun addMagnet(category: String, magnet: TorrentMagnet): Boolean = runBlocking {
        // Check if this is an IPTV magnet URI
        // Format: magnet:?xt=urn:btih:{hash}&dn={title}&tr={iptv://...}
        // We detect IPTV by checking if the tracker (tr) parameter contains iptv://
        val iptvGuid = extractIptvGuidFromMagnet(magnet.magnet)
        if (iptvGuid != null && iptvGuid.startsWith("iptv://")) {
            logger.info("Detected IPTV content from magnet URI, GUID: $iptvGuid")
            return@runBlocking handleIptvLink(iptvGuid, category, magnet)
        }
        
        // Check if this is a direct IPTV link
        if (magnet.magnet.startsWith("iptv://")) {
            return@runBlocking handleIptvLink(magnet.magnet, category, magnet)
        }
        
        // Check if this might be an IPTV hash (if Radarr sends hash directly)
        // Note: This is a fallback - normally Radarr sends the iptv:// URL via downloadurl field
        val potentialHash = magnet.magnet.trim()
        val existingTorrent = torrentRepository.getByHashIgnoreCase(potentialHash)
        if (existingTorrent != null) {
            // Check if this torrent is IPTV content by checking if files are DebridIptvContent
            val isIptvContent = existingTorrent.files.any { file ->
                file.contents is io.skjaere.debridav.fs.DebridIptvContent
            }
            if (isIptvContent) {
                logger.info("Hash $potentialHash corresponds to existing IPTV torrent, skipping re-add")
                return@runBlocking true
            }
        }
        
        // Existing debrid handling
        val debridFileContents = runBlocking { debridService.addContent(magnet) }

        if (debridFileContents.isEmpty()) {
            logger.info("${getNameFromMagnet(magnet)} is not cached in any debrid services")
            if (arrService.categoryIsMapped(category)) {
                val torrent = createTorrent(debridFileContents, category, magnet)
                arrService.markDownloadAsFailed(torrent.name!!, category)
                true
            } else false
        } else {
            createTorrent(debridFileContents, category, magnet)
            true
        }
    }
    
    private fun handleIptvLink(iptvLink: String, category: String, magnet: TorrentMagnet? = null): Boolean {
        logger.info("Processing IPTV link: $iptvLink")
        
        // Parse IPTV link format: iptv://{hash}/{providerName}/{contentId}
        // Backward compatible with old format: iptv://{providerName}/{contentId}
        val linkWithoutProtocol = iptvLink.removePrefix("iptv://")
        val parts = linkWithoutProtocol.split("/")
        
        val (hexHash, providerName, contentId) = when (parts.size) {
            3 -> {
                // New format: iptv://{hash}/{providerName}/{contentId}
                Triple(parts[0], parts[1], parts[2])
            }
            2 -> {
                // Old format (backward compatibility): iptv://{providerName}/{contentId}
                val providerName = parts[0]
                val contentId = parts[1]
                val hash = iptvRequestService.generateIptvHash(providerName, contentId)
                Triple(hash, providerName, contentId)
            }
            else -> {
                logger.error("Invalid IPTV link format: $iptvLink. Expected format: iptv://{hash}/{providerName}/{contentId} or iptv://{providerName}/{contentId}")
                return false
            }
        }
        
        // Get IPTV content entity to retrieve title
        val iptvContent = iptvContentRepository.findByProviderNameAndContentId(providerName, contentId)
        if (iptvContent == null) {
            logger.error("IPTV content not found: provider=$providerName, contentId=$contentId")
            return false
        }
        
        // Extract title from magnet URL if available, otherwise use IPTV content title
        val magnetTitle = magnet?.let { getNameFromMagnet(it) }
        
        // Add IPTV content (creates the virtual file)
        val success = runBlocking {
            iptvRequestService.addIptvContent(contentId, providerName, category, magnetTitle)
        }
        
        if (!success) {
            logger.error("Failed to add IPTV content: provider=$providerName, contentId=$contentId")
            return false
        }
        
        // Generate hash for database lookup (use original format, not hex)
        // The database stores hashCode().toString() format
        val finalHash = "${providerName}_${contentId}".hashCode().toString()
        
        // Retrieve the created file and create a Torrent entity for it
        val createdFiles = debridFileRepository.getByHash(finalHash)
            .filterIsInstance<RemotelyCachedEntity>()
        
        if (createdFiles.isEmpty()) {
            logger.error("IPTV file not found after creation: hash=$finalHash")
            return false
        }
        
        val file = createdFiles.first()
        
        // Create or update Torrent entity
        val torrent = torrentRepository.getByHashIgnoreCase(finalHash) ?: Torrent()
        
        // If reusing an existing torrent, clean up old files first
        if (torrent.id != null) {
            torrent.files.forEach { oldFile ->
                fileService.deleteFile(oldFile)
            }
            torrent.files.clear()
        }
        
        torrent.category = categoryService.findByName(category)
?: run { categoryService.createCategory(category) }
        // Use magnet title if available, otherwise use IPTV content title, removing file extension if present
        val titleToUse = magnet?.let { getNameFromMagnet(it) } ?: iptvContent.title
        torrent.name = titleToUse.substringBeforeLast(".").takeIf { it != titleToUse }
            ?: titleToUse
        torrent.created = Instant.now()
        torrent.hash = finalHash
        torrent.status = Status.LIVE
        // For IPTV files, savePath should match the pattern used by regular torrents
        // Regular torrents use: ${downloadPath}/${torrent.name} (folder path only)
        // We need to construct the folder path from the file's directory
        val directoryPath = file.directory?.path ?: debridavConfigurationProperties.downloadPath
        // savePath should be the folder path (not including filename), matching regular torrents
        torrent.savePath = directoryPath
        torrent.files = mutableListOf(file)
        
        torrentRepository.save(torrent)
        logger.info("Successfully created Torrent entity for IPTV content: ${torrent.name}")
        
        return true
    }

    fun createTorrent(
        cachedFiles: List<DebridFileContents>,
        categoryName: String,
        magnet: TorrentMagnet
    ): Torrent {
        val hash = getHashFromMagnet(magnet) ?: error("could not get hash from magnet")
        val torrent = torrentRepository.getByHashIgnoreCase(hash.hash) ?: Torrent()
        
        // If reusing an existing torrent, clean up old files first
        if (torrent.id != null) {
            torrent.files.forEach { file ->
                fileService.deleteFile(file)
            }
            torrent.files.clear()
        }
        
        torrent.category = categoryService.findByName(categoryName)
            ?: run { categoryService.createCategory(categoryName) }
        torrent.name =
            getNameFromMagnet(magnet) ?: run {
                if (cachedFiles.isEmpty()) UUID.randomUUID().toString() else getTorrentNameFromDebridFileContent(
                    cachedFiles.first()
                )
            }
        torrent.created = Instant.now()
        torrent.hash = hash.hash
        torrent.status = Status.LIVE
        torrent.savePath =
            "${debridavConfigurationProperties.downloadPath}/${torrent.name}"
        torrent.files =
            cachedFiles.map {
                fileService.createDebridFile(
                    "${debridavConfigurationProperties.downloadPath}/${torrent.name}/${it.originalPath}",
                    getHashFromMagnet(magnet)!!.hash,
                    it
                )
            }.toMutableList()

        logger.info("Saving ${torrent.files.count()} files")
        return torrentRepository.save(torrent)
    }

    fun getTorrentsByCategory(categoryName: String): List<Torrent> {
        return categoryService.findByName(categoryName)?.let { category ->
            torrentRepository.findByCategoryAndStatus(category, Status.LIVE)
        } ?: emptyList()
    }


    fun getTorrentByHash(hash: TorrentHash): Torrent? {
        return torrentRepository.getByHashIgnoreCase(hash.hash)
    }

    @Transactional
    fun deleteTorrentByHash(hash: String) {
        // First, get the torrent to clean up associated files
        val torrent = torrentRepository.getByHashIgnoreCase(hash)
        if (torrent != null) {
            // Clean up associated files
            torrent.files.forEach { file ->
                fileService.deleteFile(file)
            }
        }
        
        return torrentRepository.deleteByHashIgnoreCase(hash)
    }
    
    /**
     * Extracts the IPTV GUID from a magnet URI.
     * Format: magnet:?xt=urn:btih:{hash}&dn={title}&tr={iptv://...}
     * Returns the tr parameter value which contains the IPTV GUID if it's an IPTV link.
     */
    private fun extractIptvGuidFromMagnet(magnetUri: String): String? {
        return try {
            // Extract tracker parameter
            val trParam = magnetUri.split("&").find { it.startsWith("tr=") }
            val decodedTr = trParam?.substringAfter("tr=")?.let { 
                java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) 
            }
            // Check if it's an IPTV link
            if (decodedTr != null && decodedTr.startsWith("iptv://")) {
                decodedTr
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract IPTV GUID from magnet URI: $magnetUri", e)
            null
        }
    }

    private fun getTorrentNameFromDebridFileContent(debridFileContents: DebridFileContents): String {
        val contentPath = debridFileContents.originalPath
        val updatedTorrentName = if (contentPath!!.contains("/")) {
            contentPath.substringBeforeLast("/")
        } else contentPath.substringBeforeLast(".")

        return updatedTorrentName
    }


    companion object {
        fun getNameFromMagnet(magnet: TorrentMagnet): String? {
            return getParamsFromMagnet(magnet)["dn"]
                ?.let {
                    URLDecoder.decode(it, Charsets.UTF_8.name())
                }
        }

        fun getNameFromMagnetWithoutContainerExtension(magnet: TorrentMagnet): String? =
            getNameFromMagnet(magnet)?.withoutVideoContainerExtension()

        private fun String.withoutVideoContainerExtension(): String {
            VideoFileExtensions.KNOWN_VIDEO_EXTENSIONS.forEach { extension ->
                if (this.endsWith(extension, ignoreCase = true)) return this.substringBeforeLast(extension)
            }
            return this
        }

        fun getHashFromMagnet(magnet: TorrentMagnet): TorrentHash? {
            return getParamsFromMagnet(magnet)["xt"]
                ?.let {
                    URLDecoder.decode(
                        it.substringAfterLast("urn:btih:"),
                        Charsets.UTF_8.name()
                    ).let { TorrentHash(it) }
                }
        }

        private fun getParamsFromMagnet(magnet: TorrentMagnet): Map<String, String> {
            return magnet.magnet.split("?").last().split("&")
                .map { it.split("=") }
                .associate { it.first() to it.last() }
        }
    }
}
