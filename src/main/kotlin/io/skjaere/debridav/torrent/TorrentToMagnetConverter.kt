package io.skjaere.debridav.torrent

import com.dampcake.bencode.Bencode
import com.dampcake.bencode.Type
import io.ktor.util.decodeString
import io.skjaere.debridav.debrid.TorrentMagnet
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.ByteBuffer

@Service
class TorrentToMagnetConverter {
    private val bencode = Bencode(true)

    @Suppress("UNCHECKED_CAST")
    fun convertTorrentToMagnet(torrent: ByteArray): TorrentMagnet {
        val decodedTorrent = bencode.decode(torrent, Type.DICTIONARY)
        val torrentInfo = extractTorrentInfo(decodedTorrent)
        val hash = bencode.encode(torrentInfo)
        val digest = DigestUtils.sha1Hex(hash)
        val xt = "urn:btih:$digest"

        val name = extractTorrentName(torrentInfo)
        val dn = URLEncoder.encode(name, Charsets.UTF_8.name())
        val trackers = extractTrackers(decodedTorrent, torrentInfo)

        return TorrentMagnet("magnet:?xt=$xt&dn=$dn&tr=$trackers")
    }

    private fun extractTorrentInfo(decodedTorrent: Map<*, *>): Map<String, Any> {
        return (decodedTorrent["info"]!! as? Map<String, Any>)
            ?: throw IllegalArgumentException("Invalid torrent: missing info dictionary")
    }

    private fun extractTorrentName(torrentInfo: Map<String, Any>): String {
        return (torrentInfo["name"] as? ByteBuffer)?.decodeString()
            ?: throw IllegalArgumentException("Invalid torrent: missing name")
    }

    private fun extractTrackers(decodedTorrent: Map<*, *>, torrentInfo: Map<String, Any>): String {
        return if (torrentInfo.containsKey("announce-list")) {
            val announceList = decodedTorrent["announce-list"] as? List<String>
            announceList?.joinToString("&tr=") { URLEncoder.encode(it, Charsets.UTF_8.name()) }
                ?: throw IllegalArgumentException("Invalid torrent: announce-list is not a list of strings")
        } else {
            val announce = (decodedTorrent["announce"] as? ByteBuffer)?.decodeString()
                ?: throw IllegalArgumentException("Invalid torrent: missing or invalid announce")
            URLEncoder.encode(announce, Charsets.UTF_8.name())
        }
    }
}
