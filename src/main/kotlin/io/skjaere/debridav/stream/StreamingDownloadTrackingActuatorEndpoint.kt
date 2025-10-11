package io.skjaere.debridav.stream

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@Endpoint(id = "streaming-download-tracking")
@ConditionalOnExpression("#{'\${debridav.enable-streaming-download-tracking}'.equals('true')}")
class StreamingDownloadTrackingActuatorEndpoint(
    private val streamingService: StreamingService
) {
    @ReadOperation
    fun getHistoricalDownloadTracking(): List<DownloadTrackingInfo> {
        return streamingService.getCompletedDownloads().map { context ->
            DownloadTrackingInfo(
                filePath = context.filePath,
                fileName = context.fileName,
                requestedRangeStart = context.requestedRange?.start,
                requestedRangeFinish = context.requestedRange?.finish,
                requestedSize = context.requestedSize,
                bytesDownloaded = context.bytesDownloaded.get(),
                downloadStartTime = context.downloadStartTime,
                downloadEndTime = context.downloadEndTime,
                completionStatus = context.completionStatus,
                durationMs = context.downloadEndTime?.let { endTime ->
                    java.time.Duration.between(context.downloadStartTime, endTime).toMillis()
                },
                httpHeaders = context.httpHeaders,
                sourceIpAddress = context.sourceIpAddress
            )
        }.sortedByDescending { it.downloadStartTime }
    }

    data class DownloadTrackingInfo(
        val filePath: String,
        val fileName: String,
        val requestedRangeStart: Long?,
        val requestedRangeFinish: Long?,
        val requestedSize: Long,
        val bytesDownloaded: Long,
        val downloadStartTime: Instant,
        val downloadEndTime: Instant?,
        val completionStatus: String,
        val durationMs: Long?,
        val httpHeaders: Map<String, String>,
        val sourceIpAddress: String?
    )
}
