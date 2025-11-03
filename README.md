# DebriDav

> [!NOTE]
> This is a fork of the original [DebriDav project](https://github.com/skjaere/debridav) with enhanced features and improved configuration options. All original features remain as default options, with new capabilities available as opt-in enhancements.
>
> **Quick links to new features:**
> - [Fork Enhancements Overview](#fork-enhancements)
> - [Enhanced Caching & Performance Options](#enhanced-caching--performance-options)
> - [Streaming & Retry Configuration](#streaming--retry-configuration)
> - [Download Tracking & Monitoring](#download-tracking--monitoring)
> - [Local Video File Serving for ARR Projects](#local-video-file-serving-for-arr-projects)
> - [Streaming Download Tracking Endpoint](#streaming-download-tracking-endpoint)
> - [Example Configurations (including optimized streaming setup)](#example-configurations)
> - [Building the Application](#building-the-application)

[![build](https://github.com/skjaere/debridav/actions/workflows/build.yaml/badge.svg)](#)
[![codecov](https://codecov.io/gh/skjaere/debridav/graph/badge.svg?token=LIE8M1XE4H)](https://codecov.io/gh/skjaere/debridav)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?logo=springboot&logoColor=fff)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-%237F52FF.svg?logo=kotlin&logoColor=white)](#)
[![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=fff)](#)

[![Discord](https://img.shields.io/badge/Discord-%235865F2.svg?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/rivenmedia)

## What is it?

A small app written in Kotlin that emulates the qBittorrent and SABnzbd APIs and creates virtual files that are mapped
to remotely cached files at debrid services, essentially acting as a download client that creates virtual file
representations of remotely hosted files rather than downloading them. DebriDav exposes these files via the WebDav
protocol so that they can be mounted.

## Features

- Stream content from Real Debrid, Premiumize and Easynews with Plex/Jellyfin.
- Sort your content as you would regular files. You can create directories, rename files, and move them around any way
  you like. No need to write regular expressions.
- Seamless integration into the arr-ecosystem, providing a near identical experience to downloading torrents. DebriDav
  integrates with Sonarr and Radarr using the qBittorrent API,
  so they can add content, and automatically move your files for you.
- Supports multiple debrid providers. DebriDav supports enabling both Premiumize and Real Debrid concurrently with
  defined priorities. If a torrent is not cached in the primary provider, it will fall back to the secondary.

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

`docker run ghcr.io/skjaere/debridav:v0`

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
| PREMIUMIZE_API_KEY                 | The api key for Premiumize                                                                                                                                                                                           |                  |
| REAL_DEBRID_API_KEY                | The api key for Real Debrid                                                                                                                                                                                          |                  |
| REAL_DEBRID_SYNC_ENABLED           | If set to true, DebriDav will periodically poll Real-Debrid's API for torrents and downloads for re-use                                                                                                              | true             |
| REAL_DEBRID_SYNC_POLL_RATE         | The rate at which DebriDav will sync downloads and torrents ( if enabled by REAL_DEBRID_SYNC_ENABLED ) as a [ISO8601 time string](https://en.wikipedia.org/wiki/ISO_8601#Durations).                                       | PT4H ( 4 hours ) |
| EASYNEWS_USERNAME                  | The Easynews username                                                                                                                                                                                                |                  |
| EASYNEWS_PASSWORD                  | The Easynews password                                                                                                                                                                                                |                  |
| EASYNEWS_ENABLED_FOR_TORRENTS     | If set to true, DebriDav will search for releases in Easynews matching the torrent name of torrents added via the qBittorrent API                                                                                    | true             |
| EASYNEWS_RATE_LIMIT_WINDOW_DURATION | The size of the time window to use for rate limiting.                                                                                                                                                                | 15 seconds       |
| EASYNEWS_ALLOWED_REQUESTS_IN_WINDOW | The number of requests allowed in the time window. eg: EASYNEWS_RATE_LIMIT_WINDOW_DURATION=10s and  EASYNEWS_ALLOWED_REQUESTS_IN_WINDOW=3 will allow 3 requests per 10 seconds before forcing subsequent requests to wait. | 10               |
| EASYNEWS_CONNECT_TIMEOUT           | The amount of time in milliseconds to wait while establishing a connection to Easynews' servers.                                                                                                                     | 20000            |
| EASYNEWS_SOCKET_TIMEOUT            | The amount of time in milliseconds to wait between receiving bytes from Easynews' servers.                                                                                                                           | 5000             |
| TORBOX_API_KEY                     | The api key for TorBox                                                                                                                                                                                               |                  |
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

## Developing

A docker compose file is provided in the dev directory, with Prowlarr and rclone defined. You can add a qBittorrent
download client in prowlarr and point it to the ip obtained by running `ip addr show docker0` in order to reach your
locally running DebriDav server.

