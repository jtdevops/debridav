# Local Video File Approach for ARR Projects

## Overview

This feature provides an alternative approach to reduce bandwidth usage when ARR projects (Radarr, Sonarr, etc.) scan your media files. Instead of serving the actual media files or using byte range limiting, this approach serves small, locally-hosted video files that contain enough metadata for ARR projects to analyze.

## Why This Approach?

### Current Problems
- **Byte range limiting**: ARR projects often request specific byte ranges that don't align with chunking strategies
- **Byte duplication**: May not provide the actual metadata ARR projects need
- **Bandwidth usage**: Even with limiting, significant data is still downloaded from external sources

### Benefits of Local Video Approach
- **Zero external bandwidth usage**: No data downloaded from external sources
- **Valid video files**: ARR projects get actual video files for analysis
- **Complete control**: You control exactly what data is served
- **Simple implementation**: No complex byte duplication logic needed

## Configuration

### Basic Configuration

Add these properties to your `application.properties`:

```properties
# Enable local video file serving for ARR projects
debridav.enable-rclone-arrs-local-video=true

# Path to your local video file (must exist and be readable)
# File size and MIME type are automatically detected from the file
debridav.rclone-arrs-local-video-file-path=/path/to/your/small-video-file.mp4
```

### ARR Detection

The system uses the same detection logic as the existing rclone/arrs data limiting:

```properties
# Configure how to detect ARR requests
debridav.rclone-arrs-user-agent-pattern=rclone
debridav.rclone-arrs-hostname-pattern=radarr
```

## Creating a Test Video File

### Option 1: Use the Built-in Generator (Recommended)

The system includes a `TestVideoGenerator` utility that creates a minimal MP4 file (around 1KB) with basic video metadata.

#### Method 1: REST API Endpoint

```bash
# List available presets
curl -X GET "http://localhost:8080/api/test-video/presets"

# Generate a test video with specific preset
curl -X POST "http://localhost:8080/api/test-video/generate?path=/path/to/your/test-video.mp4&preset=MEDIUM_1080P"

# Generate a test video with default preset (SMALL_720P)
curl -X POST "http://localhost:8080/api/test-video/generate?path=/path/to/your/test-video.mp4"

# Generate a test video in the default location
curl -X POST "http://localhost:8080/api/test-video/generate-default"
```

**Available Presets:**
- `MINIMAL` - ~1KB minimal metadata only
- `SMALL_720P` - 1MB 720p (default)
- `SMALL_1080P` - 1MB 1080p
- `MEDIUM_720P` - 10MB 720p
- `MEDIUM_1080P` - 10MB 1080p
- `LARGE_720P` - 50MB 720p
- `LARGE_1080P` - 50MB 1080p

#### Method 2: Programmatic Usage

```kotlin
@Autowired
private lateinit var testVideoGenerator: TestVideoGenerator

// Generate a test video file with default preset (SMALL_720P)
val success = testVideoGenerator.generateTestVideo("/path/to/test-video.mp4")

// Generate a test video file with specific preset
val success = testVideoGenerator.generateTestVideo(
    "/path/to/test-video.mp4", 
    TestVideoGenerator.VideoPreset.MEDIUM_1080P
)

// Available presets
TestVideoGenerator.VideoPreset.MINIMAL        // ~1KB minimal
TestVideoGenerator.VideoPreset.SMALL_720P    // 1MB 720p (default)
TestVideoGenerator.VideoPreset.SMALL_1080P   // 1MB 1080p
TestVideoGenerator.VideoPreset.MEDIUM_720P   // 10MB 720p
TestVideoGenerator.VideoPreset.MEDIUM_1080P   // 10MB 1080p
TestVideoGenerator.VideoPreset.LARGE_720P    // 50MB 720p
TestVideoGenerator.VideoPreset.LARGE_1080P   // 50MB 1080p
```

#### Method 3: Script Generation

**For Linux/Mac:**
```bash
# Make the script executable and run it
chmod +x generate-test-video.sh

# Generate with default preset (SMALL_720P)
./generate-test-video.sh /path/to/your/test-video.mp4

# Generate with specific preset
./generate-test-video.sh /path/to/your/test-video.mp4 MEDIUM_1080P
```

**For Windows:**
```powershell
# Run the PowerShell script with default preset (SMALL_720P)
.\generate-test-video.ps1 -OutputPath "C:\path\to\your\test-video.mp4"

# Run with specific preset
.\generate-test-video.ps1 -OutputPath "C:\path\to\your\test-video.mp4" -Preset "MEDIUM_1080P"
```

### Option 2: Use FFmpeg to Create a Small Video

If you have FFmpeg installed, you can create a small test video:

```bash
# Create a 1-second black video (very small file)
ffmpeg -f lavfi -i color=c=black:size=320x240:duration=1 -c:v libx264 -preset ultrafast -crf 51 output.mp4

# Create a 1-second video with a simple pattern
ffmpeg -f lavfi -i testsrc=duration=1:size=320x240:rate=1 -c:v libx264 -preset ultrafast -crf 51 output.mp4

# Create a minimal video with just metadata
ffmpeg -f lavfi -i color=c=black:size=1x1:duration=0.1 -c:v libx264 -preset ultrafast -crf 51 -an output.mp4
```

### Option 3: Use an Existing Small Video

You can use any small video file (preferably under 1MB) that contains basic video metadata. Examples:
- A short test video you create
- A sample video from online sources
- Any small MP4 file with basic metadata

### Option 4: Create Your Own Minimal MP4

Create a minimal MP4 file with basic metadata. The file should:
- Be small (under 1MB recommended)
- Contain basic MP4 structure (ftyp, moov boxes)
- Have valid video metadata for ARR analysis

## How It Works

1. **Request Detection**: The system detects ARR requests using user-agent or hostname patterns
2. **File Size Interception**: **CRITICAL** - The system reports the local video file size instead of the actual media file size
3. **Content Type Matching**: Serves the correct MIME type for the local video file
4. **Local File Serving**: Instead of streaming the actual media file, it serves your local video file
5. **Range Support**: Supports byte range requests (Radarr/Sonarr often request specific ranges)
6. **Metadata Preservation**: The local file contains enough metadata for ARR analysis

### Why File Size Interception is Critical

**The Problem**: Radarr expects the full file size and will hang if it doesn't get enough data. If we report a 10GB file size but only serve 1MB of data, Radarr will wait forever for the remaining 9.999GB.

**The Solution**: We intercept the `getContentLength()` method to report the **local video file size** instead of the actual media file size. This ensures Radarr gets exactly what it expects.

## Implementation Details

### Request Flow
```
ARR Request → Detection → Local Video Service → Local File → ARR Client
```

### Key Components
- `LocalVideoService`: Handles serving local video files
- `DebridavConfigurationProperties`: Configuration management
- `StreamingService`: Integration with existing streaming logic

### Logging
The system provides detailed logging for monitoring:
- `LOCAL_VIDEO_SERVING_REQUEST`: When a local video is being served
- `LOCAL_VIDEO_SERVED_SUCCESSFULLY`: When serving completes
- `LOCAL_VIDEO_FILE_NOT_FOUND`: When the local file is missing

## Comparison with Other Approaches

### vs. Byte Range Limiting
| Feature | Byte Range Limiting | Local Video |
|---------|-------------------|-------------|
| Bandwidth Usage | Reduced but still significant | Zero external usage |
| ARR Compatibility | May not work with all requests | High compatibility |
| Implementation | Complex byte duplication | Simple file serving |
| Control | Limited by chunking strategy | Complete control |

### vs. Direct Streaming
| Feature | Direct Streaming | Local Video |
|---------|-----------------|-------------|
| Bandwidth Usage | Full file download | Zero external usage |
| ARR Compatibility | High | High |
| Performance | Depends on external source | Fast local serving |
| Storage | No local storage needed | Requires local file |

## Best Practices

### File Selection
- Use a small file (under 1MB)
- Ensure it has valid video metadata
- Test with your ARR projects first

### Configuration
- Enable only when needed
- Use appropriate detection patterns
- Monitor logs for effectiveness

### Monitoring
- Check logs for `LOCAL_VIDEO_*` messages
- Verify ARR projects are working correctly
- Monitor bandwidth usage reduction

## Troubleshooting

### Common Issues

1. **File Not Found**
   ```
   LOCAL_VIDEO_FILE_NOT_FOUND: path=/path/to/file.mp4
   ```
   - Ensure the file exists and is readable
   - Check file permissions

2. **ARR Projects Not Detected**
   - Verify user-agent and hostname patterns
   - Check request logs for detection

3. **Invalid Video File**
   - Ensure the file is a valid MP4
   - Test with a known good video file

### Debugging
Enable debug logging to see detailed information:
```properties
logging.level.io.skjaere.debridav.stream.LocalVideoService=DEBUG
```

## Migration from Byte Range Limiting

If you're currently using byte range limiting:

1. **Test First**: Enable local video approach alongside existing system
2. **Verify Detection**: Ensure ARR requests are properly detected
3. **Monitor**: Check that ARR projects work correctly
4. **Disable Old**: Once confirmed working, disable byte range limiting

## Future Enhancements

Potential improvements:
- Multiple local video files for different media types
- Dynamic video file selection based on request
- Integration with media library metadata
- Automatic test video generation

## Conclusion

The local video file approach provides a simple, effective way to reduce bandwidth usage for ARR projects while maintaining compatibility. It's particularly useful when:
- You have limited bandwidth quotas
- ARR projects are consuming significant bandwidth
- You want complete control over what data is served

This approach complements the existing byte range limiting feature and can be used as an alternative or in combination with other bandwidth-saving techniques.
