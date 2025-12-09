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

