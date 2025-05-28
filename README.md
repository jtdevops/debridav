# DebriDav

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

To disable caching completely, set `DEBRIDAV_CHUNKCACHINGSIZETHRESHOLD=0`

## Cache configuration

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

The following values can be defined as environment variables.

| NAME                               | Explanation                                                                                                                                                                                                          | Default          |
|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_ROOTPATH                  | The root path of DebriDav. DebriDav will store configuration data, databases, files under this directory. When running as docker this directory refers to the path within the docker container.                      | ./debridav-files |
| DEBRIDAV_DOWNLOADPATH              | The path under `DEBRIDAV_ROOTPATH` where downloaded files will be placed.                                                                                                                                            | /downloads       |
| DEBRIDAV_DEBRIDCLIENTS             | A comma separated list of enabled debrid providers. Allowed values are `real_debrid`, `premiumize`, `easynews` and `torbox`. Note that the order determines the priority in which they are used.                     |                  |
| DEBRIDAV_DB_HOST                   | The host of the PostgresSQL database server                                                                                                                                                                          | localhost        |
| DEBRIDAV_DB_PORT                   | The port of the PostgresSQL database server                                                                                                                                                                          | 5432             |
| DEBRIDAV_DB_DATABASENAME           | The name of the database to use within the PostgresSQL server                                                                                                                                                        | debridav         |
| DEBRIDAV_DB_USERNAME               | The username to use when connecting the PostgresSQL server                                                                                                                                                           | debridav         |
| DEBRIDAV_DB_PASSWORD               | The password to use when connecting the PostgresSQL server                                                                                                                                                           | debridav         |
| DEBRIDAV_ENABLEFILEIMPORTONSTARTUP | Enables importing content from the filesystem to the database.                                                                                                                                                       | debridav         |
| DEBRIDAV_DEFAULTCATEGORIES         | A comma separated list of categories to create on startup                                                                                                                                                            |                  |
| DEBRIDAV_LOCALENTITYMAXSIZEMB      | The maximum allowed size in MB for locally stored files. Useful to prevent accidentally large files in the database. Set to 0 for no limit                                                                           | 50               |
| DEBRIDAV_CHUNKCACHINGGRACEPERIOD   | The amount of time to keep chunks in the cache as a duration string ( 2m, 4h, 2d etc)                                                                                                                                | 4h               |
| DEBRIDAV_CHUNKCACHINGSIZETHRESHOLD | The maximum chunk size to cache in bytes.                                                                                                                                                                            | 5120000 ( 5Mb )  |
| DEBRIDAV_CACHEMAXSIZE              | The maximum size of the cache in gigabytes.                                                                                                                                                                          | 2                |
| PREMIUMIZE_APIKEY                  | The api key for Premiumize                                                                                                                                                                                           |                  |
| REAL-DEBRID_APIKEY                 | The api key for Real Debrid                                                                                                                                                                                          |                  |
| REAL-DEBRID_SYNCENABLED            | If set to true, DebriDav will periodically poll Real-Debrid's API for torrents and downloads for re-use                                                                                                              | true             |
| REAL-DEBRID_SYNCPOLLRATE           | The rate at which DebriDav will sync downloads and torrents ( if enabled by DEBRID_SYNCENABLED ) as a [ISO8601 time string](https://en.wikipedia.org/wiki/ISO_8601#Durations).                                       | PT4H ( 4 hours ) |
| EASYNEWS_USERNAME                  | The Easynews username                                                                                                                                                                                                |                  |
| EASYNEWS_PASSWORD                  | The Easynews password                                                                                                                                                                                                |                  |
| EASYNEWS_ENABLEDFORTORRENTS        | If set to true, DebriDav will search for releases in Easynews matching the torrent name of torrents added via the qBittorrent API                                                                                    | true             |
| EASYNEWS_RATELIMITWINDOWDURATION   | The size of the time window to use for rate limiting.                                                                                                                                                                | 15 seconds       |
| EASYNEWS_ALLOWEDREQUESTSINWINDOW   | The number of requests allowed in the time window. eg: EASYNEWS_RATELIMITWINDOWDURATION=10s and  EASYNEWS_ALLOWEDREQUESTSINWINDOW=3 will allow 3 requests per 10 seconds before forcing subsequent requests to wait. | 10               |
| EASYNEWS_CONNECTTIMEOUT            | The amount of time in milliseconds to wait while establishing a connection to Easynews' servers.                                                                                                                     | 20000            |
| EASYNEWS_SOCKETTIMEOUT             | The amount of time in milliseconds to wait between receiving bytes from Easynews' servers.                                                                                                                           | 5000             |
| TORBOX_APIKEY                      | The api key for TorBox                                                                                                                                                                                               |                  |
| SONARR_INTEGRATIONENABLED          | Enable integration of Sonarr.                                                                                                                                                                                        | true             |
| SONARR_HOST                        | The host of Sonarr                                                                                                                                                                                                   | sonarr-debridav  |
| SONARR_PORT                        | The port of Sonarr                                                                                                                                                                                                   | 8989             |
| SONARR_API_KEY                     | The API key for Sonarr                                                                                                                                                                                               |                  |
| SONARR_CATEGORY                    | The qBittorrent cateogy Sonarr uses                                                                                                                                                                                  | tv-sonarr        |
| RADARR_INTEGRATIONENABLED          | Enable integration of Radarr. See description of SONARR_INTEGRATION_ENABLED                                                                                                                                          | true             |
| RADARR_HOST                        | The host of Radarr                                                                                                                                                                                                   | radarr-debridav  |
| RADARR_PORT                        | The port of Radarr                                                                                                                                                                                                   | 7878             |
| RADARR_API_KEY                     | The API key for Radarr                                                                                                                                                                                               |                  |
| RADARR_CATEGORY                    | The qBittorrent cateogy Radarr uses                                                                                                                                                                                  | radarr           |

## Developing

A docker compose file is provided in the dev directory, with Prowlarr and rclone defined. You can add a qBittorrent
download client in prowlarr and point it to the ip obtained by running `ip addr show docker0` in order to reach your
locally running DebriDav server.

