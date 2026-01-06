package io.skjaere.debridav.stream

import com.fasterxml.jackson.annotation.JsonFormat
import org.apache.commons.io.FileUtils
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant
import io.skjaere.debridav.stream.HttpRequestInfo

@Component
@Endpoint(id = "streaming-download-tracking")
@ConditionalOnExpression("\${debridav.enable-streaming-download-tracking:false}")
class StreamingDownloadTrackingActuatorEndpoint(
    private val streamingService: StreamingService
) {
    @ReadOperation
    fun getHistoricalDownloadTracking(): TrackingResponse {
        val trackingInfoList = streamingService.getCompletedDownloads().map { context ->
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
                sourceInfo = httpRequestInfo.sourceInfo,
                provider = context.provider?.toString()
            )
        }.sortedByDescending { trackingInfo -> trackingInfo.downloadStartTime }
        
        // Calculate provider metrics
        val providerMetrics = trackingInfoList
            .groupBy { it.provider ?: "UNKNOWN" }
            .map { (provider, entries) ->
                val totalDownloadedBytes = entries.sumOf { it.actualBytesSent ?: it.bytesDownloaded }
                val filePaths = entries.map { it.filePath }.distinct().sorted()
                
                ProviderMetrics(
                    provider = provider,
                    totalDownloadedBytes = totalDownloadedBytes,
                    totalDownloadedBytesFormatted = FileUtils.byteCountToDisplaySize(totalDownloadedBytes),
                    filePaths = filePaths
                )
            }
            .sortedBy { it.provider }
        
        // Calculate metrics grouped by filePath
        val metricsByFilePath = trackingInfoList
            .groupBy { it.filePath }
            .mapValues { (_, entries) ->
                val totalRequestedBytes = entries.sumOf { it.requestedSize }
                val totalDownloadedBytes = entries.sumOf { it.bytesDownloaded }
                val totalActualBytesSent = entries.sumOf { it.actualBytesSent ?: 0L }
                val requestCount = entries.size
                val firstAccessTime = entries.minOfOrNull { it.downloadStartTime }
                val lastAccessTime = entries.maxOfOrNull { it.downloadStartTime }
                val completionStatusCounts = entries.groupingBy { it.completionStatus }.eachCount()
                val totalDurationMs = entries.sumOf { it.durationMs ?: 0L }
                
                FilePathMetrics(
                    filePath = entries.first().filePath,
                    fileName = entries.first().fileName,
                    totalRequestedBytes = totalRequestedBytes,
                    totalRequestedBytesFormatted = FileUtils.byteCountToDisplaySize(totalRequestedBytes),
                    totalDownloadedBytes = totalDownloadedBytes,
                    totalDownloadedBytesFormatted = FileUtils.byteCountToDisplaySize(totalDownloadedBytes),
                    totalActualBytesSent = totalActualBytesSent,
                    totalActualBytesSentFormatted = FileUtils.byteCountToDisplaySize(totalActualBytesSent),
                    requestCount = requestCount,
                    firstAccessTime = firstAccessTime,
                    lastAccessTime = lastAccessTime,
                    completionStatusCounts = completionStatusCounts,
                    totalDurationMs = totalDurationMs,
                    averageDurationMs = if (requestCount > 0) totalDurationMs / requestCount else null
                )
            }
            .values
            .sortedByDescending { it.lastAccessTime ?: Instant.EPOCH }
        
        return TrackingResponse(
            providerMetrics = providerMetrics,
            metrics = metricsByFilePath,
            details = trackingInfoList
        )
    }

    @DeleteOperation
    fun clearMetrics() {
        streamingService.clearCompletedDownloads()
    }

    data class TrackingResponse(
        val providerMetrics: List<ProviderMetrics>,
        val metrics: List<FilePathMetrics>,
        val details: List<DownloadTrackingInfo>
    )
    
    data class ProviderMetrics(
        val provider: String,
        val totalDownloadedBytes: Long,
        val totalDownloadedBytesFormatted: String,
        val filePaths: List<String>
    )
    
    data class FilePathMetrics(
        val filePath: String,
        val fileName: String,
        val totalRequestedBytes: Long,
        val totalRequestedBytesFormatted: String,
        val totalDownloadedBytes: Long,
        val totalDownloadedBytesFormatted: String,
        val totalActualBytesSent: Long,
        val totalActualBytesSentFormatted: String,
        val requestCount: Int,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        val firstAccessTime: Instant?,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        val lastAccessTime: Instant?,
        val completionStatusCounts: Map<String, Int>,
        val totalDurationMs: Long,
        val averageDurationMs: Long?
    )

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
        val sourceInfo: String?,
        val provider: String?
    )
}
