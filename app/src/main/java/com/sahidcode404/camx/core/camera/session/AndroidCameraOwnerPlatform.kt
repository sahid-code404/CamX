package com.sahidcode404.camx.core.camera.session

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Range
import android.view.Surface
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation
import com.sahidcode404.camx.core.camera.model.SensorTimestampBasis
import com.sahidcode404.camx.core.camera.raw.AndroidSensorRawImageSource
import com.sahidcode404.camx.core.camera.raw.AndroidSensorRawResultSource
import com.sahidcode404.camx.core.camera.raw.SensorRawImage
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidDeviceHandle(
    val device: CameraDevice,
    logicalCharacteristics: CameraCharacteristics,
) : CameraDeviceHandle {
    private val characteristics = ConcurrentHashMap<String, CameraCharacteristics>().apply {
        put(device.id, logicalCharacteristics)
    }

    fun cachePhysical(cameraId: PhysicalCameraId, value: CameraCharacteristics) {
        characteristics.putIfAbsent(cameraId.value, value)
    }

    fun cachedCharacteristics(physicalCameraId: PhysicalCameraId?): CameraCharacteristics? =
        characteristics[physicalCameraId?.value ?: device.id]

    override fun close() = device.close()
}

internal class AndroidSessionHandle(val session: CameraCaptureSession) : CameraCaptureSessionHandle {
    override fun close() = session.close()
}

internal class AndroidPreparedPreviewRequest(val request: CaptureRequest) : PreparedPreviewRequest

internal class AndroidPreparedRawRequest(
    val request: CaptureRequest,
    val physicalCameraId: PhysicalCameraId?,
    val characteristics: CameraCharacteristics,
) : PreparedRawRequest

internal class AndroidRawReaderHandle(
    private val reader: ImageReader,
) : RawImageReaderHandle {
    private val closed = AtomicBoolean(false)
    override val surfaceToken: Any get() = reader.surface

    fun setListener(listener: ImageReader.OnImageAvailableListener, handler: Handler) {
        reader.setOnImageAvailableListener(listener, handler)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.setOnImageAvailableListener(null, null)
        reader.close()
    }
}

internal class AndroidSensorRawImage(
    override val androidImage: Image,
    override val format: SensorRawFormat,
) : AndroidSensorRawImageSource {
    private val closed = AtomicBoolean(false)
    override val timestampNs: Long get() = androidImage.timestamp
    override val size = IntSize(androidImage.width, androidImage.height)

    override fun close() {
        if (closed.compareAndSet(false, true)) androidImage.close()
    }
}

internal class AndroidSensorRawResult(
    override val characteristics: CameraCharacteristics,
    override val totalCaptureResult: TotalCaptureResult,
    override val dngCaptureResult: CaptureResult,
) : AndroidSensorRawResultSource {
    override val sensorTimestampNs: Long =
        dngCaptureResult.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
}

internal class AndroidCameraOwnerPlatform(
    private val cameraManager: CameraManager,
    private val callbackHandler: Handler,
) : CameraOwnerPlatform {
    @SuppressLint("MissingPermission")
    override fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId.value)
        var delivered: CloseOnceCameraResource<CameraDeviceHandle>? = null
        fun deliveryFor(device: CameraDevice): CloseOnceCameraResource<CameraDeviceHandle> {
            delivered?.let { return it }
            return CloseOnceCameraResource<CameraDeviceHandle>(
                AndroidDeviceHandle(device, characteristics),
                CameraDeviceHandle::close,
            ).also { delivered = it }
        }
        cameraManager.openCamera(
            cameraId.value,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = callbacks.onOpened(deliveryFor(camera))
                override fun onDisconnected(camera: CameraDevice) = callbacks.onDisconnected(deliveryFor(camera))
                override fun onError(camera: CameraDevice, error: Int) =
                    callbacks.onError(deliveryFor(camera), error)
            },
            callbackHandler,
        )
    }

    override fun configurePreview(
        device: CameraDeviceHandle,
        surfaceToken: Any,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: CameraSessionCallbacks,
    ) = configurePreviewTargeted(
        device,
        surfaceToken,
        null,
        configuration,
        settings,
        attempt,
        callbacks,
    )

    override fun configurePreviewTargeted(
        device: CameraDeviceHandle,
        surfaceToken: Any,
        physicalCameraId: PhysicalCameraId?,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: CameraSessionCallbacks,
    ) {
        val handle = device as AndroidDeviceHandle
        cachePhysicalMetadataBeforeShutter(handle, physicalCameraId)
        val camera = handle.device
        val surface = surfaceToken as Surface
        val request = AndroidPreparedPreviewRequest(
            previewRequest(camera, surface, configuration, settings, attempt),
        )
        val stateCallback = sessionCallbacks(callbacks, request)
        createTargetedSession(camera, listOf(surface), physicalCameraId, stateCallback)
    }

    override fun startRepeating(
        session: CameraCaptureSessionHandle,
        request: PreparedPreviewRequest,
        onFrame: () -> Unit,
    ) {
        (session as AndroidSessionHandle).session.setRepeatingRequest(
            (request as AndroidPreparedPreviewRequest).request,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) = onFrame()
            },
            callbackHandler,
        )
    }

    override fun sensorRawMetadata(
        device: CameraDeviceHandle,
        physicalCameraId: PhysicalCameraId?,
        representation: SensorRawRepresentation,
    ): SensorRawMetadata? {
        if (representation.format != SensorRawFormat.RAW_SENSOR || !representation.dngWritable) return null
        val characteristics = (device as? AndroidDeviceHandle)
            ?.cachedCharacteristics(physicalCameraId) ?: return null
        val rawAdvertised = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        if (!rawAdvertised) return null
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            .orEmpty()
        if (sizes.none { it.width == representation.size.width && it.height == representation.size.height }) {
            return null
        }
        val basis = when (characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> SensorTimestampBasis.REALTIME
            else -> SensorTimestampBasis.UNKNOWN
        }
        return SensorRawMetadata(basis)
    }

    override fun createRawReader(
        context: RawCaptureContext,
        callbacks: RawImageCallbacks,
    ): RawImageReaderHandle {
        require(context.rawFormat == SensorRawFormat.RAW_SENSOR) {
            "CAMX-108 Android DNG path supports only RAW_SENSOR"
        }
        val reader = ImageReader.newInstance(
            context.rawSize.width,
            context.rawSize.height,
            ImageFormat.RAW_SENSOR,
            RAW_MAX_IMAGES,
        )
        return AndroidRawReaderHandle(reader).also { handle ->
            handle.setListener(
                ImageReader.OnImageAvailableListener { source ->
                    val image = try {
                        source.acquireNextImage()
                    } catch (_: RuntimeException) {
                        callbacks.onAcquisitionFailure()
                        return@OnImageAvailableListener
                    } ?: return@OnImageAvailableListener
                    val format = when (image.format) {
                        ImageFormat.RAW_SENSOR -> SensorRawFormat.RAW_SENSOR
                        else -> {
                            image.close()
                            callbacks.onAcquisitionFailure()
                            return@OnImageAvailableListener
                        }
                    }
                    val wrapped: SensorRawImage = AndroidSensorRawImage(image, format)
                    callbacks.onImage(CloseOnceCameraResource(wrapped, SensorRawImage::close))
                },
                callbackHandler,
            )
        }
    }

    override fun configureTemporaryRaw(
        device: CameraDeviceHandle,
        previewSurfaceToken: Any,
        physicalCameraId: PhysicalCameraId?,
        reader: RawImageReaderHandle,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: RawSessionCallbacks,
    ) {
        val handle = device as AndroidDeviceHandle
        val characteristics = checkNotNull(handle.cachedCharacteristics(physicalCameraId)) {
            "Exact RAW characteristics were not cached before shutter"
        }
        val camera = handle.device
        val previewSurface = previewSurfaceToken as Surface
        val rawSurface = reader.surfaceToken as Surface
        val previewRequest = AndroidPreparedPreviewRequest(
            previewRequest(
                camera,
                previewSurface,
                configuration,
                settings,
                attempt,
            ),
        )
        val rawBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        rawBuilder.addTarget(rawSurface)
        val rawRequest = AndroidPreparedRawRequest(
            rawBuilder.build(),
            physicalCameraId,
            characteristics,
        )
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            private var delivered: CloseOnceCameraResource<CameraCaptureSessionHandle>? = null
            fun delivery(session: CameraCaptureSession): CloseOnceCameraResource<CameraCaptureSessionHandle> {
                delivered?.let { return it }
                return CloseOnceCameraResource<CameraCaptureSessionHandle>(
                    AndroidSessionHandle(session),
                    CameraCaptureSessionHandle::close,
                ).also { delivered = it }
            }

            override fun onConfigured(session: CameraCaptureSession) {
                callbacks.onConfigured(delivery(session), previewRequest, rawRequest)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                callbacks.onConfigureFailed(delivery(session))
            }
        }
        createTargetedSession(
            camera,
            listOf(previewSurface, rawSurface),
            physicalCameraId,
            stateCallback,
        )
    }

    override fun captureOneRaw(
        session: CameraCaptureSessionHandle,
        request: PreparedRawRequest,
        callbacks: RawCaptureCallbacks,
    ) {
        val prepared = request as AndroidPreparedRawRequest
        (session as AndroidSessionHandle).session.capture(
            prepared.request,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val metadataResult = exactMetadataResult(result, prepared.physicalCameraId)
                        ?: return callbacks.onFailed()
                    callbacks.onCompleted(
                        AndroidSensorRawResult(
                            prepared.characteristics,
                            result,
                            metadataResult,
                        ),
                    )
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) = callbacks.onFailed()
            },
            callbackHandler,
        )
    }

    @Suppress("DEPRECATION")
    private fun exactMetadataResult(
        total: TotalCaptureResult,
        physicalCameraId: PhysicalCameraId?,
    ): CaptureResult? {
        if (physicalCameraId == null) return total
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return total.physicalCameraResults[physicalCameraId.value]
    }

    private fun cachePhysicalMetadataBeforeShutter(
        handle: AndroidDeviceHandle,
        physicalCameraId: PhysicalCameraId?,
    ) {
        if (physicalCameraId == null || handle.cachedCharacteristics(physicalCameraId) != null) return
        val characteristics = try {
            cameraManager.getCameraCharacteristics(physicalCameraId.value)
        } catch (_: Exception) {
            // Preview targeting remains valid even when exact physical DNG metadata is unavailable.
            // RAW admission will report unsupported for this exact profile instead of regressing preview.
            return
        }
        handle.cachePhysical(physicalCameraId, characteristics)
    }

    private fun previewRequest(
        camera: CameraDevice,
        surface: Surface,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
    ): CaptureRequest {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        if (attempt == PreviewConfigurationAttemptKind.REQUESTED && settings.fpsRequest.overrideEnabled) {
            configuration.fps.resolvedRange?.let { range ->
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(range.minimum, range.maximum))
            }
        }
        return builder.build()
    }

    private fun sessionCallbacks(
        callbacks: CameraSessionCallbacks,
        request: PreparedPreviewRequest,
    ) = object : CameraCaptureSession.StateCallback() {
        private var delivered: CloseOnceCameraResource<CameraCaptureSessionHandle>? = null
        fun delivery(session: CameraCaptureSession): CloseOnceCameraResource<CameraCaptureSessionHandle> {
            delivered?.let { return it }
            return CloseOnceCameraResource<CameraCaptureSessionHandle>(
                AndroidSessionHandle(session),
                CameraCaptureSessionHandle::close,
            ).also { delivered = it }
        }

        override fun onConfigured(session: CameraCaptureSession) {
            callbacks.onConfigured(delivery(session), request)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            callbacks.onConfigureFailed(delivery(session))
        }
    }

    @Suppress("DEPRECATION")
    private fun createTargetedSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        physicalCameraId: PhysicalCameraId?,
        callbacks: CameraCaptureSession.StateCallback,
    ) {
        if (physicalCameraId == null) {
            camera.createCaptureSession(surfaces, callbacks, callbackHandler)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw UnsupportedOperationException("Physical output requires Android API 28+")
            }
            createPhysicalSession(camera, surfaces, physicalCameraId, callbacks)
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    @Suppress("DEPRECATION")
    private fun createPhysicalSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        physicalCameraId: PhysicalCameraId,
        callbacks: CameraCaptureSession.StateCallback,
    ) {
        val outputs = surfaces.map { surface ->
            OutputConfiguration(surface).apply { setPhysicalCameraId(physicalCameraId.value) }
        }
        camera.createCaptureSessionByOutputConfigurations(outputs, callbacks, callbackHandler)
    }

    private companion object {
        const val RAW_MAX_IMAGES = 2
    }
}
