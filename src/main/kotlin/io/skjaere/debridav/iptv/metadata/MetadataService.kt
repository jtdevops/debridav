package io.skjaere.debridav.iptv.metadata

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.skjaere.debridav.iptv.IptvImdbMetadataEntity
import io.skjaere.debridav.iptv.IptvImdbMetadataRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service
class MetadataService(
    private val httpClient: HttpClient,
    private val metadataConfigurationProperties: MetadataConfigurationProperties,
    private val iptvImdbMetadataRepository: IptvImdbMetadataRepository
) {
    private val logger = LoggerFactory.getLogger(MetadataService::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetches metadata for a movie or TV show by IMDB ID
     * First checks the database cache, then fetches from OMDB API if not cached.
     * Intentionally not @Transactional so the DB connection is not held during the OMDB HTTP call
     * (used by /api/iptv/search when resolving imdbid/qTest to a title).
     *
     * @param imdbId The IMDB ID (e.g., "tt12584954")
     * @return Metadata containing title and year, or null if not found or error occurred
     */
    suspend fun getMetadataByImdbId(imdbId: String): MediaMetadata? {
        // Check cache first (short DB read)
        val cachedEntity = iptvImdbMetadataRepository.findByImdbId(imdbId)
        if (cachedEntity != null) {
            logger.debug("Found cached IMDB metadata for IMDB ID: $imdbId")
            // Update last accessed timestamp; repository method is @Transactional so call goes through proxy
            iptvImdbMetadataRepository.updateLastAccessed(imdbId, Instant.now())
            // Parse cached response JSON to extract metadata
            return parseMetadataFromJson(cachedEntity.responseJson, imdbId)
        }

        if (metadataConfigurationProperties.omdbApiKey.isBlank()) {
            logger.debug("OMDB API key not configured, skipping metadata lookup for IMDB ID: $imdbId")
            return null
        }

        val apiKey = metadataConfigurationProperties.omdbApiKey
        val baseUrl = metadataConfigurationProperties.omdbBaseUrl
        return fetchAndCacheOmdb("$baseUrl/?apikey=$apiKey&i=$imdbId", imdbIdHint = imdbId)
    }

    /**
     * Fetches OMDB metadata by title (and optional year/type).
     * Used so magnet/Torznab display names use the OMDB title for both movies and series
     * even when the Arr client searched with `q=` instead of `imdbid`.
     *
     * @param title Clean title without year (e.g. "Landman")
     * @param year Optional start year to disambiguate
     * @param omdbType OMDB type filter: "movie", "series", or null for either
     */
    suspend fun getMetadataByTitle(
        title: String,
        year: Int? = null,
        omdbType: String? = null,
    ): MediaMetadata? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return null
        }

        // Prefer a year-scoped cache hit when the caller provided a year
        val cachedEntity = if (year != null) {
            iptvImdbMetadataRepository.findFirstByTitleIgnoreCaseAndStartYear(cleanTitle, year)
                ?: iptvImdbMetadataRepository.findFirstByTitleIgnoreCase(cleanTitle)
        } else {
            iptvImdbMetadataRepository.findFirstByTitleIgnoreCase(cleanTitle)
        }
        if (cachedEntity != null) {
            logger.debug(
                "Found cached IMDB metadata for title='{}' year={} -> imdbId={}",
                cleanTitle,
                year,
                cachedEntity.imdbId,
            )
            iptvImdbMetadataRepository.updateLastAccessed(cachedEntity.imdbId, Instant.now())
            return parseMetadataFromJson(cachedEntity.responseJson, cachedEntity.imdbId)
        }

        if (metadataConfigurationProperties.omdbApiKey.isBlank()) {
            logger.debug("OMDB API key not configured, skipping title lookup for '{}'", cleanTitle)
            return null
        }

        val apiKey = metadataConfigurationProperties.omdbApiKey
        val baseUrl = metadataConfigurationProperties.omdbBaseUrl
        val encodedTitle = java.net.URLEncoder.encode(cleanTitle, Charsets.UTF_8)
        val url = buildString {
            append("$baseUrl/?apikey=$apiKey&t=$encodedTitle")
            if (year != null) append("&y=$year")
            if (!omdbType.isNullOrBlank()) append("&type=$omdbType")
        }
        return fetchAndCacheOmdb(url, imdbIdHint = null)
    }

    /**
     * Calls OMDB, parses title/year/released, and caches by IMDb id when present.
     */
    private suspend fun fetchAndCacheOmdb(url: String, imdbIdHint: String?): MediaMetadata? {
        return try {
            logger.debug("Fetching metadata from OMDB: {}", url.replace(Regex("apikey=[^&]+"), "apikey=***"))

            val response: HttpResponse = httpClient.get(url)
            if (response.status != HttpStatusCode.OK) {
                logger.warn("OMDB API returned status {} for url hint imdbId={}", response.status, imdbIdHint)
                return null
            }

            val responseBody = response.body<String>()
            val jsonObject = json.parseToJsonElement(responseBody).jsonObject
            val responseValue = jsonObject["Response"]?.jsonPrimitive?.content
            val title = jsonObject["Title"]?.jsonPrimitive?.content
            val year = jsonObject["Year"]?.jsonPrimitive?.content
            val released = jsonObject["Released"]?.jsonPrimitive?.content
            val imdbId = jsonObject["imdbID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: imdbIdHint
            val error = jsonObject["Error"]?.jsonPrimitive?.content

            if (responseValue != "True" || title == null || imdbId.isNullOrBlank()) {
                logger.warn("OMDB API returned False/incomplete response (imdbId hint={}): {}", imdbIdHint, error)
                return null
            }

            logger.debug("Successfully fetched OMDB metadata: title='{}', year='{}', released='{}', imdbId={}", title, year, released, imdbId)
            val (startYear, endYear) = parseYearRange(year)
            val releasedYear = parseReleasedYear(released)
            if (releasedYear != null && releasedYear != startYear) {
                logger.debug(
                    "OMDB Year ($startYear) differs from Released ($released) -> $releasedYear; display/search use Year (startYear), not Released"
                )
            }

            val metadata = MediaMetadata(
                title = title,
                year = startYear,
                startYear = startYear,
                endYear = endYear,
                releasedYear = releasedYear,
                imdbId = imdbId,
            )

            val existing = iptvImdbMetadataRepository.findByImdbId(imdbId)
            if (existing != null) {
                existing.title = title
                existing.startYear = startYear
                existing.endYear = endYear
                existing.responseJson = responseBody
                existing.lastAccessed = Instant.now()
                iptvImdbMetadataRepository.save(existing)
            } else {
                iptvImdbMetadataRepository.save(
                    IptvImdbMetadataEntity().apply {
                        this.imdbId = imdbId
                        this.title = title
                        this.startYear = startYear
                        this.endYear = endYear
                        this.responseJson = responseBody
                        this.lastAccessed = Instant.now()
                        this.createdAt = Instant.now()
                    }
                )
            }
            logger.debug("Cached IMDB metadata for IMDB ID: $imdbId")
            metadata
        } catch (e: Exception) {
            logger.warn("Failed to fetch metadata from OMDB API (imdbId hint={})", imdbIdHint, e)
            null
        }
    }

    /**
     * Parses metadata from JSON response string
     * @param jsonString The JSON response string from OMDB API
     * @param imdbId The IMDB ID for the metadata
     * @return MediaMetadata or null if parsing fails
     */
    private fun parseMetadataFromJson(jsonString: String, imdbId: String): MediaMetadata? {
        return try {
            val jsonElement: JsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject
            
            val title = jsonObject["Title"]?.jsonPrimitive?.content
            val year = jsonObject["Year"]?.jsonPrimitive?.content
            val released = jsonObject["Released"]?.jsonPrimitive?.content
            
            if (title != null) {
                val (startYear, endYear) = parseYearRange(year)
                val releasedYear = parseReleasedYear(released)
                MediaMetadata(
                    title = title,
                    year = startYear, // Use start year for backward compatibility
                    startYear = startYear,
                    endYear = endYear,
                    releasedYear = releasedYear,
                    imdbId = imdbId
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse cached IMDB metadata JSON for IMDB ID: $imdbId", e)
            null
        }
    }
    
    /**
     * Parses a year string that may be a single year or a range.
     * Supports formats: "2006", "2006–2013", "2006-2013" (en-dash or hyphen), "2022–" (ongoing series)
     * @return Pair of (startYear, endYear) where endYear is null if not a range or ongoing series
     */
    private fun parseYearRange(yearStr: String?): Pair<Int?, Int?> {
        if (yearStr == null || yearStr.isBlank()) {
            return Pair(null, null)
        }
        
        // Try to match complete year range patterns (en-dash or hyphen) - e.g., "2006–2013"
        val rangePattern = Regex("""(\d{4})\s*[–-]\s*(\d{4})""")
        val rangeMatch = rangePattern.find(yearStr)
        
        if (rangeMatch != null) {
            val startYear = rangeMatch.groupValues[1].toIntOrNull()
            val endYear = rangeMatch.groupValues[2].toIntOrNull()
            logger.debug("Parsed year range: startYear=$startYear, endYear=$endYear from '$yearStr'")
            return Pair(startYear, endYear)
        }
        
        // Try to match ongoing series pattern (trailing dash) - e.g., "2022–"
        val ongoingPattern = Regex("""(\d{4})\s*[–-]\s*$""")
        val ongoingMatch = ongoingPattern.find(yearStr)
        
        if (ongoingMatch != null) {
            val startYear = ongoingMatch.groupValues[1].toIntOrNull()
            logger.debug("Parsed ongoing series: startYear=$startYear from '$yearStr'")
            return Pair(startYear, null)
        }
        
        // Single year
        val singleYear = yearStr.trim().toIntOrNull()
        logger.debug("Parsed single year: $singleYear from '$yearStr'")
        return Pair(singleYear, null)
    }
    
    /**
     * Parses the year from OMDB's Released field (e.g. "18 Mar 2026", "01 Jan 2025").
     * Kept as a fallback when OMDB Year is missing; display/search prefer Year (startYear).
     */
    private fun parseReleasedYear(releasedStr: String?): Int? {
        if (releasedStr == null || releasedStr.isBlank() || releasedStr.equals("N/A", ignoreCase = true)) {
            return null
        }
        return try {
            // OMDB format: "DD Mon YYYY" (e.g. "18 Mar 2026")
            val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            val date = java.time.LocalDate.parse(releasedStr.trim(), formatter)
            logger.debug("Parsed Released '$releasedStr' -> year ${date.year}")
            date.year
        } catch (e: Exception) {
            // Fallback: try to extract 4-digit year
            val yearMatch = Regex("\\b(19|20)\\d{2}\\b").find(releasedStr)
            yearMatch?.value?.toIntOrNull()?.also {
                logger.debug("Parsed Released '$releasedStr' -> year $it (fallback)")
            }
        }
    }

    data class MediaMetadata(
        val title: String,
        val year: Int?, // Start year (for backward compatibility)
        val startYear: Int? = null,
        val endYear: Int? = null, // End year if it's a range (e.g., TV series)
        val releasedYear: Int? = null, // Year from Released date; fallback only when Year/startYear is absent
        val imdbId: String
    )
}
