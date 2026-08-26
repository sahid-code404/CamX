package com.sahidcode404.camx.core.camera.model

object RawContractLimits {
    const val MINIMUM_TIMEOUT_MILLIS = 1L
    const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    const val MAXIMUM_TIMEOUT_MILLIS = 60_000L
}

/** Public, interpretable sensor-domain representations. No processed format belongs here. */
enum class SensorRawFormat(val fidelityRank: Int) {
    RAW_SENSOR(4),
    RAW_14(3),
    RAW_12(2),
    RAW_10(1),
}

/**
 * One exact format/size tuple advertised by the shutter-time camera profile. [dngWritable]
 * describes a proven writer path; it must never be inferred merely from the bit depth.
 */
data class SensorRawRepresentation(
    val format: SensorRawFormat,
    val size: IntSize,
    val dngWritable: Boolean,
)

/** Android SENSOR_INFO_TIMESTAMP_SOURCE, captured before the shutter transaction begins. */
enum class SensorTimestampBasis {
    REALTIME,
    UNKNOWN,
}

data class RawCaptureContext(
    val captureToken: CaptureToken,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val openCameraId: CameraTransportId,
    val physicalCameraId: PhysicalCameraId?,
    val previewSurfaceIdentity: Long,
    val displayRotationAtShutter: DisplayRotation,
    val sensorOrientationDegrees: Int,
    val lensFacing: LensFacing,
    val rawRepresentation: SensorRawRepresentation,
    val sensorTimestampBasis: SensorTimestampBasis,
    val admittedAtElapsedRealtimeNs: Long,
    val deadlineElapsedRealtimeNs: Long,
    val timeoutMillis: Long,
) {
    val rawSize: IntSize get() = rawRepresentation.size
    val rawFormat: SensorRawFormat get() = rawRepresentation.format

    init {
        require(timeoutMillis in RawContractLimits.MINIMUM_TIMEOUT_MILLIS..
            RawContractLimits.MAXIMUM_TIMEOUT_MILLIS
        ) {
            "RAW timeout must be between ${RawContractLimits.MINIMUM_TIMEOUT_MILLIS} and " +
                "${RawContractLimits.MAXIMUM_TIMEOUT_MILLIS} milliseconds"
        }
        require(sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0) {
            "Sensor orientation must be one of 0, 90, 180, or 270 degrees"
        }
        require(previewSurfaceIdentity > 0L) { "Preview surface identity must be positive" }
        require(admittedAtElapsedRealtimeNs >= 0L) { "RAW admission time cannot be negative" }
        require(deadlineElapsedRealtimeNs > admittedAtElapsedRealtimeNs) {
            "RAW deadline must be later than its admission time"
        }
        require(rawRepresentation.dngWritable) {
            "The admitted sensor RAW representation must have a proven DNG writer path"
        }
    }
}

/**
 * One-time ownership handoff for a paired RAW image/result. The pair owns the image until
 * [takeImage] succeeds; otherwise [close] releases it. This type is intentionally non-copyable.
 */
class RawPair<I : AutoCloseable, R : Any>(
    val timestampNs: Long,
    image: I,
    val result: R,
) : AutoCloseable {
    private var ownedImage: I? = image

    init { require(timestampNs > 0L) { "Sensor timestamp must be positive" } }

    @Synchronized
    fun takeImage(): I = checkNotNull(ownedImage) { "RAW image ownership already transferred" }
        .also { ownedImage = null }

    @Synchronized
    override fun close() {
        val imageToClose = ownedImage ?: return
        ownedImage = null
        runCatching { imageToClose.close() }
    }
}
