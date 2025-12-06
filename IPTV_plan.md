# IPTV Provider Integration Plan

## Overview
Add support for IPTV providers (m3u playlists and Xtream Codes API) to enable VOD content access. IPTV content will be indexed in a searchable database via background syncing (every 24 hours), and made available through a new IPTV API service that follows the same pattern as qBittorrent and SABnzbd APIs.

## Architecture Approach

The implementation follows the existing DebriDav pattern:
1. **Background Sync Service**: Periodically syncs IPTV playlists/XCodes and populates a searchable database (every 24 hours)
2. **IPTV API Service**: New API service (similar to qBittorrent/SABnzbd) that receives requests from Prowlarr
3. **On-Demand File Creation**: When a request comes in, search the IPTV database, match content, and create virtual filesystem entries
4. **Multiple Provider Support**: Support multiple IPTV providers simultaneously with priority/fallback logic

## Implementation Plan

### Phase 1: Core IPTV Infrastructure

#### 1.1 IPTV Configuration
- **File**: `src/main/resources/application.properties`
- Add IPTV configuration properties:
  - `iptv.enabled=false`
  - `iptv.sync-interval=PT24H` (sync every 24 hours)
  - `iptv.providers=` (comma-separated list of provider names)
  - `iptv.provider.{name}.type=m3u|xtream` (m3u playlist or Xtream Codes)
  - `iptv.provider.{name}.m3u-url=` (URL to m3u playlist)
  - `iptv.provider.{name}.m3u-file-path=` (local file path alternative)
  - `iptv.provider.{name}.xtream-base-url=`
  - `iptv.provider.{name}.xtream-username=`
  - `iptv.provider.{name}.xtream-password=`
  - `iptv.provider.{name}.priority=1` (lower number = higher priority)
  
  Note: Only VOD content (Movies and Series) is synced - live TV streams are not supported.

#### 1.2 IPTV Provider Enum
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvProvider.kt` (new)
- Create enum: `IptvProvider { M3U, XTREAM_CODES }`

#### 1.3 IPTV Content Database Model
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvContentEntity.kt` (new)
- Create entity for IPTV content index:
  - `id: Long`, `providerName: String`, `contentId: String`, `title: String`, `normalizedTitle: String` (for fuzzy search)
  - `url: String` (tokenized URL with placeholders like `{BASE_URL}`, `{USERNAME}`, `{PASSWORD}` - no separate tokens, just credentials in URL)
  - `contentType: ContentType` (MOVIE/SERIES), `category: String`
  - `seriesInfo: SeriesInfo?` (for TV series: series name, season, episode)
  - `metadata: Map<String, String>` (JSON field for additional metadata)
  - `lastSynced: Instant`, `isActive: Boolean`
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvContentRepository.kt` (new)
- JPA repository with custom query methods:
  - `findByNormalizedTitleContaining()` for fuzzy search
  - `findByProviderNameAndContentId()` for exact lookup
  - `findByContentTypeAndIsActive()` for filtering

#### 1.4 IPTV Virtual File Model
- **File**: `src/main/kotlin/io/skjaere/debridav/fs/DebridFileContents.kt`
- Add new entity class `DebridIptvContent : DebridFileContents()`
  - Fields: `iptvUrl: String` (resolved URL with actual credentials), `iptvProviderName: String`, `iptvContentId: String`
- Extend `DebridFile` to support direct streaming URLs
- Add `IptvFile : DebridFile` class with direct URL support (no debrid provider needed)

#### 1.5 Database Migration
- **File**: `src/main/resources/db/migration/V10__iptv_support.sql` (new)
- Add `iptv_content` table for searchable index:
  - Columns: id, provider_name, content_id, title, normalized_title, url (tokenized), content_type, category, series_info (JSON), metadata (JSON), last_synced, is_active
  - Indexes: normalized_title (for fuzzy search), provider_name + content_id (unique), content_type + is_active
- Add `debrid_iptv_content` table extending `debrid_file_contents`
  - Columns: iptv_url (resolved), iptv_provider_name, iptv_content_id

### Phase 2: IPTV Content Parsing

#### 2.1 M3U Playlist Parser
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/parser/M3uParser.kt` (new)
- Parse m3u playlists to extract:
  - VOD content (filter by `#EXTINF` tags with `group-title` containing "VOD", "Movies", "Series")
  - Extract: title, URL, group-title, tvg-id
  - Tokenize URLs: Replace base URL and credentials with placeholders (`{BASE_URL}`, `{USERNAME}`, `{PASSWORD}`)
- Return list of `IptvContentItem` data class

#### 2.2 Xtream Codes API Client
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/client/XtreamCodesClient.kt` (new)
- Implement Xtream Codes API client:
  - Authentication endpoint
  - Get VOD streams endpoint (`/player_api.php?username=...&password=...&action=get_vod_streams`)
  - Get VOD categories endpoint
  - Parse responses into `IptvContentItem` list
  - Tokenize URLs: Replace base URL and credentials with placeholders

#### 2.3 IPTV Content Item Model
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/model/IptvContentItem.kt` (new)
- Data class containing:
  - `id: String`, `title: String`, `url: String` (tokenized), `category: String`, `type: ContentType` (MOVIE/SERIES), `episodeInfo: EpisodeInfo?` (for series)

### Phase 3: IPTV Sync Service (Background Indexing)

#### 3.1 IPTV Sync Service
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvSyncService.kt` (new)
- Scheduled service (every 24 hours) that:
  - Iterates through all configured IPTV providers
  - Fetches content from each provider (m3u or Xtream)
  - Parses content into structured format
  - Normalizes titles for fuzzy searching (lowercase, remove special chars, etc.)
  - Tokenizes provider URLs (replace base URLs, credentials with placeholders)
  - Updates `IptvContentEntity` records in database:
    - Inserts new content
    - Updates existing content (URL changes, metadata updates)
    - Marks removed content as inactive (`isActive=false`)
  - **IMPORTANT**: Does NOT create virtual filesystem entries - those are created on-demand only when requests come from Prowlarr/Sonarr/Radarr

#### 3.2 IPTV Content Service
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvContentService.kt` (new)
- Service for searching and retrieving IPTV content:
  - `searchContent(query: String, contentType: ContentType?): List<IptvContentEntity>` - fuzzy search
  - `findExactMatch(title: String, contentType: ContentType?): IptvContentEntity?` - exact match
  - `getContentByProviderAndId(providerName: String, contentId: String): IptvContentEntity?`
  - `normalizeTitle(title: String): String` - title normalization for fuzzy matching
  - `resolveIptvUrl(tokenizedUrl: String, providerName: String): String` - replaces URL placeholders with actual provider configuration values

### Phase 4: IPTV API Service (New Download Client)

#### 4.1 IPTV API Controller
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/api/IptvApiController.kt` (new)
- New API service following qBittorrent/SABnzbd pattern:
  - `POST /api/iptv/search` - Search IPTV content database
    - Parameters: `query` (title), `type` (movie/series), `category` (optional)
    - Returns: List of matching IPTV content
  - `POST /api/iptv/add` - Add IPTV content request (similar to qBittorrent addTorrent)
    - Parameters: `contentId`, `providerName`, `category`
    - Searches IPTV database, resolves URL, creates virtual filesystem entry
    - Returns success/failure
  - `GET /api/iptv/status` - Get download status (for compatibility with Prowlarr)
  - `GET /api/iptv/list` - List active IPTV downloads
  - `POST /api/iptv/delete` - Remove IPTV content

#### 4.2 IPTV Request Handler
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvRequestService.kt` (new)
- Service to handle IPTV content requests:
  - `addIptvContent(contentId: String, providerName: String, category: String): Boolean`
    - Looks up content in IPTV database
    - Resolves tokenized URL to actual URL (injects provider base URL, username, password at runtime)
    - Creates `DebridIptvContent` entity with resolved URL
    - Creates virtual file in `/downloads/{category}/` folder (same structure as debrid content)
    - Returns true if successful
  - `resolveIptvUrl(tokenizedUrl: String, providerName: String): String`
    - Replaces URL placeholders (`{BASE_URL}`, `{USERNAME}`, `{PASSWORD}`) with actual provider configuration values
    - No token refresh needed - just credential substitution
  - `searchIptvContent(query: String, contentType: ContentType?): List<IptvSearchResult>`
    - Uses `IptvContentService` to search database
    - Returns formatted results for Prowlarr compatibility

#### 4.3 Prowlarr Integration Support
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/api/IptvProwlarrAdapter.kt` (new)
- Adapter to make IPTV API compatible with Prowlarr's Torrent/Usenet indexer format:
  - Convert IPTV search results to Prowlarr-compatible format
  - Handle both Torrent and Usenet indexer types (Prowlarr limitation)
  - Map IPTV content to "magnet" or "nzb" format for Prowlarr

### Phase 5: Streaming Support

#### 5.1 IPTV Streaming Handler
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/streaming/IptvStreamingHandler.kt` (new)
- Reuse `DefaultStreamableLinkPreparer` for direct URL streaming
- Handle IPTV-specific headers if needed (User-Agent, Referer)
- Support byte-range requests for IPTV streams

#### 5.2 Update StreamingService
- **File**: `src/main/kotlin/io/skjaere/debridav/stream/StreamingService.kt`
- Add handling for IPTV content type
- Route IPTV files to direct streaming handler (bypass debrid clients)
- IPTV files use resolved URLs (with actual credentials) stored in `DebridIptvContent`

### Phase 6: Virtual Filesystem Integration

#### 6.1 Update DatabaseFileService
- **File**: `src/main/kotlin/io/skjaere/debridav/fs/DatabaseFileService.kt`
- Add method `createIptvFile()` to create IPTV content files
- Handle IPTV content in file creation logic
- Files created in `/downloads/{category}/` - same location as debrid content
- No indication in VFS that files are from IPTV vs Debrid - transparent to user

#### 6.2 Update StreamableResourceFactory
- **File**: `src/main/kotlin/io/skjaere/debridav/resource/StreamableResourceFactory.kt`
- Ensure IPTV files are properly resolved and streamed
- IPTV files appear identical to debrid files in VFS

### Phase 7: Configuration & Management

#### 7.1 Configuration Properties
- **File**: `src/main/kotlin/io/skjaere/debridav/configuration/DebridavConfigurationProperties.kt`
- Add IPTV configuration section with all properties
- Support multiple providers with priority ordering

#### 7.2 IPTV Management Endpoints (Optional)
- **File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvController.kt` (new)
- REST endpoints for:
  - Manual sync trigger: `POST /api/iptv/sync`
  - Get sync status: `GET /api/iptv/status`
  - List IPTV content: `GET /api/iptv/content`

## File Structure

```
src/main/kotlin/io/skjaere/debridav/
├── iptv/
│   ├── IptvProvider.kt
│   ├── IptvSyncService.kt
│   ├── IptvContentService.kt
│   ├── IptvRequestService.kt
│   ├── IptvController.kt (optional)
│   ├── api/
│   │   ├── IptvApiController.kt
│   │   └── IptvProwlarrAdapter.kt
│   ├── client/
│   │   └── XtreamCodesClient.kt
│   ├── parser/
│   │   └── M3uParser.kt
│   ├── streaming/
│   │   └── IptvStreamingHandler.kt
│   └── model/
│       ├── IptvContentItem.kt
│       └── IptvContentEntity.kt
├── fs/
│   └── DebridFileContents.kt (extend with DebridIptvContent)
└── configuration/
    └── DebridavConfigurationProperties.kt (add IPTV config)
```

## Key Design Decisions

1. **Direct Streaming**: IPTV URLs are streamed directly without debrid providers. Reuse existing streaming infrastructure via `DefaultStreamableLinkPreparer`.

2. **Content Organization**: 
   - All IPTV content goes to `/downloads/{category}/` (same as debrid content)
   - No separate IPTV folder structure - transparent to user
   - Sonarr/Radarr will rename and move files to `/tv/` and `/movies/` as needed
   - Virtual filesystem shows no indication of IPTV vs Debrid source

3. **URL Tokenization**: 
   - IPTV URLs stored in database use placeholders (e.g., `{BASE_URL}`, `{USERNAME}`, `{PASSWORD}`)
   - No separate authentication tokens - just username/password embedded in URL
   - Actual provider details (base URL, username, password) injected at runtime when creating virtual files
   - Allows provider configuration changes without database updates

4. **On-Demand File Creation**: 
   - Virtual filesystem entries created ONLY when requests come from Prowlarr/Sonarr/Radarr
   - Background sync only populates searchable database index
   - No automatic file creation during sync

5. **Content Matching**: Use fuzzy title matching to identify content when searching from Prowlarr.

6. **Series Detection**: Parse series by analyzing titles and grouping patterns (e.g., "Series Name S01E01").

7. **Multiple Provider Support**: Support multiple IPTV providers with priority ordering for fallback.

## Testing Considerations

- Unit tests for M3U parser
- Unit tests for Xtream Codes client
- Unit tests for URL tokenization/resolution
- Integration tests for sync service
- Integration tests for IPTV API endpoints
- Test streaming with sample IPTV URLs
- Test fuzzy matching algorithms
- Test multiple provider priority/fallback

## Configuration Example

```properties
iptv.enabled=true
iptv.sync-interval=PT24H
iptv.providers=provider1,provider2
iptv.provider.provider1.type=m3u
iptv.provider.provider1.m3u-url=https://example.com/playlist.m3u
iptv.provider.provider1.priority=1
iptv.provider.provider2.type=xtream
iptv.provider.provider2.xtream-base-url=https://xtream.example.com
iptv.provider.provider2.xtream-username=user
iptv.provider.provider2.xtream-password=pass
iptv.provider.provider2.priority=2
```

## Future Enhancements

- IPTV content caching/transcoding options
- Advanced fuzzy matching algorithms for better content discovery

