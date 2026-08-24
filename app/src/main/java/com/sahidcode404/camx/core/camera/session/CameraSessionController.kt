package com.sahidcode404.camx.core.camera.session

import android.os.Handler
import android.os.HandlerThread
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraResourceSnapshot
import com.sahidcode404.camx.core.camera.runtime.CameraGenerationGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The sole Camera2 resource owner. This foundation owns serialization, callback-thread lifecycle,
 * generations, and state publication. CAMX-103 fills in public Camera2 open/session calls here; no
 * other path receives that authority.
 */
class CameraSessionController {
    private val callbackThread = HandlerThread("camx-camera-control").apply { start() }
    private val callbackDispatcher: CoroutineDispatcher = Handler(callbackThread.looper)
        .asCoroutineDispatcher("camx-camera-control")
    private val mutationGate = CameraStateMutationGate(callbackDispatcher)
    private val asyncOwnership = CameraAsyncOwnership()
    private val generations = CameraGenerationGate()
    private val shutdownRequested = AtomicBoolean(false)
    private val shutdownComplete = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow<CameraEngineState>(
        CameraEngineState.WaitingForSurface(selection = null),
    )
    private val mutableResources = MutableStateFlow(CameraResourceSnapshot(cameraWorkers = 1))

    val state: StateFlow<CameraEngineState> = mutableState.asStateFlow()
    val resources: StateFlow<CameraResourceSnapshot> = mutableResources.asStateFlow()

    suspend fun select(selection: ActiveCameraSelection) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            val current = mutableState.value
            val currentRoute = current.selectionOrNull()?.routeId
            CameraStateTransitions.requirePhaseAllowed(current, CameraEnginePhase.SWITCHING)
            val nextGenerations = generations.advanceSelection()
            asyncOwnership.invalidatePending()
            val effectiveSelection = selection.copy(
                selectionGeneration = nextGenerations.selection,
                sessionGeneration = nextGenerations.session,
            )
            transition(CameraEngineState.Switching(from = currentRoute, to = effectiveSelection))
            transition(CameraEngineState.WaitingForSurface(selection = effectiveSelection))
        }
    }

    suspend fun pause() {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            if (mutableState.value == CameraEngineState.Closed) return@mutate
            val nextGenerations = generations.advanceSession()
            asyncOwnership.invalidatePending()
            val selection = mutableState.value.selectionOrNull()?.copy(
                sessionGeneration = nextGenerations.session,
            )
            transition(CameraEngineState.Pausing(selection))
            transition(CameraEngineState.WaitingForSurface(selection))
        }
    }

    suspend fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            withContext(NonCancellable) { shutdownComplete.await() }
            return
        }
        var terminalFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                mutationGate.mutate {
                    generations.advanceSession()
                    asyncOwnership.shutdown()
                    mutableState.value = CameraEngineState.Closed
                    mutableResources.value = CameraResourceSnapshot()
                }
            } catch (error: Throwable) {
                terminalFailure = error
            }
            try {
                withContext(Dispatchers.IO) {
                    callbackThread.quitSafely()
                    callbackThread.join()
                }
            } catch (error: Throwable) {
                val primary = terminalFailure
                if (primary == null) {
                    terminalFailure = error
                } else if (primary !== error) {
                    primary.addSuppressed(error)
                }
            } finally {
                val failure = terminalFailure
                if (failure == null) {
                    shutdownComplete.complete(Unit)
                } else {
                    shutdownComplete.completeExceptionally(failure)
                }
            }
        }
        terminalFailure?.let { throw it }
    }

    private fun transition(next: CameraEngineState) {
        val current = mutableState.value
        CameraStateTransitions.requireAllowed(current, next)
        mutableState.value = next
    }
}
