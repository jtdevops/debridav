package io.skjaere.debridav.torrent

import com.fasterxml.jackson.annotation.JsonProperty
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity

data class TorrentsInfoResponse(
    @JsonProperty("added_on")
    val addedOn: Int,
    @JsonProperty("amount_left")
    val amountLeft: Long,
    @JsonProperty("auto_tmm")
    val autoTmm: Boolean,
    val availability: Float,
    val category: String,
    val completed: Long,
    @JsonProperty("completion_id")
    val completionOn: Int,
    @JsonProperty("content_path")
    val contentPath: String,
    @JsonProperty("dl_limit")
    val dlLimit: Int,
    @JsonProperty("dlspeed")
    val dlSpeed: Int,
    val downloaded: Long,
    @JsonProperty("downloaded_session")
    val downloadedSession: Long,
    val eta: Int,
    @JsonProperty("f_l_piece_prio")
    val firstLastPiecePriority: Boolean,
    @JsonProperty("force_start")
    val forceStart: Boolean,
    val hash: String,
    @JsonProperty("last_activity")
    val lastActivity: Int,
    @JsonProperty("magnet_uri")
    val magnetUri: String,
    @JsonProperty("max_ration")
    val maxRatio: Float,
    @JsonProperty("max_seeding_time")
    val maxSeedingTime: Int,
    val name: String,
    @JsonProperty("num_complete")
    val numComplete: Int,
    @JsonProperty("num_incomplete")
    val numIncomplete: Int,
    @JsonProperty("num_leechs")
    val numLeeches: Int,
    @JsonProperty("num_seeds")
    val numSeeds: Int,
    val priority: Int,
    val progress: Float,
    val ratio: Float,
    @JsonProperty("ratio_limit")
    val ratioLimit: Float,
    @JsonProperty("save_path")
    val savePath: String,
    @JsonProperty("seeding_time")
    val seedingTime: Int,
    @JsonProperty("seeding_time_limit")
    val seedingTimeLimit: Int,
    @JsonProperty("seen_complete")
    val seenComplete: Int,
    @JsonProperty("seq_dl")
    val seqDl: Boolean,
    val size: Long,
    val state: String,
    @JsonProperty("super_seeding")
    val superSeeding: Boolean,
    val tags: String,
    @JsonProperty("time_active")
    val timeActive: Int,
    @JsonProperty("total_size")
    val totalSize: Long,
    val tracker: String,
    @JsonProperty("upl_limit")
    val upLimit: Int,
    val uploaded: Long,
    @JsonProperty("uploaded_session")
    val uploadedSession: Long,
    @JsonProperty("upspeed")
    val upSpeed: Int
) {
    companion object {
        fun ofTorrent(
            torrent: Torrent,
            mountPath: String,
            downloadRoot: String,
            debugSuffix: String? = null
        ): TorrentsInfoResponse {
            // torrent.savePath is relative to mount path (e.g. /downloads/TorrentName).
            // content_path = mountPath + torrent.savePath → /data/downloads/TorrentName
            // save_path = download root (mountPath + downloadPath) → /data/downloads
            // Matching qBittorrent: save_path is the download directory; content_path is the torrent folder.
            val baseContentPath = "$mountPath${torrent.savePath}"
            val contentPath = if (debugSuffix != null) "$baseContentPath$debugSuffix" else baseContentPath
            // Real byte totals (Long) so torrents/info matches torrents/files and
            // multi-GB releases are not capped at Int.MAX_VALUE (~2.1 GiB).
            val totalBytes = torrent.files.sumOf { fileSizeBytes(it) }

            return TorrentsInfoResponse(
                addedOn = torrent.created!!.toEpochMilli().toInt(),
                amountLeft = 0L,
                autoTmm = false,
                availability = 1.0.toFloat(),
                category = torrent.category!!.name!!,
                completed = totalBytes,
                completionOn = torrent.created!!.toEpochMilli().toInt(),
                contentPath = contentPath,
                dlLimit = 0,
                dlSpeed = 0,
                downloaded = totalBytes,
                downloadedSession = totalBytes,
                eta = 100,
                firstLastPiecePriority = false,
                forceStart = false,
                hash = torrent.hash!!,
                lastActivity = 100,
                magnetUri = "",
                maxRatio = 10.0.toFloat(),
                maxSeedingTime = 100,
                name = torrent.name!!,
                numComplete = 0,
                numIncomplete = 0,
                numLeeches = 0,
                numSeeds = 0,
                priority = 1,
                progress = 1.0.toFloat(),
                ratio = 1.0.toFloat(),
                ratioLimit = 1.0.toFloat(),
                savePath = downloadRoot,
                seedingTime = 0,
                seedingTimeLimit = 0,
                seenComplete = 0,
                seqDl = false,
                size = totalBytes,
                state = "pausedUP",
                superSeeding = false,
                tags = "",
                timeActive = 0,
                totalSize = totalBytes,
                tracker = "",
                upLimit = 0,
                uploaded = 0L,
                uploadedSession = 0L,
                upSpeed = 0
            )
        }

        private fun fileSizeBytes(file: DbEntity): Long {
            file.size?.takeIf { it > 0L }?.let { return it }
            return when (file) {
                is RemotelyCachedEntity ->
                    file.contents?.size?.takeIf { it > 0L } ?: 0L
                else -> 0L
            }
        }
    }
}
