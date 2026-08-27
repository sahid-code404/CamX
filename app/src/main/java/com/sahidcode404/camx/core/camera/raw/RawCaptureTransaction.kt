package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.RawCaptureContext

sealed interface RawCaptureOutcome {
    data class Saved(val contentUri: String, val byteCount: Long) : RawCaptureOutcome
    data class Failed(val failure: CameraFailure) : RawCaptureOutcome
    data object Cancelled : RawCaptureOutcome
}

sealed interface RawCaptureUiState {
    data object Unavailable : RawCaptureUiState
    data object Ready : RawCaptureUiState
    data class Capturing(val captureToken: CaptureToken) : RawCaptureUiState
    data class Saving(val captureToken: CaptureToken) : RawCaptureUiState
    data class Recovering(
        val captureToken: CaptureToken,
        val outcome: RawCaptureOutcome,
    ) : RawCaptureUiState
}

data class RawShutterInput(
    val displayRotation: DisplayRotation,
    val sensorOrientationDegrees: Int,
    val lensFacing: LensFacing,
    val lifecycleActive: Boolean = true,
) {
    init {
        require(sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0) {
            "Sensor orientation must be one of 0, 90, 180, or 270 degrees"
        }
    }
}

interface RawSessionLease : AutoCloseable {
    val context: RawCaptureContext
}

/**
 * Session owner creates and revokes the lease. Implementations may not open CameraDevice and must
 * destroy every transaction resource before completing.
 */
fun interface RawCaptureTransaction {
    suspend fun execute(lease: RawSessionLease): RawCaptureOutcome
}
