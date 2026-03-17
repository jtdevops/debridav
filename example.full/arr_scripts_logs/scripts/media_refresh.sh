#!/usr/bin/env bash
#
# Media Refresh Script
#
# This script is intended to be run as a custom script from Radarr or Sonarr
# (e.g. on Import, Upgrade, Rename). It does the following:
#
# 1. Debounces rapid events (e.g. multiple imports) so that scans are triggered
#    once per batch after a short wait (configurable via DEBOUNCE_SECONDS).
#
# 2. Optionally triggers library scans in Jellyfin and/or Emby for the affected
#    media paths. Configure JELLYFIN_URL + JELLYFIN_API_KEY and/or EMBY_URL +
#    EMBY_API_KEY in .env to enable; omit either URL or API key to skip that
#    service.
#
# 3. Optionally triggers Radarr/Sonarr scan jobs in Seerr so that Seerr's
#    catalog stays in sync. Configure SEERR_URL + SEERR_API_KEY in .env to
#    enable; omit either to skip.
#
# 4. Supports path mapping (e.g. /data/movies -> /data/movies_strm) so that
#    Jellyfin/Emby scan the correct paths when your *arr and media paths differ.
#
# Logs are written to dated files (e.g. media_refresh.2025-03-12.log) and older
# logs are pruned automatically; retention is configurable via LOG_RETENTION_COUNT.
#
# Setup:
#   1. Copy .env.example to .env in this directory and fill in the values you need.
#      API keys: Jellyfin/Emby = Dashboard → API Keys; Seerr = Settings → General → API Key.
#   2. In Radarr or Sonarr: Settings → Connect → add a "Custom Script" connection.
#      Set the path to this script (e.g. /scripts/media_refresh.sh if run in Docker).
#      Choose the events you want (e.g. On Import, On Upgrade, On Rename). The script
#      receives environment variables from the app (e.g. radarr_movie_path, sonarr_series_path).
#
########################################
# LOAD .ENV FROM SCRIPT DIRECTORY
########################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
    set -a
    source "$ENV_FILE"
    set +a
else
    echo "ERROR: .env file not found in $SCRIPT_DIR"
    exit 1
fi

########################################
# DETECT SOURCE + EVENT TYPE
########################################

if [[ -n "$radarr_instancename" ]]; then
    SOURCE="$radarr_instancename"
    EVENT_TYPE="${radarr_eventtype:-Unknown}"
elif [[ -n "$sonarr_instancename" ]]; then
    SOURCE="$sonarr_instancename"
    EVENT_TYPE="${sonarr_eventtype:-Unknown}"
else
    SOURCE="Unknown"
    EVENT_TYPE="Unknown"
fi

########################################
# LOGGING (dated files + retention cleanup)
########################################

LOG_FILE="${LOG_FILE:-/logs/media_refresh.log}"
LOG_DIR="$(dirname "$LOG_FILE")"
LOG_BASE="$(basename "$LOG_FILE" .log)"
LOG_FILE="${LOG_DIR}/${LOG_BASE}.$(date '+%Y-%m-%d').log"
LOG_RETENTION_COUNT="${LOG_RETENTION_COUNT:-3}"

mkdir -p "$LOG_DIR"
touch "$LOG_FILE"

# Remove old dated log files, keep only the most recent LOG_RETENTION_COUNT (by filename date)
readarray -t LOG_FILES < <(ls "${LOG_DIR}/${LOG_BASE}".*.log 2>/dev/null | sort)
total=${#LOG_FILES[@]}
for ((i=0; i < total - LOG_RETENTION_COUNT; i++)); do
    rm -f "${LOG_FILES[i]}"
done

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [$SOURCE][$EVENT_TYPE][PID $$] $1" >> "$LOG_FILE"
}

########################################
# DEBUG FUNCTION
########################################

debug_dump() {

    log "---- DEBUG: Script Arguments ----"

    i=1
    for arg in "$@"; do
        log "ARG[$i]=$arg"
        ((i++))
    done

    log "---- DEBUG: Environment Variables ----"

    env | sort | while read line; do
        log "$line"
    done

    log "---- END DEBUG ----"
}

if [[ "$DEBUG" == "true" ]]; then
    debug_dump "$@"
fi

########################################
# HANDLE TEST EVENT
########################################

log "==================== NEW REQUEST ===================="

if [[ "$EVENT_TYPE" == "Test" ]]; then
    log "Test event received"
    echo "Media refresh script test successful."
    exit 0
fi

########################################
# EVENT FILTER
########################################

event_allowed() {

    FILTER="$1"

    if [[ -z "$FILTER" ]]; then
        return 0
    fi

    IFS=',' read -ra EVENTS <<< "$FILTER"

    for e in "${EVENTS[@]}"; do
        if [[ "$EVENT_TYPE" == "$e" ]]; then
            return 0
        fi
    done

    return 1
}

########################################
# DETECT MEDIA PATH
########################################

MEDIA_FOLDER_PATH=""

if [[ -n "$radarr_movie_path" ]]; then
    MEDIA_FOLDER_PATH="$radarr_movie_path"

elif [[ -n "$radarr_moviefile_paths" ]]; then
    FIRST_FILE=$(echo "$radarr_moviefile_paths" | cut -d',' -f1)
    MEDIA_FOLDER_PATH="$(dirname "$FIRST_FILE")"

elif [[ -n "$radarr_moviefile_path" ]]; then
    MEDIA_FOLDER_PATH="$(dirname "$radarr_moviefile_path")"
fi

if [[ -z "$MEDIA_FOLDER_PATH" ]]; then
    if [[ -n "$sonarr_series_path" ]]; then
        MEDIA_FOLDER_PATH="$sonarr_series_path"
    elif [[ -n "$sonarr_episodefile_path" ]]; then
        MEDIA_FOLDER_PATH="$(dirname "$sonarr_episodefile_path")"
    fi
fi

if [[ -z "$MEDIA_FOLDER_PATH" ]]; then
    log "ERROR: No media path received"
    debug_dump "$@"
    exit 1
fi

log "Source instance: $SOURCE"
log "Event type: $EVENT_TYPE"
log "Media folder path: $MEDIA_FOLDER_PATH"

########################################
# PATH MAPPING
########################################

declare -a PATH_LIST
PATH_LIST+=("$MEDIA_FOLDER_PATH")

declare -A PATH_MAP
PATH_MAP["/data/movies"]="/data/movies_strm"
PATH_MAP["/data/tv"]="/data/tv_strm"

for SRC in "${!PATH_MAP[@]}"; do
    if [[ "$MEDIA_FOLDER_PATH" == $SRC* ]]; then
        DEST="${PATH_MAP[$SRC]}"
        PATH_LIST+=("${MEDIA_FOLDER_PATH/$SRC/$DEST}")
    fi
done

mapfile -t UNIQUE_PATHS < <(printf '%s\n' "${PATH_LIST[@]}" | sort -u)

########################################
# DEBOUNCE SETUP
########################################

QUEUE_ROOT="/tmp/media_refresh"
mkdir -p "$QUEUE_ROOT/jellyfin_emby"
mkdir -p "$QUEUE_ROOT/seerr"

DEBOUNCE_SECONDS="${DEBOUNCE_SECONDS:-10}"

PATH_HASH=$(echo -n "$MEDIA_FOLDER_PATH" | md5sum | cut -d' ' -f1)

QUEUE_FILE="$QUEUE_ROOT/jellyfin_emby/${PATH_HASH}.queue"
TIME_FILE="$QUEUE_ROOT/jellyfin_emby/${PATH_HASH}.time"
PID_FILE="$QUEUE_ROOT/jellyfin_emby/${PATH_HASH}.pid"

########################################
# ADD TO QUEUE
########################################

for p in "${UNIQUE_PATHS[@]}"; do
    log "Debounce: Queueing path $p"
    echo "$p" >> "$QUEUE_FILE"
done

date +%s > "$TIME_FILE"

########################################
# START WORKER IF NEEDED
########################################

if [[ -f "$PID_FILE" ]] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    log "Debounce: Worker already running for this path"
else

(
echo $$ > "$PID_FILE"

log "-------- Debounce worker started for path hash $PATH_HASH --------"

while true
do
    sleep "$DEBOUNCE_SECONDS"

    NOW=$(date +%s)
    LAST=$(cat "$TIME_FILE")

    DIFF=$((NOW-LAST))

    if (( DIFF < DEBOUNCE_SECONDS )); then
        log "Debounce: New event detected, extending wait window"
        continue
    fi

    break
done

log "-------- Debounce window expired, processing queued paths --------"

mapfile -t PATHS < <(sort -u "$QUEUE_FILE")
> "$QUEUE_FILE"

########################################
# PROCESS PATHS
########################################

for TARGET_PATH in "${PATHS[@]}"; do

    log "Processing path: $TARGET_PATH"

    if [[ -n "$JELLYFIN_URL" && -n "$JELLYFIN_API_KEY" ]]; then
        if event_allowed "$JELLYFIN_EVENTS"; then
            log "Jellyfin: Triggering library scan for $TARGET_PATH"
            curl -s -X POST \
            "$JELLYFIN_URL/Library/Media/Updated" \
            -H "Content-Type: application/json" \
            -H "X-Emby-Token: $JELLYFIN_API_KEY" \
            -d "{\"Updates\":[{\"Path\":\"$TARGET_PATH\",\"UpdateType\":\"Created\"}]}" > /dev/null
        fi
    fi

    if [[ -n "$EMBY_URL" && -n "$EMBY_API_KEY" ]]; then
        if event_allowed "$EMBY_EVENTS"; then
            log "Emby: Triggering library scan for $TARGET_PATH"
            curl -s -X POST \
            "$EMBY_URL/Library/Media/Updated" \
            -H "Content-Type: application/json" \
            -H "X-Emby-Token: $EMBY_API_KEY" \
            -d "{\"Updates\":[{\"Path\":\"$TARGET_PATH\",\"UpdateType\":\"Created\"}]}" > /dev/null
        fi
    fi

done

########################################
# SEERR GLOBAL DEBOUNCE
########################################

if [[ -n "$SEERR_URL" && -n "$SEERR_API_KEY" ]] && event_allowed "$SEERR_EVENTS"; then

SEERR_QUEUE="$QUEUE_ROOT/seerr/queue"
SEERR_TIME="$QUEUE_ROOT/seerr/time"
SEERR_PID="$QUEUE_ROOT/seerr/pid"

echo "$SOURCE" >> "$SEERR_QUEUE"
date +%s > "$SEERR_TIME"

if [[ ! -f "$SEERR_PID" ]] || ! kill -0 $(cat "$SEERR_PID") 2>/dev/null; then

(
echo $$ > "$SEERR_PID"

log "Seerr: Debounce worker started"

sleep "$DEBOUNCE_SECONDS"

if grep -q Radarr "$SEERR_QUEUE"; then
    log "Seerr: Running radarr-scan"
    curl -s -X POST -H "X-Api-Key: $SEERR_API_KEY" "$SEERR_URL/api/v1/settings/jobs/radarr-scan/run" > /dev/null
fi

if grep -q Sonarr "$SEERR_QUEUE"; then
    log "Seerr: Running sonarr-scan"
    curl -s -X POST -H "X-Api-Key: $SEERR_API_KEY" "$SEERR_URL/api/v1/settings/jobs/sonarr-scan/run" > /dev/null
fi

rm -f "$SEERR_QUEUE" "$SEERR_PID"

log "Seerr: Scan triggered successfully"

) &
fi

fi

rm -f "$PID_FILE"

log "-------- Debounce processing complete --------"

) &

fi

log "Script finished"
