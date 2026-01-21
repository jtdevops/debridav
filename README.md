# DebriDav

[![build](https://github.com/jtdevops/debridav/actions/workflows/build.yaml/badge.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?logo=springboot&logoColor=fff)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-%237F52FF.svg?logo=kotlin&logoColor=white)](#)
[![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=fff)](#)

> [!NOTE]
> This is a fork of the original [DebriDav project](https://github.com/skjaere/debridav) with enhanced features and improved configuration options. All original features remain as default options, with new capabilities available as opt-in enhancements.

> [!TIP]
> **TLDR**: All new settings are preconfigured in the `example.full` folder's `docker-compose.yml` file. For a quick start with optimized streaming configuration, see the [Example Configurations](#example-configurations) section below.

> **Quick links to new features:**
> - [Fork Enhancements Overview](#fork-enhancements)
> - [IPTV Movies/TV Shows Integration](#iptv-moviestv-shows-integration)
> - [WebDAV Folder Mapping](#webdav-folder-mapping)
> - [Enhanced Caching & Performance Options](#enhanced-caching--performance-options)
> - [Streaming & Retry Configuration](#streaming--retry-configuration)
> - [Download Tracking & Monitoring](#download-tracking--monitoring)
> - [Local Video File Serving for ARR Projects](#local-video-file-serving-for-arr-projects)
> - [Streaming Download Tracking Endpoint](#streaming-download-tracking-endpoint)
> - [Example Configurations (including optimized streaming setup)](#example-configurations)
> - [Building the Application](#building-the-application)

<br/>

## What is it?

A small app written in Kotlin that emulates the qBittorrent and SABnzbd APIs and creates virtual files that are mapped
to remotely cached files at debrid services, essentially acting as a download client that creates virtual file
representations of remotely hosted files rather than downloading them. DebriDav exposes these files via the WebDav
protocol so that they can be mounted.

## Features

- Stream content from Real Debrid, Premiumize and Easynews with Plex/Jellyfin.
- **IPTV Movies/TV Shows**: Index and stream VOD content from IPTV providers (Xtream Codes API and M3U playlists) alongside debrid content.
- **STRM File Support**: Generate STRM files for media servers (Plex, Jellyfin) that can use direct external URLs or proxy URLs with automatic link refresh for expiring URLs.
- Sort your content as you would regular files. You can create directories, rename files, and move them around any way
  you like. No need to write regular expressions.
- Seamless integration into the arr-ecosystem, providing a near identical experience to downloading torrents. DebriDav
  integrates with Sonarr and Radarr using the qBittorrent API,
  so they can add content, and automatically move your files for you.
- Supports multiple debrid providers. DebriDav supports enabling both Premiumize and Real Debrid concurrently with
  defined priorities. If a torrent is not cached in the primary provider, it will fall back to the secondary.
- Supports multiple IPTV providers with configurable priority and automatic content syncing.

## Fork Enhancements

This fork adds several significant improvements and new features while maintaining full backward compatibility:

### 🚀 Performance & Optimization
- **Granular Caching Controls**: Enable or disable chunk caching and in-memory buffering independently for fine-tuned performance
- **Debrid Client Response Caching**: Reduces API calls to debrid providers by caching direct download responses (Premiumize) and torrent info (Real Debrid)
- **Configurable Cache Durations**: Customize link liveness cache and cached file cache durations to match your usage patterns
- **Byte Range Request Control**: Option to disable aggressive chunking and caching for scenarios where direct streaming is preferred

### 📊 Monitoring & Observability
- **Streaming Download Tracking**: New actuator endpoint (`/actuator/streaming-download-tracking`) provides detailed insights into download behavior, bandwidth usage, and completion status
- **Enhanced Logging**: Improved debug logging with structured log messages for better troubleshooting
- **Configuration Logging**: Startup configuration logging for easier debugging and verification

### 🎬 ARR Integration Improvements
- **Local Video File Serving**: Serve small, locally-hosted video files to ARR projects instead of actual media files, dramatically reducing bandwidth usage during library scans
- **Automatic Link Refresh**: Automatic retry with fresh links when streaming errors occur
- **Configurable Streaming Retries**: Fine-tune retry behavior with customizable delays and retry counts for different error types

### 📁 STRM File Support
- **Automatic STRM Generation**: Generate STRM files for media servers (Plex, Jellyfin) with configurable URL types
- **Provider-Specific URL Configuration**: Use VFS paths, direct external URLs, or proxy URLs with automatic link refresh per provider
- **Flexible Filtering**: Control which files and providers use STRM files with regex patterns and provider lists
- **Proxy URL Support**: Automatic link refresh for expiring URLs when using proxy URLs in STRM files

### 📺 IPTV Integration
- **IPTV Movies/TV Shows Support**: Index and stream VOD content from IPTV providers alongside debrid content
- **Xtream Codes API Support**: Full support for Xtream Codes IPTV providers with automatic content syncing
- **M3U Playlist Support**: Support for M3U playlist-based IPTV providers (untested)
- **Prowlarr Integration**: Custom indexer definition for searching IPTV content through Prowlarr
- **Multiple Provider Support**: Configure multiple IPTV providers with priority-based fallback
- **Metadata Integration**: Optional OMDB API integration for enhanced content metadata
- **Direct Streaming**: IPTV content streams directly from providers without requiring debrid services

### 🔧 Developer Experience
- **Multi-Stage Docker Build**: Optimized Docker builds with separate build and runtime stages
- **Enhanced Error Handling**: Better error handling and recovery mechanisms throughout the application
- **Code Quality Improvements**: Various bug fixes and code improvements for better maintainability

All original features remain enabled by default. These enhancements are opt-in and can be configured via environment variables documented below.

## How does it work?

It is designed to be used with the *arr ecosystem. DebriDav emulates the qBittorrent and SABnzbd APIs, so you can add it
as download clients in the arrs.
Once a magnet/nzb is sent to DebriDav it will check if it is cached in any of the available debrid providers and
create file representations for the streamable files hosted at debrid providers.

Note that DebriDav does not read the torrents added to your Real Debrid account, or your Premiumize cloud storage.
Content you wish to be accessible through DebriDav must be added with the qBittorrent API. An feature to import
these files to DebriDav may be added in the future.

## Which debrid services are supported?

Currently Real Debrid, Premiumize, Easynews and TorBox are supported. If there is demand more may be added in the
future.

### Note about Real Debrid

Due to changes in the Real Debrid API, to the authors best knowledge, the only way to check if a file is instantly
available
is to start the torrent and then check if the contained files have links available for streaming.
This means that if Real Debrid is enabled, every time a magnet is added to Debridav, the torrent will potentially be
started on Real Debrid's service. DebriDav will attempt to immediately delete the torrent if no links are available.

### Note about Easynews

Easynews does not provide apis to use the contents of an nzb file to search for streamable content, so instead DebriDav
will attempt to use the search feature to find an approximate match for the name of the nzb or torrent.

funkypenguin of Elfhosted has created [an indexer](https://github.com/elfhosted/fakearr) that pairs well with DebriDav
and Easynews.

## Caching

When a new video file is added to the library, this file will typically be read by multiple services attempting to
extract metadata from it. This can result in a lot of calls to the debrid provider that hosts the file, and a slow
import process.

To help with this, DebriDav features an opinionated byte cache designed to cache requests to extract metadata from
media files. It does this by caching the bytes of a request that only read below a defined threshold of bytes
( default 5mb ). If you use rclone to mount DebriDav, it is recommended to disable its cache for this reason.

To purge the cache, you may send a `DELETE` request to `http://<debridav>/actuator/cache`. If using the example docker
compose, you may use `curl -X DELETE http://localhost:8888/actuator/cache`.

### Cache Configuration Options

The fork adds granular control over caching behavior:

- **Chunk Caching**: Control whether bytes are cached to the database (`DEBRIDAV_ENABLE_CHUNK_CACHING`). Enabled by default.
- **In-Memory Buffering**: Control whether data is buffered in memory before being sent to the client (`DEBRIDAV_ENABLE_IN_MEMORY_BUFFERING`). Enabled by default.
- **Cache Durations**: Configure how long link liveness checks and cached file metadata are kept (`DEBRIDAV_LINK_LIVENESS_CACHE_DURATION`, `DEBRIDAV_CACHED_FILE_CACHE_DURATION`).
- **Debrid Client Caching**: Response caching for debrid provider API calls reduces redundant requests (`DEBRIDAV_DEBRID_DIRECT_DL_RESPONSE_CACHE_EXPIRATION_SECONDS`).

**To disable caching completely**, you have two options:

1. **Legacy method** (original behavior): Set `DEBRIDAV_CHUNKCACHINGSIZETHRESHOLD=0`
   - This prevents any bytes from being cached by making the threshold zero, so caching stops immediately
   - However, some cache structure overhead may still remain

2. **Recommended method** (new granular control): Set both `DEBRIDAV_ENABLE_CHUNK_CACHING=false` and `DEBRIDAV_ENABLE_IN_MEMORY_BUFFERING=false`
   - This prevents cache structures from being initialized at all, providing a cleaner and more efficient approach
   - Also provides finer control - you can disable database caching while keeping in-memory buffering, or vice versa

### Legacy Cache Configuration

`DEBRIDAV_CHUNKCACHINGGRACEPERIOD` controls the amount of time that should pass from the last time the item is
read from the cache until it should be deleted in string format ( ie 10m, 2h, 4d ), or 0m to keep them until
manually cleared. The default value is 0m ( off )
`DEBRIDAV_CHUNKCACHINGSIZETHRESHOLD` controls the maxiumum size of byte range requests to cache in bytes.
The default value is 5120000 ( 5Mb )
`DEBRIDAV_CACHEMAXSIZE` controls the max size of the cache in gigabytes. If the size of the cache exceeds this number,
the items which were accessed the longest time ago will be purged from the cache to make space for the new entry.

## Migrating to 0.8.0

Since 0.8.0 DebriDav uses a PostgreSQL database to store it's content. Unless disabled by setting
`DEBRIDAV_ENABLEFILEIMPORTONSTARTUP` to `false`, DebriDav will attempt to import existing content into the database.
It is recommended to disable this feature after a successful import to improve startup time.

## Monitoring

There is a docker compose file in /example/observability which includes some useful services for monitoring the DebriDav
and associated services. See [OBSERVABILITY.md](example/monitoring/MONITORING.md)

### Streaming Download Tracking Endpoint

When `DEBRIDAV_ENABLE_STREAMING_DOWNLOAD_TRACKING=true`, a new actuator endpoint is available at `/actuator/streaming-download-tracking` 
that provides detailed insights into download behavior:

- Requested byte ranges and sizes
- Actual bytes downloaded and sent
- Download completion status and duration
- HTTP headers and source information
- Bandwidth usage metrics

Access the endpoint via: `curl http://localhost:8888/actuator/streaming-download-tracking` (adjust host/port as needed)

## How do I use it?

### Elfhosted

Like the concept of streaming your Premiumize / EasyNews content, but don't want the hassle of configuring and
self-hosting?

[ElfHosted](https://elfhosted.com) is a geeky, [open-source](https://docs.elfhosted.com/open-source/) PaaS, which
provides all the "plumbing" (_hosting, security, updates, etc_) for your self-hosted apps. ElfHosted provide entire
hosted streaming "bundles", so all you have to do is plug in your EasyNews / Premiumize credentials, fire up Radarr /
Sonarr, and start streaming!

ElfHosted offer pre-configured bundles (*with a $1 7-day trial*) for Streaming from Premiumize
with [Plex](https://store.elfhosted.com/product/hobbit-plex-premiumize-aars/), [Emby](https://store.elfhosted.com/product/hobbit-emby-premiumize-aars/),
or [Jellyfin](https://store.elfhosted.com/product/hobbit-jellyfin-premiumize-aars/), as well as from EasyNews
with [Plex](https://store.elfhosted.com/product/hobbit-plex-easynews-aars/), [Emby](https://store.elfhosted.com/product/hobbit-emby-easynews-aars/),
or [Jellyfin](https://store.elfhosted.com/product/hobbit-jellyfin-easynews-aars/), and also
offers [DebriDav "unbundled"](https://store.elfhosted.com/product/debridav/) to augment their existing, debrid-connected
stacks.

> [!IMPORTANT]
> A portion of your ElfHosted DebriDav subscription supports further development of DebriDav, under
> the ["Elf-illiate" program](https://store.elfhosted.com/affiliate/)

### Requirements

Since 0.8.0, DebriDav requires a postgres server.
To build the project you will need a java 21 JDK.

### Running with Docker compose ( recommended )

See [QUICKSTART](example/QUICKSTART.md)

#### Example Configurations

The project includes two example configurations:

- **`example/`** - Basic example configuration with standard setup
- **`example.full/`** - Optimized streaming configuration with enhanced settings for production use

The `example.full` folder contains an optimized streaming configuration that includes:
- Pre-configured streaming optimizations (byte range chunking disabled, direct streaming enabled)
- Enhanced caching configurations for reduced bandwidth usage
- Local video file serving setup for ARR projects
- IPTV integration configuration with example provider setups (Xtream Codes)
- Prowlarr custom indexer definition for IPTV content searching
- Complete docker-compose setup with all services (DebriDav, rclone, Sonarr, Radarr, Prowlarr, PostgreSQL)
- Additional media server configurations (Plex, Jellyfin, Jellyseerr)

See [QUICKSTART.md](example.full/QUICKSTART.md) for detailed setup instructions.

#### Building the Application

When using the `example.full` configuration, you can build the DebriDav application in two ways:

**Option 1: Using docker-compose (Recommended)**
```bash
cd example.full
docker-compose build debridav
```

**Option 2: Using docker build directly**
```bash
docker build -t debridav:latest -f ../Dockerfile ..
```

The build context from `example.full` points to the project root (`..`), so when building manually you need to specify the Dockerfile path relative to the build context (the project root).

### Running the jar

Run `./gradlew bootJar` to build the jar, and then `java -jar build/libs/debridav-0.1.0-SNAPSHOT.jar` to run the app.
Alternatively `./gradlew bootRun` can be used.

### Running with docker

`docker run ghcr.io/jtdevops/debridav:latest`

### Build docker image

To build the docker image run `./gradlew jibDockerBuild`

You will want to use rclone to mount DebriDav to a directory which can be shared among docker containers.
[docker-compose.yaml](example/docker-compose.yaml) in examples/ can be used as a starting point.

## Configuration

The following values can be defined as environment variables. These environment variable names match the Spring Boot property names (with hyphens converted to underscores and uppercase). When using docker-compose, these variables are referenced in your `.env` file and passed to the container.

| NAME                               | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_ROOT_PATH                 | The root path of DebriDav. DebriDav will store configuration data, databases, files under this directory. When running as docker this directory refers to the path within the docker container. Used in docker-compose as `${DEBRIDAV_ROOT_PATH}`.                      | ./debridav-files |
| DEBRIDAV_DOWNLOAD_PATH             | The path under `DEBRIDAV_ROOT_PATH` where downloaded files will be placed. Used in docker-compose as `${DEBRIDAV_DOWNLOAD_PATH}`.                                                                                                                                            | /downloads       |
| DEBRIDAV_MOUNT_PATH_CONTAINERS     | The path where DebriDav will be mounted inside docker containers. Used in docker-compose as `${DEBRIDAV_MOUNT_PATH_CONTAINERS}`. If kept at default values, downloads will be visible to the arrs in /data/downloads. | /data            |
| DEBRIDAV_MOUNT_PATH_HOST_FS        | The path where DebriDav will be mounted on your host filesystem. Used in docker-compose as `${DEBRIDAV_MOUNT_PATH_HOST_FS}`.                                                                                               | ./debridav       |
| DEBRIDAV_ROOT_HOST_FS              | The path on the host filesystem DebriDav will use for storage. Used in docker-compose as `${DEBRIDAV_ROOT_HOST_FS}`.                                                                                                  | ./debridav-storage |
| DEBRIDAV_PORT                      | The port to expose DebriDav on the host. Used in docker-compose as `${DEBRIDAV_PORT}`.                                                                                                                              | 8888             |
| DEBRIDAV_DEBRID_CLIENTS            | A comma separated list of enabled debrid providers. Allowed values are `real_debrid`, `premiumize`, `easynews` and `torbox`. Note that the order determines the priority in which they are used.                     |                  |
| DEBRIDAV_DB_HOST                   | The host of the PostgresSQL database server                                                                                                                                                                          | localhost        |
| DEBRIDAV_DB_PORT                   | The port of the PostgresSQL database server                                                                                                                                                                          | 5432             |
| DEBRIDAV_DB_DATABASE_NAME          | The name of the database to use within the PostgresSQL server                                                                                                                                                        | debridav         |
| DEBRIDAV_DB_USERNAME               | The username to use when connecting the PostgresSQL server                                                                                                                                                           | debridav         |
| DEBRIDAV_DB_PASSWORD               | The password to use when connecting the PostgresSQL server                                                                                                                                                           | debridav         |
| DEBRIDAV_ENABLE_FILE_IMPORT_ON_STARTUP | Enables importing content from the filesystem to the database.                                                                                                                                                       | true             |
| DEBRIDAV_DEFAULT_CATEGORIES       | A comma separated list of categories to create on startup                                                                                                                                                            |                  |
| DEBRIDAV_LOCAL_ENTITY_MAX_SIZE_MB  | The maximum allowed size in MB for locally stored files. Useful to prevent accidentally large files in the database. Set to 0 for no limit                                                                           | 50               |
| DEBRIDAV_CHUNK_CACHING_GRACE_PERIOD | The amount of time to keep chunks in the cache as a duration string ( 2m, 4h, 2d etc)                                                                                                                                | 4h               |
| DEBRIDAV_CHUNK_CACHING_SIZE_THRESHOLD | The maximum chunk size to cache in bytes.                                                                                                                                                                            | 5120000 ( 5Mb )  |
| DEBRIDAV_CACHE_MAX_SIZE_GB        | The maximum size of the cache in gigabytes.                                                                                                                                                                          | 2                |
| PREMIUMIZE_API_KEY                 | The api key for Premiumize (REST API)                                                                                                                                                                                |                  |
| PREMIUMIZE_WEBDAV_USERNAME         | The username for Premiumize WebDAV (required for WebDAV folder mapping). Get from https://www.premiumize.me/account                                                                                 |                  |
| PREMIUMIZE_WEBDAV_PASSWORD         | The password for Premiumize WebDAV (required for WebDAV folder mapping). Get from https://www.premiumize.me/account                                                                                 |                  |
| REAL_DEBRID_API_KEY                | The api key for Real Debrid (REST API)                                                                                                                                                                               |                  |
| REAL_DEBRID_WEBDAV_USERNAME        | The username for Real-Debrid WebDAV (required for WebDAV folder mapping). Get from your Real-Debrid account settings                                                                                |                  |
| REAL_DEBRID_WEBDAV_PASSWORD        | The password for Real-Debrid WebDAV (required for WebDAV folder mapping). Get from your Real-Debrid account settings                                                                                |                  |
| REAL_DEBRID_SYNC_ENABLED           | If set to true, DebriDav will periodically poll Real-Debrid's API for torrents and downloads for re-use                                                                                                              | true             |
| REAL_DEBRID_SYNC_POLL_RATE         | The rate at which DebriDav will sync downloads and torrents ( if enabled by REAL_DEBRID_SYNC_ENABLED ) as a [ISO8601 time string](https://en.wikipedia.org/wiki/ISO_8601#Durations).                                       | PT4H ( 4 hours ) |
| EASYNEWS_USERNAME                  | The Easynews username                                                                                                                                                                                                |                  |
| EASYNEWS_PASSWORD                  | The Easynews password                                                                                                                                                                                                |                  |
| EASYNEWS_ENABLED_FOR_TORRENTS     | If set to true, DebriDav will search for releases in Easynews matching the torrent name of torrents added via the qBittorrent API                                                                                    | true             |
| EASYNEWS_RATE_LIMIT_WINDOW_DURATION | The size of the time window to use for rate limiting.                                                                                                                                                                | 15 seconds       |
| EASYNEWS_ALLOWED_REQUESTS_IN_WINDOW | The number of requests allowed in the time window. eg: EASYNEWS_RATE_LIMIT_WINDOW_DURATION=10s and  EASYNEWS_ALLOWED_REQUESTS_IN_WINDOW=3 will allow 3 requests per 10 seconds before forcing subsequent requests to wait. | 10               |
| EASYNEWS_CONNECT_TIMEOUT           | The amount of time in milliseconds to wait while establishing a connection to Easynews' servers.                                                                                                                     | 20000            |
| EASYNEWS_SOCKET_TIMEOUT            | The amount of time in milliseconds to wait between receiving bytes from Easynews' servers.                                                                                                                           | 5000             |
| TORBOX_API_KEY                     | The api key for TorBox (REST API)                                                                                                                                                                                    |                  |
| TORBOX_WEBDAV_USERNAME             | The username for TorBox WebDAV (required for WebDAV folder mapping). Get from your TorBox account settings                                                                                                         |                  |
| TORBOX_WEBDAV_PASSWORD             | The password for TorBox WebDAV (required for WebDAV folder mapping). Get from your TorBox account settings                                                                                                         |                  |
| SONARR_INTEGRATION_ENABLED         | Enable integration of Sonarr.                                                                                                                                                                                        | true             |
| SONARR_HOST                        | The host of Sonarr                                                                                                                                                                                                   | sonarr-debridav  |
| SONARR_PORT                        | The port of Sonarr                                                                                                                                                                                                   | 8989             |
| SONARR_API_KEY                     | The API key for Sonarr                                                                                                                                                                                               |                  |
| SONARR_CATEGORY                    | The qBittorrent cateogy Sonarr uses                                                                                                                                                                                  | tv-sonarr        |
| RADARR_INTEGRATION_ENABLED         | Enable integration of Radarr. See description of SONARR_INTEGRATION_ENABLED                                                                                                                                          | true             |
| RADARR_HOST                        | The host of Radarr                                                                                                                                                                                                   | radarr-debridav  |
| RADARR_PORT                        | The port of Radarr                                                                                                                                                                                                   | 7878             |
| RADARR_API_KEY                     | The API key for Radarr                                                                                                                                                                                               |                  |
| RADARR_CATEGORY                    | The qBittorrent cateogy Radarr uses                                                                                                                                                                                  | radarr           |

### Enhanced Caching & Performance Options

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_ENABLE_CHUNK_CACHING                  | Enable or disable caching of file chunks to the database. When disabled, chunks are not stored in the database cache.                                                                                             | true             |
| DEBRIDAV_ENABLE_IN_MEMORY_BUFFERING            | Enable or disable in-memory buffering of data before sending to clients. When disabled, data is streamed directly without buffering.                                                                                | true             |
| DEBRIDAV_DISABLE_BYTE_RANGE_REQUEST_CHUNKING   | When set to true, disables aggressive chunking and caching of byte range requests. Uses exact user-requested ranges without breaking them into chunks for caching optimization. Useful for direct streaming scenarios. | false            |
| DEBRIDAV_LINK_LIVENESS_CACHE_DURATION          | Duration to cache link liveness check results. Format: duration string (e.g., 15m, 30m, 1h). Reduces redundant API calls to debrid providers.                                                                      | 15m              |
| DEBRIDAV_CACHED_FILE_CACHE_DURATION            | Duration to cache cached file metadata. Format: duration string (e.g., 15m, 30m, 1h). Reduces redundant API calls when checking file availability.                                                                   | 30m              |
| DEBRIDAV_DEBRID_DIRECT_DL_RESPONSE_CACHE_EXPIRATION_SECONDS | Duration to cache debrid client API responses (Premiumize direct downloads, Real Debrid torrent info). Format: duration string (e.g., 30s, 1m). Reduces API rate limiting issues. | 30s              |

### Streaming & Retry Configuration

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_STREAMING_DELAY_BETWEEN_RETRIES       | Delay between retry attempts when streaming encounters errors. Format: duration string (e.g., 50ms, 100ms, 1s).                                                                                                   | 50ms             |
| DEBRIDAV_STREAMING_RETRIES_ON_PROVIDER_ERROR   | Number of retry attempts when encountering provider errors during streaming. Set to 0 to disable retries.                                                                                                           | 2                |
| DEBRIDAV_STREAMING_WAIT_AFTER_NETWORK_ERROR     | Wait time after a network error before retrying. Format: duration string (e.g., 100ms, 1s).                                                                                                                          | 100ms            |
| DEBRIDAV_STREAMING_WAIT_AFTER_PROVIDER_ERROR    | Wait time after a provider error before retrying. Format: duration string (e.g., 1m, 5m).                                                                                                                          | 1m               |
| DEBRIDAV_STREAMING_WAIT_AFTER_CLIENT_ERROR      | Wait time after a client error before retrying. Format: duration string (e.g., 100ms, 1s).                                                                                                                           | 100ms            |
| DEBRIDAV_STREAMING_BUFFER_SIZE                  | Buffer size in bytes for direct streaming. Controls the size of the buffer used when streaming content directly from external providers without caching. Larger buffers may improve throughput but use more memory. | 65536 (64KB)     |
| DEBRIDAV_STREAMING_FLUSH_MULTIPLIER             | Flush multiplier for streaming. Flush interval = buffer-size × flush-multiplier. Example: With default 64KB buffer and multiplier of 4, flushes occur every 256KB. Higher values reduce flush frequency but may increase latency. | 4                |

### Download Tracking & Monitoring

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_ENABLE_STREAMING_DOWNLOAD_TRACKING    | Enable tracking of streaming downloads for monitoring and analysis. When enabled, provides detailed metrics via `/actuator/streaming-download-tracking` endpoint.                                                | false            |
| DEBRIDAV_STREAMING_DOWNLOAD_TRACKING_CACHE_EXPIRATION_HOURS | Duration to keep completed download tracking entries in the cache. Format: duration string (e.g., 24h, 48h). Longer durations use more memory but provide more historical data. | 24h              |

### Local Video File Serving for ARR Projects

This feature dramatically reduces bandwidth usage when ARR projects (Radarr, Sonarr, etc.) scan your media files by serving small local video files instead of downloading the actual media files.

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_ENABLE_RCLONE_ARRS_LOCAL_VIDEO        | Enable serving local video files for ARR requests instead of actual media files. This reduces bandwidth usage during library scans. Requires `DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_PATHS` to be configured.     | false            |
| DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_PATHS   | Video file mapping for local video serving. Format: `resolution=filepath,resolution=filepath` or single file path. Examples: `1080p=/local-video/test.mp4,720p=/local-video/test_720p.mp4` or `/local-video/default.mp4`. Can use pipe syntax for multiple resolutions: `2160p\|1080p=/local-video/high_res.mp4`. |                  |
| DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_PATH_REGEX    | Optional regex pattern to match file paths for local video serving. If not specified, all paths matching ARR detection will use local video files. Example: `.*\\.(mp4\|mkv\|avi)$`                               |                  |
| DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_MIN_SIZE_KB   | Minimum file size threshold in KB for local video serving. Files smaller than this threshold will be served externally instead of using local video. Useful for serving small clips externally while using local video for large files. Example: 1024 (1MB) |                  |
| DEBRIDAV_RCLONE_ARRS_USER_AGENT_PATTERN        | User agent pattern for detecting ARR requests. When requests match this pattern, local video serving is applied. Example: `rclone/arrs` or `rclone`                                                                |                  |
| DEBRIDAV_RCLONE_ARRS_HOSTNAME_PATTERN          | Hostname pattern for detecting ARR requests. When requests come from matching hostnames, local video serving is applied. Example: `radarr` or `sonarr`                                                             |                  |

For detailed information about the local video file approach, see [LOCAL_VIDEO_FOR_ARRS.md](LOCAL_VIDEO_FOR_ARRS.md).

### IPTV Movies/TV Shows Integration

The IPTV integration allows DebriDav to index and serve VOD (Video on Demand) content from IPTV providers. This content appears in the virtual filesystem alongside debrid content, making it accessible to Sonarr/Radarr through the existing download client interface and searchable through Prowlarr using a custom indexer definition.

**Key Features:**
- **Xtream Codes Support**: Full support for Xtream Codes API-based IPTV providers (thoroughly tested)
- **M3U Playlist Support**: Support for M3U playlist-based IPTV providers (untested)
- **Background Syncing**: Automatic periodic syncing of IPTV content catalogs
- **Search API**: RESTful API endpoint for searching IPTV content by title, IMDb ID, TMDB ID, and more
- **Prowlarr Integration**: Custom indexer definition (`debridav-iptv.yml`) for seamless integration with Prowlarr
- **Multiple Providers**: Support for multiple IPTV providers with configurable priority
- **Metadata Enhancement**: Optional OMDB API integration for enhanced movie/TV show metadata
- **Direct Streaming**: IPTV content streams directly from providers without requiring debrid services
- **Language Prefix Support**: Configurable language or source prefixes for content matching (e.g., "EN" for English, "UNV" for Universal Studios)

**Configuration:**

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| IPTV_ENABLED                                   | Enable IPTV support. When enabled, DebriDav will sync and index IPTV content from configured providers.                                                                                                           | false            |
| IPTV_SYNC_INTERVAL                             | Interval for automatic IPTV content syncing. Format: ISO8601 duration string (e.g., PT24H for 24 hours, PT12H for 12 hours, PT1H for 1 hour).                                                                      | PT24H            |
| IPTV_PROVIDERS                                 | Comma-separated list of IPTV provider names to enable. Example: `provider1,provider2`. If not specified, all configured providers are enabled.                                                                     |                  |
| IPTV_LANGUAGE_PREFIXES                         | Language or source prefixes to identify IPTV content. Format: comma-separated list. Example: `007,4K-A+,EN,NF,UNV,VP`. For prefixes with trailing spaces, use indexed format: `IPTV_LANGUAGE_PREFIXES_INDEX_0="AM| "`. Prefixes are used to match content titles (e.g., 'EN' for English, 'UNV' for Universal Studios). This is specific to each IPTV provider's content naming conventions. Predefined separators are automatically applied to each prefix. |                  |
| IPTV_METADATA_OMDB_API_KEY                     | Optional OMDB API key for enhanced metadata retrieval. When provided, DebriDav will fetch additional movie/TV show metadata from OMDB.                                                                             |                  |
| IPTV_INCLUDE_PROVIDER_IN_MAGNET_TITLE          | Include the IPTV provider name in the magnet URI title. Useful for identifying the source provider in search results.                                                                                             | false            |
| IPTV_USE_LOCAL_RESPONSES                       | Cache IPTV API responses locally to reduce API calls and improve performance. When enabled, responses are saved to the folder specified by `IPTV_RESPONSE_SAVE_FOLDER`.                                            | false            |
| IPTV_RESPONSE_SAVE_FOLDER                      | Folder path where cached IPTV responses are stored when `IPTV_USE_LOCAL_RESPONSES` is enabled. Example: `/iptv_cache`.                                                                                              |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_TYPE             | Type of IPTV provider. Valid values: `xtream_codes` (for Xtream Codes API) or `m3u` (for M3U playlists). Replace `{PROVIDER_NAME}` with your provider name (e.g., `provider1`).                                     |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_XTREAM_BASE_URL  | Base URL for Xtream Codes provider. Format: `https://example.com:8080` (include port if not 80/443). Required when `TYPE=xtream_codes`.                                                                              |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_XTREAM_USERNAME  | Username for Xtream Codes provider authentication. Required when `TYPE=xtream_codes`.                                                                                                                              |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_XTREAM_PASSWORD  | Password for Xtream Codes provider authentication. Required when `TYPE=xtream_codes`.                                                                                                                              |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_M3U_URL          | URL to M3U playlist file. Required when `TYPE=m3u`. Example: `https://example.com/playlist.m3u`.                                                                                                                   |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_M3U_FILE_PATH    | Local file path to M3U playlist file. Alternative to `M3U_URL` when playlist is stored locally. Required when `TYPE=m3u` and `M3U_URL` is not specified. Example: `/path/to/playlist.m3u`.                            |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_PRIORITY         | Priority for provider fallback. Lower numbers have higher priority. When searching, providers are queried in priority order. Example: `1` (highest priority), `2` (lower priority).                                 |                  |
| IPTV_PROVIDER_{PROVIDER_NAME}_SYNC_ENABLED     | Enable or disable syncing for a specific provider. When disabled, the provider's content will not be synced. Useful for temporarily disabling problematic providers.                                              | true             |
| IPTV_FFPROBE_PATH                              | Path to FFprobe executable. Default: "ffprobe" (assumes ffprobe is in system PATH). Example: `/usr/bin/ffprobe`.                                                                                                    | ffprobe          |
| IPTV_FFPROBE_TIMEOUT                           | FFprobe execution timeout. Format: ISO8601 duration string (e.g., PT30S for 30 seconds, PT60S for 60 seconds).                                                                                                    | PT30S            |
| IPTV_METADATA_FETCH_BATCH_SIZE                 | Number of concurrent metadata fetch requests per batch during IPTV search operations. When multiple movies or series are identified during search, metadata fetching is processed in parallel batches.            | 5                |
| DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_IPTV_BYPASS_PROVIDERS | Comma-separated list of IPTV provider names that should bypass local video file serving for ARR requests. Use `*` to bypass for all IPTV providers. When bypassed, IPTV content is served directly from the provider instead of local video files. |                  |

**Example Configuration:**

```yaml
# Enable IPTV
IPTV_ENABLED=true
IPTV_SYNC_INTERVAL=PT24H

# List of provider names (comma-separated)
IPTV_PROVIDERS=provider1,provider2

# Configure first provider (Xtream Codes)
IPTV_PROVIDER_PROVIDER1_TYPE=xtream_codes
IPTV_PROVIDER_PROVIDER1_XTREAM_BASE_URL=https://example.com:8080
IPTV_PROVIDER_PROVIDER1_XTREAM_USERNAME=your_username
IPTV_PROVIDER_PROVIDER1_XTREAM_PASSWORD=your_password
IPTV_PROVIDER_PROVIDER1_PRIORITY=1

# Configure second provider (M3U - untested)
IPTV_PROVIDER_PROVIDER2_TYPE=m3u
IPTV_PROVIDER_PROVIDER2_M3U_URL=https://example.com/playlist.m3u
IPTV_PROVIDER_PROVIDER2_PRIORITY=2

# Optional metadata enhancement
IPTV_METADATA_OMDB_API_KEY=your_omdb_api_key

# Optional: FFprobe metadata enhancement (extracts resolution, codec, and file size from media files)
# Note: Metadata enhancement is now controlled via Prowlarr indexer setting (enhanceMetadata)
IPTV_FFPROBE_PATH=ffprobe
IPTV_FFPROBE_TIMEOUT=PT30S

# Optional: Metadata fetch batch size (number of concurrent metadata fetch requests per batch)
IPTV_METADATA_FETCH_BATCH_SIZE=5

# Optional: Language prefixes for content matching
# Can be used to identify specific language or source prefixes to IPTV content.
# For example 'EN' for English, or 'UNV' for Universal Studios.
# This is specific to each IPTV provider and how they provide their content.
IPTV_LANGUAGE_PREFIXES_INDEX_0="AM| "
IPTV_LANGUAGE_PREFIXES="007,4K-A+,4K-AMZ,4K-D+,4K-EN,4K-MAX,4K-MRVL,4K-NF,4K-NF-DO,4M-AMZ,A+,AMZ,CR,D+,D+ ,DWA,EN,EN-TOP,EX,MRVL,Nf,NF,NF-DO,NICK,P+,PCOK,PRMT,SHWT,SKY,TOP,TOP-DO,UFC,UNV,VP"

# Cache IPTV responses locally
#IPTV_USE_LOCAL_RESPONSES=true
IPTV_RESPONSE_SAVE_FOLDER=/iptv_cache

# Bypass local video serving for all IPTV providers
DEBRIDAV_RCLONE_ARRS_LOCAL_VIDEO_FILE_IPTV_BYPASS_PROVIDERS=*
```

**Prowlarr Integration:**

A custom indexer definition file (`debridav-iptv.yml`) is included in the `example.full/prowlarr-config/Definitions/Custom/` directory. To use it:

1. Copy the file to your Prowlarr configuration directory: `prowlarr-config/Definitions/Custom/debridav-iptv.yml`
2. Restart Prowlarr to load the custom indexer
3. Add "DebriDav IPTV" as an indexer in Prowlarr settings
4. Configure the following indexer settings (optional):
   - **Fetch actual file size from URL**: When enabled (default: true), fetches actual file size from IPTV URL using HTTP Range requests. This provides more accurate file sizes for Radarr/Sonarr. This setting controls file size retrieval for search results independently of server-side metadata enhancement settings.
   - **Maximum results to process**: Maximum number of search results to process for metadata enhancements (file size, resolution, codec, etc.). Results are sorted by relevance score before limiting. Leave empty or set to 0 to process all results (default). This can improve performance when many results are returned.
5. Configure validation titles (optional) for testing the indexer connection
6. Sync the indexer with Sonarr/Radarr

The indexer supports searching by title, IMDb ID, TMDB ID, TVDB ID, and other metadata fields.

**API Endpoints:**

- `GET /api/iptv/search` - Search IPTV content (supports query parameters: `q`, `imdbid`, `tmdbid`, `tvdbid`, `year`, `type`, etc.)
- `POST /api/iptv/sync` - Manually trigger IPTV content sync
- `GET /api/iptv/status` - Get IPTV sync status

**Important Notes:**

- **M3U Playlist Support**: M3U playlist support is implemented but untested. Xtream Codes API support has been thoroughly tested and is recommended for production use.
- **Direct Streaming**: IPTV content streams directly from the provider URL without requiring debrid services. This makes IPTV content available even without a debrid subscription.
- **Content Syncing**: IPTV content is synced periodically in the background. The first sync may take some time depending on the size of your provider's catalog.
- **Virtual Filesystem**: IPTV content appears in the same virtual filesystem as debrid content, making it transparent to Sonarr/Radarr.

For detailed IPTV configuration and troubleshooting, see [IPTV_CONFIGURATION_GUIDE.md](IPTV_CONFIGURATION_GUIDE.md).

### WebDAV Folder Mapping

Map folders from WebDAV providers (built-in debrid providers or custom WebDAV servers) to your VFS. Files are synced periodically and appear as virtual directories in your file system. This feature automatically keeps your VFS in sync with your WebDAV server, including automatic cleanup of deleted files and folders.

**Key Features:**
- **Automatic File Syncing**: Files from WebDAV folders are automatically synced to your VFS at configurable intervals
- **Automatic Cleanup**: When files or folders are deleted from WebDAV, they are automatically removed from the VFS and database
- **Directory Structure Preservation**: Empty directories from WebDAV are created in VFS to maintain folder structure
- **Built-in Provider Support**: Seamless integration with Premiumize, Real-Debrid, and TorBox WebDAV
- **Custom Provider Support**: Configure any WebDAV server with custom authentication

**How It Works:**
1. DebriDav periodically scans configured WebDAV folders (default: every hour)
2. New files are added to the VFS as virtual files pointing to WebDAV URLs
3. Files deleted from WebDAV are automatically removed from VFS and database
4. Folders deleted from WebDAV are removed along with all their contents
5. Empty directories from WebDAV are created in VFS to match the folder structure

**Built-in Providers:**
- **Premiumize**: Uses `https://webdav.premiumize.me` (auto-configured)
- **Real-Debrid**: Uses `https://dav.real-debrid.com` (auto-configured)
- **TorBox**: Uses `https://webdav.torbox.app` (auto-configured)

**Custom Providers:** Configure any WebDAV server using `DEBRIDAV_WEBDAV_PROVIDER_{NAME}_*` environment variables.

**Configuration:**

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_WEBDAV_FOLDER_MAPPING_ENABLED         | Enable/disable WebDAV folder mapping feature.                                                                                                                                                                        | false            |
| DEBRIDAV_WEBDAV_FOLDER_MAPPING_PROVIDERS       | Comma-separated list of providers to enable. Built-in: `premiumize`, `real_debrid`, `torbox`. Custom providers: any name configured via `DEBRIDAV_WEBDAV_PROVIDER_{NAME}_*` variables.                                |                  |
| DEBRIDAV_WEBDAV_FOLDER_MAPPING_MAPPINGS        | Folder mappings per provider. Format: `provider:externalPath=internalPath`. Example: `premiumize:/pm_movies=/pm_movies,mywebdav:/media=/custom_media`. No method suffix needed - all mappings use WebDAV.              |                  |
| DEBRIDAV_WEBDAV_FOLDER_MAPPING_SYNC_INTERVAL   | Default sync interval. Format: ISO 8601 duration (PT1H = 1 hour). Can be overridden per provider via `DEBRIDAV_WEBDAV_PROVIDER_{NAME}_SYNC_INTERVAL`.                                                              | PT1H             |
| DEBRIDAV_WEBDAV_FOLDER_MAPPING_ALLOWED_EXTENSIONS | Comma-separated list of file extensions to sync. If empty, syncs all files.                                                                                                                                       | mkv,mp4,avi,mov,m4v,mpg,mpeg,wmv,flv,webm,ts,m2ts,srt,sub,ass,ssa,vtt,idx,sup |
| DEBRIDAV_WEBDAV_FOLDER_MAPPING_LOG_ROOT_FOLDERS | Comma-separated list of provider names to enable root folder logging for. When enabled, INFO log messages will show available root folders on the WebDAV server. Useful for discovering available folders when configuring mappings. Example: `premiumize,real_debrid,mywebdav` |                  |
| DEBRIDAV_WEBDAV_PROVIDER_{NAME}_URL            | WebDAV URL for custom provider. Example: `DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_URL=https://webdav.example.com`                                                                                                        |                  |
| DEBRIDAV_WEBDAV_PROVIDER_{NAME}_USERNAME       | Username for custom WebDAV provider. Example: `DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_USERNAME=user`                                                                                                                      |                  |
| DEBRIDAV_WEBDAV_PROVIDER_{NAME}_PASSWORD       | Password for custom WebDAV provider. Example: `DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_PASSWORD=secret`                                                                                                                   |                  |
| DEBRIDAV_WEBDAV_PROVIDER_{NAME}_SYNC_INTERVAL  | Optional sync interval override for custom provider. Example: `DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_SYNC_INTERVAL=PT2H`                                                                                                  |                  |

**Example Configuration:**

```yaml
# Enable WebDAV folder mapping
DEBRIDAV_WEBDAV_FOLDER_MAPPING_ENABLED=true
DEBRIDAV_WEBDAV_FOLDER_MAPPING_PROVIDERS=premiumize,mywebdav

# Built-in provider mappings (no URL needed - auto-configured)
DEBRIDAV_WEBDAV_FOLDER_MAPPING_MAPPINGS=premiumize:/pm_movies=/pm_movies

# Custom WebDAV provider configuration
DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_URL=https://webdav.example.com
DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_USERNAME=user
DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_PASSWORD=secret
DEBRIDAV_WEBDAV_PROVIDER_MYWEBDAV_SYNC_INTERVAL=PT2H

# Custom provider mapping
DEBRIDAV_WEBDAV_FOLDER_MAPPING_MAPPINGS=premiumize:/pm_movies=/pm_movies,mywebdav:/media=/custom_media
```

**Built-in Provider Credentials:**
- **Premiumize**: Set `PREMIUMIZE_WEBDAV_USERNAME` and `PREMIUMIZE_WEBDAV_PASSWORD` (separate from REST API key)
- **Real-Debrid**: Set `REAL_DEBRID_WEBDAV_USERNAME` and `REAL_DEBRID_WEBDAV_PASSWORD` (separate from REST API key)
- **TorBox**: Set `TORBOX_WEBDAV_USERNAME` and `TORBOX_WEBDAV_PASSWORD` (separate from REST API key)

**API Endpoints:**
- `POST /api/webdav-folder-mapping/sync` - Manually trigger sync for all mappings
- `POST /api/webdav-folder-mapping/provider/{provider}/sync` - Sync all mappings for a specific provider
- `GET /api/webdav-folder-mapping` - List all folder mappings

**Important Notes:**
- **Automatic Cleanup**: When you delete a file or folder from WebDAV, it will be automatically removed from DebriDav's VFS and database on the next sync. This includes:
  - Files deleted from WebDAV → removed from VFS and database
  - Folders deleted from WebDAV → removed from VFS along with all child files and subdirectories
  - Empty folders → created in VFS if they exist in WebDAV (to maintain folder structure)
- **Scope Safety**: Only directories under the configured `internalPath` are processed. Sibling folders outside the mapping are never affected.
- **File Extensions**: By default, only common media and subtitle file extensions are synced. Configure `DEBRIDAV_WEBDAV_FOLDER_MAPPING_ALLOWED_EXTENSIONS` to customize.
- **Sync Frequency**: Files are synced periodically (default: 1 hour). You can trigger manual syncs via the API endpoints or adjust the sync interval per provider.
- **Subtitle Files**: Subtitle files (`.srt`, `.sub`, `.ass`, etc.) are automatically downloaded and stored locally for better performance.

### STRM File Support

STRM files are text files that contain URLs pointing to media content. Media servers like Jellyfin and Emby can read STRM files and stream content directly from the URLs they contain. **Note: Plex does not support STRM files** - use VFS paths for Plex. DebriDav can automatically generate STRM files for your content, providing an alternative to the Virtual File System (VFS) approach.

**Why Use STRM Files?**

STRM files help reduce the amount of data that media servers download when analyzing media files (movies/TV shows). This is particularly important for providers that track download usage and may cap or limit downloads, such as Premiumize (see their [Fair Use policy](https://www.premiumize.me/fairuse)). By using STRM files, media servers can access metadata and stream content more efficiently without downloading entire files during library scans.

**Key Features:**
- **Automatic STRM Generation**: Creates STRM files mirroring your content structure in separate folders
- **Multiple URL Types**: Supports VFS paths, direct external URLs, or proxy URLs with automatic link refresh
- **Provider-Specific Configuration**: Configure different URL types per provider (e.g., use proxy URLs for Premiumize, direct URLs for IPTV)
- **Automatic Link Refresh**: Proxy URLs automatically check and refresh expired links when accessed
- **Flexible Filtering**: Control which files and providers use STRM files

**How It Works:**

1. **VFS Paths (Default)**: STRM files contain paths to DebriDav's VFS endpoints. Media servers access content through DebriDav's streaming service.
2. **Direct External URLs**: STRM files contain direct URLs from debrid providers. Media servers stream directly from providers, bypassing DebriDav for the actual streaming. Useful for providers with stable, non-expiring URLs (e.g., IPTV).
3. **Proxy URLs**: STRM files contain proxy URLs pointing to DebriDav. When accessed, DebriDav verifies/refreshes the external URL and either redirects to it or streams through the proxy. Essential for providers with expiring URLs (e.g., Premiumize).

**Configuration:**

| NAME                                           | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_STRM_ENABLED                          | Enable/disable STRM file generation. When enabled, STRM folders will appear mirroring your content structure.                                                                                                      | false            |
| DEBRIDAV_STRM_FOLDER_MAPPINGS                  | Maps root folders to STRM folders (comma-separated key=value pairs). Example: `tv=tv_strm,movies=movies_strm` creates `/tv_strm` mirroring `/tv` and `/movies_strm` mirroring `/movies`.                              |                  |
| DEBRIDAV_STRM_ROOT_PATH_PREFIX                 | Optional prefix for paths written in STRM files. If set to `/data`, a file at `/tv/show/episode.mkv` will have STRM content `/data/tv/show/episode.mkv`. This should match your Docker volume mapping (typically `/data`).                                                           |                  |
| DEBRIDAV_STRM_FILE_EXTENSION_MODE              | How to handle file extensions: `REPLACE` (episode.mkv → episode.strm) or `APPEND` (episode.mkv → episode.mkv.strm).                                                                                                | REPLACE          |
| DEBRIDAV_STRM_FILE_FILTER_MODE                 | Which files to convert: `ALL` (all files), `MEDIA_ONLY` (only media extensions), `NON_STRM` (all except .strm).                                                                                                     | MEDIA_ONLY       |
| DEBRIDAV_STRM_MEDIA_EXTENSIONS                 | Comma-separated list of media file extensions when filter mode is `MEDIA_ONLY`. Default: `mkv,mp4,avi,mov,m4v,mpg,mpeg,wmv,flv,webm,ts,m2ts`.                                                                       | mkv,mp4,avi,mov,m4v,mpg,mpeg,wmv,flv,webm,ts,m2ts |
| DEBRIDAV_STRM_PROVIDERS                        | Comma-separated list of provider names for which to create STRM files. Supports `ALL`/`*` for all providers and `!` prefix for negation. Example: `*`, `*,!IPTV`, or `REAL_DEBRID,PREMIUMIZE`. **Note**: This setting takes precedence over `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS`. If a provider is excluded here, no STRM files are created for it, regardless of the external URL setting.                        | * (all providers) |
| DEBRIDAV_STRM_EXCLUDE_FILENAME_REGEX           | Optional regex pattern to match filenames that should be excluded from STRM file creation. Example: `.*sample.*` or `.*\\.sample\\.mkv$`.                                                                          |                  |
| DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS  | Comma-separated list of provider names for which to use external URLs in STRM files. When enabled for a provider, STRM files are still created but contain direct external URLs instead of VFS paths. **Note**: This only applies if STRM files are created for the provider (see `DEBRIDAV_STRM_PROVIDERS`). If a provider is excluded from STRM creation, this setting has no effect. Supports `ALL`/`*` for all providers and `!` prefix for negation. Example: `IPTV`, `ALL`, `*,!REAL_DEBRID`.                  |                  |
| DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS | Comma-separated list of provider names for which external URLs should use proxy URLs instead of direct URLs. When enabled, STRM files contain proxy URLs that check and refresh expired URLs. Supports `ALL`/`*` for all providers and `!` prefix for negation. Example: `PREMIUMIZE`, `ALL`, `*,!IPTV`. |                  |
| DEBRIDAV_STRM_PROXY_BASE_URL                    | Base URL for STRM redirect proxy. Defaults to `http://{detected-hostname}:8080` if not set (hostname detected via network at startup). Example: `http://debridav:8080`.                                             |                  |
| DEBRIDAV_STRM_PROXY_STREAM_MODE                 | Enable streaming mode for STRM proxy. If `true`, content is streamed directly through the proxy instead of redirecting to external URLs. Provides more control over content delivery. If `false` (default), the proxy redirects to the external URL after verifying/refreshing it. | false            |

**Example Configuration:**

```yaml
# Enable STRM feature
DEBRIDAV_STRM_ENABLED=true
DEBRIDAV_STRM_FOLDER_MAPPINGS=tv=tv_strm,movies=movies_strm
DEBRIDAV_STRM_ROOT_PATH_PREFIX=/data

# Use direct external URLs for IPTV (stable URLs)
DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=IPTV

# Use proxy URLs for Premiumize (expiring URLs)
DEBRIDAV_STRM_PROXY_EXTERNAL_URL_FOR_PROVIDERS=PREMIUMIZE
DEBRIDAV_STRM_PROXY_BASE_URL=http://debridav:8080

# File extension mode
DEBRIDAV_STRM_FILE_EXTENSION_MODE=REPLACE
DEBRIDAV_STRM_FILE_FILTER_MODE=MEDIA_ONLY
```

**Use Cases:**

- **Media Server Integration**: Some media servers work better with STRM files than VFS mounts
- **Expiring URLs**: Use proxy URLs for providers with expiring URLs (e.g., Premiumize) to automatically refresh links
- **Direct Streaming**: Use direct external URLs for providers with stable URLs (e.g., IPTV) to reduce server load
- **Selective Provider Control**: Configure different URL types per provider based on their URL stability

**Important Notes:**

- **Media Server Compatibility**: **Plex does not support STRM files** - use VFS paths for Plex. Jellyfin and Emby fully support STRM files.
- **STRM File Creation**: STRM files are created when STRM is enabled and other conditions are met (provider inclusion, filename exclusion, file filter mode). The `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS` setting only changes what URL is written inside the STRM file (external URL vs VFS path), not whether the STRM file is created.
- **Configuration Precedence**: `DEBRIDAV_STRM_PROVIDERS` takes precedence over `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS`. If a provider is excluded in `DEBRIDAV_STRM_PROVIDERS` (e.g., `*,!IPTV`), no STRM files are created for that provider, and `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS` has no effect for that provider. For example, with `DEBRIDAV_STRM_PROVIDERS=*,!IPTV` and `DEBRIDAV_STRM_USE_EXTERNAL_URL_FOR_PROVIDERS=IPTV`, no STRM files are created for IPTV, so the external URL setting is irrelevant.
- **Proxy URLs**: When using proxy URLs, DebriDav checks if URLs are expired when accessed and refreshes them before redirecting or streaming. This ensures URLs are always valid when playback starts.
- **Streaming Mode**: When `DEBRIDAV_STRM_PROXY_STREAM_MODE=true`, content is streamed through DebriDav instead of redirecting. This provides more control but uses more server resources.
- **VFS vs STRM**: VFS paths work through DebriDav's streaming service, while STRM files with external URLs allow media servers to stream directly from providers. Choose based on your needs.
- **Download Usage Reduction**: STRM files help reduce data downloaded during media analysis, which is important for providers like Premiumize that track download usage and may cap/limit downloads per their Fair Use policy.

For detailed STRM configuration and examples, see [STRM_DOCKER_COMPOSE_ENV_VARS.md](STRM_DOCKER_COMPOSE_ENV_VARS.md). For information about different streaming flows, see [MEDIA_STREAMING_FLOWS.md](MEDIA_STREAMING_FLOWS.md).

## Developing

A docker compose file is provided in the dev directory, with Prowlarr and rclone defined. You can add a qBittorrent
download client in prowlarr and point it to the ip obtained by running `ip addr show docker0` in order to reach your
locally running DebriDav server.

