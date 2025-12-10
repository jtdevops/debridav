# STRM Configuration - Docker Compose Environment Variables

The following environment variables can be used in Docker Compose to configure the STRM file enhancement feature.

## STRM Configuration Environment Variables

| Environment Variable | Description | Default | Example |
|---------------------|-------------|---------|---------|
| `DEBRIDAV_STRM_ENABLED` | Enable/disable STRM feature | `false` | `true` |
| `DEBRIDAV_STRM_FOLDER_MAPPINGS` | Maps root folders to STRM folders (comma-separated key=value pairs) | (empty) | `tv=tv_strm,movies=movies_strm` |
| `DEBRIDAV_STRM_ROOT_PATH_PREFIX` | Optional prefix for paths written in STRM files | (empty) | `/media` |
| `DEBRIDAV_STRM_FILE_EXTENSION_MODE` | How to handle file extensions: `REPLACE` (episode.mkv -> episode.strm) or `APPEND` (episode.mkv -> episode.mkv.strm) | `REPLACE` | `REPLACE` or `APPEND` |
| `DEBRIDAV_STRM_FILE_FILTER_MODE` | Which files to convert: `ALL` (all files), `MEDIA_ONLY` (only media extensions), `NON_STRM` (all except .strm) | `MEDIA_ONLY` | `ALL`, `MEDIA_ONLY`, or `NON_STRM` |
| `DEBRIDAV_STRM_MEDIA_EXTENSIONS` | Comma-separated list of media file extensions when filter mode is `MEDIA_ONLY` | `mkv,mp4,avi,mov,m4v,mpg,mpeg,wmv,flv,webm,ts,m2ts` | `mkv,mp4,avi,mov` |
| `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS` | Comma-separated list of provider names for which to use external URLs. Supports `ALL`/`*` for all providers and `!` prefix for negation | (empty) | `IPTV`, `ALL`, `*,!REAL_DEBRID`, or `IPTV,REAL_DEBRID` |
| `DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS` | Comma-separated list of provider names for which external URLs should use proxy URLs instead of direct URLs. When enabled, STRM files contain proxy URLs that check and refresh expired URLs. Supports `ALL`/`*` for all providers and `!` prefix for negation | (empty) | `PREMIUMIZE`, `ALL`, `*,!IPTV`, or `PREMIUMIZE,REAL_DEBRID` |
| `DEBRIDAV_STRM_PROXY_BASE_URL` | Base URL for STRM redirect proxy. Defaults to `http://{detected-hostname}:8080` if not set (hostname detected via network at startup) | (empty - uses `http://{detected-hostname}:8080`) | `http://debridav:8080` or `http://192.168.1.100:8080` |

## Docker Compose Example

Add these environment variables to your `debridav` service in `docker-compose.yml`:

```yaml
services:
  debridav:
    image: ghcr.io/jtdevops/debridav:latest
    container_name: debridav
    restart: unless-stopped
    environment:
      # ... other environment variables ...
      
      # STRM Configuration
      - DEBRIDAV_STRM_ENABLED=true
      - DEBRIDAV_STRM_FOLDER_MAPPINGS=tv=tv_strm,movies=movies_strm
      - DEBRIDAV_STRM_ROOT_PATH_PREFIX=/media
      - DEBRIDAV_STRM_FILE_EXTENSION_MODE=REPLACE
      - DEBRIDAV_STRM_FILE_FILTER_MODE=MEDIA_ONLY
      - DEBRIDAV_STRM_MEDIA_EXTENSIONS=mkv,mp4,avi,mov,m4v
      # Optional: Use external URLs only for specific providers
      # Examples:
      # - DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=IPTV  # Only IPTV
      # - DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=ALL  # All providers
      # - DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=*,!REAL_DEBRID  # All except Real-Debrid
      # Optional: Use proxy URLs for external URLs for specific providers
      # When enabled, STRM files will contain proxy URLs instead of direct external URLs
      # The proxy checks if URLs are expired when accessed and refreshes them before redirecting
      # Examples:
      # - DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=PREMIUMIZE  # Only Premiumize
      # - DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=ALL  # All providers
      # - DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=*,!IPTV  # All except IPTV (IPTV URLs don't expire)
      # Optional: Configure proxy base URL (defaults to http://{HOSTNAME}:8080)
      # - DEBRIDAV_STRM_PROXY_BASE_URL=http://debridav:8080  # Use container hostname
```

## .env File Example

Alternatively, you can define these in your `.env` file and reference them in docker-compose:

```bash
# STRM Configuration
DEBRIDAV_STRM_ENABLED=true
DEBRIDAV_STRM_FOLDER_MAPPINGS=tv=tv_strm,movies=movies_strm
DEBRIDAV_STRM_ROOT_PATH_PREFIX=/media
DEBRIDAV_STRM_FILE_EXTENSION_MODE=REPLACE
DEBRIDAV_STRM_FILE_FILTER_MODE=MEDIA_ONLY
DEBRIDAV_STRM_MEDIA_EXTENSIONS=mkv,mp4,avi,mov,m4v
# Optional: Use external URLs only for specific providers
# Examples:
# DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=IPTV  # Only IPTV
# DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=ALL  # All providers
# DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=*,!REAL_DEBRID  # All except Real-Debrid
# Optional: Use proxy URLs for external URLs for specific providers
# When enabled, STRM files will contain proxy URLs instead of direct external URLs
# The proxy checks if URLs are expired when accessed and refreshes them before redirecting
# Examples:
# DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=PREMIUMIZE  # Only Premiumize
# DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=ALL  # All providers
# DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=*,!IPTV  # All except IPTV (IPTV URLs don't expire)
# Optional: Configure proxy base URL (defaults to http://{HOSTNAME}:8080)
# DEBRIDAV_STRM_PROXY_BASE_URL=http://debridav:8080  # Use container hostname
```

Then reference them in docker-compose.yml:

```yaml
services:
  debridav:
    environment:
      - DEBRIDAV_STRM_ENABLED=${DEBRIDAV_STRM_ENABLED:-false}
      - DEBRIDAV_STRM_FOLDER_MAPPINGS=${DEBRIDAV_STRM_FOLDER_MAPPINGS:-}
      - DEBRIDAV_STRM_ROOT_PATH_PREFIX=${DEBRIDAV_STRM_ROOT_PATH_PREFIX:-}
      - DEBRIDAV_STRM_FILE_EXTENSION_MODE=${DEBRIDAV_STRM_FILE_EXTENSION_MODE:-REPLACE}
      - DEBRIDAV_STRM_FILE_FILTER_MODE=${DEBRIDAV_STRM_FILE_FILTER_MODE:-MEDIA_ONLY}
      - DEBRIDAV_STRM_MEDIA_EXTENSIONS=${DEBRIDAV_STRM_MEDIA_EXTENSIONS:-mkv,mp4,avi,mov,m4v,mpg,mpeg,wmv,flv,webm,ts,m2ts}
      - DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=${DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS:-}
      - DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=${DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS:-}
      - DEBRIDAV_STRM_PROXY_BASE_URL=${DEBRIDAV_STRM_PROXY_BASE_URL:-}
```

## Notes

- **DEBRIDAV_STRM_ENABLED**: Set to `true` to enable the STRM feature. When `false`, STRM folders will not appear.
- **DEBRIDAV_STRM_FOLDER_MAPPINGS**: Format is `original_folder=strm_folder` pairs separated by commas. Example: `tv=tv_strm,movies=movies_strm` creates `/tv_strm` mirroring `/tv` and `/movies_strm` mirroring `/movies`.
- **DEBRIDAV_STRM_ROOT_PATH_PREFIX**: Optional prefix added to paths in STRM file content. If set to `/media`, a file at `/tv/show/episode.mkv` will have STRM content `/media/tv/show/episode.mkv`.
- **DEBRIDAV_STRM_FILE_EXTENSION_MODE**: 
  - `REPLACE`: Replaces the original extension with `.strm` (e.g., `episode.mkv` -> `episode.strm`)
  - `APPEND`: Appends `.strm` to the filename (e.g., `episode.mkv` -> `episode.mkv.strm`)
- **DEBRIDAV_STRM_FILE_FILTER_MODE**:
  - `ALL`: Convert all files to STRM files
  - `MEDIA_ONLY`: Only convert files with extensions listed in `DEBRIDAV_STRM_MEDIA_EXTENSIONS`
  - `NON_STRM`: Convert all files except `.strm` files
- **DEBRIDAV_STRM_MEDIA_EXTENSIONS**: Comma-separated list of file extensions (without dots). Only used when `DEBRIDAV_STRM_FILE_FILTER_MODE=MEDIA_ONLY`.
- **DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS**: 
  - Configuration option for controlling which providers use external URLs in STRM files
  - Comma-separated list of provider names
  - Valid provider names: `IPTV`, `REAL_DEBRID`, `PREMIUMIZE`, `EASYNEWS`, `TORBOX`
  - **Special values**:
    - `ALL` or `*` - Use external URLs for all providers
    - `!PROVIDER_NAME` - Negation prefix to exclude a provider (e.g., `*,!REAL_DEBRID` means all providers except Real-Debrid)
  - **Examples**:
    - `IPTV` - Only IPTV content will use external URLs, all debrid providers will use VFS paths
    - `IPTV,REAL_DEBRID` - Both IPTV and Real-Debrid content will use external URLs
    - `ALL` or `*` - All providers will use external URLs
    - `*,!REAL_DEBRID` - All providers except Real-Debrid will use external URLs
    - `IPTV,PREMIUMIZE,!EASYNEWS` - IPTV and Premiumize will use external URLs, but not EasyNews (negation takes precedence)
  - If not set (empty), all providers will use VFS paths (default behavior)
  - If external URL is not available for a provider, falls back to VFS path
  - **Use case**: IPTV URLs are stable and don't expire, so they're safe to use in STRM files. Some debrid provider URLs may expire, making them unreliable for STRM files that are accessed later. This allows you to selectively enable external URLs only for providers with stable URLs.
- **DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS**: 
  - Configuration option for controlling which providers should use proxy URLs instead of direct external URLs in STRM files
  - **Takes priority over `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS`** - if a provider is enabled here, it will use proxy URLs regardless of the other setting
  - **Implicitly enables external URLs** - if a provider is enabled here, external URLs are automatically used (proxy URLs are external URLs)
  - Comma-separated list of provider names
  - Valid provider names: `IPTV`, `REAL_DEBRID`, `PREMIUMIZE`, `EASYNEWS`, `TORBOX`
  - **Special values**:
    - `ALL` or `*` - Use proxy URLs for all providers
    - `!PROVIDER_NAME` - Negation prefix to exclude a provider (e.g., `*,!IPTV` means all providers except IPTV)
  - **Examples**:
    - `PREMIUMIZE` - Only Premiumize will use proxy URLs (implicitly enables external URLs for Premiumize)
    - `PREMIUMIZE,REAL_DEBRID` - Both Premiumize and Real-Debrid will use proxy URLs
    - `ALL` or `*` - All providers will use proxy URLs
    - `*,!IPTV` - All providers except IPTV will use proxy URLs (IPTV URLs don't expire)
  - **Interaction with `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS`**:
    - If a provider is in both: Uses proxy URL (proxy takes priority)
    - If a provider is only in `PROXY_EXTERNAL_URL`: Uses proxy URL (implicitly enables external URLs)
    - If a provider is only in `USE_EXTERNAL_URL`: Uses direct external URL
    - If a provider is in neither: Uses VFS path
  - If not set (empty), external URLs will use direct URLs (default behavior)
  - **How it works**: When enabled, STRM files contain proxy URLs (e.g., `http://debridav:8080/strm-proxy/123/episode.mkv`) instead of direct external URLs. When a media server (like Jellyfin) requests the proxy URL, the proxy checks if the URL is expired and refreshes it before redirecting to the active URL. This ensures URLs are always valid when playback starts.
  - **Use case**: When using external URLs for providers like PREMIUMIZE where URLs may expire, this feature ensures that expired URLs are automatically refreshed when media servers try to play content. This works similarly to how VFS handles expired URLs during streaming, but for STRM files that use external URLs directly.
- **DEBRIDAV_STRM_PROXY_BASE_URL**: 
  - Base URL for the STRM redirect proxy endpoint
  - Defaults to `http://{detected-hostname}:8080` if not set (hostname detected via network at startup)
  - The hostname is automatically detected using network detection (`InetAddress.getLocalHost()`) at application startup
  - Falls back to `HOSTNAME` environment variable if network detection fails
  - This should be the base URL that media servers (Jellyfin, Plex) can reach from their network
  - **Examples**:
    - `http://debridav:8080` - Use container hostname (recommended for Docker Compose)
    - `http://my-server.local:8080` - Use a custom hostname
    - `http://192.168.1.100:8080` - Use an IP address (not recommended, use hostname instead)
  - **Use case**: In Docker Compose setups, media servers need to be able to reach the proxy endpoint. Using the container hostname ensures proper network resolution. The port defaults to 8080 (the server port).
  - **Note**: If hostname detection fails and `HOSTNAME` environment variable is not available and this is not set, the application will fail to start with a clear error message.

- **DEBRIDAV_STRM_PROXY_STREAM_MODE**: 
  - Enable streaming mode for STRM proxy (default: `false`)
  - If `true`, content is streamed directly through the proxy instead of redirecting to external URLs
  - If `false` (default), the proxy redirects to the external URL after verifying/refreshing it
  - **When streaming mode is enabled**:
    - Content is streamed directly through the proxy using the same streaming logic as VFS files
    - Provides more control over content delivery
    - Allows for better caching and monitoring
    - Supports byte-range requests (for seeking in media players)
    - URL verification and refresh still occurs before streaming
  - **When streaming mode is disabled** (default):
    - The proxy performs URL verification/refresh and then redirects (307) to the external URL
    - Media servers handle the actual streaming from the external URL
    - Lower server resource usage (no streaming through proxy)
  - **Use case**: Enable streaming mode when you want more control over content delivery, need better monitoring/logging, or want to apply additional processing/filtering to the stream. Keep disabled if you prefer direct streaming from external URLs to reduce server load.
  - **Example**: `DEBRIDAV_STRM_PROXY_STREAM_MODE=true`

