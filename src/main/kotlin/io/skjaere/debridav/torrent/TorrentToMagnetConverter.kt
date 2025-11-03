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

    fun convertTorrentToMagnet(torrent: ByteArray): TorrentMagnet {
        val decodedTorrent = bencode.decode(torrent, Type.DICTIONARY)
        val torrentInfo = extractTorrentInfo(decodedTorrent)
        val hash = bencode.encode(torrentInfo)
        val digest = DigestUtils.sha1Hex(hash)
        val xt = "urn:btih:" + digest

        val name = extractTorrentName(torrentInfo)
        val dn = URLEncoder.encode(name, Charsets.UTF_8.name())
        val trackers = extractTrackers(decodedTorrent, torrentInfo)

        return TorrentMagnet("magnet:?xt=" + xt + "&dn=" + dn + "&tr=" + trackers)
    }

    private fun extractTorrentInfo(decodedTorrent: Map<*, *>): Map<String, Any> {
        val info = decodedTorrent["info"]
        if (info is Map<*, *>) {
            // Convert to Map<String, Any> by checking each key is String
            return info.entries.associate { (key, value) ->
                if (key is String) key to (value as Any) else throw IllegalArgumentException("Invalid torrent: info dictionary contains non-string keys")
            }
        }
        throw IllegalArgumentException("Invalid torrent: missing info dictionary")
    }

    private fun extractTorrentName(torrentInfo: Map<String, Any>): String {
        val name = torrentInfo["name"]
        return if (name is ByteBuffer) {
            name.decodeString()
        } else {
            throw IllegalArgumentException("Invalid torrent: missing name")
        }
    }

    private fun extractTrackers(decodedTorrent: Map<*, *>, torrentInfo: Map<String, Any>): String {
        return if (torrentInfo.containsKey("announce-list")) {
            val announceList = decodedTorrent["announce-list"]
            if (announceList is List<*>) {
                // Convert to List<String> by checking each element is String
                val stringList = announceList.map { element ->
                    if (element is String) element else throw IllegalArgumentException("Invalid torrent: announce-list contains non-string elements")
                }
                stringList.joinToString("&tr=") { URLEncoder.encode(it, Charsets.UTF_8.name()) }
            } else {
                throw IllegalArgumentException("Invalid torrent: announce-list is not a list")
            }
        } else {
            val announce = decodedTorrent["announce"]
            if (announce is ByteBuffer) {
                URLEncoder.encode(announce.decodeString(), Charsets.UTF_8.name())
            } else {
                throw IllegalArgumentException("Invalid torrent: missing or invalid announce")
            }
        }
    }
}
