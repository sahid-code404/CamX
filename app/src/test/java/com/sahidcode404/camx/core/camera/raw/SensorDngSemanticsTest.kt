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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorDngSemanticsTest {
    @Test
    fun exactRawSensorImageAndResultAreAccepted() {
        assertNull(SensorDngSemantics.validate(context(), image(), result()))
    }

    @Test
    fun neverAcceptsFormatSizeOrTimestampSubstitution() {
        assertEquals(
            SensorDngSemanticFailure.IMAGE_FORMAT_MISMATCH,
            SensorDngSemantics.validate(context(), image(format = SensorRawFormat.RAW_12), result()),
        )
        assertEquals(
            SensorDngSemanticFailure.IMAGE_SIZE_MISMATCH,
            SensorDngSemantics.validate(context(), image(size = IntSize(3, 4)), result()),
        )
        assertEquals(
            SensorDngSemanticFailure.INVALID_IMAGE_TIMESTAMP,
            SensorDngSemantics.validate(context(), image(timestamp = 0L), result()),
        )
        assertEquals(
            SensorDngSemanticFailure.INVALID_RESULT_TIMESTAMP,
            SensorDngSemantics.validate(context(), image(), result(timestamp = -1L)),
        )
        assertEquals(
            SensorDngSemanticFailure.TIMESTAMP_MISMATCH,
            SensorDngSemantics.validate(context(), image(), result(timestamp = 8L)),
        )
    }

    private fun context() = RawCaptureContext(
        captureToken = CaptureToken(1L),
        selectionGeneration = SelectionGeneration(1L),
        sessionGeneration = SessionGeneration(1L),
        canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
        cameraProfileFingerprint = CameraProfileFingerprint("profile"),
        routeId = CameraRouteId("route"),
        openCameraId = CameraTransportId("opaque"),
        physicalCameraId = null,
        previewSurfaceIdentity = 1L,
        displayRotationAtShutter = DisplayRotation.ROTATION_0,
        sensorOrientationDegrees = 90,
        lensFacing = LensFacing.BACK,
        rawRepresentation = SensorRawRepresentation(
            SensorRawFormat.RAW_SENSOR,
            IntSize(4, 3),
            dngWritable = true,
        ),
        sensorTimestampBasis = SensorTimestampBasis.UNKNOWN,
        admittedAtElapsedRealtimeNs = 1L,
        deadlineElapsedRealtimeNs = 2_000_000_001L,
        timeoutMillis = 2_000L,
    )

    private fun image(
        timestamp: Long = 7L,
        format: SensorRawFormat = SensorRawFormat.RAW_SENSOR,
        size: IntSize = IntSize(4, 3),
    ) = object : SensorRawImage {
        override val timestampNs = timestamp
        override val format = format
        override val size = size
        override fun close() = Unit
    }

    private fun result(timestamp: Long = 7L) = object : SensorRawCaptureResult {
        override val sensorTimestampNs = timestamp
    }
}
