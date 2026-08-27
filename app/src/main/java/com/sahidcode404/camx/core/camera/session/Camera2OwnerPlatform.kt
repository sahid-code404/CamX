package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation
import com.sahidcode404.camx.core.camera.model.SensorTimestampBasis
import com.sahidcode404.camx.core.camera.raw.SensorRawCaptureResult
import com.sahidcode404.camx.core.camera.raw.SensorRawImage
import com.sahidcode404.camx.core.settings.SettingsSnapshot

/** Opaque test seam; raw CameraDevice/CameraCaptureSession types never cross this file boundary. */
internal interface CameraDeviceHandle {
    fun close()
}

internal interface CameraCaptureSessionHandle {
    fun close()
}

internal interface PreparedPreviewRequest

internal interface PreparedRawRequest

internal interface RawImageReaderHandle : AutoCloseable {
    val surfaceToken: Any
}

internal data class SensorRawMetadata(
    val timestampBasis: SensorTimestampBasis,
)

internal interface CameraOpenCallbacks {
    fun onOpened(delivery: CloseOnceCameraResource<CameraDeviceHandle>)
    fun onDisconnected(delivery: CloseOnceCameraResource<CameraDeviceHandle>)
    fun onError(delivery: CloseOnceCameraResource<CameraDeviceHandle>, platformCode: Int)
}

internal interface CameraSessionCallbacks {
    fun onConfigured(
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
        request: PreparedPreviewRequest,
    )

    fun onConfigureFailed(delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>)
}

internal interface RawImageCallbacks {
    fun onImage(delivery: CloseOnceCameraResource<SensorRawImage>)
    fun onAcquisitionFailure()
}

internal interface RawSessionCallbacks {
    fun onConfigured(
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
        previewRequest: PreparedPreviewRequest,
        rawRequest: PreparedRawRequest,
    )

    fun onConfigureFailed(delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>)
}

internal interface RawCaptureCallbacks {
    fun onCompleted(result: SensorRawCaptureResult)
    fun onFailed()
}

internal interface CameraOwnerPlatform {
    fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks)

    fun configurePreview(
        device: CameraDeviceHandle,
        surfaceToken: Any,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: CameraSessionCallbacks,
    )

    /**
     * Typed physical-output extension of the frozen CAMX-103 preview seam.
     * Existing direct-preview fakes remain source-compatible through the default implementation.
     */
    fun configurePreviewTargeted(
        device: CameraDeviceHandle,
        surfaceToken: Any,
        physicalCameraId: PhysicalCameraId?,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: CameraSessionCallbacks,
    ) = configurePreview(
        device = device,
        surfaceToken = surfaceToken,
        configuration = configuration,
        settings = settings,
        attempt = attempt,
        callbacks = callbacks,
    )

    fun startRepeating(
        session: CameraCaptureSessionHandle,
        request: PreparedPreviewRequest,
        onFrame: () -> Unit,
    )

    /** Cached exact-profile metadata only; this seam may not perform shutter-time discovery. */
    fun sensorRawMetadata(
        device: CameraDeviceHandle,
        physicalCameraId: PhysicalCameraId?,
        representation: SensorRawRepresentation,
    ): SensorRawMetadata? = null

    fun createRawReader(
        context: RawCaptureContext,
        callbacks: RawImageCallbacks,
    ): RawImageReaderHandle = throw UnsupportedOperationException("Sensor RAW reader unavailable")

    fun configureTemporaryRaw(
        device: CameraDeviceHandle,
        previewSurfaceToken: Any,
        physicalCameraId: PhysicalCameraId?,
        reader: RawImageReaderHandle,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: RawSessionCallbacks,
    ): Unit {
        throw UnsupportedOperationException("Temporary Sensor RAW session unavailable")
    }

    /** One invocation must submit exactly one non-repeating still request. */
    fun captureOneRaw(
        session: CameraCaptureSessionHandle,
        request: PreparedRawRequest,
        callbacks: RawCaptureCallbacks,
    ): Unit {
        throw UnsupportedOperationException("One-shot Sensor RAW request unavailable")
    }
}
