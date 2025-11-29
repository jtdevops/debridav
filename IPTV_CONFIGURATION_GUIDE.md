# IPTV Integration Configuration Guide

## Overview

The IPTV integration allows DebriDav to index and serve VOD (Video on Demand) content from IPTV providers (M3U playlists or Xtream Codes API). This content appears in the virtual filesystem alongside debrid content, making it accessible to Sonarr/Radarr through the existing download client interface.

## Current Status

⚠️ **Important**: The IPTV integration is implemented, but **Prowlarr does not natively support IPTV as an indexer type**. Prowlarr only supports Torrent and Usenet indexers. The IPTV content is searchable through DebriDav's API, but automatic integration with Prowlarr requires additional development (the `IptvProwlarrAdapter` mentioned in the plan is not yet implemented).

## Configuration Steps

### 1. Configure IPTV Providers in DebriDav

Edit `src/main/resources/application.properties` or your runtime configuration:

```properties
# Enable IPTV
iptv.enabled=true

# Sync interval (default: every 24 hours)
iptv.sync-interval=PT24H

# List of provider names (comma-separated)
iptv.providers=my-iptv-provider,backup-provider
```

#### Configure M3U Provider

```properties
# For provider named "my-iptv-provider"
iptv.provider.my-iptv-provider.type=m3u
iptv.provider.my-iptv-provider.m3u-url=https://example.com/playlist.m3u
# OR use local file path:
# iptv.provider.my-iptv-provider.m3u-file-path=/path/to/playlist.m3u
iptv.provider.my-iptv-provider.priority=1
```

#### Configure Xtream Codes Provider

```properties
# For provider named "backup-provider"
iptv.provider.backup-provider.type=xtream_codes
iptv.provider.backup-provider.xtream-base-url=https://example.com:8080
iptv.provider.backup-provider.xtream-username=your_username
iptv.provider.backup-provider.xtream-password=your_password
iptv.provider.backup-provider.priority=2
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

Check that content has been synced:

```bash
# Search for content
curl -X POST "http://localhost:8080/api/iptv/search?query=Movie%20Title&type=MOVIE"

# List all content (if implemented)
curl http://localhost:8080/api/iptv/content
```

## Integration with Sonarr/Radarr

### Current Approach (Recommended)

Since Prowlarr doesn't support IPTV indexers natively, you have two options:

#### Option A: Manual IPTV Content Addition

1. **Search for IPTV content** using DebriDav's API:
   ```bash
   curl -X POST "http://localhost:8080/api/iptv/search?query=Breaking%20Bad&type=SERIES"
   ```

2. **Add IPTV content** to DebriDav:
   ```bash
   curl -X POST http://localhost:8080/api/iptv/add \
     -H "Content-Type: application/json" \
     -d '{
       "contentId": "12345",
       "providerName": "my-iptv-provider",
       "category": "tv-sonarr"
     }'
   ```

3. **Sonarr/Radarr will discover the content** through the virtual filesystem at `/downloads/{category}/` and process it normally.

#### Option B: Use DebriDav as Download Client (Existing Setup)

Sonarr and Radarr are already configured to use DebriDav as a download client (qBittorrent interface). IPTV content added through the API will appear in the same download location and be processed automatically.

**Configuration in Sonarr/Radarr:**
- **Download Client**: qBittorrent
- **Host**: `debridav` (or your DebriDav hostname)
- **Port**: `8080`
- **Category**: Use the same categories as configured in DebriDav (e.g., `tv-sonarr`, `radarr`)

## Future Prowlarr Integration

To fully integrate IPTV with Prowlarr, the following would need to be implemented:

1. **Prowlarr Indexer Adapter**: Create a custom indexer type that queries DebriDav's IPTV search API
2. **Prowlarr Plugin**: Develop a Prowlarr plugin that adds IPTV as a native indexer type
3. **Alternative**: Modify the IPTV API to return results in a Prowlarr-compatible format (Torrent/Usenet indexer format)

### Potential Implementation

The `IptvProwlarrAdapter` (mentioned in `IPTV_plan.md`) would need to:
- Convert IPTV search results to Prowlarr's expected indexer format
- Map IPTV content to "magnet" or "nzb" format for Prowlarr compatibility
- Handle both Torrent and Usenet indexer types (Prowlarr limitation)

## IPTV API Endpoints

### Search IPTV Content
```http
POST /api/iptv/search?query={title}&type={MOVIE|SERIES}&category={optional}
```

**Response:**
```json
[
  {
    "contentId": "12345",
    "providerName": "my-iptv-provider",
    "title": "Movie Title",
    "contentType": "MOVIE",
    "category": "Movies"
  }
]
```

### Add IPTV Content
```http
POST /api/iptv/add
Content-Type: application/json

{
  "contentId": "12345",
  "providerName": "my-iptv-provider",
  "category": "tv-sonarr"
}
```

### Get Status
```http
GET /api/iptv/status
```

### Manual Sync
```http
POST /api/iptv/sync
```

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

1. **Configure IPTV provider** in `application.properties`
2. **Restart DebriDav** to load configuration
3. **Wait for sync** (or trigger manually)
4. **Search for content**: `POST /api/iptv/search?query=The%20Matrix&type=MOVIE`
5. **Add content**: `POST /api/iptv/add` with contentId and providerName
6. **Content appears** in `/downloads/radarr/The Matrix.mp4`
7. **Radarr detects** the file and processes it normally
8. **File is moved** to `/movies/` as configured in Radarr

## Notes

- IPTV URLs are tokenized in the database (placeholders like `{BASE_URL}`, `{USERNAME}`, `{PASSWORD}`)
- Actual credentials are injected at runtime when creating virtual files
- This allows provider configuration changes without database updates
- IPTV content streams directly - no debrid provider needed
- Multiple IPTV providers are supported with priority ordering

