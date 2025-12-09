package io.skjaere.debridav.iptv.util

import org.apache.commons.codec.digest.DigestUtils

object IptvHashUtil {
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
}

