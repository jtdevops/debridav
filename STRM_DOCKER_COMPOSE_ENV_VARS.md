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

