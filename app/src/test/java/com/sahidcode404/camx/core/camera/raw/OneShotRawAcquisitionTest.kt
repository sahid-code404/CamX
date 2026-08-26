package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation
import com.sahidcode404.camx.core.camera.model.SensorTimestampBasis
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotRawAcquisitionTest {
    @Test
    fun imageThenResultTransfersExactPair() = runTest {
        val acquisition = OneShotRawAcquisition<FakeImage, FakeResult>(context())
        val image = FakeImage(11L)

        assertEquals(RawEvidenceDisposition.BUFFERED, acquisition.offerImage(image))
        assertEquals(RawEvidenceDisposition.PAIRED, acquisition.offerResult(FakeResult(11L)))
        acquisition.awaitPair().use { pair ->
            assertEquals(11L, pair.timestampNs)
            assertEquals(image, pair.takeImage())
        }
        assertFalse(image.closed)
        image.close()
        acquisition.close()
        assertEquals(1, image.closeCount.get())
    }

    @Test
    fun resultThenImageTransfersExactPair() = runTest {
        val acquisition = OneShotRawAcquisition<FakeImage, FakeResult>(context())
        val image = FakeImage(22L)

        assertEquals(RawEvidenceDisposition.BUFFERED, acquisition.offerResult(FakeResult(22L)))
        assertEquals(RawEvidenceDisposition.PAIRED, acquisition.offerImage(image))
        acquisition.awaitPair().close()

        assertTrue(image.closed)
        assertEquals(1, image.closeCount.get())
    }

    @Test
    fun mismatchedInvalidDuplicateOverflowAndCloseReleaseEveryImageOnce() {
        val acquisition = OneShotRawAcquisition<FakeImage, FakeResult>(context())
        val wrongTimestamp = FakeImage(0L)
        val wrongFormat = FakeImage(1L, format = SensorRawFormat.RAW_12)
        val wrongSize = FakeImage(2L, size = IntSize(2, 2))
        val duplicateOld = FakeImage(3L)
        val duplicateNew = FakeImage(3L)
        val overflowOld = FakeImage(4L)
        val retained = FakeImage(5L)

        assertEquals(RawEvidenceDisposition.INVALID_CLOSED, acquisition.offerImage(wrongTimestamp))
        assertEquals(RawEvidenceDisposition.INVALID_CLOSED, acquisition.offerImage(wrongFormat))
        assertEquals(RawEvidenceDisposition.INVALID_CLOSED, acquisition.offerImage(wrongSize))
        acquisition.offerImage(duplicateOld)
        acquisition.offerImage(duplicateNew)
        acquisition.offerImage(overflowOld)
        acquisition.offerImage(retained)
        acquisition.close()
        acquisition.close()

        listOf(wrongTimestamp, wrongFormat, wrongSize, duplicateOld, duplicateNew, overflowOld, retained)
            .forEach { image -> assertEquals(1, image.closeCount.get()) }
    }

    @Test
    fun timeoutClosesImageOnlyOrphanAndResultOnlyTimesOut() = runTest {
        val imageOnly = OneShotRawAcquisition<FakeImage, FakeResult>(context(timeoutMillis = 1L))
        val image = FakeImage(31L)
        imageOnly.offerImage(image)
        val imageTimeout = runCatching { imageOnly.awaitPair() }.exceptionOrNull()
        assertTrue(imageTimeout is TimeoutCancellationException)
        assertEquals(1, image.closeCount.get())

        val resultOnly = OneShotRawAcquisition<FakeImage, FakeResult>(context(timeoutMillis = 1L))
        resultOnly.offerResult(FakeResult(32L))
        val resultTimeout = runCatching { resultOnly.awaitPair() }.exceptionOrNull()
        assertTrue(resultTimeout is TimeoutCancellationException)
        assertEquals(0 to 0, resultOnly.pendingCounts())
    }

    @Test
    fun cancellationAndLateCallbacksCannotRetakeOwnership() = runTest {
        val acquisition = OneShotRawAcquisition<FakeImage, FakeResult>(context())
        val waiter = async { acquisition.awaitPair() }
        waiter.cancelAndJoin()
        acquisition.close()
        val late = FakeImage(41L)

        assertEquals(RawEvidenceDisposition.STALE_CLOSED, acquisition.offerImage(late))
        assertEquals(RawEvidenceDisposition.STALE_IGNORED, acquisition.offerResult(FakeResult(41L)))
        assertTrue(waiter.isCancelled)
        assertTrue(late.closed)
    }

    private fun context(timeoutMillis: Long = 2_000L) = RawCaptureContext(
        captureToken = CaptureToken(1L),
        selectionGeneration = SelectionGeneration(2L),
        sessionGeneration = SessionGeneration(3L),
        canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
        cameraProfileFingerprint = CameraProfileFingerprint("profile"),
        routeId = CameraRouteId("route"),
        openCameraId = CameraTransportId("opaque"),
        physicalCameraId = null,
        previewSurfaceIdentity = 4L,
        displayRotationAtShutter = DisplayRotation.ROTATION_90,
        sensorOrientationDegrees = 90,
        lensFacing = LensFacing.BACK,
        rawRepresentation = SensorRawRepresentation(
            SensorRawFormat.RAW_SENSOR,
            IntSize(4, 3),
            dngWritable = true,
        ),
        sensorTimestampBasis = SensorTimestampBasis.UNKNOWN,
        admittedAtElapsedRealtimeNs = 5L,
        deadlineElapsedRealtimeNs = 5L + timeoutMillis * 1_000_000L,
        timeoutMillis = timeoutMillis,
    )

    private class FakeImage(
        override val timestampNs: Long,
        override val format: SensorRawFormat = SensorRawFormat.RAW_SENSOR,
        override val size: IntSize = IntSize(4, 3),
    ) : SensorRawImage {
        val closeCount = AtomicInteger()
        val closed get() = closeCount.get() > 0
        override fun close() {
            check(closeCount.incrementAndGet() == 1) { "Image closed more than once" }
        }
    }

    private data class FakeResult(
        override val sensorTimestampNs: Long,
    ) : SensorRawCaptureResult
}
