package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.preview.PreviewPolicyInput
import com.sahidcode404.camx.core.camera.preview.PreviewPolicyResult
import com.sahidcode404.camx.core.camera.preview.PreviewStreamPolicy
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface VisiblePreviewProblem {
    data object NoCredibleSeed : VisiblePreviewProblem
    data class Capability(val reason: SelectedSeedCapabilityFailure) : VisiblePreviewProblem
    data class Policy(val result: PreviewPolicyResult.Unsupported) : VisiblePreviewProblem
    data class Controller(val failure: CameraFailure) : VisiblePreviewProblem
    data class Startup(val kind: VisiblePreviewStartupFailure) : VisiblePreviewProblem
}

enum class VisiblePreviewStartupFailure {
    SEED_DISCOVERY_FAILED,
    PREVIEW_START_FAILED,
}

data class VisiblePreviewRenderSpec(
    val bufferSize: IntSize,
    val geometry: PreviewGeometry,
)

sealed interface VisiblePreviewUiState {
    data object WaitingForPermission : VisiblePreviewUiState
    data object Starting : VisiblePreviewUiState
    data object WaitingForSurface : VisiblePreviewUiState
    data class Opening(val render: VisiblePreviewRenderSpec) : VisiblePreviewUiState
    data class Previewing(
        val render: VisiblePreviewRenderSpec,
        val firstFrameVerified: Boolean,
    ) : VisiblePreviewUiState
    data class Unavailable(val problem: VisiblePreviewProblem) : VisiblePreviewUiState
    data class Error(val problem: VisiblePreviewProblem) : VisiblePreviewUiState
}

internal fun interface VisiblePreviewSeedSource {
    suspend fun discoverSeed(): CameraRoute?
}

internal fun interface VisiblePreviewPolicyPort {
    fun resolve(input: PreviewPolicyInput): PreviewPolicyResult
}

internal interface VisiblePreviewLease : AutoCloseable {
    val identity: PreviewSurfaceIdentity
    val viewSize: IntSize
    val bufferSize: IntSize
}

internal interface VisiblePreviewSurfacePort {
    suspend fun awaitSurface(): VisiblePreviewLease
    suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize)
}

internal interface VisiblePreviewSessionPort {
    val state: StateFlow<CameraEngineState>

    suspend fun startPreview(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        lease: VisiblePreviewLease,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    )

    suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity)
    suspend fun pause()
    suspend fun shutdown()
}

/**
 * Low-frequency production startup coordinator. Camera2 resources remain owned exclusively by
 * CameraSessionController; this class owns orchestration, not devices/sessions.
 */
class VisiblePreviewCoordinator internal constructor(
    private val seedSource: VisiblePreviewSeedSource,
    private val capabilitySource: SelectedSeedPreviewCapabilitySource,
    private val surfacePort: VisiblePreviewSurfacePort,
    private val session: VisiblePreviewSessionPort,
    private val settings: () -> SettingsSnapshot = { SettingsSnapshot() },
    private val mirrorFrontPreview: () -> Boolean = { true },
    private val policy: VisiblePreviewPolicyPort = VisiblePreviewPolicyPort(PreviewStreamPolicy::resolve),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val shutdownRequested = AtomicBoolean(false)
    private val mutableUiState = MutableStateFlow<VisiblePreviewUiState>(
        VisiblePreviewUiState.WaitingForPermission,
    )
    private val mutableRenderSpec = MutableStateFlow<VisiblePreviewRenderSpec?>(null)

    private var permissionGranted = false
    private var resumed = false
    private var displayRotation = DisplayRotation.ROTATION_0
    private var startupGeneration = 0L
    private var startupJob: Job? = null

    val uiState: StateFlow<VisiblePreviewUiState> = mutableUiState.asStateFlow()
    val renderSpec: StateFlow<VisiblePreviewRenderSpec?> = mutableRenderSpec.asStateFlow()

    init {
        scope.launch {
            session.state.collect(::projectControllerState)
        }
    }

    fun setPermission(granted: Boolean) {
        if (shutdownRequested.get()) return
        scope.launch {
            if (permissionGranted == granted) return@launch
            permissionGranted = granted
            if (!granted) {
                invalidateStartup()
                mutableRenderSpec.value = null
                session.pause()
                mutableUiState.value = VisiblePreviewUiState.WaitingForPermission
            } else if (resumed) {
                beginStartup()
            }
        }
    }

    fun resume(rotation: DisplayRotation) {
        if (shutdownRequested.get()) return
        scope.launch {
            val rotationChanged = displayRotation != rotation
            displayRotation = rotation
            val wasResumed = resumed
            resumed = true
            if (!permissionGranted) {
                mutableUiState.value = VisiblePreviewUiState.WaitingForPermission
                return@launch
            }
            if (!wasResumed || rotationChanged || startupJob == null) beginStartup()
        }
    }

    fun updateDisplayRotation(rotation: DisplayRotation) {
        if (shutdownRequested.get()) return
        scope.launch {
            if (displayRotation == rotation) return@launch
            displayRotation = rotation
            if (permissionGranted && resumed) {
                invalidateStartup()
                mutableRenderSpec.value = null
                session.pause()
                beginStartup()
            }
        }
    }

    fun pause() {
        if (shutdownRequested.get()) return
        scope.launch {
            resumed = false
            invalidateStartup()
            mutableRenderSpec.value = null
            session.pause()
            mutableUiState.value = if (permissionGranted) {
                VisiblePreviewUiState.WaitingForSurface
            } else {
                VisiblePreviewUiState.WaitingForPermission
            }
        }
    }

    fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
        if (shutdownRequested.get()) return
        scope.launch {
            invalidateStartup()
            mutableRenderSpec.value = null
            session.surfaceInvalidated(identity)
            if (permissionGranted && resumed) beginStartup()
        }
    }

    fun requestShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        scope.launch {
            invalidateStartup()
            mutableRenderSpec.value = null
            try {
                session.shutdown()
            } finally {
                scope.cancel()
            }
        }
    }

    internal suspend fun shutdownForTest() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        invalidateStartup()
        mutableRenderSpec.value = null
        try {
            session.shutdown()
        } finally {
            scope.cancel()
        }
    }

    private fun beginStartup() {
        if (!permissionGranted || !resumed || shutdownRequested.get()) return
        invalidateStartup()
        val generation = startupGeneration
        startupJob = scope.launch {
            runStartup(generation)
        }
    }

    private suspend fun runStartup(generation: Long) {
        mutableUiState.value = VisiblePreviewUiState.Starting
        val route = try {
            seedSource.discoverSeed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (isCurrent(generation)) {
                mutableUiState.value = VisiblePreviewUiState.Error(
                    VisiblePreviewProblem.Startup(VisiblePreviewStartupFailure.SEED_DISCOVERY_FAILED),
                )
            }
            return
        }
        if (!isCurrent(generation)) return
        if (route == null) {
            mutableUiState.value = VisiblePreviewUiState.Unavailable(VisiblePreviewProblem.NoCredibleSeed)
            return
        }

        val capabilityResult = capabilitySource.read(route)
        if (!isCurrent(generation)) return
        val selectedCapabilities = when (capabilityResult) {
            is SelectedSeedCapabilityResult.Available -> capabilityResult.value
            is SelectedSeedCapabilityResult.Unavailable -> {
                mutableUiState.value = VisiblePreviewUiState.Unavailable(
                    VisiblePreviewProblem.Capability(capabilityResult.reason),
                )
                return
            }
        }
        val selection = bootstrapSelection(route, selectedCapabilities)
        mutableUiState.value = VisiblePreviewUiState.WaitingForSurface

        var lease: VisiblePreviewLease? = null
        var handedToController = false
        try {
            lease = surfacePort.awaitSurface()
            if (!isCurrent(generation)) return
            val settingsSnapshot = settings()
            val policyResult = policy.resolve(
                PreviewPolicyInput(
                    capabilities = selectedCapabilities.capabilities,
                    viewSize = lease.viewSize,
                    sensorOrientationDegrees = selectedCapabilities.sensorOrientationDegrees,
                    displayRotation = displayRotation,
                    lensFacing = selectedCapabilities.lensFacing,
                    mirrorFrontPreview = mirrorFrontPreview(),
                    requestedStreamType = settingsSnapshot.previewStreamType,
                    highResolutionViewfinder = settingsSnapshot.highResolutionViewfinder,
                    fpsRequest = settingsSnapshot.fpsRequest,
                ),
            )
            if (!isCurrent(generation)) return
            val supported = when (policyResult) {
                is PreviewPolicyResult.Supported -> policyResult
                is PreviewPolicyResult.Unsupported -> {
                    mutableUiState.value = VisiblePreviewUiState.Unavailable(
                        VisiblePreviewProblem.Policy(policyResult),
                    )
                    return
                }
            }
            val render = VisiblePreviewRenderSpec(
                bufferSize = supported.configuration.size,
                geometry = supported.geometry,
            )
            mutableRenderSpec.value = render
            mutableUiState.value = VisiblePreviewUiState.Opening(render)
            surfacePort.awaitBufferSize(lease.identity, supported.configuration.size)
            if (!isCurrent(generation)) return
            try {
                session.startPreview(
                    selection = selection,
                    route = route,
                    lease = lease,
                    configuration = supported.configuration,
                    settings = settingsSnapshot,
                )
                handedToController = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrent(generation)) {
                    mutableUiState.value = VisiblePreviewUiState.Error(
                        VisiblePreviewProblem.Startup(VisiblePreviewStartupFailure.PREVIEW_START_FAILED),
                    )
                }
            }
        } finally {
            if (!handedToController) lease?.close()
        }
    }

    private fun invalidateStartup() {
        check(startupGeneration < Long.MAX_VALUE) { "Visible preview startup generation exhausted" }
        startupGeneration += 1L
        startupJob?.cancel()
        startupJob = null
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == startupGeneration && permissionGranted && resumed && !shutdownRequested.get()

    private fun projectControllerState(state: CameraEngineState) {
        if (!permissionGranted || !resumed || shutdownRequested.get()) return
        val render = mutableRenderSpec.value
        when (state) {
            is CameraEngineState.Opening,
            is CameraEngineState.ConfiguringPreview,
            is CameraEngineState.Switching,
            -> if (render != null) mutableUiState.value = VisiblePreviewUiState.Opening(render)
            is CameraEngineState.Previewing -> if (render != null) {
                mutableUiState.value = VisiblePreviewUiState.Previewing(
                    render = render,
                    firstFrameVerified = state.firstFrameVerified,
                )
            }
            is CameraEngineState.RecoverableError -> {
                mutableUiState.value = VisiblePreviewUiState.Error(
                    VisiblePreviewProblem.Controller(state.failure),
                )
            }
            is CameraEngineState.StructuralError -> {
                mutableUiState.value = VisiblePreviewUiState.Error(
                    VisiblePreviewProblem.Controller(state.failure),
                )
            }
            else -> Unit
        }
    }

    private companion object {
        fun bootstrapSelection(
            route: CameraRoute,
            capabilities: SelectedSeedPreviewCapabilities,
        ): ActiveCameraSelection {
            val evidenceKey = buildString {
                append("route=").append(route.id.value)
                append(";source=").append(route.source.name)
                append(";orientation=").append(capabilities.sensorOrientationDegrees)
                append(";facing=").append(capabilities.lensFacing.name)
                capabilities.capabilities.previewStreams
                    .sortedWith(compareBy({ it.type.ordinal }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs ?: Long.MAX_VALUE }))
                    .forEach {
                        append(";stream=").append(it.type.name)
                            .append(':').append(it.size.width).append('x').append(it.size.height)
                            .append(':').append(it.minimumFrameDurationNs ?: -1L)
                    }
                capabilities.capabilities.fpsRanges
                    .sortedWith(compareBy({ it.minimum }, { it.maximum }))
                    .forEach { append(";fps=").append(it.minimum).append('-').append(it.maximum) }
            }
            val routeKey = digestHex("bootstrap-canonical|${route.id.value}", 16)
            val profileKey = digestHex("bootstrap-profile|$evidenceKey", 16)
            return ActiveCameraSelection(
                canonicalLensFingerprint = CanonicalLensFingerprint("bootstrap:$routeKey"),
                profileFingerprint = CameraProfileFingerprint("bootstrap:$profileKey"),
                routeId = route.id,
                selectionGeneration = SelectionGeneration(0L),
                sessionGeneration = SessionGeneration(0L),
            )
        }

        fun digestHex(value: String, bytes: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            val alphabet = "0123456789abcdef"
            val out = CharArray(bytes * 2)
            var cursor = 0
            for (index in 0 until bytes) {
                val byte = digest[index].toInt() and 0xff
                out[cursor++] = alphabet[byte ushr 4]
                out[cursor++] = alphabet[byte and 0x0f]
            }
            return String(out)
        }
    }
}
