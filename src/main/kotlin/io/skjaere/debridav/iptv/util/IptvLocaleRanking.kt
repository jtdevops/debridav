package io.skjaere.debridav.iptv.util

/**
 * Ranks IPTV search hits by the release-group locale suffix they will get in magnet titles.
 *
 * Configured language prefixes (e.g. EN via env vars) are stripped, so the release group
 * ends blank of locale: `-IPTV-streamq`. Foreign packs keep a locale suffix
 * (`-IPTV-streamq-DE`) and rank lower so small Prowlarr limits still return the blank one.
 */
object IptvLocaleRanking {
    private val LEADING_LOCALE = Regex("^([A-Za-z]{2,3})\\s*[|\\-]\\s+")

    /** Boost so blank-suffix hits outrank same-title foreign packs on relevance. */
    const val BLANK_LOCALE_RELEVANCE_BONUS = 5_000

    /**
     * True when magnet naming will use a blank locale suffix (`-IPTV-{provider}`),
     * not a trailing locale (`-IPTV-{provider}-DE`).
     *
     * Matches [io.skjaere.debridav.iptv.IptvRequestService] behavior: configured English
     * prefixes are stripped; other leading locale codes become a release-group suffix.
     */
    fun hasBlankLocaleSuffix(
        title: String,
        expandedStrippedPrefixes: List<String>,
        baseStrippedPrefixes: Set<String>,
    ): Boolean {
        val trimmed = title.trimStart()
        if (expandedStrippedPrefixes.any { prefix ->
                val cleaned = prefix.trim().removeSurrounding("\"").removeSurrounding("'")
                cleaned.isNotEmpty() && trimmed.startsWith(cleaned, ignoreCase = false)
            }
        ) {
            return true
        }

        val match = LEADING_LOCALE.find(trimmed) ?: return true // no locale prefix → blank suffix
        val code = match.groupValues[1].uppercase()
        return code in baseStrippedPrefixes
    }

    /** Sort key: blank locale suffix first (0), locale-suffixed packs after (1). */
    fun localeSuffixSortKey(
        title: String,
        expandedStrippedPrefixes: List<String>,
        baseStrippedPrefixes: Set<String>,
    ): Int = if (hasBlankLocaleSuffix(title, expandedStrippedPrefixes, baseStrippedPrefixes)) 0 else 1

    fun relevanceWithLocaleBonus(
        baseScore: Int,
        title: String,
        expandedStrippedPrefixes: List<String>,
        baseStrippedPrefixes: Set<String>,
    ): Int {
        val bonus = if (hasBlankLocaleSuffix(title, expandedStrippedPrefixes, baseStrippedPrefixes)) {
            BLANK_LOCALE_RELEVANCE_BONUS
        } else {
            0
        }
        return baseScore + bonus
    }
}
