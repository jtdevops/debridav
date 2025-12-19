# Media Streaming Flows Documentation

This document describes the various ways/flows that external providers' media files are served to requesting media players. The flows assume that a connection to the external media provider has already been established.

## Table of Contents

1. [VFS Path Flow](#1-vfs-path-flow)
2. [Direct External URL Flow (STRM)](#2-direct-external-url-flow-strm)
3. [Proxy URL Flow with Redirect (STRM)](#3-proxy-url-flow-with-redirect-strm)
4. [Proxy URL Flow with Streaming (STRM)](#4-proxy-url-flow-with-streaming-strm)
5. [Direct Streaming Flow (VFS)](#5-direct-streaming-flow-vfs)
6. [Channel-Based Streaming Flow (VFS)](#6-channel-based-streaming-flow-vfs)
7. [Local Video File Flow (ARR Requests)](#7-local-video-file-flow-arr-requests)

---

## 1. VFS Path Flow

### Description
The default flow where media players access files through the Virtual File System (WebDAV). The media player requests files via HTTP/WebDAV, and DebriDav streams the content from the external provider through its streaming service.

### Flow Diagram
```
HTTP Response (External Provider) → StreamingService → OutputStream → Client
```

### How It Works
1. Media player makes HTTP/WebDAV request to DebriDav VFS endpoint
2. `DebridFileResource` receives the request
3. `StreamingService` handles the streaming logic
4. Content is streamed from external provider to media player
5. Streaming can use either direct streaming or channel-based streaming (see flows 5 and 6)

### Configuration Options

**Enable/Disable**: This is the default flow. No specific configuration needed to enable.

**Related Configuration**:
- `debridav.strm.enabled` - When set to `false`, STRM files are not created, forcing use of VFS paths
- `debridav.strm.use-external-url-for-providers` - When empty or provider not listed, VFS paths are used in STRM files

**Example Configuration**:
```properties
# Disable STRM feature to force VFS path usage
debridav.strm.enabled=false
```

---

## 2. Direct External URL Flow (STRM)

### Description
STRM files contain direct external URLs from debrid providers. Media servers (Plex, Jellyfin) read the STRM file and directly access the external URL, bypassing DebriDav for the actual streaming.

### Flow Diagram
```
HTTP Response (External Provider) → Media Server → Client
```

### How It Works
1. STRM file is created with direct external URL (e.g., `https://real-debrid.com/dl/...`)
2. Media server reads STRM file content
3. Media server makes direct HTTP request to external provider URL
4. External provider streams content directly to media server
5. Media server streams to media player

### Configuration Options

**Enable**:
```properties
# Enable STRM feature
debridav.strm.enabled=true

# Enable external URLs for specific providers
debridav.strm.use-external-url-for-providers=REAL_DEBRID,PREMIUMIZE
```

**Disable**:
```properties
# Disable STRM feature entirely
debridav.strm.enabled=false

# OR keep STRM enabled but don't use external URLs (falls back to VFS paths)
debridav.strm.use-external-url-for-providers=
```

**Provider Configuration**:
- Supports provider names: `IPTV`, `REAL_DEBRID`, `PREMIUMIZE`, `EASYNEWS`, `TORBOX`
- Supports `ALL` or `*` for all providers
- Supports negation with `!` prefix (e.g., `*,!IPTV`)

**Additional STRM Configuration**:
```properties
# STRM folder mappings
debridav.strm.folder-mappings=tv=tv_strm,movies=movies_strm

# Optional path prefix for STRM content
debridav.strm.root-path-prefix=/data

# File extension mode (REPLACE or APPEND)
debridav.strm.file-extension-mode=REPLACE

# File filter mode (ALL, MEDIA_ONLY, NON_STRM)
debridav.strm.file-filter-mode=MEDIA_ONLY
```

**Environment Variables**:
```bash
DEBRIDAV_STRM_ENABLED=true
DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=REAL_DEBRID,PREMIUMIZE
```

---

## 3. Proxy URL Flow with Redirect (STRM)

### Description
STRM files contain proxy URLs that point to DebriDav. When accessed, DebriDav verifies/refreshes the external URL and redirects (307) the media server to the external provider URL. This is useful for providers with expiring URLs (e.g., Premiumize).

### Flow Diagram
```
HTTP Response (External Provider) → Media Server → Client
```

### How It Works
1. STRM file is created with proxy URL (e.g., `http://debridav:8080/strm-proxy/12345/movie.mkv`)
2. Media server reads STRM file and requests proxy URL
3. `StrmRedirectProxyController` receives the request
4. DebriDav checks if the external URL is still valid (using cache)
5. If expired, DebriDav refreshes the URL from the provider
6. DebriDav returns 307 redirect to the refreshed/verified external URL
7. Media server follows redirect and streams from external provider

### Configuration Options

**Enable**:
```properties
# Enable STRM feature
debridav.strm.enabled=true

# Enable proxy URLs for providers (takes priority over direct external URLs)
debridav.strm.proxy-external-url-for-providers=PREMIUMIZE,REAL_DEBRID

# Configure proxy base URL (optional, defaults to detected hostname:8080)
debridav.strm.proxy-base-url=http://debridav:8080

# Disable streaming mode (default) - uses redirect
debridav.strm.proxy-stream-mode=false
```

**Disable**:
```properties
# Don't use proxy URLs (use direct external URLs or VFS paths instead)
debridav.strm.proxy-external-url-for-providers=
```

**Provider Configuration**:
- Same provider syntax as direct external URLs
- Proxy URLs take priority over direct external URLs
- IPTV provider URLs don't expire, so proxy URLs are typically not needed for IPTV

**Environment Variables**:
```bash
DEBRIDAV_STRM_ENABLED=true
DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=PREMIUMIZE
DEBRIDAV_STRM_PROXY_BASE_URL=http://debridav:8080
DEBRIDAV_STRM_PROXY_STREAM_MODE=false
```

---

## 4. Proxy URL Flow with Streaming (STRM)

### Description
Similar to flow 3, but instead of redirecting, DebriDav streams the content directly through the proxy. This provides more control over content delivery, better monitoring, and allows for caching.

### Flow Diagram
```
HTTP Response (External Provider) → Channel (2000 capacity = ~128MB buffer) → OutputStream → Media Server → Client
```

### How It Works
1. STRM file is created with proxy URL
2. Media server requests proxy URL with Range headers (for seeking)
3. `StrmRedirectProxyController` receives the request
4. DebriDav verifies/refreshes the external URL if needed
5. Instead of redirecting, DebriDav uses `StreamingService` to stream content
6. Content flows: External Provider → DebriDav → Media Server → Media Player
7. Supports byte-range requests for video seeking

### Configuration Options

**Enable**:
```properties
# Enable STRM feature
debridav.strm.enabled=true

# Enable proxy URLs for providers
debridav.strm.proxy-external-url-for-providers=PREMIUMIZE,REAL_DEBRID

# Enable streaming mode (instead of redirect)
debridav.strm.proxy-stream-mode=true

# Configure proxy base URL
debridav.strm.proxy-base-url=http://debridav:8080
```

**Disable**:
```properties
# Disable streaming mode (use redirect instead)
debridav.strm.proxy-stream-mode=false
```

**Environment Variables**:
```bash
DEBRIDAV_STRM_ENABLED=true
DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=PREMIUMIZE
DEBRIDAV_STRM_PROXY_STREAM_MODE=true
DEBRIDAV_STRM_PROXY_BASE_URL=http://debridav:8080
```

**Benefits of Streaming Mode**:
- More control over content delivery
- Better monitoring and logging
- Supports caching and buffering
- Can apply additional processing/filtering
- Higher server resource usage compared to redirect mode

---

## 5. Direct Streaming Flow (VFS)

### Description
When chunk caching and in-memory buffering are both disabled, and there are no cached chunks, data flows directly from the external provider to the client without server-side buffering. This minimizes latency and server resource usage.

### Flow Diagram
```
HTTP Response (External Provider) → 64KB buffer → OutputStream → Client
```

### How It Works
1. Media player makes VFS request with Range header
2. `StreamingService` checks if direct streaming conditions are met:
   - `enableChunkCaching = false`
   - `enableInMemoryBuffering = false`
   - No cached chunks exist
   - Single remote source
3. If conditions met, uses `streamDirectlyFromHttp()`
4. Data flows directly from HTTP response to client output stream
5. Uses minimal 64KB buffer for efficient streaming
6. No channel-based buffering or database caching

### Configuration Options

**Enable**:
```properties
# Disable chunk caching
debridav.enable-chunk-caching=false

# Disable in-memory buffering
debridav.enable-in-memory-buffering=false

# Optional: Disable byte range request chunking for more direct streaming
debridav.disable-byte-range-request-chunking=true
```

**Disable**:
```properties
# Enable either caching or buffering to use channel-based streaming
debridav.enable-chunk-caching=true
# OR
debridav.enable-in-memory-buffering=true
```

**Environment Variables**:
```bash
DEBRIDAV_ENABLE_CHUNK_CACHING=false
DEBRIDAV_ENABLE_IN_MEMORY_BUFFERING=false
DEBRIDAV_DISABLE_BYTE_RANGE_REQUEST_CHUNKING=true
```

**Benefits**:
- Minimal latency
- Low server resource usage
- Direct data flow from provider to client
- No server-side buffering overhead

**Trade-offs**:
- No caching benefits
- No ability to serve from cached chunks
- Less control over streaming

---

## 6. Channel-Based Streaming Flow (VFS)

### Description
When chunk caching or in-memory buffering is enabled, or when cached chunks exist, the system uses channel-based streaming with buffering. This allows for better performance when serving cached data and more control over the streaming process.

### Flow Diagram
```
[Database (Cached Chunks) + HTTP Response (External Provider)] → Channel (2000 capacity = ~128MB buffer) → Merge → OutputStream → Client
```

### How It Works
1. Media player makes VFS request with Range header
2. `StreamingService` generates a streaming plan based on:
   - Cached chunks in database
   - Requested byte range
   - File metadata
3. Plan identifies sources: cached chunks and remote ranges
4. Data is fetched from multiple sources:
   - Cached chunks: read from database
   - Remote ranges: fetched from external provider
5. Data flows through channels and is merged
6. Buffered data is sent to client in order

### Configuration Options

**Enable**:
```properties
# Enable chunk caching (default)
debridav.enable-chunk-caching=true

# Enable in-memory buffering (default)
debridav.enable-in-memory-buffering=true

# Configure chunk caching threshold (default: 5MB)
debridav.chunk-caching-size-threshold=5120000

# Configure cache max size
debridav.cache-max-size-gb=10
```

**Disable**:
```properties
# Disable both to use direct streaming (see flow 5)
debridav.enable-chunk-caching=false
debridav.enable-in-memory-buffering=false
```

**Environment Variables**:
```bash
DEBRIDAV_ENABLE_CHUNK_CACHING=true
DEBRIDAV_ENABLE_IN_MEMORY_BUFFERING=true
DEBRIDAV_CHUNK_CACHING_SIZE_THRESHOLD=5120000
DEBRIDAV_CACHE_MAX_SIZE_GB=10
```

**Benefits**:
- Can serve from cached chunks (faster for repeated requests)
- Better performance for metadata extraction
- More control over streaming process
- Can optimize for specific byte ranges

**Trade-offs**:
- Higher server resource usage
- Database storage for cached chunks
- Additional latency from buffering

---

## 7. Local Video File Flow (ARR Requests)

### Description
For ARR (Auto-Radarr/Sonarr) requests, DebriDav can serve small local video files instead of streaming from external providers. This reduces bandwidth usage when ARR projects scan media files for metadata.

### Flow Diagram
```
Local File → 64KB buffer → OutputStream → ARR Project
```

### How It Works
1. ARR project (Radarr/Sonarr) makes VFS request to scan media file
2. `ArrRequestDetector` identifies request as ARR request (by hostname/user-agent pattern)
3. `DebridFileResource` checks if local video serving is enabled
4. If enabled and path matches regex, `LocalVideoService` serves local video file
5. Local file is streamed instead of external file
6. ARR project receives metadata from local file
7. No bandwidth used from external provider

### Configuration Options

**Enable**:
```properties
# Enable local video serving for ARR requests
debridav.enable-rclone-arrs-local-video=true

# Configure local video file paths (resolution-based or single file)
debridav.rclone-arrs-local-video-file-paths=1080p=/local-video/video_1080p.mp4,720p=/local-video/video_720p.mp4

# Optional: Regex pattern to match file paths
debridav.rclone-arrs-local-video-path-regex=.*\\.(mp4|mkv|avi)$

# Optional: Minimum file size threshold (files smaller than this use external)
debridav.rclone-arrs-local-video-min-size-kb=1024

# ARR detection patterns
debridav.rclone-arrs-user-agent-pattern=rclone
debridav.rclone-arrs-hostname-pattern=radarr
```

**Disable**:
```properties
# Disable local video serving
debridav.enable-rclone-arrs-local-video=false
```

**IPTV Provider Bypass**:
```properties
# Bypass local video for specific IPTV providers (use external URLs)
debridav.rclone-arrs-local-video-file-iptv-bypass-providers=*
# OR specific providers
debridav.rclone-arrs-local-video-file-iptv-bypass-providers=provider1,provider2
```

**Environment Variables**:
```bash
DEBRIDAV_ENABLE_RCLONE_ARRS_LOCAL_VIDEO=true
DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_PATHS=1080p=/local-video/video_1080p.mp4
DEBRIDAV_RCLONE_ARRS_HOSTNAME_PATTERN=rclone_arrs
DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_MIN_SIZE_KB=1024
```

**File Path Configuration Examples**:
- Single file: `debridav.rclone-arrs-local-video-file-paths=/tmp/video.mp4`
- Resolution mapping: `debridav.rclone-arrs-local-video-file-paths=1080p=/tmp/video_1080p.mkv,720p=/tmp/video_720p.mp4`
- Pipe syntax (multiple resolutions): `debridav.rclone-arrs-local-video-file-paths=2160p|1080p=/tmp/video_1080p.mkv`
- Default fallback: `debridav.rclone-arrs-local-video-file-paths=1080p=/tmp/video_1080p.mkv,/tmp/video_default.mp4`

**Benefits**:
- Zero bandwidth usage from external providers for ARR scans
- Faster metadata extraction (local files)
- Complete control over what ARR projects see
- Valid video files for ARR analysis

**Trade-offs**:
- Requires local storage for video files
- May not reflect actual file properties (size, codec, etc.)

---

## Flow Selection Priority

The system selects flows based on the following priority:

### For STRM Files:
1. **Proxy URLs** (if `debridav.strm.proxy-external-url-for-providers` enabled for provider)
   - If `debridav.strm.proxy-stream-mode=true`: Flow 4 (Proxy Streaming)
   - If `debridav.strm.proxy-stream-mode=false`: Flow 3 (Proxy Redirect)
2. **Direct External URLs** (if `debridav.strm.use-external-url-for-providers` enabled for provider): Flow 2
3. **VFS Paths** (default): Flow 1, then Flow 5 or 6 based on caching configuration

### For VFS Requests:
1. **Local Video Files** (if ARR request detected and enabled): Flow 7
2. **Direct Streaming** (if caching disabled): Flow 5
3. **Channel-Based Streaming** (if caching enabled): Flow 6

---

## Summary Table

| Flow | Use Case | Server Load | Bandwidth | Caching | Configuration Key |
|------|----------|-------------|-----------|---------|-------------------|
| 1. VFS Path | Default WebDAV access | Medium | External | Optional | Default (no config needed) |
| 2. Direct External URL | STRM with direct URLs | Low | External | No | `strm.use-external-url-for-providers` |
| 3. Proxy Redirect | STRM with URL refresh | Low | External | No | `strm.proxy-external-url-for-providers` + `proxy-stream-mode=false` |
| 4. Proxy Streaming | STRM with control | High | External | Optional | `strm.proxy-external-url-for-providers` + `proxy-stream-mode=true` |
| 5. Direct Streaming | VFS without caching | Low | External | No | `enable-chunk-caching=false` + `enable-in-memory-buffering=false` |
| 6. Channel-Based Streaming | VFS with caching | High | External | Yes | `enable-chunk-caching=true` OR `enable-in-memory-buffering=true` |
| 7. Local Video File | ARR metadata scans | Low | None | No | `enable-rclone-arrs-local-video=true` |

---

## Notes

- **Redirects**: This document assumes connections to external providers are already established. Actual redirect handling is not covered as per requirements.
- **Provider Support**: All flows support multiple debrid providers (Real-Debrid, Premiumize, Easynews, TorBox) and IPTV providers.
- **Range Requests**: All VFS flows support HTTP Range requests for video seeking.
- **Error Handling**: All flows include retry logic and error handling for provider failures.
- **Monitoring**: Streaming flows support download tracking when `enable-streaming-download-tracking=true`.

