package io.skjaere.debridav.test

import io.skjaere.debridav.iptv.util.IptvHashUtil
import io.skjaere.debridav.torrent.TORRENT_HASH_LENGTH
import io.skjaere.debridav.torrent.TorrentHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IptvHashUtilTest {
    @Test
    fun `toTorrentInfoHash returns lowercase hex of length 40`() {
        val hash = IptvHashUtil.toTorrentInfoHash("streamq_12345")

        assertEquals(TORRENT_HASH_LENGTH, hash.length)
        assertTrue(IptvHashUtil.isTorrentInfoHash(hash))
        assertEquals(hash, hash.lowercase())
        // Must satisfy qBittorrent emulation's TorrentHash validator
        TorrentHash(hash)
    }

    @Test
    fun `toTorrentInfoHash is stable for the same material`() {
        val a = IptvHashUtil.toTorrentInfoHash("provider_content")
        val b = IptvHashUtil.toTorrentInfoHash("provider_content")
        val c = IptvHashUtil.toTorrentInfoHash("provider_other")

        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun `isTorrentInfoHash rejects short Java hashCode style ids`() {
        assertFalse(IptvHashUtil.isTorrentInfoHash("1045355193"))
        assertFalse(IptvHashUtil.isTorrentInfoHash("3E8F2A1B"))
        assertTrue(IptvHashUtil.isTorrentInfoHash("a".repeat(40)))
    }
}
