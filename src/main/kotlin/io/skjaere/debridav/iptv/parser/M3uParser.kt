package io.skjaere.debridav.iptv.parser

import io.skjaere.debridav.iptv.configuration.IptvProviderConfiguration
import io.skjaere.debridav.iptv.model.ContentType
import io.skjaere.debridav.iptv.model.EpisodeInfo
import io.skjaere.debridav.iptv.model.IptvContentItem
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.regex.Pattern

class M3uParser {
    private val logger = LoggerFactory.getLogger(M3uParser::class.java)
    
    // Pattern to match #EXTINF lines: #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="..." [title]
    // More flexible pattern to handle various M3U formats
    // Format: #EXTINF:duration [attributes],title
    private val extinfPattern = Pattern.compile(
        """#EXTINF:(-?\d+)(?:\s+.*?group-title="([^"]*)")?.*?,\s*(.+)""",
        Pattern.CASE_INSENSITIVE
    )
    
    // Pattern to match series episodes: Series Name S01E01, Series Name - S01E01, etc.
    private val seriesPattern = Pattern.compile(
        """(.+?)\s*[-]?\s*[Ss](\d+)[Ee](\d+)""",
        Pattern.CASE_INSENSITIVE
    )

    fun parseM3u(
        content: String,
        providerConfig: IptvProviderConfiguration
    ): List<IptvContentItem> {
        val items = mutableListOf<IptvContentItem>()
        val lines = content.lines()
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            if (line.startsWith("#EXTINF:")) {
                val extinfLine = line
                val urlLine = if (i + 1 < lines.size) lines[i + 1].trim() else null
                
                if (urlLine != null && urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                    val item = parseExtinfLine(extinfLine, urlLine, providerConfig)
                    if (item != null) {
                        items.add(item)
                    }
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        
        logger.info("Parsed ${items.size} items from M3U playlist for provider ${providerConfig.name}")
        return items
    }
    
    private fun parseExtinfLine(
        extinfLine: String,
        urlLine: String,
        providerConfig: IptvProviderConfiguration
    ): IptvContentItem? {
        val matcher = extinfPattern.matcher(extinfLine)
        
        if (!matcher.find()) {
            return null
        }
        
        val duration = matcher.group(1)
        val groupTitle = matcher.group(2) ?: ""
        val title = matcher.group(3) ?: ""
        
        // Filter VOD content only if configured
        val isVod = groupTitle.contains("VOD", ignoreCase = true) ||
                   groupTitle.contains("Movies", ignoreCase = true) ||
                   groupTitle.contains("Series", ignoreCase = true) ||
                   groupTitle.contains("TV Shows", ignoreCase = true)
        
        if (!isVod) {
            return null
        }
        
        // Determine content type
        val contentType = if (groupTitle.contains("Movies", ignoreCase = true) ||
                             (!groupTitle.contains("Series", ignoreCase = true) && 
                              !groupTitle.contains("TV Shows", ignoreCase = true) &&
                              !seriesPattern.matcher(title).find())) {
            ContentType.MOVIE
        } else {
            ContentType.SERIES
        }
        
        // Parse series info if it's a series
        val episodeInfo = if (contentType == ContentType.SERIES) {
            parseSeriesInfo(title)
        } else {
            null
        }
        
        // Tokenize URL
        val tokenizedUrl = tokenizeUrl(urlLine, providerConfig)
        
        // Generate content ID from URL or title
        val contentId = generateContentId(urlLine, title)
        
        return IptvContentItem(
            id = contentId,
            title = title,
            url = tokenizedUrl,
            category = groupTitle,
            type = contentType,
            episodeInfo = episodeInfo
        )
    }
    
    private fun parseSeriesInfo(title: String): EpisodeInfo? {
        val matcher = seriesPattern.matcher(title)
        if (matcher.find()) {
            val seriesName = matcher.group(1).trim()
            val season = matcher.group(2).toIntOrNull()
            val episode = matcher.group(3).toIntOrNull()
            return EpisodeInfo(
                seriesName = seriesName,
                season = season,
                episode = episode
            )
        }
        return null
    }
    
    private fun tokenizeUrl(url: String, providerConfig: IptvProviderConfiguration): String {
        var tokenized = url
        
        // Replace base URL with placeholder
        providerConfig.m3uUrl?.let { m3uUrl ->
            try {
                val uri = URI(m3uUrl)
                val baseUrl = "${uri.scheme}://${uri.host}${uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""}"
                tokenized = tokenized.replace(baseUrl, "{BASE_URL}")
            } catch (e: Exception) {
                logger.debug("Could not extract base URL from m3u-url: $m3uUrl", e)
            }
        }
        
        // Replace username/password in URL if present
        // Common patterns: http://username:password@host or ?username=...&password=...
        tokenized = tokenized.replace(Regex("://([^:]+):([^@]+)@"), "://{USERNAME}:{PASSWORD}@")
        tokenized = tokenized.replace(Regex("[?&]username=([^&]+)"), "?username={USERNAME}")
        tokenized = tokenized.replace(Regex("[?&]password=([^&]+)"), "&password={PASSWORD}")
        
        return tokenized
    }
    
    private fun generateContentId(url: String, title: String): String {
        // Use URL hash or title hash as content ID
        return url.hashCode().toString() + "_" + title.hashCode().toString()
    }
}

