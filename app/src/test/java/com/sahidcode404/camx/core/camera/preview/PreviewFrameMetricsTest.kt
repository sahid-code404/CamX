package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFrameMetricsTest {
    @Test
    fun metricsAreBoundedAndIgnoreNonIncreasingTimestamps() {
        val metrics = PreviewFrameMetrics(
            requested = PreviewFpsRequest(true, 30, 30),
            resolved = CameraFpsCapability(30, 30),
            capacity = 3,
        )
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(100L)
        metrics.recordSensorTimestamp(50L)
        metrics.recordSensorTimestamp(200L)
        metrics.recordSensorTimestamp(300L)
        metrics.recordSensorTimestamp(500L)
        metrics.recordSensorTimestamp(800L)

        val snapshot = metrics.snapshot()
        assertEquals(3, snapshot.sampleCount)
        assertEquals(200L, snapshot.p50FrameIntervalNs)
        assertEquals(300L, snapshot.p95FrameIntervalNs)
        assertTrue(requireNotNull(snapshot.movingAverageFps) > 0.0)
    }
}
