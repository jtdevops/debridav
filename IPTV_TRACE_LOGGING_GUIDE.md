# IPTV Trace Logging Guide

This guide explains how to enable and view TRACE level exception logs for IPTV streaming to diagnose communication issues with IPTV providers.

## Overview

TRACE level exception logging has been added to the following classes that handle IPTV streaming:

1. **`io.skjaere.debridav.stream.StreamingService`** - Main streaming service that handles IPTV content streaming
2. **`io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer`** - HTTP client wrapper that prepares requests for IPTV URLs

## Log Prefixes

All IPTV-related TRACE logs use the following prefixes for easy filtering:

- `IPTV_STREAMING_EXCEPTION` - General exceptions during IPTV streaming
- `IPTV_STREAMING_EOF` - EOFException during IPTV stream read
- `IPTV_STREAMING_IO_EXCEPTION` - IOException during IPTV streaming
- `IPTV_STREAMING_RUNTIME_EXCEPTION` - RuntimeException during IPTV streaming
- `IPTV_HTTP_REQUEST_EXCEPTION` - Exceptions preparing HTTP requests for IPTV URLs
- `IPTV_HTTP_HEAD_EXCEPTION` - Exceptions checking IPTV link status

## Configuration

### Option 1: Enable TRACE for Specific Classes (Recommended)

Add the following to your `application.properties` or environment variables:

```properties
# Enable TRACE logging for IPTV streaming classes
logging.level.io.skjaere.debridav.stream.StreamingService=TRACE
logging.level.io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer=TRACE
```

### Option 2: Enable TRACE for IPTV Package

If you want to see all IPTV-related logs at TRACE level:

```properties
logging.level.io.skjaere.debridav.iptv=TRACE
logging.level.io.skjaere.debridav.stream.StreamingService=TRACE
logging.level.io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer=TRACE
```

### Option 3: Environment Variables (Docker)

If running in Docker, you can set:

```yaml
environment:
  - LOGGING_LEVEL_IO_SKJAERE_DEBRIDAV_STREAM_STREAMINGSERVICE=TRACE
  - LOGGING_LEVEL_IO_SKJAERE_DEBRIDAV_DEBRID_CLIENT_DEFAULTSTREAMABLELINKPREPARER=TRACE
```

## Viewing TRACE Logs

### Filter by Log Prefix

Use grep to filter only IPTV-related TRACE logs:

```bash
# View all IPTV TRACE logs
docker logs debridav 2>&1 | grep -i "IPTV.*TRACE\|TRACE.*IPTV"

# View only IPTV exception logs
docker logs debridav 2>&1 | grep "IPTV.*EXCEPTION"

# View IPTV streaming exceptions
docker logs debridav 2>&1 | grep "IPTV_STREAMING_EXCEPTION"

# View IPTV HTTP request exceptions
docker logs debridav 2>&1 | grep "IPTV_HTTP.*EXCEPTION"
```

### Follow Logs in Real-Time

```bash
# Follow logs and filter for IPTV TRACE logs
docker logs -f debridav 2>&1 | grep --line-buffered "IPTV.*EXCEPTION"

# Follow all IPTV-related logs
docker logs -f debridav 2>&1 | grep --line-buffered -i "iptv"
```

### Using jq (if logs are JSON formatted)

```bash
# Filter JSON logs for IPTV exceptions
docker logs debridav 2>&1 | jq 'select(.message | contains("IPTV") and contains("EXCEPTION"))'
```

## Log Information Included

Each TRACE log entry includes:

- **path** - The file path being streamed
- **link** - The IPTV URL (truncated to 100 characters for security)
- **provider** - The provider name
- **exceptionClass** - The actual exception class name (e.g., `SSLHandshakeException`, `ConnectTimeoutException`, `SocketTimeoutException`)
- **Full exception stack trace** - Complete exception details for debugging

The `exceptionClass` field is particularly useful for identifying the root cause when exceptions are caught by generic `catch (e: Exception)` blocks.

## Example Log Output

```
2025-11-17T12:00:00.000-05:00 TRACE 1 --- [nio-8080-exec-1] i.s.d.stream.StreamingService : IPTV_STREAMING_EXCEPTION: Exception during IPTV stream read: path=/downloads/movies/Movie.Title.2024.1080p.BluRay.x264-IPTV-provider1.mp4, link=https://example.com/movie/user/pass/12345.mp4, provider=null, exceptionClass=SSLHandshakeException
javax.net.ssl.SSLHandshakeException: sun.security.validator.ValidatorException: PKIX path building failed
    at io.ktor.client.engine.cio.CIOHttpRequest$execute$2.invokeSuspend(CIOHttpRequest.kt:123)
    ...
```

Notice the `exceptionClass=SSLHandshakeException` field which immediately identifies the exception type, even when caught by a generic exception handler.

## Troubleshooting

### If TRACE logs don't appear:

1. **Verify logging level**: Check that TRACE is enabled for the specific classes
2. **Check log level hierarchy**: Ensure parent loggers aren't set to a higher level (e.g., ROOT logger set to INFO)
3. **Verify class names**: Double-check the package names match your application structure
4. **Verify logger names**: Ensure the logger in the code uses the correct class name (e.g., `LoggerFactory.getLogger(DefaultStreamableLinkPreparer::class.java)` not a different class)
5. **Check environment variables**: Verify the environment variables are correctly set in the container:
   ```bash
   docker exec <container_name> env | grep LOGGING_LEVEL
   ```

### To disable TRACE logging:

Simply remove or comment out the logging level configuration, or set it back to DEBUG/INFO:

```properties
logging.level.io.skjaere.debridav.stream.StreamingService=DEBUG
logging.level.io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer=DEBUG
```

## Security Note

TRACE logs may contain sensitive information such as:
- Partial IPTV URLs (truncated to 100 chars)
- Provider names
- File paths

Ensure TRACE logs are only enabled in secure environments and not exposed publicly.

