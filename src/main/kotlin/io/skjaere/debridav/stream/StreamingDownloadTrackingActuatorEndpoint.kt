package io.skjaere.debridav.stream

import com.fasterxml.jackson.annotation.JsonFormat
import org.apache.commons.io.FileUtils
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant
import io.skjaere.debridav.stream.HttpRequestInfo

@Component
@Endpoint(id = "streaming-download-tracking")
@ConditionalOnExpression("#{'\${debridav.enable-streaming-download-tracking}'.equals('true')}")
class StreamingDownloadTrackingActuatorEndpoint(
    private val streamingService: StreamingService
) {
    @ReadOperation
    fun getHistoricalDownloadTracking(): List<DownloadTrackingInfo> {
        return streamingService.getCompletedDownloads().map { context ->
            val httpRequestInfo = context.httpHeaders.entries.associate { entry -> entry.key to entry.value }
                .let { headers -> HttpRequestInfo(headers, context.sourceIpAddress) }

            DownloadTrackingInfo(
                filePath = context.filePath,
                fileName = context.fileName,
                requestedRangeStart = context.requestedRange?.start,
                requestedRangeFinish = context.requestedRange?.finish,
                requestedSizeFormatted = FileUtils.byteCountToDisplaySize(context.requestedSize),
                requestedSize = context.requestedSize,
                downloadStartTime = context.downloadStartTime,
                bytesDownloadedFormatted = FileUtils.byteCountToDisplaySize(context.bytesDownloaded.get()),
                bytesDownloaded = context.bytesDownloaded.get(),
                actualBytesSent = context.actualBytesSent,
                actualBytesSentFormatted = context.actualBytesSent?.let { bytes -> FileUtils.byteCountToDisplaySize(bytes) },
                // Completion metadata
                downloadEndTime = context.downloadEndTime,
                completionStatus = context.completionStatus,
                durationMs = context.downloadEndTime?.let { endTime ->
                    java.time.Duration.between(context.downloadStartTime, endTime).toMillis()
                },
                httpHeaders = context.httpHeaders,
                sourceInfo = httpRequestInfo.sourceInfo
            )
        }.sortedByDescending { trackingInfo -> trackingInfo.downloadStartTime }
    }

    data class DownloadTrackingInfo(
        val filePath: String,
        val fileName: String,
        val requestedRangeStart: Long?,
        val requestedRangeFinish: Long?,
        val requestedSizeFormatted: String,
        val requestedSize: Long,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        val downloadStartTime: Instant,
        val bytesDownloadedFormatted: String,
        val bytesDownloaded: Long,
        val actualBytesSent: Long?,
        val actualBytesSentFormatted: String?,
        // Completion metadata
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        val downloadEndTime: Instant?,
        val completionStatus: String,
        val durationMs: Long?,
        val httpHeaders: Map<String, String>,
        val sourceInfo: String?
    )
}
