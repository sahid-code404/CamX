package com.sahidcode404.camx.core.camera.bootstrap

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import com.sahidcode404.camx.core.camera.discovery.AndroidFirstInstallSeedDiscovery
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.preview.GenerationSafePreviewSurfaceProvider
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceLease
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.camera.session.CameraSessionController
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Lifecycle-scoped production graph. CameraSessionController remains the sole Camera2 resource owner. */
class VisiblePreviewGraph(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val controller = CameraSessionController(cameraManager)
    private val seedDiscovery = AndroidFirstInstallSeedDiscovery(
        cameraManager = cameraManager,
        environment = runtimeEnvironmentFingerprint(),
    )
    private val surfaceBridge = AndroidVisiblePreviewSurfaceBridge()

    val coordinator = VisiblePreviewCoordinator(
        seedSource = VisiblePreviewSeedSource { seedDiscovery.discover().route },
        capabilitySource = AndroidSelectedSeedPreviewCapabilityReader(cameraManager),
        surfacePort = surfaceBridge,
        session = AndroidVisiblePreviewSessionPort(controller),
        settings = { SettingsSnapshot() },
    )

    fun publishSurface(binding: PreviewSurfaceBinding) {
        val replaced = surfaceBridge.publish(binding)
        if (replaced != null) coordinator.surfaceInvalidated(replaced)
    }

    fun surfaceDestroyed(identity: PreviewSurfaceIdentity) {
        if (surfaceBridge.invalidate(identity)) coordinator.surfaceInvalidated(identity)
    }

    override fun close() {
        surfaceBridge.close()
        coordinator.requestShutdown()
    }

    private fun runtimeEnvironmentFingerprint(): CameraEnvironmentFingerprint {
        val fingerprint = Build.FINGERPRINT.takeIf(String::isNotBlank) ?: "unavailable"
        return CameraEnvironmentFingerprint("android-api${Build.VERSION.SDK_INT}:$fingerprint")
    }
}

internal class AndroidVisiblePreviewSurfaceBridge : VisiblePreviewSurfacePort, AutoCloseable {
    private val provider = GenerationSafePreviewSurfaceProvider()
    private val currentBinding = MutableStateFlow<PreviewSurfaceBinding?>(null)

    /** Returns the replaced identity, if this publication replaced a different current Surface. */
    fun publish(binding: PreviewSurfaceBinding): PreviewSurfaceIdentity? {
        val previous = currentBinding.value?.identity
        provider.publish(binding)
        currentBinding.value = binding
        return previous?.takeIf { it != binding.identity }
    }

    /** Returns true only when the destroyed identity was current. */
    fun invalidate(identity: PreviewSurfaceIdentity): Boolean {
        val current = currentBinding.value ?: return false
        if (current.identity != identity) return false
        currentBinding.value = null
        provider.invalidate(identity)
        return true
    }

    override suspend fun awaitSurface(): VisiblePreviewLease =
        AndroidVisiblePreviewLease(provider.awaitSurface())

    override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) {
        val current = currentBinding.value
        if (current != null && current.identity == identity && current.bufferSize == size) return
        currentBinding.filterNotNull().first { binding ->
            binding.identity == identity && binding.bufferSize == size
        }
    }

    override fun close() {
        currentBinding.value = null
        provider.close()
    }
}

private class AndroidVisiblePreviewLease(
    internal val delegate: PreviewSurfaceLease,
) : VisiblePreviewLease {
    override val identity: PreviewSurfaceIdentity
        get() = delegate.binding.identity
    override val viewSize: IntSize
        get() = delegate.binding.viewSize
    override val bufferSize: IntSize
        get() = delegate.binding.bufferSize

    override fun close() {
        delegate.close()
    }
}

private class AndroidVisiblePreviewSessionPort(
    private val controller: CameraSessionController,
) : VisiblePreviewSessionPort {
    override val state: StateFlow<CameraEngineState> = controller.state

    override suspend fun startPreview(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        lease: VisiblePreviewLease,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) {
        val androidLease = requireNotNull(lease as? AndroidVisiblePreviewLease) {
            "Production visible preview requires an Android PreviewSurfaceLease"
        }
        controller.startPreview(
            selection = selection,
            route = route,
            surfaceLease = androidLease.delegate,
            configuration = configuration,
            settings = settings,
        )
    }

    override suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
        controller.surfaceInvalidated(identity)
    }

    override suspend fun pause() {
        controller.pause()
    }

    override suspend fun shutdown() {
        controller.shutdown()
    }
}
