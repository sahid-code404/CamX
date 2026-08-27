package com.sahidcode404.camx.core.camera.session

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.sahidcode404.camx.core.camera.diagnostics.CameraDeviceError
import com.sahidcode404.camx.core.camera.diagnostics.CameraDisabled
import com.sahidcode404.camx.core.camera.diagnostics.CameraDisconnected
import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.diagnostics.CameraInUse
import com.sahidcode404.camx.core.camera.diagnostics.MaximumCamerasInUse
import com.sahidcode404.camx.core.camera.diagnostics.PermissionDenied
import com.sahidcode404.camx.core.camera.diagnostics.RawUnsupported
import com.sahidcode404.camx.core.camera.diagnostics.RequestedConfigurationKind
import com.sahidcode404.camx.core.camera.diagnostics.RequestedConfigurationRejected
import com.sahidcode404.camx.core.camera.diagnostics.SafeBaselineConfigurationRejected
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraResourceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraStartupMilestone
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.RawContractLimits
import com.sahidcode404.camx.core.camera.raw.AndroidSensorDngWriter
import com.sahidcode404.camx.core.camera.raw.OneShotRawAcquisition
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.camera.raw.RawCaptureUiState
import com.sahidcode404.camx.core.camera.raw.RawPublicationPermit
import com.sahidcode404.camx.core.camera.raw.SensorDngWriter
import com.sahidcode404.camx.core.camera.raw.SensorRawCaptureResult
import com.sahidcode404.camx.core.camera.raw.SensorRawImage
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceLease
import com.sahidcode404.camx.core.camera.runtime.CameraGenerationGate
import com.sahidcode404.camx.core.camera.trace.BoundedCameraStartupTrace
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sole Camera2 device/session/repeating-preview owner. */
class CameraSessionController private constructor(
    internal val runtime: ControllerRuntime,
    internal val elapsedRealtimeNs: () -> Long,
    internal val rawTimeoutMillis: Long,
) {
    private val callbackDispatcher: CoroutineDispatcher = runtime.dispatcher
    private val mutationGate = CameraStateMutationGate(callbackDispatcher)
    private val asyncOwnership = CameraAsyncOwnership()
    internal val rawMutationGate: CameraStateMutationGate get() = mutationGate
    internal val rawAsyncOwnership: CameraAsyncOwnership get() = asyncOwnership
    internal val generations = CameraGenerationGate()
    internal val callbackScope = CoroutineScope(SupervisorJob() + callbackDispatcher)
    internal val shutdownRequested = AtomicBoolean(false)
    private val shutdownComplete = CompletableDeferred<Unit>()
    private val trace = BoundedCameraStartupTrace()
    internal val mutableState = MutableStateFlow<CameraEngineState>(
        CameraEngineState.WaitingForSurface(selection = null),
    )
    internal val mutableResources = MutableStateFlow(
        CameraResourceSnapshot(cameraWorkers = runtime.workerCount),
    )
    internal val mutableRawCaptureState = MutableStateFlow<RawCaptureUiState>(RawCaptureUiState.Unavailable)
    internal val mutableLastRawOutcome = MutableStateFlow<RawCaptureOutcome?>(null)

    internal var activeSurface: ActiveSurface? = null
    internal var activeDevice: ActiveDevice? = null
    internal var activeSession: ActiveSession? = null
    internal var currentPreview: PreviewIntent? = null
    internal var activeRaw: ActiveRawTransaction? = null
    internal val structurallyRejectedRawProfiles = LinkedHashSet<CameraProfileFingerprint>()

    constructor(cameraManager: CameraManager) : this(
        runtime = createAndroidRuntime(cameraManager, unavailableDngWriter()),
        elapsedRealtimeNs = SystemClock::elapsedRealtimeNanos,
        rawTimeoutMillis = RawContractLimits.DEFAULT_TIMEOUT_MILLIS,
    )

    constructor(context: Context, cameraManager: CameraManager) : this(
        runtime = createAndroidRuntime(cameraManager, AndroidSensorDngWriter(context)),
        elapsedRealtimeNs = SystemClock::elapsedRealtimeNanos,
        rawTimeoutMillis = RawContractLimits.DEFAULT_TIMEOUT_MILLIS,
    )

    internal constructor(
        platform: CameraOwnerPlatform,
        dispatcher: CoroutineDispatcher,
        elapsedRealtimeNs: () -> Long = { 0L },
        shutdownWorker: suspend () -> Unit = {},
        workerCount: Int = 1,
        sensorDngWriter: SensorDngWriter = unavailableDngWriter(),
        rawTimeoutMillis: Long = RawContractLimits.DEFAULT_TIMEOUT_MILLIS,
    ) : this(
        ControllerRuntime(platform, sensorDngWriter, dispatcher, shutdownWorker, workerCount),
        elapsedRealtimeNs,
        rawTimeoutMillis,
    )

    init {
        require(rawTimeoutMillis in RawContractLimits.MINIMUM_TIMEOUT_MILLIS..
            RawContractLimits.MAXIMUM_TIMEOUT_MILLIS
        ) { "RAW timeout is outside the shared contract" }
    }

    val state: StateFlow<CameraEngineState> = mutableState.asStateFlow()
    val resources: StateFlow<CameraResourceSnapshot> = mutableResources.asStateFlow()
    val rawCaptureState: StateFlow<RawCaptureUiState> = mutableRawCaptureState.asStateFlow()
    val lastRawOutcome: StateFlow<RawCaptureOutcome?> = mutableLastRawOutcome.asStateFlow()

    fun traceSnapshot() = trace.snapshot()

    suspend fun select(selection: ActiveCameraSelection) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        var cleanup: CameraCleanupPlan? = null
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            val current = mutableState.value
            CameraStateTransitions.requirePhaseAllowed(current, CameraEnginePhase.SWITCHING)
            val next = generations.advanceSelection()
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            currentPreview = null
            val effective = selection.copy(
                selectionGeneration = next.selection,
                sessionGeneration = next.session,
            )
            transition(CameraEngineState.Switching(current.selectionOrNull()?.routeId, effective))
            transition(CameraEngineState.WaitingForSurface(effective))
        }
        closePlan(cleanup)
    }

    suspend fun startPreview(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        surfaceLease: PreviewSurfaceLease,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) {
        val binding = surfaceLease.binding
        startPreviewInternal(
            selection,
            route,
            SurfaceInput(binding.identity, binding.surface, surfaceLease::close),
            configuration,
            settings,
        )
    }

    internal suspend fun startPreviewForTest(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        surfaceIdentity: PreviewSurfaceIdentity,
        surfaceToken: Any,
        closeSurface: () -> Unit,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) = startPreviewInternal(
        selection,
        route,
        SurfaceInput(surfaceIdentity, surfaceToken, closeSurface),
        configuration,
        settings,
    )

    suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        var cleanup: CameraCleanupPlan? = null
        var permit: PendingCameraOperationPermit? = null
        mutationGate.mutate {
            val surface = activeSurface
            if (surface == null || surface.identity != identity || mutableState.value == CameraEngineState.Closed) {
                return@mutate
            }
            val previous = currentPreview
            val next = generations.advanceSession()
            val selection = mutableState.value.selectionOrNull()?.copy(sessionGeneration = next.session)
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            currentPreview = null
            transition(CameraEngineState.Pausing(selection))
            if (selection != null && previous != null) {
                asyncOwnership.publishIntent(
                    CameraOperationIdentity(selection, identity, previous.attempt),
                )
                permit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
            } else {
                transition(CameraEngineState.WaitingForSurface(selection))
            }
        }
        closePlan(cleanup)
        completePauseCleanup(permit)
    }

    suspend fun pause() {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        var cleanup: CameraCleanupPlan? = null
        var permit: PendingCameraOperationPermit? = null
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            if (mutableState.value == CameraEngineState.Closed) return@mutate
            val previousSurface = activeSurface
            val previousPreview = currentPreview
            val next = generations.advanceSession()
            val selection = mutableState.value.selectionOrNull()?.copy(sessionGeneration = next.session)
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            currentPreview = null
            transition(CameraEngineState.Pausing(selection))
            if (selection != null && previousSurface != null && previousPreview != null) {
                asyncOwnership.publishIntent(
                    CameraOperationIdentity(selection, previousSurface.identity, previousPreview.attempt),
                )
                permit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
            } else {
                transition(CameraEngineState.WaitingForSurface(selection))
            }
        }
        closePlan(cleanup)
        completePauseCleanup(permit)
    }

    suspend fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            withContext(NonCancellable) { shutdownComplete.await() }
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            var cleanup: CameraCleanupPlan? = null
            try {
                mutationGate.mutate {
                    if (mutableState.value != CameraEngineState.Closed) {
                        val next = generations.advanceSession()
                        val selection = mutableState.value.selectionOrNull()?.copy(sessionGeneration = next.session)
                        asyncOwnership.shutdown()
                        cleanup = detachAllLocked()
                        currentPreview = null
                        if (mutableState.value !is CameraEngineState.Pausing) {
                            transition(CameraEngineState.Pausing(selection))
                        }
                    }
                }
                closePlan(cleanup)
                mutationGate.mutate {
                    if (mutableState.value != CameraEngineState.Closed) transition(CameraEngineState.Closed)
                    mutableResources.value = CameraResourceSnapshot()
                }
            } catch (error: Throwable) {
                failure = error
            }
            callbackScope.cancel()
            try {
                runtime.shutdownWorker()
            } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            } finally {
                val terminal = failure
                if (terminal == null) shutdownComplete.complete(Unit)
                else shutdownComplete.completeExceptionally(terminal)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun startPreviewInternal(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        surface: SurfaceInput,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        require(route.id == selection.routeId) { "Active selection route must match CameraRoute" }
        var cleanup: CameraCleanupPlan? = null
        var switch: SwitchCommand? = null
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            val current = mutableState.value
            CameraStateTransitions.requirePhaseAllowed(current, CameraEnginePhase.SWITCHING)
            val next = generations.advanceSelection()
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            val effective = selection.copy(
                selectionGeneration = next.selection,
                sessionGeneration = next.session,
            )
            val active = ActiveSurface(
                surface.identity,
                surface.token,
                CameraResourceCleanup(surface.close),
            )
            activeSurface = active
            val preview = PreviewIntent(
                effective,
                route,
                active,
                configuration,
                settings,
                PreviewConfigurationAttemptKind.REQUESTED,
            )
            currentPreview = preview
            updateResourcesLocked()
            asyncOwnership.publishIntent(preview.identity())
            val cleanupPermit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
            transition(CameraEngineState.Switching(current.selectionOrNull()?.routeId, effective))
            switch = SwitchCommand(preview, cleanupPermit)
        }
        closePlan(cleanup)
        val command = switch ?: return
        var open: OpenCommand? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.cleanupPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val preview = currentPreview ?: return@mutate
            if (preview.identity() != command.preview.identity()) return@mutate
            val openPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
            transition(CameraEngineState.Opening(preview.selection, preview.selection.sessionGeneration))
            mark(CameraStartupMilestone.OPEN_REQUESTED, preview.selection)
            open = OpenCommand(preview, openPermit)
        }
        open?.let(::issueOpen)
    }

    private fun issueOpen(command: OpenCommand) {
        try {
            runtime.platform.open(
                command.preview.route.openCameraId,
                object : CameraOpenCallbacks {
                    override fun onOpened(delivery: CloseOnceCameraResource<CameraDeviceHandle>) {
                        dispatchDelivered(delivery) { handleOpened(command, delivery) }
                    }

                    override fun onDisconnected(delivery: CloseOnceCameraResource<CameraDeviceHandle>) {
                        dispatchDelivered(delivery) {
                            handleDeviceTerminal(command, delivery, CameraDisconnected)
                        }
                    }

                    override fun onError(
                        delivery: CloseOnceCameraResource<CameraDeviceHandle>,
                        platformCode: Int,
                    ) {
                        dispatchDelivered(delivery) {
                            handleDeviceTerminal(command, delivery, platformDeviceFailure(platformCode))
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleOpenInvocationFailure(command, mapOpenInvocationFailure(error)) }
            }
        }
    }

    private suspend fun handleOpened(
        command: OpenCommand,
        delivery: CloseOnceCameraResource<CameraDeviceHandle>,
    ) {
        var staleCleanup: CameraResourceCleanup? = null
        var configure: ConfigureCommand? = null
        mutationGate.mutate {
            when (val adoption = asyncOwnership.resolveResource(command.openPermit, delivery)) {
                is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
                is ResourceAdoption.Adopted -> {
                    val preview = currentPreview
                    if (preview == null || preview.identity() != command.preview.identity()) {
                        staleCleanup = CameraResourceCleanup(adoption.resource::close)
                        return@mutate
                    }
                    val deviceEventPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
                    command.deviceEventPermit.set(deviceEventPermit)
                    activeDevice = ActiveDevice(
                        adoption.resource,
                        CameraResourceCleanup(adoption.resource::close),
                        deviceEventPermit,
                        command,
                    )
                    updateResourcesLocked()
                    transition(CameraEngineState.ConfiguringPreview(preview.selection, preview.attempt))
                    mark(CameraStartupMilestone.CAMERA_OPENED, preview.selection)
                    val configPermit = asyncOwnership.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
                    mark(CameraStartupMilestone.SESSION_CONFIG_REQUESTED, preview.selection)
                    configure = ConfigureCommand(preview, configPermit, adoption.resource)
                }
            }
        }
        closeCleanup(staleCleanup)
        configure?.let(::issueConfigure)
    }

    private fun issueConfigure(command: ConfigureCommand) {
        try {
            runtime.platform.configurePreviewTargeted(
                command.device,
                command.preview.surface.token,
                command.preview.route.physicalCameraId,
                command.preview.configuration,
                command.preview.settings,
                command.preview.attempt,
                object : CameraSessionCallbacks {
                    override fun onConfigured(
                        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                        request: PreparedPreviewRequest,
                    ) {
                        dispatchDelivered(delivery) { handleConfigured(command, delivery, request) }
                    }

                    override fun onConfigureFailed(
                        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                    ) {
                        dispatchDelivered(delivery) { handleConfigureFailed(command, delivery) }
                    }
                },
            )
        } catch (_: Throwable) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleConfigureInvocationFailure(command) }
            }
        }
    }

    private suspend fun handleConfigured(
        command: ConfigureCommand,
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
        request: PreparedPreviewRequest,
    ) {
        var staleCleanup: CameraResourceCleanup? = null
        var repeating: RepeatingCommand? = null
        mutationGate.mutate {
            when (val adoption = asyncOwnership.resolveResource(command.configurationPermit, delivery)) {
                is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
                is ResourceAdoption.Adopted -> {
                    val preview = currentPreview
                    if (preview == null || preview.identity() != command.preview.identity()) {
                        staleCleanup = CameraResourceCleanup(adoption.resource::close)
                        return@mutate
                    }
                    activeSession = ActiveSession(
                        adoption.resource,
                        CameraResourceCleanup(adoption.resource::close),
                    )
                    updateResourcesLocked()
                    mark(CameraStartupMilestone.SESSION_CONFIGURED, preview.selection)
                    val repeatingPermit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
                    val firstFramePermit = asyncOwnership.begin(PendingCameraStage.FIRST_FRAME)
                    repeating = RepeatingCommand(
                        preview,
                        repeatingPermit,
                        firstFramePermit,
                        adoption.resource,
                        request,
                    )
                }
            }
        }
        closeCleanup(staleCleanup)
        repeating?.let(::issueRepeating)
    }

    private fun issueRepeating(command: RepeatingCommand) {
        try {
            runtime.platform.startRepeating(command.session, command.request) {
                if (!shutdownRequested.get()) {
                    callbackScope.launch { handleFirstFrame(command.firstFramePermit) }
                }
            }
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleRepeatingStarted(command) }
            }
        } catch (_: Throwable) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleRepeatingRejected(command) }
            }
        }
    }

    private suspend fun handleRepeatingStarted(command: RepeatingCommand) {
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.repeatingPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val preview = currentPreview ?: return@mutate
            if (preview.identity() != command.preview.identity()) return@mutate
            transition(CameraEngineState.Previewing(preview.selection, firstFrameVerified = false))
            refreshRawAvailabilityLocked()
        }
    }

    private suspend fun handleFirstFrame(permit: PendingCameraOperationPermit) {
        mutationGate.mutate {
            val previewing = mutableState.value as? CameraEngineState.Previewing ?: return@mutate
            if (previewing.firstFrameVerified || permit.intent.selection != previewing.selection) return@mutate
            if (asyncOwnership.completeSignal(permit) != CameraCallbackDecision.ACCEPTED) return@mutate
            mark(CameraStartupMilestone.FIRST_CAPTURE_RESULT, previewing.selection)
            mark(CameraStartupMilestone.FIRST_PREVIEW_FRAME, previewing.selection)
            transition(previewing.copy(firstFrameVerified = true))
            refreshRawAvailabilityLocked()
        }
    }

    private suspend fun handleConfigureFailed(
        command: ConfigureCommand,
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
    ) {
        var deliveredCleanup: CameraResourceCleanup? = null
        var rejection: PreviewRejection? = null
        mutationGate.mutate {
            when (val resolution = asyncOwnership.resolveResource(command.configurationPermit, delivery)) {
                is ResourceAdoption.Stale -> deliveredCleanup = resolution.cleanup
                is ResourceAdoption.Adopted -> {
                    deliveredCleanup = CameraResourceCleanup(resolution.resource::close)
                    rejection = rejectPreviewAttemptLocked(command.preview)
                }
            }
        }
        closeCleanup(deliveredCleanup)
        val outcome = rejection
        closePlan(outcome?.cleanup)
        outcome?.next?.let(::issueConfigure)
    }

    private suspend fun handleConfigureInvocationFailure(command: ConfigureCommand) {
        var rejection: PreviewRejection? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.configurationPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            rejection = rejectPreviewAttemptLocked(command.preview)
        }
        val outcome = rejection
        closePlan(outcome?.cleanup)
        outcome?.next?.let(::issueConfigure)
    }

    private suspend fun handleRepeatingRejected(command: RepeatingCommand) {
        var rejection: PreviewRejection? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.repeatingPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val preview = currentPreview
            if (preview == null || preview.identity() != command.preview.identity()) return@mutate
            val sessionCleanup = detachSessionLocked()
            val rejected = rejectPreviewAttemptLocked(preview)
            rejection = PreviewRejection(
                rejected.next,
                combineCleanup(sessionCleanup, rejected.cleanup),
            )
        }
        val outcome = rejection
        closePlan(outcome?.cleanup)
        outcome?.next?.let(::issueConfigure)
    }

    private fun rejectPreviewAttemptLocked(rejected: PreviewIntent): PreviewRejection {
        if (currentPreview?.identity() != rejected.identity()) return PreviewRejection()
        if (rejected.attempt == PreviewConfigurationAttemptKind.SAFE_BASELINE) {
            asyncOwnership.invalidatePending()
            currentPreview = null
            val cleanup = detachAllLocked()
            transition(
                CameraEngineState.StructuralError(
                    rejected.selection,
                    SafeBaselineConfigurationRejected,
                ),
            )
            return PreviewRejection(cleanup = cleanup)
        }
        val state = mutableState.value
        if (state !is CameraEngineState.ConfiguringPreview ||
            state.attempt != PreviewConfigurationAttemptKind.REQUESTED
        ) return PreviewRejection()
        val device = activeDevice ?: return PreviewRejection()
        val requestedFailure = RequestedConfigurationRejected(requestedFailureKind(rejected))
        check(requestedFailure.policy.fallbackPermitted && !requestedFailure.policy.structural)
        val next = generations.advanceSession()
        val baseline = rejected.copy(
            selection = rejected.selection.copy(sessionGeneration = next.session),
            attempt = PreviewConfigurationAttemptKind.SAFE_BASELINE,
        )
        asyncOwnership.publishIntent(baseline.identity())
        val deviceEventPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
        device.eventPermit = deviceEventPermit
        device.openCommand.deviceEventPermit.set(deviceEventPermit)
        currentPreview = baseline
        transition(CameraEngineState.ConfiguringPreview(baseline.selection, baseline.attempt))
        val configPermit = asyncOwnership.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
        mark(CameraStartupMilestone.SESSION_CONFIG_REQUESTED, baseline.selection)
        return PreviewRejection(
            next = ConfigureCommand(baseline, configPermit, device.handle),
        )
    }

    private fun requestedFailureKind(preview: PreviewIntent): RequestedConfigurationKind = when {
        preview.settings.fpsRequest.overrideEnabled -> RequestedConfigurationKind.EXACT_FPS_RANGE
        preview.configuration.highResolutionViewfinder -> RequestedConfigurationKind.HIGH_RESOLUTION_PREVIEW
        else -> RequestedConfigurationKind.ENHANCEMENT
    }

    private suspend fun handleOpenInvocationFailure(command: OpenCommand, failure: CameraFailure) {
        var cleanup: CameraCleanupPlan? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.openPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            cleanup = failCurrentLocked(command.preview, failure)
        }
        closePlan(cleanup)
    }

    private suspend fun handleDeviceTerminal(
        command: OpenCommand,
        delivery: CloseOnceCameraResource<CameraDeviceHandle>,
        failure: CameraFailure,
    ) {
        var cleanup: CameraCleanupPlan? = null
        var deliveredCleanup: CameraResourceCleanup? = null
        mutationGate.mutate {
            val eventPermit = command.deviceEventPermit.get()
            if (eventPermit != null) {
                if (asyncOwnership.completeSignal(eventPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
                cleanup = failCurrentLocked(currentPreview ?: command.preview, failure)
                return@mutate
            }
            when (val resolution = asyncOwnership.resolveResource(command.openPermit, delivery)) {
                is ResourceAdoption.Stale -> deliveredCleanup = resolution.cleanup
                is ResourceAdoption.Adopted -> {
                    deliveredCleanup = CameraResourceCleanup(resolution.resource::close)
                    cleanup = failCurrentLocked(command.preview, failure)
                }
            }
        }
        closeCleanup(deliveredCleanup)
        closePlan(cleanup)
    }

    internal fun failCurrentLocked(preview: PreviewIntent, failure: CameraFailure): CameraCleanupPlan? {
        val next = generations.advanceSession()
        val failed = preview.selection.copy(sessionGeneration = next.session)
        asyncOwnership.invalidatePending()
        currentPreview = null
        val cleanup = detachAllLocked()
        if (failure.policy.structural) transition(CameraEngineState.StructuralError(failed, failure))
        else transition(CameraEngineState.RecoverableError(failed, failure))
        return cleanup
    }

    private suspend fun completePauseCleanup(permit: PendingCameraOperationPermit?) {
        if (permit == null) return
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(permit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val pausing = mutableState.value as? CameraEngineState.Pausing ?: return@mutate
            asyncOwnership.invalidatePending()
            transition(CameraEngineState.WaitingForSurface(pausing.selection))
        }
    }

    private fun detachAllLocked(): CameraCleanupPlan? {
        val cleanups = ArrayList<CameraResourceCleanup>(7)
        activeSession?.let { cleanups += it.cleanup }
        activeRaw?.let { raw ->
            raw.publicationPermit.revoke()
            raw.waitJob?.let { job -> cleanups += CameraResourceCleanup(job::cancel) }
            raw.deadlineJob?.let { job -> cleanups += CameraResourceCleanup(job::cancel) }
            raw.readerCleanup?.let(cleanups::add)
            cleanups += CameraResourceCleanup(raw.acquisition::close)
        }
        activeDevice?.let { cleanups += it.cleanup }
        activeSurface?.let { cleanups += it.cleanup }
        activeSession = null
        activeRaw = null
        activeDevice = null
        activeSurface = null
        mutableRawCaptureState.value = RawCaptureUiState.Unavailable
        updateResourcesLocked()
        return if (cleanups.isEmpty()) null else CameraCleanupPlan(cleanups)
    }

    internal fun detachRawLocked(): CameraCleanupPlan? {
        val raw = activeRaw ?: return null
        raw.publicationPermit.revoke()
        activeRaw = null
        val cleanups = ArrayList<CameraResourceCleanup>(4)
        raw.waitJob?.let { job -> cleanups += CameraResourceCleanup(job::cancel) }
        raw.deadlineJob?.let { job -> cleanups += CameraResourceCleanup(job::cancel) }
        raw.readerCleanup?.let(cleanups::add)
        cleanups += CameraResourceCleanup(raw.acquisition::close)
        updateResourcesLocked()
        return CameraCleanupPlan(cleanups)
    }

    internal fun detachSessionLocked(): CameraCleanupPlan? {
        val session = activeSession ?: return null
        activeSession = null
        updateResourcesLocked()
        return CameraCleanupPlan(listOf(session.cleanup))
    }

    internal fun combineCleanup(first: CameraCleanupPlan?, second: CameraCleanupPlan?): CameraCleanupPlan? = when {
        first == null -> second
        second == null -> first
        else -> CameraCleanupPlan(
            listOf(
                CameraResourceCleanup { first.closeAllOnce() },
                CameraResourceCleanup { second.closeAllOnce() },
            ),
        )
    }

    internal fun updateResourcesLocked() {
        val raw = activeRaw
        mutableResources.value = CameraResourceSnapshot(
            cameraDevices = if (activeDevice == null) 0 else 1,
            captureSessions = if (activeSession == null) 0 else 1,
            ownedSurfaces = if (activeSurface == null) 0 else 1,
            imageReaders = if (raw?.reader == null) 0 else 1,
            openImages = raw?.openImageCount ?: 0,
            cameraWorkers = if (shutdownRequested.get()) 0 else runtime.workerCount,
            activeRawTransactions = if (raw == null) 0 else 1,
            pendingRawImages = raw?.pendingImageCount ?: 0,
            pendingRawResults = raw?.pendingResultCount ?: 0,
        )
    }

    internal fun mark(milestone: CameraStartupMilestone, selection: ActiveCameraSelection) {
        trace.mark(
            milestone,
            elapsedRealtimeNs(),
            selection.selectionGeneration,
            selection.sessionGeneration,
        )
    }

    internal fun transition(next: CameraEngineState) {
        CameraStateTransitions.requireAllowed(mutableState.value, next)
        mutableState.value = next
    }

    internal fun <T> dispatchDelivered(
        delivery: CloseOnceCameraResource<T>,
        block: suspend () -> Unit,
    ) {
        if (shutdownRequested.get()) {
            closeCleanup(delivery.detachForStaleCleanup())
        } else {
            callbackScope.launch { block() }
        }
    }

    internal fun closeCleanup(cleanup: CameraResourceCleanup?) {
        if (cleanup == null) return
        try {
            cleanup.closeOnce()
        } catch (_: Throwable) {
            // Close has no authority to mutate current state; all detached cleanups are best effort.
        }
    }

    internal fun closePlan(plan: CameraCleanupPlan?) {
        if (plan == null) return
        try {
            plan.closeAllOnce()
        } catch (_: Throwable) {
            // CameraCleanupPlan already attempts every detached cleanup and preserves close ordering.
        }
    }

    private data class SurfaceInput(
        val identity: PreviewSurfaceIdentity,
        val token: Any,
        val close: () -> Unit,
    )

    internal data class ActiveSurface(
        val identity: PreviewSurfaceIdentity,
        val token: Any,
        val cleanup: CameraResourceCleanup,
    )

    internal data class ActiveSession(
        val handle: CameraCaptureSessionHandle,
        val cleanup: CameraResourceCleanup,
    )

    internal data class ActiveDevice(
        val handle: CameraDeviceHandle,
        val cleanup: CameraResourceCleanup,
        var eventPermit: PendingCameraOperationPermit,
        val openCommand: OpenCommand,
    )

    internal data class PreviewIntent(
        val selection: ActiveCameraSelection,
        val route: CameraRoute,
        val surface: ActiveSurface,
        val configuration: PreviewConfiguration,
        val settings: SettingsSnapshot,
        val attempt: PreviewConfigurationAttemptKind,
    ) {
        fun identity(captureToken: CaptureToken? = null) = CameraOperationIdentity(
            selection,
            surface.identity,
            attempt,
            captureToken,
        )
    }

    private data class SwitchCommand(
        val preview: PreviewIntent,
        val cleanupPermit: PendingCameraOperationPermit,
    )

    internal data class OpenCommand(
        val preview: PreviewIntent,
        val openPermit: PendingCameraOperationPermit,
        val deviceEventPermit: AtomicReference<PendingCameraOperationPermit?> = AtomicReference(null),
    )

    private data class ConfigureCommand(
        val preview: PreviewIntent,
        val configurationPermit: PendingCameraOperationPermit,
        val device: CameraDeviceHandle,
    )

    private data class PreviewRejection(
        val next: ConfigureCommand? = null,
        val cleanup: CameraCleanupPlan? = null,
    )

    private data class RepeatingCommand(
        val preview: PreviewIntent,
        val repeatingPermit: PendingCameraOperationPermit,
        val firstFramePermit: PendingCameraOperationPermit,
        val session: CameraCaptureSessionHandle,
        val request: PreparedPreviewRequest,
    )

    internal data class ActiveRawTransaction(
        val context: RawCaptureContext,
        val outputPlan: CameraSessionOutputPlan,
        val acquisition: OneShotRawAcquisition<SensorRawImage, SensorRawCaptureResult>,
        val publicationPermit: RawPublicationPermit,
        var reader: RawImageReaderHandle? = null,
        var readerCleanup: CameraResourceCleanup? = null,
        var imagePermit: PendingCameraOperationPermit? = null,
        var resultPermit: PendingCameraOperationPermit? = null,
        var waitJob: Job? = null,
        var deadlineJob: Job? = null,
        var openImageCount: Int = 0,
        var pendingImageCount: Int = 0,
        var pendingResultCount: Int = 0,
    ) {
        fun refreshPendingCounts() {
            val counts = acquisition.pendingCounts()
            pendingImageCount = counts.first
            pendingResultCount = counts.second
        }
    }

    internal data class RawConfigureCommand(
        val preview: PreviewIntent,
        val context: RawCaptureContext,
        val device: CameraDeviceHandle,
        val configurationPermit: PendingCameraOperationPermit,
        val imagePermit: PendingCameraOperationPermit,
        val resultPermit: PendingCameraOperationPermit,
        val transaction: ActiveRawTransaction,
    )

    internal data class RawCaptureCommand(
        val configure: RawConfigureCommand,
        val session: CameraCaptureSessionHandle,
        val previewRequest: PreparedPreviewRequest,
        val rawRequest: PreparedRawRequest,
    )

    internal data class RestorePreviewCommand(
        val preview: PreviewIntent,
        val token: CaptureToken,
        val outcome: RawCaptureOutcome,
        val device: CameraDeviceHandle,
        val configurationPermit: PendingCameraOperationPermit,
        val attempt: Int,
    )

    internal data class RestoreRepeatingCommand(
        val restore: RestorePreviewCommand,
        val session: CameraCaptureSessionHandle,
        val request: PreparedPreviewRequest,
        val repeatingPermit: PendingCameraOperationPermit,
        val firstFramePermit: PendingCameraOperationPermit,
    )

    internal data class ControllerRuntime(
        val platform: CameraOwnerPlatform,
        val sensorDngWriter: SensorDngWriter,
        val dispatcher: CoroutineDispatcher,
        val shutdownWorker: suspend () -> Unit,
        val workerCount: Int,
    ) {
        init { require(workerCount in 0..1) { "At most one camera callback worker is supported" } }
    }

    private companion object {
        fun createAndroidRuntime(
            cameraManager: CameraManager,
            sensorDngWriter: SensorDngWriter,
        ): ControllerRuntime {
            val thread = HandlerThread("camx-camera-control").apply { start() }
            val handler = Handler(thread.looper)
            return ControllerRuntime(
                AndroidCameraOwnerPlatform(cameraManager, handler),
                sensorDngWriter,
                handler.asCoroutineDispatcher("camx-camera-control"),
                shutdownWorker = {
                    withContext(Dispatchers.IO) {
                        thread.quitSafely()
                        thread.join()
                    }
                },
                workerCount = 1,
            )
        }

        fun unavailableDngWriter() = SensorDngWriter { _, image, _, _ ->
            runCatching { image.close() }
            failedStatic(RawUnsupported)
        }

        private fun failedStatic(failure: CameraFailure): RawCaptureOutcome =
            RawCaptureOutcome.Failed(failure)

        fun mapOpenInvocationFailure(error: Throwable): CameraFailure = when (error) {
            is SecurityException -> PermissionDenied(permanentlyDenied = false)
            is CameraAccessException -> when (error.reason) {
                CameraAccessException.CAMERA_IN_USE -> CameraInUse
                CameraAccessException.MAX_CAMERAS_IN_USE -> MaximumCamerasInUse
                CameraAccessException.CAMERA_DISABLED -> CameraDisabled
                else -> CameraDeviceError(error.reason)
            }
            else -> CameraDeviceError(-1)
        }

        fun platformDeviceFailure(code: Int): CameraFailure = when (code) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> CameraInUse
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> MaximumCamerasInUse
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraDisabled
            else -> CameraDeviceError(code)
        }
    }
}
