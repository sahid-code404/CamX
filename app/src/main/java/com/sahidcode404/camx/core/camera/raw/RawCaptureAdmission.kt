package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation

enum class RawAdmissionRejection {
    LIFECYCLE_INACTIVE,
    PREVIEW_NOT_VERIFIED,
    SELECTION_UNAVAILABLE,
    SESSION_BUSY,
    PAUSING,
    SHUTDOWN,
    CAPTURE_ALREADY_ACTIVE,
    SENSOR_RAW_UNSUPPORTED,
    REQUIRED_METADATA_UNAVAILABLE,
    BOUNDED_RESOURCES_UNAVAILABLE,
}

sealed interface RawAdmissionDecision {
    data class Admitted(
        val selection: ActiveCameraSelection,
        val representation: SensorRawRepresentation,
    ) : RawAdmissionDecision

    data class Rejected(val reason: RawAdmissionRejection) : RawAdmissionDecision
}

data class RawAdmissionInput(
    val lifecycleActive: Boolean,
    val previewVerified: Boolean,
    val selection: ActiveCameraSelection?,
    val sessionBusy: Boolean,
    val pausing: Boolean,
    val shutdown: Boolean,
    val captureActive: Boolean,
    val representation: SensorRawRepresentation?,
    val requiredMetadataAvailable: Boolean,
    val boundedResourcesAvailable: Boolean,
)

/** Pure admission policy used before any Camera2 resource is reconfigured. */
object RawCaptureAdmission {
    fun decide(input: RawAdmissionInput): RawAdmissionDecision {
        val rejection = when {
            input.shutdown -> RawAdmissionRejection.SHUTDOWN
            input.pausing -> RawAdmissionRejection.PAUSING
            !input.lifecycleActive -> RawAdmissionRejection.LIFECYCLE_INACTIVE
            !input.previewVerified -> RawAdmissionRejection.PREVIEW_NOT_VERIFIED
            input.selection == null -> RawAdmissionRejection.SELECTION_UNAVAILABLE
            input.sessionBusy -> RawAdmissionRejection.SESSION_BUSY
            input.captureActive -> RawAdmissionRejection.CAPTURE_ALREADY_ACTIVE
            input.representation == null -> RawAdmissionRejection.SENSOR_RAW_UNSUPPORTED
            !input.requiredMetadataAvailable -> RawAdmissionRejection.REQUIRED_METADATA_UNAVAILABLE
            !input.boundedResourcesAvailable -> RawAdmissionRejection.BOUNDED_RESOURCES_UNAVAILABLE
            else -> null
        }
        return if (rejection != null) {
            RawAdmissionDecision.Rejected(rejection)
        } else {
            RawAdmissionDecision.Admitted(
                selection = checkNotNull(input.selection),
                representation = checkNotNull(input.representation),
            )
        }
    }
}
