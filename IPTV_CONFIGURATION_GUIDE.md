# IPTV Integration Configuration Guide

## Overview

The IPTV integration allows DebriDav to index and serve VOD (Video on Demand) content from IPTV providers (M3U playlists or Xtream Codes API). This content appears in the virtual filesystem alongside debrid content, making it accessible to Sonarr/Radarr through the existing download client interface.

## Current Status

✅ **IPTV Integration**: Fully implemented and functional. IPTV content can be searched and streamed through DebriDav.

✅ **Prowlarr Integration**: A custom indexer definition (`debridav-iptv.yml`) is provided that allows Prowlarr to search IPTV content through DebriDav's API. This enables seamless integration with Sonarr and Radarr.

⚠️ **M3U Playlist Support**: M3U playlist support is implemented but untested. Xtream Codes API support has been thoroughly tested and is recommended for production use.

## Configuration Steps

### 1. Configure IPTV Providers in DebriDav

Configure IPTV providers using environment variables (recommended for Docker) or in `application.properties`:

**Using Environment Variables (Docker Compose):**

```yaml
# Enable IPTV
IPTV_ENABLED=true

# Sync interval (default: every 24 hours)
IPTV_SYNC_INTERVAL=PT24H

# List of provider names (comma-separated)
IPTV_PROVIDERS=provider1,provider2

# Configure first provider (Xtream Codes - recommended)
IPTV_PROVIDER_PROVIDER1_TYPE=xtream_codes
IPTV_PROVIDER_PROVIDER1_XTREAM_BASE_URL=https://example.com:8080
IPTV_PROVIDER_PROVIDER1_XTREAM_USERNAME=your_username
IPTV_PROVIDER_PROVIDER1_XTREAM_PASSWORD=your_password
IPTV_PROVIDER_PROVIDER1_PRIORITY=1

# Configure second provider (M3U - untested)
IPTV_PROVIDER_PROVIDER2_TYPE=m3u
IPTV_PROVIDER_PROVIDER2_M3U_URL=https://example.com/playlist.m3u
# OR use local file path:
# IPTV_PROVIDER_PROVIDER2_M3U_FILE_PATH=/path/to/playlist.m3u
IPTV_PROVIDER_PROVIDER2_PRIORITY=2

# Optional: Language prefixes for content matching
# Can be used to identify specific language or source prefixes to IPTV content.
# For example 'EN' for English, or 'UNV' for Universal Studios.
# This is specific to each IPTV provider and how they provide their content.
IPTV_LANGUAGE_PREFIXES_INDEX_0="AM| "
IPTV_LANGUAGE_PREFIXES="007,4K-A+,4K-AMZ,4K-D+,4K-EN,4K-MAX,4K-MRVL,4K-NF,4K-NF-DO,4M-AMZ,A+,AMZ,CR,D+,D+ ,DWA,EN,EN-TOP,EX,MRVL,Nf,NF,NF-DO,NICK,P+,PCOK,PRMT,SHWT,SKY,TOP,TOP-DO,UFC,UNV,VP"

# Optional: OMDB API key for enhanced metadata
IPTV_METADATA_OMDB_API_KEY=your_omdb_api_key

# Optional: Cache IPTV responses locally
#IPTV_USE_LOCAL_RESPONSES=true
IPTV_RESPONSE_SAVE_FOLDER=/iptv_cache

# Optional: Include provider name in magnet title
IPTV_INCLUDE_PROVIDER_IN_MAGNET_TITLE=true

# Optional: Bypass local video serving for IPTV providers
DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_IPTV_BYPASS_PROVIDERS=*
```

**Using application.properties:**

```properties
# Enable IPTV
iptv.enabled=true

# Sync interval (default: every 24 hours)
iptv.sync-interval=PT24H

# List of provider names (comma-separated)
iptv.providers=provider1,provider2

# Configure first provider (Xtream Codes)
iptv.provider.provider1.type=xtream_codes
iptv.provider.provider1.xtream-base-url=https://example.com:8080
iptv.provider.provider1.xtream-username=your_username
iptv.provider.provider1.xtream-password=your_password
iptv.provider.provider1.priority=1

# Configure second provider (M3U - untested)
iptv.provider.provider2.type=m3u
iptv.provider.provider2.m3u-url=https://example.com/playlist.m3u
# OR use local file path:
# iptv.provider.provider2.m3u-file-path=/path/to/playlist.m3u
iptv.provider.provider2.priority=2

# Optional: Language prefixes for content matching
# Can be used to identify specific language or source prefixes to IPTV content.
# For example 'EN' for English, or 'UNV' for Universal Studios.
# This is specific to each IPTV provider and how they provide their content.
iptv.language-prefixes-index[0]="AM| "
iptv.language-prefixes=007,4K-A+,4K-AMZ,4K-D+,4K-EN,4K-MAX,4K-MRVL,4K-NF,4K-NF-DO,4M-AMZ,A+,AMZ,CR,D+,D+ ,DWA,EN,EN-TOP,EX,MRVL,Nf,NF,NF-DO,NICK,P+,PCOK,PRMT,SHWT,SKY,TOP,TOP-DO,UFC,UNV,VP
```

### 2. Start IPTV Sync

The IPTV sync service runs automatically every 24 hours (or as configured). You can also trigger a manual sync:

```bash
# Manual sync via API
curl -X POST http://localhost:8080/api/iptv/sync

# Check sync status
curl http://localhost:8080/api/iptv/status
```

### 3. Verify IPTV Content is Indexed

**Option A: Using cURL (for API testing):**

```bash
# Search for content
curl "http://localhost:8080/api/iptv/search?q=Movie%20Title&type=MOVIE"

# Check sync status
curl http://localhost:8080/api/iptv/status
```

**Option B: Using Radarr/Sonarr with DEBUG Logging (Recommended):**

1. Enable DEBUG logging in Radarr/Sonarr:
   - Go to Settings → General → Logging
   - Set Log Level to `Debug` or `Trace`

2. Configure Prowlarr with the DebriDav IPTV indexer (see Prowlarr Integration section below)

3. Perform a search in Radarr/Sonarr for a movie or TV show that exists in your IPTV provider

4. Check the logs to verify:
   - IPTV search queries are being sent
   - Results are being returned
   - Content is being added to the download client

This method provides better visibility into the entire workflow and is recommended for troubleshooting.

## Integration with Sonarr/Radarr

### Prowlarr Integration (Recommended)

DebriDav includes a custom Prowlarr indexer definition that enables seamless IPTV content searching through Prowlarr.

**Setup Steps:**

1. **Copy the indexer definition file**:
   - The file `debridav-iptv.yml` is located in `example.full/prowlarr-config/Definitions/Custom/`
   - Copy it to your Prowlarr configuration directory: `prowlarr-config/Definitions/Custom/debridav-iptv.yml`

2. **Restart Prowlarr** to load the custom indexer definition

3. **Add the indexer in Prowlarr**:
   - Go to Settings → Indexers
   - Click "Add Indexer"
   - Select "DebriDav IPTV" from the list
   - Configure the following:
     - **Base URL**: `http://debridav:8080` (or your DebriDav hostname)
     - **Validation Movie Title** (optional): A movie title that exists in your IPTV provider for testing
     - **Validation TV Title** (optional): A TV show title that exists in your IPTV provider for testing

4. **Sync with Sonarr/Radarr**:
   - Go to Settings → Apps → Sonarr/Radarr
   - Click "Sync" or "Full Sync" to sync the new indexer
   - Restart Sonarr/Radarr after syncing

5. **Verify Integration**:
   - Enable DEBUG logging in Radarr/Sonarr (Settings → General → Logging → Log Level: Debug)
   - Perform a search in Radarr/Sonarr for content that exists in your IPTV provider
   - Check the logs to verify IPTV search queries and results

**How It Works:**

- When Sonarr/Radarr searches for content, Prowlarr queries the DebriDav IPTV indexer
- DebriDav searches its IPTV content database and returns results in Prowlarr-compatible format
- Results appear as "torrents" in Prowlarr (using the `iptv://` protocol internally)
- Sonarr/Radarr can then add the content through DebriDav's qBittorrent API interface
- IPTV content appears in the virtual filesystem and is processed like regular debrid content

### Alternative: Manual IPTV Content Addition

If you prefer not to use Prowlarr, you can manually add IPTV content:

1. **Search for IPTV content** using DebriDav's API:
   ```bash
   curl "http://localhost:8080/api/iptv/search?q=Breaking%20Bad&type=SERIES"
   ```

2. **Add IPTV content** to DebriDav via the qBittorrent API:
   - Use the magnet URI from the search results
   - Add it through Sonarr/Radarr's download client interface, or
   - Use the qBittorrent API directly

3. **Sonarr/Radarr will discover the content** through the virtual filesystem at `/downloads/{category}/` and process it normally.

**Configuration in Sonarr/Radarr:**
- **Download Client**: qBittorrent
- **Host**: `debridav` (or your DebriDav hostname)
- **Port**: `8080`
- **Category**: Use the same categories as configured in DebriDav (e.g., `tv-sonarr`, `radarr`)

## IPTV API Endpoints

### Search IPTV Content
```http
GET /api/iptv/search?q={title}&type={MOVIE|SERIES}&imdbid={imdb_id}&tmdbid={tmdb_id}&tvdbid={tvdb_id}&year={year}
```

**Query Parameters:**
- `q` - Search query (title)
- `type` - Content type: `MOVIE` or `SERIES`
- `imdbid` - IMDb ID (optional)
- `tmdbid` - TMDB ID (optional)
- `tvdbid` - TVDB ID (optional, for TV shows)
- `year` - Release year (optional)
- `season` - Season number (optional, for TV shows)
- `ep` - Episode number (optional, for TV shows)

**Example:**
```bash
curl "http://localhost:8080/api/iptv/search?q=The%20Matrix&type=MOVIE&year=1999"
```

**Response:**
```json
[
  {
    "contentId": "12345",
    "providerName": "provider1",
    "title": "The Matrix (1999)",
    "contentType": "MOVIE",
    "category": "Movies",
    "size": 2147483648,
    "infohash": "abc123...",
    "magnetUri": "iptv://abc123.../provider1/12345",
    "guid": "iptv://abc123.../provider1/12345",
    "url": "iptv://abc123.../provider1/12345"
  }
]
```

### Get Sync Status
```http
GET /api/iptv/status
```

**Example:**
```bash
curl http://localhost:8080/api/iptv/status
```

**Response:**
```json
{
  "lastSyncTime": "2024-01-01T12:00:00Z",
  "nextSyncTime": "2024-01-02T12:00:00Z",
  "syncInProgress": false,
  "providers": [
    {
      "name": "provider1",
      "lastSyncTime": "2024-01-01T12:00:00Z",
      "contentCount": 5000
    }
  ]
}
```

### Manual Sync
```http
POST /api/iptv/sync
```

**Example:**
```bash
curl -X POST http://localhost:8080/api/iptv/sync
```

**Note:** IPTV content is typically added through Prowlarr/Sonarr/Radarr integration rather than directly via API. The search endpoint is primarily used by Prowlarr's custom indexer.

## How It Works

1. **Background Sync**: DebriDav periodically syncs IPTV playlists/APIs and stores content metadata in a searchable database
2. **On-Demand File Creation**: When IPTV content is added via API, DebriDav:
   - Looks up the content in the IPTV database
   - Resolves the tokenized URL (replaces placeholders with actual credentials)
   - Creates a virtual file in `/downloads/{category}/`
   - The file appears identical to debrid content in the filesystem
3. **Streaming**: IPTV content streams directly from the provider URL (no debrid service needed)
4. **Sonarr/Radarr Processing**: Content is processed normally - Sonarr/Radarr see it as a regular download

## Content Organization

- All IPTV content goes to `/downloads/{category}/` (same as debrid content)
- No separate IPTV folder structure - transparent to user
- Sonarr/Radarr will rename and move files to `/tv/` and `/movies/` as configured
- Virtual filesystem shows no indication of IPTV vs Debrid source

## IPTV Live TV Channels

IPTV Live TV channel support allows you to sync and access live TV channels from Xtream Codes providers. Live channels appear in the `/live` folder in the virtual filesystem.

### Feature Toggle

IPTV Live has two separate toggles:

1. **Global Folder Visibility** (`IPTV_LIVE_ENABLED`): Controls whether the `/live` folder is visible in the VFS
2. **Per-Provider Sync Toggle** (`IPTV_PROVIDER_{NAME}_LIVE_SYNC_ENABLED`): Controls whether to sync live content from each provider (opt-in, defaults to `false`)

#### Global Folder Visibility

```yaml
# Enable /live folder visibility (default: false)
IPTV_LIVE_ENABLED=true
```

```properties
iptv.live.enabled=true
```

When `IPTV_LIVE_ENABLED=false`, the `/live` folder is hidden from directory listings but persists in the database (similar to `/movies` and `/tv`).

#### Per-Provider Live Sync (Opt-In)

Live sync is **disabled by default** for each provider. You must explicitly enable it per provider:

```yaml
# Enable live sync for a specific provider (opt-in, defaults to false)
IPTV_PROVIDER_PROVIDER1_LIVE_SYNC_ENABLED=true

# Another provider without this setting will not sync live content (default: false)
# IPTV_PROVIDER_PROVIDER2_LIVE_SYNC_ENABLED is not set, so it defaults to false
```

```properties
# Per-provider setting (default: false)
iptv.provider.provider1.live.sync-enabled=true
```

**Behavior:**
- `IPTV_LIVE_ENABLED` controls `/live` folder visibility only
- Per-provider `live.sync-enabled` defaults to `false` and must be explicitly set to `true` to sync live content from that provider
- Even if `IPTV_LIVE_ENABLED=true`, you still need to set `IPTV_PROVIDER_{NAME}_LIVE_SYNC_ENABLED=true` for each provider you want to sync live content from
- This allows you to enable the `/live` folder but selectively sync live content from only specific providers

### Configuration

#### Basic Configuration

```yaml
# Enable IPTV Live
IPTV_LIVE_ENABLED=true

# Optional: Separate sync interval for live content (more frequent than VOD/Series)
# If not set, live content syncs during the main sync interval
IPTV_LIVE_SYNC_INTERVAL=PT1H

# Optional: Hide category folders, show channels directly under provider folders
# Default: false (shows category folders)
# When true: /live/provider1/BBC One.m3u8 (instead of /live/provider1/Sports/BBC One.m3u8)
IPTV_LIVE_FLAT_CATEGORIES=false

# Optional: Hide provider folders, show their contents directly under /live
# Default: false (shows provider folders)
# When true: /live/Sports/BBC One.m3u8 (or /live/BBC One.m3u8 if categories also flattened)
IPTV_LIVE_FLAT_PROVIDERS=false

# Optional: Sort channels alphabetically instead of provider order
# Default: false (uses provider order)
IPTV_LIVE_SORT_ALPHABETICALLY=false

# Optional: Create VFS entries under /live during sync
# Default: true (creates VFS entries)
# Set to false to skip VFS creation (useful for cleaning up categories/channels before they appear in VFS)
IPTV_LIVE_CREATE_VFS_ENTRIES=true

# Optional: File extension for live channels (per-provider)
# Default: "ts" (can be "m3u8", "ts", etc.)
# Example: IPTV_PROVIDER_PROVIDER1_LIVE_CHANNEL_EXTENSION=ts
```

#### Database Filtering (Exclude Only)

Database filtering controls what gets synced to the database. **Only exclude lists are used** - all channels are imported except those explicitly excluded. This ensures content is available for later VFS inclusion without re-syncing from the provider.

```yaml
# Exclude categories from database sync (regex supported)
# Excluding a category also excludes all channels within that category
IPTV_PROVIDER_PROVIDER1_LIVE_DB_CATEGORY_EXCLUDE=Adult,XXX
# Or use indexed format for better readability:
IPTV_PROVIDER_PROVIDER1_LIVE_DB_CATEGORY_EXCLUDE_INDEX_0=Adult
IPTV_PROVIDER_PROVIDER1_LIVE_DB_CATEGORY_EXCLUDE_INDEX_1=XXX

# Exclude channels from database sync (regex supported)
# Only applies to channels in non-excluded categories
IPTV_PROVIDER_PROVIDER1_LIVE_DB_CHANNEL_EXCLUDE=.*Test.*
# Or use indexed format:
IPTV_PROVIDER_PROVIDER1_LIVE_DB_CHANNEL_EXCLUDE_INDEX_0=.*Test.*
IPTV_PROVIDER_PROVIDER1_LIVE_DB_CHANNEL_EXCLUDE_INDEX_1=.*Demo.*
```

#### VFS Filtering (Include/Exclude)

VFS filtering controls what appears in the `/live` folder from database content. **Both include and exclude lists are used**. Exclude takes precedence over include.

```yaml
# Include categories in /live folder (regex supported)
# Including a category includes all channels within that category (unless channel is excluded)
IPTV_PROVIDER_PROVIDER1_LIVE_CATEGORY_INCLUDE=Sports,News,Entertainment
# Or use indexed format:
IPTV_PROVIDER_PROVIDER1_LIVE_CATEGORY_INCLUDE_INDEX_0=Sports
IPTV_PROVIDER_PROVIDER1_LIVE_CATEGORY_INCLUDE_INDEX_1=News

# Exclude categories from /live folder (regex supported)
# Excluding a category also excludes all channels within that category
IPTV_PROVIDER_PROVIDER1_LIVE_CATEGORY_EXCLUDE=.*HD.*
# Or use indexed format:
IPTV_PROVIDER_PROVIDER1_LIVE_CATEGORY_EXCLUDE_INDEX_0=.*HD.*

# Include channels in /live folder (regex supported)
# Only applies to channels in included categories
IPTV_PROVIDER_PROVIDER1_LIVE_CHANNEL_INCLUDE=.*BBC.*
# Or use indexed format:
IPTV_PROVIDER_PROVIDER1_LIVE_CHANNEL_INCLUDE_INDEX_0=.*BBC.*

# Exclude channels from /live folder (regex supported)
# Only applies to channels in included categories
IPTV_PROVIDER_PROVIDER1_LIVE_CHANNEL_EXCLUDE=.*Test.*
# Or use indexed format:
IPTV_PROVIDER_PROVIDER1_LIVE_CHANNEL_EXCLUDE_INDEX_0=.*Test.*
```

### Filtering Behavior

1. **Category Exclusion Cascades**: Excluding a category (in DB or VFS filtering) excludes all channels within that category.
2. **Category Inclusion Cascades**: Including a category (in VFS filtering) includes all channels within that category (unless the channel is explicitly excluded).
3. **Channel Filters Apply Within Categories**: Channel filters only apply to channels in included categories (for VFS filtering) or non-excluded categories (for DB filtering).
4. **Exclude Takes Precedence**: In VFS filtering, exclude patterns take precedence over include patterns.

### File Structure

Live channels are **always stored** as `/live/{provider}/{category}/{channel}.{extension}` in the database/VFS, where:
- `{provider}` is the sanitized provider name (prevents collisions between providers)
- `{category}` is the sanitized category name
- `{channel}` is the sanitized channel name
- `{extension}` is the configured extension (defaults to `.ts`, configurable per provider via `IPTV_PROVIDER_{PROVIDER}_LIVE_CHANNEL_EXTENSION`)

**Example paths:**
- `/live/provider1/Sports/BBC One.ts`
- `/live/provider1/News/CNN.ts`
- `/live/provider2/Sports/ESPN.ts`

Two separate options control folder flattening:

- **`IPTV_LIVE_FLAT_CATEGORIES`**: When `true`, hides category folders in provider directories, showing channels directly under provider folders (e.g., `/live/provider1/BBC One.ts`)
- **`IPTV_LIVE_FLAT_PROVIDERS`**: When `true`, hides provider folders in `/live`, showing their contents directly under `/live` (e.g., `/live/Sports/BBC One.ts` or `/live/BBC One.ts` if categories are also flattened)

**Presentation Examples:**
- Both `false`: `/live/provider1/Sports/BBC One.ts` (full structure)
- Only `FLAT_CATEGORIES=true`: `/live/provider1/BBC One.ts` (channels directly under provider, categories hidden)
- Only `FLAT_PROVIDERS=true`: `/live/Sports/BBC One.ts` (categories directly under /live, providers hidden)
- Both `true`: `/live/BBC One.ts` (all channels directly under /live, both providers and categories hidden)

Files are always stored with the full path structure (`/live/{provider}/{category}/{channel}.{extension}`) internally, regardless of presentation options.

### Sync Behavior

- **Main Sync**: Live content syncs concurrently with VOD/Series content during the main scheduled sync.
- **Independent Sync**: If `IPTV_LIVE_SYNC_INTERVAL` is configured, live content syncs separately at that interval (more frequent than main sync).
- **Manual Sync**: Trigger live-only sync via `POST /api/iptv/live/sync`.

### Manual Live Sync

```http
POST /api/iptv/live/sync
```

**Example:**
```bash
curl -X POST http://localhost:8080/api/iptv/live/sync
```

### STRM File Compatibility

The STRM file generation feature respects folder visibility. If `/live` is hidden (when `IPTV_LIVE_ENABLED=false`), no STRM files are generated for it, even if configured in `DEBRIDAV_STRM_FOLDER_MAPPINGS`.

## Troubleshooting

### IPTV Content Not Syncing

1. Check IPTV is enabled: `iptv.enabled=true`
2. Verify provider configuration is correct
3. Check logs for sync errors
4. Trigger manual sync: `POST /api/iptv/sync`

### IPTV Content Not Found

1. Verify content exists in provider's playlist/API
2. Check sync completed successfully
3. Try searching with different query terms

### Sonarr/Radarr Not Processing IPTV Content

1. Verify content was added to correct category
2. Check download client configuration in Sonarr/Radarr
3. Ensure category matches between DebriDav and Sonarr/Radarr
4. Check file appears in `/downloads/{category}/` via WebDAV

### Prowlarr Sending Wrong Query (Validation Title Instead of Actual Search)

If you're seeing the validation title (e.g., "Spider-Man") being sent instead of the actual search query (e.g., "Spaceballs") when searching from Radarr:

1. **Check Prowlarr Indexer Configuration**:
   - In Prowlarr, go to Settings → Indexers → Your DebriDav IPTV Indexer
   - Verify the "Validation Movie Title" and "Validation TV Title" fields
   - These should only be used for testing, not for actual searches

2. **Re-sync Indexer with Radarr**:
   - In Prowlarr, go to Settings → Apps → Radarr
   - Click "Sync" or "Full Sync" to ensure Radarr has the latest indexer configuration
   - Restart Radarr after syncing

3. **Restart Prowlarr**:
   - After making configuration changes, restart Prowlarr to ensure the new template is loaded

4. **Check Logs**:
   - Check DebriDav logs for the actual query being received
   - Check Prowlarr logs for query extraction issues
   - The updated configuration now logs the full query string for debugging

5. **Clear Validation Titles** (Optional):
   - After successful setup and testing, you can clear the validation title fields in Prowlarr
   - This ensures they're not accidentally used as fallback queries
   - The indexer will still work - it will just return empty results for connection tests

6. **Verify Template Syntax**:
   - Ensure you're using the latest `debridav-iptv.yml` configuration file
   - The template should prioritize `.Query.Q` over validation titles
   - Check that Prowlarr has reloaded the custom indexer definition

## Example Workflow

### Using Prowlarr Integration (Recommended)

1. **Configure IPTV providers** in your `docker-compose.yml` or environment variables
2. **Restart DebriDav** to load configuration
3. **Wait for initial sync** (or trigger manually: `curl -X POST http://localhost:8080/api/iptv/sync`)
4. **Copy `debridav-iptv.yml`** to your Prowlarr configuration directory
5. **Restart Prowlarr** to load the custom indexer
6. **Add "DebriDav IPTV" indexer** in Prowlarr settings
7. **Sync indexer** with Sonarr/Radarr
8. **Search for content** in Radarr/Sonarr (e.g., "The Matrix")
9. **Select IPTV result** from Prowlarr search results
10. **Radarr/Sonarr adds content** through DebriDav's qBittorrent API
11. **Content appears** in `/downloads/radarr/The Matrix.mp4` (or appropriate category)
12. **Radarr detects** the file and processes it normally
13. **File is moved** to `/movies/` as configured in Radarr

### Using API Directly (Alternative)

1. **Configure IPTV providers** in your configuration
2. **Restart DebriDav** to load configuration
3. **Wait for sync** (or trigger manually)
4. **Search for content**: `curl "http://localhost:8080/api/iptv/search?q=The%20Matrix&type=MOVIE"`
5. **Use the magnet URI** from search results to add content via qBittorrent API or Sonarr/Radarr
6. **Content appears** in `/downloads/radarr/The Matrix.mp4`
7. **Radarr detects** the file and processes it normally
8. **File is moved** to `/movies/` as configured in Radarr

## Notes

- **Tokenized URLs**: IPTV URLs are tokenized in the database (placeholders like `{BASE_URL}`, `{USERNAME}`, `{PASSWORD}`). Actual credentials are injected at runtime when creating virtual files, allowing provider configuration changes without database updates.

- **Direct Streaming**: IPTV content streams directly from the provider URL without requiring debrid services. This makes IPTV content available even without a debrid subscription.

- **Multiple Providers**: Multiple IPTV providers are supported with configurable priority ordering. Providers are queried in priority order when searching for content.

- **M3U Playlist Support**: M3U playlist support is implemented but untested. Xtream Codes API support has been thoroughly tested and is recommended for production use.

- **Local Video Bypass**: When `DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_IPTV_BYPASS_PROVIDERS` is configured, IPTV content bypasses local video file serving and streams directly from the provider. This is useful when you want ARR projects to scan actual IPTV content rather than placeholder files.

- **Language Prefixes**: Language or source prefixes (e.g., "EN" for English, "UNV" for Universal Studios) can be configured to identify specific content types. Prefixes are automatically expanded with predefined separators to match content titles with various naming conventions. This is specific to each IPTV provider's content naming conventions.

- **Metadata Enhancement**: Optional OMDB API integration provides enhanced metadata for movies and TV shows, improving search accuracy and content matching.

- **Response Caching**: When `IPTV_USE_LOCAL_RESPONSES=true`, IPTV API responses are cached locally to reduce API calls and improve performance. Cached responses are stored in the folder specified by `IPTV_RESPONSE_SAVE_FOLDER`.

- **Prowlarr Integration**: The custom indexer definition (`debridav-iptv.yml`) enables Prowlarr to search IPTV content seamlessly. Results appear as "torrents" in Prowlarr but are actually IPTV content that streams directly from providers.

