package com.sahidcode404.camx.core.camera.bootstrap

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import com.sahidcode404.camx.core.camera.cache.AtomicCameraCachePersistence
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledgeRepository
import com.sahidcode404.camx.core.camera.discovery.AndroidAdvertisedCameraEvidenceBackend
import com.sahidcode404.camx.core.camera.discovery.AndroidFirstInstallSeedDiscovery
import com.sahidcode404.camx.core.camera.discovery.DiscoveryDepth
import com.sahidcode404.camx.core.camera.discovery.DiscoveryMetadataBudget
import com.sahidcode404.camx.core.camera.discovery.JavaDeepControlCertifier
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedCameraEvidenceBackend
import com.sahidcode404.camx.core.camera.discovery.NdkDeepAuxDiscoveryBackend
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.preview.GenerationSafePreviewSurfaceProvider
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceLease
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.camera.session.CameraSessionController
import com.sahidcode404.camx.core.camera.topology.CameraTopologyRepository
import com.sahidcode404.camx.core.camera.topology.JavaDeepCertificationSource
import com.sahidcode404.camx.core.camera.topology.JavaLevel2EvidenceSource
import com.sahidcode404.camx.core.camera.topology.NdkDeepEvidenceSource
import com.sahidcode404.camx.core.camera.topology.NdkLevel2EvidenceSource
import com.sahidcode404.camx.core.camera.topology.PostFirstFrameAuxDiscoveryOrchestrator
import com.sahidcode404.camx.core.camera.topology.PostFirstFrameTopologyReconciler
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Lifecycle-scoped production graph. CameraSessionController remains the sole Camera2 resource owner. */
class VisiblePreviewGraph(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val environment = runtimeEnvironmentFingerprint()
    private val controller = CameraSessionController(cameraManager)
    private val seedDiscovery = AndroidFirstInstallSeedDiscovery(
        cameraManager = cameraManager,
        environment = environment,
    )
    private val metadataBudget = DiscoveryMetadataBudget()
    private val javaAdvertisedDiscovery = AndroidAdvertisedCameraEvidenceBackend(
        cameraManager = cameraManager,
        environment = environment,
        metadataBudget = metadataBudget,
    )
    private val ndkAdvertisedDiscovery = NdkAdvertisedCameraEvidenceBackend(environment)
    private val ndkDeepDiscovery = NdkDeepAuxDiscoveryBackend(
        environment = environment,
        metadataBudget = metadataBudget,
    )
    private val javaDeepCertifier = JavaDeepControlCertifier(
        cameraManager = cameraManager,
        environment = environment,
        metadataBudget = metadataBudget,
    )
    private val cachePersistence = AtomicCameraCachePersistence(
        File(appContext.filesDir, "camera-cache"),
    )
    private val deepKnowledgeRepository = DeepDiscoveryKnowledgeRepository(cachePersistence)
    private val surfaceBridge = AndroidVisiblePreviewSurfaceBridge()
    private val topologySignalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val topologyRepository = CameraTopologyRepository()

    private val auxDiscoveryOrchestrator = PostFirstFrameAuxDiscoveryOrchestrator(
        environment = environment,
        javaLevel2 = JavaLevel2EvidenceSource { emit ->
            javaAdvertisedDiscovery.discoverIncrementally(DiscoveryDepth.ADVERTISED, emit)
        },
        ndkLevel2 = NdkLevel2EvidenceSource {
            metadataBudget.withNativeMetadata {
                ndkAdvertisedDiscovery.discoverReport(DiscoveryDepth.ADVERTISED)
            }
        },
        ndkDeep = NdkDeepEvidenceSource { request, emit ->
            ndkDeepDiscovery.discoverIncrementally(request, emit)
        },
        javaDeep = JavaDeepCertificationSource { outcomes, existingJavaEvidence, emit ->
            javaDeepCertifier.certifyIncrementally(outcomes, existingJavaEvidence, emit)
        },
        deepKnowledge = deepKnowledgeRepository,
    )

    private val topologyReconciler = PostFirstFrameTopologyReconciler(
        environment = environment,
        repository = topologyRepository,
        providers = listOf(auxDiscoveryOrchestrator),
    )

    val coordinator = VisiblePreviewCoordinator(
        seedSource = VisiblePreviewSeedSource { seedDiscovery.discover().route },
        capabilitySource = AndroidSelectedSeedPreviewCapabilityReader(cameraManager),
        surfacePort = surfaceBridge,
        session = AndroidVisiblePreviewSessionPort(controller),
        topology = topologyRepository.topology,
        runtimeApiLevel = Build.VERSION.SDK_INT,
        settings = { SettingsSnapshot() },
    )

    init {
        // Only a verified first frame arms Level-2/Level-4 discovery. Metadata work is independent of
        // the camera dispatcher and each topology improvement only refreshes lens availability.
        topologySignalScope.launch {
            coordinator.uiState.collect { state ->
                if (state is VisiblePreviewUiState.Previewing && state.firstFrameVerified) {
                    topologyReconciler.startAfterFirstFrame()
                }
            }
        }
        // A JAVA_DEEP_PROBED route becomes session-verified history only after the sole controller
        // reports the exact first frame for the user's real selection.
        topologySignalScope.launch {
            controller.state.collect { state ->
                val previewing = state as? CameraEngineState.Previewing ?: return@collect
                if (!previewing.firstFrameVerified) return@collect
                val topology = topologyRepository.topology.value ?: return@collect
                val route = topology.routes.firstOrNull { it.id == previewing.selection.routeId } ?: return@collect
                if (CameraRouteSource.JAVA_DEEP_PROBED !in route.sources) return@collect
                deepKnowledgeRepository.markSessionVerified(environment, route.openCameraId.value)
            }
        }
    }

    fun publishSurface(binding: PreviewSurfaceBinding) {
        when (val change = surfaceBridge.publish(binding)) {
            SurfacePublication.Unchanged -> Unit
            is SurfacePublication.Replaced -> coordinator.surfaceInvalidated(change.previousIdentity)
            is SurfacePublication.ViewportChanged -> coordinator.surfaceInvalidated(change.identity)
        }
    }

    fun surfaceDestroyed(identity: PreviewSurfaceIdentity) {
        if (surfaceBridge.invalidate(identity)) coordinator.surfaceInvalidated(identity)
    }

    override fun close() {
        topologySignalScope.cancel()
        topologyReconciler.close()
        surfaceBridge.close()
        coordinator.requestShutdown()
    }

    private fun runtimeEnvironmentFingerprint(): CameraEnvironmentFingerprint {
        val fingerprint = Build.FINGERPRINT.takeIf(String::isNotBlank) ?: "unavailable"
        return CameraEnvironmentFingerprint("android-api${Build.VERSION.SDK_INT}:$fingerprint")
    }
}

internal sealed interface SurfacePublication {
    data object Unchanged : SurfacePublication
    data class Replaced(val previousIdentity: PreviewSurfaceIdentity) : SurfacePublication
    data class ViewportChanged(val identity: PreviewSurfaceIdentity) : SurfacePublication
}

internal class AndroidVisiblePreviewSurfaceBridge : VisiblePreviewSurfacePort, AutoCloseable {
    private val provider = GenerationSafePreviewSurfaceProvider()
    private val currentBinding = MutableStateFlow<PreviewSurfaceBinding?>(null)

    fun publish(binding: PreviewSurfaceBinding): SurfacePublication {
        val previous = currentBinding.value
        provider.publish(binding)
        currentBinding.value = binding
        return when {
            previous == null -> SurfacePublication.Unchanged
            previous.identity != binding.identity -> SurfacePublication.Replaced(previous.identity)
            previous.viewSize != binding.viewSize -> SurfacePublication.ViewportChanged(binding.identity)
            else -> SurfacePublication.Unchanged
        }
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
