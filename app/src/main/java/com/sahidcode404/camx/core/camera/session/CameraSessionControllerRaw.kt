package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.diagnostics.PermissionDenied
import com.sahidcode404.camx.core.camera.diagnostics.RawCaptureTimeout
import com.sahidcode404.camx.core.camera.diagnostics.RawPairTimeout
import com.sahidcode404.camx.core.camera.diagnostics.RawSessionRejected
import com.sahidcode404.camx.core.camera.diagnostics.TrustChange
import com.sahidcode404.camx.core.camera.model.CameraStartupMilestone
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.RawContractLimits
import com.sahidcode404.camx.core.camera.model.RawPair
import com.sahidcode404.camx.core.camera.raw.OneShotRawAcquisition
import com.sahidcode404.camx.core.camera.raw.RawAdmissionDecision
import com.sahidcode404.camx.core.camera.raw.RawAdmissionInput
import com.sahidcode404.camx.core.camera.raw.RawAdmissionRejection
import com.sahidcode404.camx.core.camera.raw.RawCaptureAdmission
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.camera.raw.RawCaptureUiState
import com.sahidcode404.camx.core.camera.raw.RawEvidenceDisposition
import com.sahidcode404.camx.core.camera.raw.RawPublicationPermit
import com.sahidcode404.camx.core.camera.raw.RawShutterInput
import com.sahidcode404.camx.core.camera.raw.SensorRawCaptureResult
import com.sahidcode404.camx.core.camera.raw.SensorRawImage
import com.sahidcode404.camx.core.camera.raw.SensorRawProfileResolver
import com.sahidcode404.camx.core.camera.session.CameraSessionController.ActiveRawTransaction
import com.sahidcode404.camx.core.camera.session.CameraSessionController.ActiveSession
import com.sahidcode404.camx.core.camera.session.CameraSessionController.RawCaptureCommand
import com.sahidcode404.camx.core.camera.session.CameraSessionController.RawConfigureCommand
import com.sahidcode404.camx.core.camera.session.CameraSessionController.RestorePreviewCommand
import com.sahidcode404.camx.core.camera.session.CameraSessionController.RestoreRepeatingCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller-owned CAMX-108 transaction hooks. CameraSessionController remains the authoritative
 * state/resource owner; Android resource construction stays in its single platform adapter.
 */

internal suspend fun CameraSessionController.captureRaw(input: RawShutterInput): RawAdmissionDecision {
    var decision: RawAdmissionDecision = RawAdmissionDecision.Rejected(
        RawAdmissionRejection.PREVIEW_NOT_VERIFIED,
    )
    var detachedPreview: CameraCleanupPlan? = null
    var command: RawConfigureCommand? = null
    rawMutationGate.mutate {
        val state = mutableState.value
        val previewing = state as? CameraEngineState.Previewing
        val preview = currentPreview
        val device = activeDevice
        val representation = preview
            ?.takeIf { it.selection.profileFingerprint !in structurallyRejectedRawProfiles }
            ?.route?.capabilities?.let(SensorRawProfileResolver::resolve)
        val metadata = if (device != null && preview != null && representation != null) {
            runtime.platform.sensorRawMetadata(
                device.handle,
                preview.route.physicalCameraId,
                representation,
            )
        } else {
            null
        }
        decision = RawCaptureAdmission.decide(
            RawAdmissionInput(
                lifecycleActive = input.lifecycleActive,
                previewVerified = previewing?.firstFrameVerified == true,
                selection = previewing?.selection,
                sessionBusy = state !is CameraEngineState.Previewing,
                pausing = state is CameraEngineState.Pausing,
                shutdown = shutdownRequested.get() || state == CameraEngineState.Closed,
                captureActive = activeRaw != null || generations.snapshot().capture != null,
                representation = representation,
                requiredMetadataAvailable = metadata != null && preview != null && device != null,
                boundedResourcesAvailable = activeRaw == null,
            ),
        )
        val admitted = decision as? RawAdmissionDecision.Admitted ?: return@mutate
        checkNotNull(preview)
        checkNotNull(device)
        checkNotNull(metadata)

        val next = generations.advanceSession()
        val token = generations.beginCapture()
        val selection = admitted.selection.copy(sessionGeneration = next.session)
        val immutablePreview = preview.copy(selection = selection)
        currentPreview = immutablePreview
        val admittedAt = elapsedRealtimeNs().coerceAtLeast(0L)
        val timeoutMillis = rawTimeoutMillis
        val timeoutNs = timeoutMillis * RAW_NANOS_PER_MILLISECOND
        check(admittedAt <= Long.MAX_VALUE - timeoutNs) { "RAW deadline overflow" }
        val context = RawCaptureContext(
            captureToken = token,
            selectionGeneration = selection.selectionGeneration,
            sessionGeneration = selection.sessionGeneration,
            canonicalLensFingerprint = selection.canonicalLensFingerprint,
            cameraProfileFingerprint = selection.profileFingerprint,
            routeId = selection.routeId,
            openCameraId = immutablePreview.route.openCameraId,
            physicalCameraId = immutablePreview.route.physicalCameraId,
            previewSurfaceIdentity = immutablePreview.surface.identity.value,
            displayRotationAtShutter = input.displayRotation,
            sensorOrientationDegrees = input.sensorOrientationDegrees,
            lensFacing = input.lensFacing,
            rawRepresentation = admitted.representation,
            sensorTimestampBasis = metadata.timestampBasis,
            admittedAtElapsedRealtimeNs = admittedAt,
            deadlineElapsedRealtimeNs = admittedAt + timeoutNs,
            timeoutMillis = timeoutMillis,
        )
        rawAsyncOwnership.publishIntent(
            immutablePreview.identity(captureToken = token),
        )
        val deviceEventPermit = rawAsyncOwnership.begin(PendingCameraStage.OPEN)
        device.eventPermit = deviceEventPermit
        device.openCommand.deviceEventPermit.set(deviceEventPermit)
        val transaction = ActiveRawTransaction(
            context = context,
            outputPlan = CameraSessionOutputPlan.temporaryRaw(immutablePreview.surface.identity, token),
            acquisition = OneShotRawAcquisition(context),
            publicationPermit = RawPublicationPermit(),
        )
        transaction.deadlineJob = callbackScope.launch(start = CoroutineStart.LAZY) {
            val remainingNs = context.deadlineElapsedRealtimeNs -
                elapsedRealtimeNs().coerceAtLeast(0L)
            if (remainingNs > 0L) {
                val remainingMillis = remainingNs / RAW_NANOS_PER_MILLISECOND +
                    if (remainingNs % RAW_NANOS_PER_MILLISECOND == 0L) 0L else 1L
                delay(remainingMillis)
            }
            handleRawDeadline(context)
        }
        activeRaw = transaction
        val configurationPermit = rawAsyncOwnership.begin(PendingCameraStage.RAW_CONFIGURATION)
        val imagePermit = rawAsyncOwnership.begin(PendingCameraStage.RAW_IMAGE)
        val resultPermit = rawAsyncOwnership.begin(PendingCameraStage.RAW_RESULT)
        transaction.imagePermit = imagePermit
        transaction.resultPermit = resultPermit
        detachedPreview = detachSessionLocked()
        transition(CameraEngineState.ConfiguringRaw(selection, token))
        mutableLastRawOutcome.value = null
        mutableRawCaptureState.value = RawCaptureUiState.Capturing(context.captureToken)
        updateResourcesLocked()
        mark(CameraStartupMilestone.SHUTTER_PRESS, selection)
        command = RawConfigureCommand(
            preview = immutablePreview,
            context = context,
            device = device.handle,
            configurationPermit = configurationPermit,
            imagePermit = imagePermit,
            resultPermit = resultPermit,
            transaction = transaction,
        )
        decision = RawAdmissionDecision.Admitted(selection, admitted.representation)
    }
    withContext(runtime.dispatcher) {
        closePlan(detachedPreview)
        command?.let {
            it.transaction.deadlineJob?.start()
            beginRawReaderAndConfigure(it)
        }
    }
    return decision
}

private fun CameraSessionController.beginRawReaderAndConfigure(command: RawConfigureCommand) {
    val reader = try {
        runtime.platform.createRawReader(
            command.context,
            object : RawImageCallbacks {
                override fun onImage(delivery: CloseOnceCameraResource<SensorRawImage>) {
                    dispatchDelivered(delivery) { handleRawImage(command, delivery) }
                }

                override fun onAcquisitionFailure() {
                    if (!shutdownRequested.get()) {
                        callbackScope.launch {
                            finishRawAndRestore(command.context, failed(RawPairTimeout))
                        }
                    }
                }
            },
        )
    } catch (failure: Exception) {
        if (!shutdownRequested.get()) {
            callbackScope.launch {
                finishRawAndRestore(command.context, failed(rawConfigurationFailure(failure)))
            }
        }
        return
    }
    callbackScope.launch {
        var staleReader: RawImageReaderHandle? = null
        var issue = false
        rawMutationGate.mutate {
            val active = activeRaw
            if (active !== command.transaction ||
                !generations.acceptsCapture(
                    command.context.selectionGeneration,
                    command.context.sessionGeneration,
                    command.context.captureToken,
                )
            ) {
                staleReader = reader
                return@mutate
            }
            active.reader = reader
            active.readerCleanup = CameraResourceCleanup(reader::close)
            updateResourcesLocked()
            issue = true
        }
        staleReader?.let { runCatching { it.close() } }
        if (issue) issueRawConfigure(command, reader)
    }
}

private fun CameraSessionController.issueRawConfigure(command: RawConfigureCommand, reader: RawImageReaderHandle) {
    try {
        runtime.platform.configureTemporaryRaw(
            device = command.device,
            previewSurfaceToken = command.preview.surface.token,
            physicalCameraId = command.preview.route.physicalCameraId,
            reader = reader,
            configuration = command.preview.configuration,
            settings = command.preview.settings,
            attempt = command.preview.attempt,
            callbacks = object : RawSessionCallbacks {
                override fun onConfigured(
                    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                    previewRequest: PreparedPreviewRequest,
                    rawRequest: PreparedRawRequest,
                ) {
                    dispatchDelivered(delivery) {
                        handleRawConfigured(command, delivery, previewRequest, rawRequest)
                    }
                }

                override fun onConfigureFailed(
                    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                ) {
                    dispatchDelivered(delivery) { handleRawConfigureFailed(command, delivery) }
                }
            },
        )
    } catch (failure: Exception) {
        if (!shutdownRequested.get()) {
            callbackScope.launch {
                finishRawAndRestore(command.context, failed(rawConfigurationFailure(failure)))
            }
        }
    }
}

private suspend fun CameraSessionController.handleRawConfigured(
    command: RawConfigureCommand,
    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
    previewRequest: PreparedPreviewRequest,
    rawRequest: PreparedRawRequest,
) {
    var staleCleanup: CameraResourceCleanup? = null
    var capture: RawCaptureCommand? = null
    rawMutationGate.mutate {
        when (val adoption = rawAsyncOwnership.resolveResource(command.configurationPermit, delivery)) {
            is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
            is ResourceAdoption.Adopted -> {
                val active = activeRaw
                if (active !== command.transaction ||
                    !generations.acceptsCapture(
                        command.context.selectionGeneration,
                        command.context.sessionGeneration,
                        command.context.captureToken,
                    )
                ) {
                    staleCleanup = CameraResourceCleanup(adoption.resource::close)
                    return@mutate
                }
                activeSession = ActiveSession(
                    adoption.resource,
                    CameraResourceCleanup(adoption.resource::close),
                )
                transition(
                    CameraEngineState.CapturingRaw(
                        command.preview.selection,
                        command.context.captureToken,
                    ),
                )
                mark(CameraStartupMilestone.RAW_SESSION_READY, command.preview.selection)
                updateResourcesLocked()
                capture = RawCaptureCommand(
                    command,
                    adoption.resource,
                    previewRequest,
                    rawRequest,
                )
            }
        }
    }
    closeCleanup(staleCleanup)
    capture?.let { issueOneRawCapture(it) }
}

private suspend fun CameraSessionController.handleRawConfigureFailed(
    command: RawConfigureCommand,
    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
) {
    var cleanup: CameraResourceCleanup? = null
    var accepted = false
    rawMutationGate.mutate {
        when (val adoption = rawAsyncOwnership.resolveResource(command.configurationPermit, delivery)) {
            is ResourceAdoption.Stale -> cleanup = adoption.cleanup
            is ResourceAdoption.Adopted -> {
                cleanup = CameraResourceCleanup(adoption.resource::close)
                accepted = activeRaw === command.transaction
            }
        }
    }
    closeCleanup(cleanup)
    if (accepted) finishRawAndRestore(command.context, failed(RawSessionRejected))
}

private fun CameraSessionController.issueOneRawCapture(command: RawCaptureCommand) {
    try {
        runtime.platform.startRepeating(command.session, command.previewRequest) {}
        command.configure.transaction.waitJob = callbackScope.launch {
            try {
                val pair = command.configure.transaction.acquisition.awaitPair()
                handleRawPair(command.configure, pair)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                finishRawAndRestore(command.configure.context, failed(RawPairTimeout))
            } catch (_: CancellationException) {
                // Explicit pause/switch/shutdown cleanup already owns the terminal destination.
            }
        }
        mark(CameraStartupMilestone.RAW_REQUEST, command.configure.preview.selection)
        runtime.platform.captureOneRaw(
            command.session,
            command.rawRequest,
            object : RawCaptureCallbacks {
                override fun onCompleted(result: SensorRawCaptureResult) {
                    if (!shutdownRequested.get()) {
                        callbackScope.launch { handleRawResult(command.configure, result) }
                    }
                }

                override fun onFailed() {
                    if (!shutdownRequested.get()) {
                        callbackScope.launch {
                            finishRawAndRestore(command.configure.context, failed(RawCaptureTimeout))
                        }
                    }
                }
            },
        )
    } catch (_: Exception) {
        if (!shutdownRequested.get()) {
            callbackScope.launch {
                finishRawAndRestore(command.configure.context, failed(RawCaptureTimeout))
            }
        }
    }
}

private suspend fun CameraSessionController.handleRawImage(
    command: RawConfigureCommand,
    delivery: CloseOnceCameraResource<SensorRawImage>,
) {
    var staleCleanup: CameraResourceCleanup? = null
    var image: SensorRawImage? = null
    rawMutationGate.mutate {
        when (val adoption = rawAsyncOwnership.resolveResource(command.imagePermit, delivery)) {
            is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
            is ResourceAdoption.Adopted -> {
                if (activeRaw !== command.transaction) {
                    staleCleanup = CameraResourceCleanup(adoption.resource::close)
                } else {
                    image = adoption.resource
                    command.transaction.openImageCount = 1
                    updateResourcesLocked()
                }
            }
        }
    }
    closeCleanup(staleCleanup)
    val offered = image ?: return
    val disposition = command.transaction.acquisition.offerImage(offered)
    rawMutationGate.mutate {
        if (activeRaw !== command.transaction) return@mutate
        command.transaction.refreshPendingCounts()
        if (disposition == RawEvidenceDisposition.INVALID_CLOSED) {
            command.transaction.openImageCount = 0
        }
        enterPairingIfNeededLocked(command)
        mark(CameraStartupMilestone.RAW_IMAGE, command.preview.selection)
        updateResourcesLocked()
    }
    if (disposition == RawEvidenceDisposition.INVALID_CLOSED) {
        finishRawAndRestore(command.context, failed(RawPairTimeout))
    }
}

private suspend fun CameraSessionController.handleRawResult(
    command: RawConfigureCommand,
    result: SensorRawCaptureResult,
) {
    var accepted = false
    rawMutationGate.mutate {
        if (rawAsyncOwnership.completeSignal(command.resultPermit) != CameraCallbackDecision.ACCEPTED) {
            return@mutate
        }
        accepted = activeRaw === command.transaction
    }
    if (!accepted) return
    val disposition = command.transaction.acquisition.offerResult(result)
    rawMutationGate.mutate {
        if (activeRaw !== command.transaction) return@mutate
        command.transaction.refreshPendingCounts()
        enterPairingIfNeededLocked(command)
        mark(CameraStartupMilestone.RAW_RESULT, command.preview.selection)
        updateResourcesLocked()
    }
    if (disposition == RawEvidenceDisposition.STALE_IGNORED && result.sensorTimestampNs <= 0L) {
        finishRawAndRestore(command.context, failed(RawPairTimeout))
    }
}

private fun CameraSessionController.enterPairingIfNeededLocked(command: RawConfigureCommand) {
    val state = mutableState.value
    if (state is CameraEngineState.CapturingRaw && state.token == command.context.captureToken) {
        transition(CameraEngineState.PairingRaw(command.preview.selection, command.context.captureToken))
    }
}

private suspend fun CameraSessionController.handleRawPair(
    command: RawConfigureCommand,
    pair: com.sahidcode404.camx.core.camera.model.RawPair<SensorRawImage, SensorRawCaptureResult>,
) {
    var accepted = false
    var image: SensorRawImage? = null
    var deadlineJob: kotlinx.coroutines.Job? = null
    rawMutationGate.mutate {
        if (activeRaw !== command.transaction ||
            !generations.acceptsCapture(
                command.context.selectionGeneration,
                command.context.sessionGeneration,
                command.context.captureToken,
            )
        ) return@mutate
        enterPairingIfNeededLocked(command)
        val state = mutableState.value as? CameraEngineState.PairingRaw ?: return@mutate
        if (state.token != command.context.captureToken) return@mutate
        image = pair.takeImage()
        command.transaction.openImageCount = 1
        command.transaction.pendingImageCount = 0
        command.transaction.pendingResultCount = 0
        deadlineJob = command.transaction.deadlineJob
        command.transaction.deadlineJob = null
        transition(CameraEngineState.WritingDng(command.preview.selection, command.context.captureToken))
        mutableRawCaptureState.value = RawCaptureUiState.Saving(command.context.captureToken)
        mark(CameraStartupMilestone.RAW_PAIR, command.preview.selection)
        mark(CameraStartupMilestone.DNG_WRITE_START, command.preview.selection)
        updateResourcesLocked()
        accepted = true
    }
    if (!accepted) {
        pair.close()
        return
    }
    deadlineJob?.cancel()
    pair.close()
    val ownedImage = checkNotNull(image)
    val outcome = runtime.sensorDngWriter.write(
        command.context,
        ownedImage,
        pair.result,
        authorizePublish = {
            command.transaction.publicationPermit.claim()
        },
    )
    command.transaction.openImageCount = 0
    mark(CameraStartupMilestone.DNG_WRITE_END, command.preview.selection)
    finishRawAndRestore(command.context, outcome)
}

private suspend fun CameraSessionController.finishRawAndRestore(
    context: RawCaptureContext,
    outcome: RawCaptureOutcome,
) {
    var cleanup: CameraCleanupPlan? = null
    var restore: RestorePreviewCommand? = null
    rawMutationGate.mutate {
        val active = activeRaw ?: return@mutate
        if (active.context.captureToken != context.captureToken ||
            !generations.acceptsCapture(
                context.selectionGeneration,
                context.sessionGeneration,
                context.captureToken,
            )
        ) return@mutate
        active.publicationPermit.revoke()
        val failure = (outcome as? RawCaptureOutcome.Failed)?.failure
        if (failure?.policy?.trustChange == TrustChange.REJECT_RAW_PROFILE) {
            if (structurallyRejectedRawProfiles.size == RAW_MAXIMUM_REJECTED_PROFILES) {
                structurallyRejectedRawProfiles.remove(structurallyRejectedRawProfiles.first())
            }
            structurallyRejectedRawProfiles += context.cameraProfileFingerprint
        }
        generations.endCapture(context.captureToken)
        val next = generations.advanceSession()
        val preview = currentPreview ?: return@mutate
        val selection = preview.selection.copy(sessionGeneration = next.session)
        val restoredPreview = preview.copy(selection = selection)
        currentPreview = restoredPreview
        rawAsyncOwnership.publishIntent(restoredPreview.identity(captureToken = context.captureToken))
        val device = activeDevice ?: return@mutate
        val deviceEventPermit = rawAsyncOwnership.begin(PendingCameraStage.OPEN)
        device.eventPermit = deviceEventPermit
        device.openCommand.deviceEventPermit.set(deviceEventPermit)
        val rawSessionCleanup = detachSessionLocked()
        val rawCleanup = detachRawLocked()
        cleanup = combineCleanup(rawSessionCleanup, rawCleanup)
        transition(CameraEngineState.RestoringPreview(selection, context.captureToken))
        mutableLastRawOutcome.value = outcome
        mutableRawCaptureState.value = RawCaptureUiState.Recovering(context.captureToken, outcome)
        val permit = rawAsyncOwnership.begin(PendingCameraStage.RAW_RESTORE_CONFIGURATION)
        restore = RestorePreviewCommand(
            preview = restoredPreview,
            token = context.captureToken,
            outcome = outcome,
            device = device.handle,
            configurationPermit = permit,
            attempt = 0,
        )
        updateResourcesLocked()
    }
    closePlan(cleanup)
    restore?.let { issueRestorePreview(it) }
}

private fun CameraSessionController.failed(failure: CameraFailure): RawCaptureOutcome = RawCaptureOutcome.Failed(failure)

private suspend fun CameraSessionController.handleRawDeadline(context: RawCaptureContext) {
    var timeoutFailure: CameraFailure? = null
    rawMutationGate.mutate {
        val active = activeRaw ?: return@mutate
        if (active.context.captureToken != context.captureToken ||
            !generations.acceptsCapture(
                context.selectionGeneration,
                context.sessionGeneration,
                context.captureToken,
            )
        ) return@mutate
        timeoutFailure = when (val state = mutableState.value) {
            is CameraEngineState.ConfiguringRaw ->
                RawCaptureTimeout.takeIf { state.token == context.captureToken }
            is CameraEngineState.CapturingRaw ->
                RawCaptureTimeout.takeIf { state.token == context.captureToken }
            is CameraEngineState.PairingRaw ->
                RawPairTimeout.takeIf { state.token == context.captureToken }
            else -> null
        }
    }
    timeoutFailure?.let { failure ->
        finishRawAndRestore(context, failed(failure))
    }
}

private fun rawConfigurationFailure(failure: Exception): CameraFailure = when (failure) {
    is UnsupportedOperationException,
    is IllegalArgumentException -> RawSessionRejected
    is SecurityException -> PermissionDenied(permanentlyDenied = false)
    else -> RawCaptureTimeout
}

private fun CameraSessionController.issueRestorePreview(command: RestorePreviewCommand) {
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
                    dispatchDelivered(delivery) {
                        handleRestoreConfigured(command, delivery, request)
                    }
                }

                override fun onConfigureFailed(
                    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                ) {
                    dispatchDelivered(delivery) { handleRestoreConfigureFailed(command, delivery) }
                }
            },
        )
        if (!shutdownRequested.get()) {
            callbackScope.launch {
                delay(rawTimeoutMillis)
                handleRestoreInvocationFailure(command)
            }
        }
    } catch (_: Exception) {
        if (!shutdownRequested.get()) {
            callbackScope.launch { handleRestoreInvocationFailure(command) }
        }
    }
}

private suspend fun CameraSessionController.handleRestoreConfigured(
    command: RestorePreviewCommand,
    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
    request: PreparedPreviewRequest,
) {
    var staleCleanup: CameraResourceCleanup? = null
    var repeating: RestoreRepeatingCommand? = null
    rawMutationGate.mutate {
        when (val adoption = rawAsyncOwnership.resolveResource(command.configurationPermit, delivery)) {
            is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
            is ResourceAdoption.Adopted -> {
                val state = mutableState.value as? CameraEngineState.RestoringPreview
                val preview = currentPreview
                if (state?.token != command.token ||
                    preview == null ||
                    preview.selection != command.preview.selection
                ) {
                    staleCleanup = CameraResourceCleanup(adoption.resource::close)
                    return@mutate
                }
                activeSession = ActiveSession(
                    adoption.resource,
                    CameraResourceCleanup(adoption.resource::close),
                )
                val repeatingPermit = rawAsyncOwnership.begin(PendingCameraStage.RAW_RESTORE_REPEATING)
                val firstFramePermit = rawAsyncOwnership.begin(PendingCameraStage.RAW_RESTORE_FIRST_FRAME)
                repeating = RestoreRepeatingCommand(
                    command,
                    adoption.resource,
                    request,
                    repeatingPermit,
                    firstFramePermit,
                )
                updateResourcesLocked()
            }
        }
    }
    closeCleanup(staleCleanup)
    repeating?.let { issueRestoreRepeating(it) }
}

private fun CameraSessionController.issueRestoreRepeating(command: RestoreRepeatingCommand) {
    try {
        runtime.platform.startRepeating(command.session, command.request) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleRestoreFirstFrame(command) }
            }
        }
        if (!shutdownRequested.get()) {
            callbackScope.launch { handleRestoreRepeatingStarted(command) }
            callbackScope.launch {
                delay(rawTimeoutMillis)
                handleRestoreFirstFrameTimeout(command)
            }
        }
    } catch (_: Exception) {
        if (!shutdownRequested.get()) {
            callbackScope.launch { handleRestoreRepeatingFailed(command) }
        }
    }
}

private suspend fun CameraSessionController.handleRestoreRepeatingStarted(command: RestoreRepeatingCommand) {
    rawMutationGate.mutate {
        if (rawAsyncOwnership.completeSignal(command.repeatingPermit) != CameraCallbackDecision.ACCEPTED) {
            return@mutate
        }
        val state = mutableState.value as? CameraEngineState.RestoringPreview ?: return@mutate
        if (state.token != command.restore.token) return@mutate
    }
}

private suspend fun CameraSessionController.handleRestoreFirstFrame(command: RestoreRepeatingCommand) {
    rawMutationGate.mutate {
        val state = mutableState.value as? CameraEngineState.RestoringPreview ?: return@mutate
        if (state.token != command.restore.token || state.selection != command.restore.preview.selection) {
            return@mutate
        }
        if (rawAsyncOwnership.completeSignal(command.firstFramePermit) != CameraCallbackDecision.ACCEPTED) {
            return@mutate
        }
        transition(CameraEngineState.Previewing(state.selection, firstFrameVerified = true))
        mark(CameraStartupMilestone.PREVIEW_RESTORED, state.selection)
        refreshRawAvailabilityLocked()
    }
}

private suspend fun CameraSessionController.handleRestoreFirstFrameTimeout(
    command: RestoreRepeatingCommand,
) {
    var cleanup: CameraCleanupPlan? = null
    var retry = false
    rawMutationGate.mutate {
        if (rawAsyncOwnership.completeSignal(command.firstFramePermit) !=
            CameraCallbackDecision.ACCEPTED
        ) return@mutate
        val state = mutableState.value as? CameraEngineState.RestoringPreview ?: return@mutate
        if (state.token != command.restore.token || state.selection != command.restore.preview.selection) {
            return@mutate
        }
        cleanup = detachSessionLocked()
        retry = true
    }
    closePlan(cleanup)
    if (retry) retryOrFailRestore(command.restore)
}

private suspend fun CameraSessionController.handleRestoreConfigureFailed(
    command: RestorePreviewCommand,
    delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
) {
    var cleanup: CameraResourceCleanup? = null
    var retry = false
    rawMutationGate.mutate {
        when (val adoption = rawAsyncOwnership.resolveResource(command.configurationPermit, delivery)) {
            is ResourceAdoption.Stale -> cleanup = adoption.cleanup
            is ResourceAdoption.Adopted -> {
                cleanup = CameraResourceCleanup(adoption.resource::close)
                retry = mutableState.value is CameraEngineState.RestoringPreview
            }
        }
    }
    closeCleanup(cleanup)
    if (retry) retryOrFailRestore(command)
}

private suspend fun CameraSessionController.handleRestoreInvocationFailure(command: RestorePreviewCommand) {
    var accepted = false
    rawMutationGate.mutate {
        accepted = rawAsyncOwnership.completeSignal(command.configurationPermit) ==
            CameraCallbackDecision.ACCEPTED &&
            mutableState.value is CameraEngineState.RestoringPreview
    }
    if (accepted) retryOrFailRestore(command)
}

private suspend fun CameraSessionController.handleRestoreRepeatingFailed(command: RestoreRepeatingCommand) {
    var cleanup: CameraCleanupPlan? = null
    var retry = false
    rawMutationGate.mutate {
        if (rawAsyncOwnership.completeSignal(command.repeatingPermit) != CameraCallbackDecision.ACCEPTED) {
            return@mutate
        }
        val state = mutableState.value as? CameraEngineState.RestoringPreview ?: return@mutate
        if (state.token != command.restore.token || state.selection != command.restore.preview.selection) {
            return@mutate
        }
        rawAsyncOwnership.completeSignal(command.firstFramePermit)
        cleanup = detachSessionLocked()
        retry = true
    }
    closePlan(cleanup)
    if (retry) retryOrFailRestore(command.restore)
}

private suspend fun CameraSessionController.retryOrFailRestore(command: RestorePreviewCommand) {
    var next: RestorePreviewCommand? = null
    var cleanup: CameraCleanupPlan? = null
    rawMutationGate.mutate {
        val state = mutableState.value as? CameraEngineState.RestoringPreview ?: return@mutate
        if (state.token != command.token || currentPreview?.selection != command.preview.selection) {
            return@mutate
        }
        if (command.attempt < RAW_MAXIMUM_RESTORE_RETRIES) {
            val permit = rawAsyncOwnership.begin(PendingCameraStage.RAW_RESTORE_CONFIGURATION)
            next = command.copy(configurationPermit = permit, attempt = command.attempt + 1)
        } else {
            mutableRawCaptureState.value = RawCaptureUiState.Unavailable
            cleanup = failCurrentLocked(command.preview, RawCaptureTimeout)
        }
    }
    closePlan(cleanup)
    next?.let { issueRestorePreview(it) }
}

internal fun CameraSessionController.refreshRawAvailabilityLocked() {
    if (activeRaw != null) return
    val previewing = mutableState.value as? CameraEngineState.Previewing
    val preview = currentPreview
    val device = activeDevice
    val representation = preview
        ?.takeIf { it.selection.profileFingerprint !in structurallyRejectedRawProfiles }
        ?.route?.capabilities?.let(SensorRawProfileResolver::resolve)
    val ready = previewing?.firstFrameVerified == true &&
        preview != null && device != null && representation != null &&
        runtime.platform.sensorRawMetadata(
            device.handle,
            preview.route.physicalCameraId,
            representation,
        ) != null
    mutableRawCaptureState.value = if (ready) RawCaptureUiState.Ready else RawCaptureUiState.Unavailable
}


private const val RAW_NANOS_PER_MILLISECOND = 1_000_000L
private const val RAW_MAXIMUM_RESTORE_RETRIES = 1
private const val RAW_MAXIMUM_REJECTED_PROFILES = 128
