package io.skjaere.debridav.test

import io.skjaere.debridav.iptv.util.IptvLocaleRanking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IptvLocaleRankingTest {
    // Env-configured prefixes that are stripped → blank `-IPTV-streamq` suffix
    private val strippedExpanded = listOf("EN - ", "EN| ", "EN- ")
    private val strippedBase = setOf("EN")

    @Test
    fun `stripped EN and blank titles become blank locale suffix`() {
        assertTrue(IptvLocaleRanking.hasBlankLocaleSuffix("EN - Landman (2024) (US)", strippedExpanded, strippedBase))
        assertTrue(IptvLocaleRanking.hasBlankLocaleSuffix("Landman (2024)", strippedExpanded, strippedBase))
        assertFalse(IptvLocaleRanking.hasBlankLocaleSuffix("BG - Landman (2024)", strippedExpanded, strippedBase))
        assertFalse(IptvLocaleRanking.hasBlankLocaleSuffix("DE - Landman (2024) (US)", strippedExpanded, strippedBase))
        assertFalse(IptvLocaleRanking.hasBlankLocaleSuffix("IN - Landman (2024) (US)", strippedExpanded, strippedBase))
    }

    @Test
    fun `blank locale suffix ranks above locale-suffixed packs`() {
        val titles = listOf(
            "BG - Landman (2024)",
            "IN - Landman (2024) (US)",
            "EN - Landman (2024) (US)",
            "DE - Landman (2024) (US)",
            "FR - Landman (2024) (US)",
        )
        val sorted = titles.sortedWith(
            compareByDescending<String> {
                IptvLocaleRanking.relevanceWithLocaleBonus(750, it, strippedExpanded, strippedBase)
            }.thenBy {
                IptvLocaleRanking.localeSuffixSortKey(it, strippedExpanded, strippedBase)
            }
        )
        assertEquals("EN - Landman (2024) (US)", sorted.first())
        assertTrue(
            IptvLocaleRanking.relevanceWithLocaleBonus(750, "EN - Landman", strippedExpanded, strippedBase) >
                IptvLocaleRanking.relevanceWithLocaleBonus(750, "DE - Landman", strippedExpanded, strippedBase)
        )
    }
}
