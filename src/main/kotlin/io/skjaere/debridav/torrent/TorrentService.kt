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
import io.skjaere.debridav.iptv.IptvContentService
import io.skjaere.debridav.iptv.IptvRequestService
import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.util.VideoFileExtensions
import jakarta.transaction.Transactional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val iptvContentRepository: IptvContentRepository,
    private val iptvConfigurationProperties: IptvConfigurationProperties,
    private val iptvContentService: IptvContentService?,
    private val downloadsCleanupService: io.skjaere.debridav.cleanup.DownloadsCleanupService?
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)
    private val cleanupScope = CoroutineScope(Dispatchers.IO)

    @Transactional
    fun addTorrent(category: String, torrent: MultipartFile): Boolean {
        return addMagnet(
            category, torrentToMagnetConverter.convertTorrentToMagnet(torrent.bytes)
        )
    }

    @Transactional
    fun addMagnet(category: String, magnet: TorrentMagnet): Boolean = runBlocking {
        // Trigger automatic cleanup of orphaned torrents/files BEFORE adding new torrent
        // This prevents race conditions where a newly added torrent might be immediately cleaned up
        // Run in separate thread so it doesn't block the torrent add operation
        triggerAutomaticCleanupAsync()
        
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
            val result = createTorrent(debridFileContents, category, magnet)
            true
        }
    }
    
    /**
     * Triggers automatic cleanup of orphaned torrents/files in a separate thread.
     * This runs BEFORE adding a new torrent to prevent race conditions where
     * a newly added torrent might be immediately cleaned up if cleanup runs after
     * the torrent is added but before all database records are fully committed.
     */
    private fun triggerAutomaticCleanupAsync() {
        downloadsCleanupService?.let { cleanupService ->
            logger.debug("Triggering automatic cleanup of orphaned torrents/files in background thread")
            cleanupScope.launch {
                try {
                    logger.trace("Automatic cleanup thread started")
                    // Automatic cleanup uses configuration settings (default: immediate cleanup)
                    // This runs in a separate thread and doesn't block the torrent add operation
                    cleanupService.cleanupOrphanedTorrentsAndFiles(dryRun = false)
                    logger.trace("Automatic cleanup thread completed")
                } catch (e: Exception) {
                    // Log but don't fail the torrent add operation if cleanup fails
                    logger.warn("Automatic cleanup failed: ${e.message}", e)
                }
            }
        } ?: run {
            logger.trace("DownloadsCleanupService not available, skipping automatic cleanup")
        }
    }
    
    private fun handleIptvLink(iptvLink: String, category: String, magnet: TorrentMagnet? = null): Boolean {
        // Trigger automatic cleanup of orphaned torrents/files BEFORE adding new IPTV torrent
        // This prevents race conditions where a newly added torrent might be immediately cleaned up
        // Run in separate thread so it doesn't block the torrent add operation
        triggerAutomaticCleanupAsync()
        
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
            logger.error("IPTV content not found: iptvProvider=$providerName, contentId=$contentId")
            return false
        }
        
        // Extract title from magnet URL if available, otherwise use IPTV content title
        val magnetTitle = magnet?.let { getNameFromMagnet(it) }
        
        // Extract season/episode from magnet title (e.g., "Dexter.(US).2006.S08.1080p..." -> season=8)
        val (season, episode) = extractSeasonEpisodeFromTitle(magnetTitle)
        
        // Add IPTV content (creates the virtual file)
        val success = runBlocking {
            iptvRequestService.addIptvContent(contentId, providerName, category, magnetTitle, season, episode)
        }
        
        if (!success) {
            logger.error("Failed to add IPTV content: iptvProvider=$providerName, contentId=$contentId")
            return false
        }
        
        // Retrieve all created files for this IPTV content
        // For series, this will get all episode files for the season (already filtered during creation)
        // For movies/single episodes, this will get the single file
        val allCreatedFiles = debridFileRepository.findByIptvContentRefId(iptvContent.id!!)
            .filterIsInstance<RemotelyCachedEntity>()
        
        if (allCreatedFiles.isEmpty()) {
            logger.error("IPTV files not found after creation: iptvContentRefId=${iptvContent.id}")
            return false
        }
        
        // Filter files to only include the requested episode if a specific episode was requested
        // When a specific episode is requested (e.g., S07E02), we should only include files for that episode
        // to avoid replacing files from other episodes in the same season
        val filesToInclude = if (episode != null && season != null) {
            // Extract episode identifier from magnet title (e.g., "S07E02" from "Dexter.(US).(2006).S07E02...")
            val episodePattern = Regex("S${String.format("%02d", season)}E${String.format("%02d", episode)}")
            val filteredFiles = allCreatedFiles.filter { file ->
                val fileName = file.name ?: ""
                episodePattern.containsMatchIn(fileName)
            }
            if (filteredFiles.isEmpty()) {
                logger.warn("No files found matching episode S${String.format("%02d", season)}E${String.format("%02d", episode)}. Using all files as fallback.")
                allCreatedFiles
            } else {
                logger.debug("Filtered to ${filteredFiles.size} file(s) for episode S${String.format("%02d", season)}E${String.format("%02d", episode)}")
                filteredFiles
            }
        } else {
            // No specific episode requested, include all files (for season or movie)
            allCreatedFiles
        }
        
        // Generate hash based on actual episodes included in the torrent
        // This ensures each unique combination of episodes gets a unique hash
        val finalHash = if (season != null && iptvContent.contentType == ContentType.SERIES) {
            // Extract episode identifiers from file names (e.g., "S07E01", "S07E02", etc.)
            val episodePattern = Regex("S(\\d{2})E(\\d{2})")
            val episodeIdentifiers = filesToInclude.mapNotNull { file ->
                val fileName = file.name ?: ""
                episodePattern.find(fileName)?.value
            }.sorted().distinct()
            
            if (episodeIdentifiers.isEmpty()) {
                // Fallback: use season-based hash if we can't extract episodes
                logger.warn("Could not extract episode identifiers from file names, using season-based hash")
                "${providerName}_${contentId}_S${String.format("%02d", season)}".hashCode().toString()
            } else {
                // Create hash from sorted list of episode identifiers
                // Format: providerName_contentId_S07E01_S07E02_S07E03...
                val episodeString = episodeIdentifiers.joinToString("_")
                val hashString = "${providerName}_${contentId}_$episodeString"
                logger.debug("Generated hash from episodes: $episodeString -> hash string: $hashString")
                hashString.hashCode().toString()
            }
        } else {
            // Movie or single episode: use providerName_contentId
            "${providerName}_${contentId}".hashCode().toString()
        }
        
        // Create or update Torrent entity
        // With episode-specific hashing, each unique combination of episodes gets its own torrent
        val torrent = torrentRepository.getByHashIgnoreCase(finalHash) ?: Torrent()
        
        // If torrent already exists with the same hash, check if files need to be updated
        // This handles the case where the same episode combination is requested again
        if (torrent.id != null) {
            // Check if all files already exist in the torrent
            // RemotelyCachedEntity doesn't have a path property, only name
            val existingFileNames = torrent.files.mapNotNull { it.name }.toSet()
            val newFiles = filesToInclude.filter { file ->
                val fileName = file.name ?: ""
                fileName !in existingFileNames
            }
            if (newFiles.isEmpty()) {
                logger.debug("All files already exist in torrent with hash $finalHash, skipping add")
                return true
            }
            // Some files are missing, add them (shouldn't happen with episode-specific hashes, but handle it)
            logger.debug("Adding ${newFiles.size} missing file(s) to existing torrent (${torrent.files.size} existing files)")
            torrent.files.addAll(newFiles)
            torrentRepository.save(torrent)
            logger.info("Successfully added ${newFiles.size} file(s) to existing torrent: ${torrent.name} (total: ${torrent.files.size} files)")
            return true
        }
        
        torrent.category = categoryService.findByName(category)
            ?: run { categoryService.createCategory(category) }
        // Use magnet title if available, otherwise use IPTV content title
        // Remove any media file extension from the title to use as folder name
        val titleToUse = magnet?.let { getNameFromMagnet(it) } ?: iptvContent.title
        // Remove media extension if present (e.g., .mp4, .mkv, etc.)
        val videoExtensions = listOf("mp4", "mkv", "avi", "ts", "mov", "wmv", "flv", "webm", "m4v", 
            "m2ts", "mts", "vob", "ogv", "3gp", "asf", "rm", "rmvb")
        val titleWithoutExtension = videoExtensions.firstOrNull { ext ->
            titleToUse.endsWith(".$ext", ignoreCase = true)
        }?.let { ext ->
            titleToUse.removeSuffix(".$ext").removeSuffix(".${ext.uppercase()}")
        } ?: titleToUse
        torrent.name = titleWithoutExtension
        torrent.created = Instant.now()
        torrent.hash = finalHash
        torrent.status = Status.LIVE
        // For IPTV files, savePath should match the pattern used by regular torrents
        // Regular torrents use: ${downloadPath}/${torrent.name} (folder path only, relative to mount path)
        // This ensures consistency with regular torrents and correct path construction in API responses
        torrent.savePath = "${debridavConfigurationProperties.downloadPath}/${torrent.name}"
        torrent.files = filesToInclude.toMutableList()
        
        torrentRepository.save(torrent)
        logger.info("Successfully created Torrent entity for IPTV content: ${torrent.name} with ${filesToInclude.size} file(s)")
        
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
            val torrentName = torrent.name ?: "unknown"
            val fileCount = torrent.files.size
            val fileDetails = torrent.files.mapNotNull { file ->
                when (file) {
                    is RemotelyCachedEntity -> {
                        val fileName = file.name ?: "unknown"
                        val vfsPath = file.directory?.fileSystemPath()?.let { "$it/$fileName" } ?: fileName
                        "$vfsPath (id: ${file.id}, size: ${file.size ?: 0} bytes)"
                    }
                    else -> {
                        val fileName = file.name ?: "unknown"
                        "$fileName (id: ${file.id}, type: ${file.javaClass.simpleName})"
                    }
                }
            }
            
            logger.info("Deleting torrent: hash=$hash, name='$torrentName', files=$fileCount")
            if (fileDetails.isNotEmpty()) {
                logger.info("Deleting associated files: ${fileDetails.joinToString("; ")}")
            }
            
            // Clean up associated files
            torrent.files.forEach { file ->
                fileService.deleteFile(file)
            }
            
            logger.info("Deleted torrent: hash=$hash, name='$torrentName', removed $fileCount file(s)")
        } else {
            logger.warn("Torrent not found for deletion: hash=$hash")
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

    /**
     * Extracts season and episode numbers from a title string.
     * Looks for patterns like "S08", "S08E01", "S8", "S8E1", etc.
     * Returns Pair(season, episode) where episode can be null if only season is found.
     */
    private fun extractSeasonEpisodeFromTitle(title: String?): Pair<Int?, Int?> {
        if (title == null) return Pair(null, null)
        
        // Pattern: S{season}E{episode} or S{season}
        // Examples: S08E01, S8E1, S08, S8
        val pattern = Regex("""S(\d+)(?:E(\d+))?""", RegexOption.IGNORE_CASE)
        val match = pattern.find(title)
        
        if (match != null) {
            val seasonStr = match.groupValues[1]
            val episodeStr = match.groupValues.getOrNull(2)
            
            val season = seasonStr.toIntOrNull()
            val episode = episodeStr?.toIntOrNull()
            
            if (season != null) {
                logger.debug("Extracted season=$season, episode=$episode from title: $title")
                return Pair(season, episode)
            }
        }
        
        return Pair(null, null)
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
