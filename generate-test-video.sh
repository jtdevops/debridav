#!/bin/bash

# Script to generate a test video file for ARR projects
# This creates a configurable MP4 file that can be used with the local video feature

set -e

# Default values
OUTPUT_PATH="${1:-/tmp/debridav-test-video.mp4}"
PRESET="${2:-SMALL_720P}"

echo "🎬 Generating test video file for ARR projects..."
echo "📁 Output path: $OUTPUT_PATH"
echo "🎯 Preset: $PRESET"

# Create parent directory if it doesn't exist
mkdir -p "$(dirname "$OUTPUT_PATH")"

# Method 1: Try FFmpeg first (if available)
if command -v ffmpeg &> /dev/null; then
    echo "✅ FFmpeg found, creating video with preset: $PRESET"
    
    # Parse preset to get resolution, duration, and bitrate
    case "$PRESET" in
        "MINIMAL")
            RESOLUTION="1x1"
            DURATION="0.1"
            BITRATE="500k"
            ;;
        "SMALL_720P")
            RESOLUTION="1280x720"
            DURATION="8"
            BITRATE="1000k"
            ;;
        "SMALL_1080P")
            RESOLUTION="1920x1080"
            DURATION="4"
            BITRATE="2000k"
            ;;
        "MEDIUM_720P")
            RESOLUTION="1280x720"
            DURATION="40"
            BITRATE="2000k"
            ;;
        "MEDIUM_1080P")
            RESOLUTION="1920x1080"
            DURATION="20"
            BITRATE="4000k"
            ;;
        "LARGE_720P")
            RESOLUTION="1280x720"
            DURATION="100"
            BITRATE="4000k"
            ;;
        "LARGE_1080P")
            RESOLUTION="1920x1080"
            DURATION="50"
            BITRATE="8000k"
            ;;
        *)
            echo "⚠️  Unknown preset: $PRESET, using SMALL_720P"
            RESOLUTION="1280x720"
            DURATION="8"
            BITRATE="1000k"
            ;;
    esac
    
    if [ "$PRESET" = "MINIMAL" ]; then
        # Create minimal MP4 structure
        cat > "$OUTPUT_PATH" << 'EOF'
ftypisom    isomiso2mp41moovmdat
EOF
    else
        # Create video with FFmpeg using bitrate control
        ffmpeg -f lavfi -i "testsrc=duration=$DURATION:size=$RESOLUTION:rate=25" \
               -c:v libx264 -preset ultrafast -b:v "$BITRATE" -maxrate "$BITRATE" \
               -bufsize "$(echo $BITRATE | sed 's/k//')k" -pix_fmt yuv420p \
               -movflags +faststart -an -y "$OUTPUT_PATH" 2>/dev/null
    fi
    
    echo "✅ Test video generated successfully with FFmpeg"
    echo "📊 File size: $(du -h "$OUTPUT_PATH" | cut -f1)"
    echo "🎯 MIME type: video/mp4"
    echo "📐 Resolution: $RESOLUTION"
    echo "⏱️  Duration: ${DURATION}s"
    echo "📡 Bitrate: $BITRATE"
else
    echo "⚠️  FFmpeg not found, creating minimal MP4 structure..."
    
    # Create a minimal MP4 file with basic structure
    cat > "$OUTPUT_PATH" << 'EOF'
ftypisom    isomiso2mp41moovmdat
EOF
    
    echo "✅ Minimal MP4 file created"
    echo "📊 File size: $(du -h "$OUTPUT_PATH" | cut -f1)"
    echo "🎯 MIME type: video/mp4"
fi

echo ""
echo "🚀 Configuration for application.properties:"
echo "debridav.enable-rclone-arrs-local-video=true"
echo "debridav.rclone-arrs-local-video-file-path=$OUTPUT_PATH"
echo ""
echo "✨ Test video file ready for use!"
