package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureAdmissionTest {
    @Test
    fun onlyFullyVerifiedBoundedPathIsAdmitted() {
        val decision = RawCaptureAdmission.decide(validInput())

        assertTrue(decision is RawAdmissionDecision.Admitted)
    }

    @Test
    fun eachAdmissionPreconditionHasTypedRejection() {
        val cases = listOf(
            validInput().copy(lifecycleActive = false) to RawAdmissionRejection.LIFECYCLE_INACTIVE,
            validInput().copy(previewVerified = false) to RawAdmissionRejection.PREVIEW_NOT_VERIFIED,
            validInput().copy(selection = null) to RawAdmissionRejection.SELECTION_UNAVAILABLE,
            validInput().copy(sessionBusy = true) to RawAdmissionRejection.SESSION_BUSY,
            validInput().copy(pausing = true) to RawAdmissionRejection.PAUSING,
            validInput().copy(shutdown = true) to RawAdmissionRejection.SHUTDOWN,
            validInput().copy(captureActive = true) to RawAdmissionRejection.CAPTURE_ALREADY_ACTIVE,
            validInput().copy(representation = null) to RawAdmissionRejection.SENSOR_RAW_UNSUPPORTED,
            validInput().copy(requiredMetadataAvailable = false) to
                RawAdmissionRejection.REQUIRED_METADATA_UNAVAILABLE,
            validInput().copy(boundedResourcesAvailable = false) to
                RawAdmissionRejection.BOUNDED_RESOURCES_UNAVAILABLE,
        )

        cases.forEach { (input, expected) ->
            assertEquals(RawAdmissionDecision.Rejected(expected), RawCaptureAdmission.decide(input))
        }
    }

    @Test
    fun shutdownAndPauseWinOverLessSpecificRejections() {
        assertEquals(
            RawAdmissionDecision.Rejected(RawAdmissionRejection.SHUTDOWN),
            RawCaptureAdmission.decide(validInput().copy(shutdown = true, previewVerified = false)),
        )
        assertEquals(
            RawAdmissionDecision.Rejected(RawAdmissionRejection.PAUSING),
            RawCaptureAdmission.decide(validInput().copy(pausing = true, previewVerified = false)),
        )
    }

    private fun validInput() = RawAdmissionInput(
        lifecycleActive = true,
        previewVerified = true,
        selection = ActiveCameraSelection(
            canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
            profileFingerprint = CameraProfileFingerprint("profile"),
            routeId = CameraRouteId("route"),
            selectionGeneration = SelectionGeneration(2L),
            sessionGeneration = SessionGeneration(3L),
        ),
        sessionBusy = false,
        pausing = false,
        shutdown = false,
        captureActive = false,
        representation = SensorRawRepresentation(
            SensorRawFormat.RAW_SENSOR,
            IntSize(4000, 3000),
            dngWritable = true,
        ),
        requiredMetadataAvailable = true,
        boundedResourcesAvailable = true,
    )
}
