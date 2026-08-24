package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import kotlin.math.ceil

data class PreviewFrameMetricsSnapshot(
    val requested: PreviewFpsRequest,
    val resolved: CameraFpsCapability?,
    val sampleCount: Int,
    val movingAverageFps: Double?,
    val p50FrameIntervalNs: Long?,
    val p95FrameIntervalNs: Long?,
)

class PreviewFrameMetrics(
    private val requested: PreviewFpsRequest,
    private val resolved: CameraFpsCapability?,
    capacity: Int = 120,
) {
    private val intervals: LongArray
    private var size = 0
    private var writeIndex = 0
    private var previousTimestampNs: Long? = null

    init {
        require(capacity in 2..MAX_CAPACITY) { "Metrics capacity must be between 2 and $MAX_CAPACITY" }
        intervals = LongArray(capacity)
    }

    @Synchronized
    fun recordSensorTimestamp(timestampNs: Long) {
        if (timestampNs <= 0L) return
        val previous = previousTimestampNs
        if (previous == null) {
            previousTimestampNs = timestampNs
            return
        }
        if (timestampNs <= previous) return
        previousTimestampNs = timestampNs
        intervals[writeIndex] = timestampNs - previous
        writeIndex = (writeIndex + 1) % intervals.size
        if (size < intervals.size) size += 1
    }

    @Synchronized
    fun snapshot(): PreviewFrameMetricsSnapshot {
        if (size == 0) {
            return PreviewFrameMetricsSnapshot(requested, resolved, 0, null, null, null)
        }
        val ordered = LongArray(size) { index -> intervals[index] }.sortedArray()
        val meanInterval = ordered.fold(0.0) { sum, value -> sum + value.toDouble() } / size
        return PreviewFrameMetricsSnapshot(
            requested = requested,
            resolved = resolved,
            sampleCount = size,
            movingAverageFps = if (meanInterval > 0.0) 1_000_000_000.0 / meanInterval else null,
            p50FrameIntervalNs = percentile(ordered, 0.50),
            p95FrameIntervalNs = percentile(ordered, 0.95),
        )
    }

    private fun percentile(sorted: LongArray, percentile: Double): Long {
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private companion object {
        const val MAX_CAPACITY = 4_096
    }
}
