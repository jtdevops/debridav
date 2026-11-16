package io.skjaere.debridav.iptv.util

import io.skjaere.debridav.torrent.TORRENT_HASH_LENGTH
import org.apache.commons.codec.digest.DigestUtils

object IptvHashUtil {
    private val HEX_HASH = Regex("^[0-9a-fA-F]{$TORRENT_HASH_LENGTH}$")

    /**
     * Computes SHA-256 hash of the given content string
     * @param content The content to hash
     * @return Hex string representation of the SHA-256 hash (64 characters)
     */
    fun computeHash(content: String): String {
        return DigestUtils.sha256Hex(content)
    }

    /**
     * Computes combined hash of multiple content strings
     * Useful for hashing multiple API responses together
     * @param contents The content strings to hash together
     * @return Hex string representation of the SHA-256 hash
     */
    fun computeCombinedHash(vararg contents: String): String {
        val combined = contents.joinToString("\n")
        return computeHash(combined)
    }

    /**
     * BitTorrent info-hash style identifier: lowercase SHA-1 hex, exactly 40 characters.
     * Used for IPTV magnets and qBittorrent-emulation torrent hashes so Arr clients can track them.
     */
    fun toTorrentInfoHash(material: String): String {
        val hash = DigestUtils.sha1Hex(material).lowercase()
        require(hash.length == TORRENT_HASH_LENGTH) {
            "SHA-1 hex must be $TORRENT_HASH_LENGTH characters, was ${hash.length}"
        }
        return hash
    }

    /** True when [hash] is a 40-character hexadecimal info hash. */
    fun isTorrentInfoHash(hash: String): Boolean = HEX_HASH.matches(hash)
}
