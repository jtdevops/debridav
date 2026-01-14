package io.skjaere.debridav.util

import java.text.DecimalFormat

object ByteFormatUtil {
    private val decimalFormat = DecimalFormat("0.00")
    
    /**
     * Formats bytes to a human-readable string with 2 decimal places.
     * Examples:
     * - 1024 bytes -> "1.00 KB"
     * - 2585766025 bytes -> "2.41 GB"
     * - 1403478290 bytes -> "1.31 GB"
     */
    fun byteCountToDisplaySize(bytes: Long): String {
        val kb: Double = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        val tb = gb * 1024
        
        return when {
            bytes < kb -> "$bytes B"
            bytes < mb -> "${decimalFormat.format(bytes / kb)} KB"
            bytes < gb -> "${decimalFormat.format(bytes / mb)} MB"
            bytes < tb -> "${decimalFormat.format(bytes / gb)} GB"
            else -> "${decimalFormat.format(bytes / tb)} TB"
        }
    }
}
