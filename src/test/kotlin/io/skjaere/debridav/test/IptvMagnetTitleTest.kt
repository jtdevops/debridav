package io.skjaere.debridav.test

import io.skjaere.debridav.iptv.util.IptvMagnetTitle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IptvMagnetTitleTest {
    @Test
    fun `prefers OMDB display title over IPTV catalog name with country tag`() {
        val title = IptvMagnetTitle.resolveBaseTitle(
            preferredDisplayTitle = "Landman",
            searchTitle = "Landman",
            iptvCatalogTitle = "EN - Landman (US)",
        )
        assertEquals("Landman", title)
    }

    @Test
    fun `falls back to Arr search title before IPTV catalog name`() {
        val title = IptvMagnetTitle.resolveBaseTitle(
            preferredDisplayTitle = null,
            searchTitle = "Landman",
            iptvCatalogTitle = "Landman (US)",
        )
        assertEquals("Landman", title)
    }

    @Test
    fun `ignores imdb id shaped search titles when choosing display name`() {
        val title = IptvMagnetTitle.resolveBaseTitle(
            preferredDisplayTitle = null,
            searchTitle = "tt14186672",
            iptvCatalogTitle = "Landman (US)",
        )
        assertEquals("Landman (US)", title)
    }

    @Test
    fun `works the same for movie catalog titles`() {
        val title = IptvMagnetTitle.resolveBaseTitle(
            preferredDisplayTitle = "Moana 2",
            searchTitle = "Moana 2",
            iptvCatalogTitle = "EN - Moana 2 (US)",
        )
        assertEquals("Moana 2", title)
    }
}
