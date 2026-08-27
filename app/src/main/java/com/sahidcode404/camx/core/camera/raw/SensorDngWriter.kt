package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.RawCaptureContext

enum class SensorDngSemanticFailure {
    REPRESENTATION_NOT_WRITABLE,
    IMAGE_FORMAT_MISMATCH,
    IMAGE_SIZE_MISMATCH,
    INVALID_IMAGE_TIMESTAMP,
    INVALID_RESULT_TIMESTAMP,
    TIMESTAMP_MISMATCH,
}

object SensorDngSemantics {
    fun validate(
        context: RawCaptureContext,
        image: SensorRawImage,
        result: SensorRawCaptureResult,
    ): SensorDngSemanticFailure? = when {
        !context.rawRepresentation.dngWritable ->
            SensorDngSemanticFailure.REPRESENTATION_NOT_WRITABLE
        image.format != context.rawFormat -> SensorDngSemanticFailure.IMAGE_FORMAT_MISMATCH
        image.size != context.rawSize -> SensorDngSemanticFailure.IMAGE_SIZE_MISMATCH
        image.timestampNs <= 0L -> SensorDngSemanticFailure.INVALID_IMAGE_TIMESTAMP
        result.sensorTimestampNs <= 0L -> SensorDngSemanticFailure.INVALID_RESULT_TIMESTAMP
        image.timestampNs != result.sensorTimestampNs -> SensorDngSemanticFailure.TIMESTAMP_MISMATCH
        else -> null
    }
}

/** Truthful one-shot sensor DNG boundary. It never accepts a computational negative. */
internal fun interface SensorDngWriter {
    suspend fun write(
        context: RawCaptureContext,
        image: SensorRawImage,
        result: SensorRawCaptureResult,
        authorizePublish: () -> Boolean,
    ): RawCaptureOutcome
}
