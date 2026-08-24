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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The sole Camera2 resource owner. This foundation owns serialization, callback-thread lifecycle,
 * generations, and state publication. CAMX-103 fills in public Camera2 open/session calls here; no
 * other path receives that authority.
 */
class CameraSessionController {
    private val operationMutex = Mutex()
    private val callbackThread = HandlerThread("camx-camera-control").apply { start() }
    private val callbackDispatcher: CoroutineDispatcher = Handler(callbackThread.looper)
        .asCoroutineDispatcher("camx-camera-control")
    private val generations = CameraGenerationGate()
    private val shutdownRequested = AtomicBoolean(false)
    private val shutdownComplete = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow<CameraEngineState>(
        CameraEngineState.WaitingForSurface(selection = null),
    )
    private val mutableResources = MutableStateFlow(CameraResourceSnapshot(cameraWorkers = 1))

    val state: StateFlow<CameraEngineState> = mutableState.asStateFlow()
    val resources: StateFlow<CameraResourceSnapshot> = mutableResources.asStateFlow()

    suspend fun select(selection: ActiveCameraSelection) = serialized {
        val current = mutableState.value
        val currentRoute = current.selectionOrNull()?.routeId
        CameraStateTransitions.requirePhaseAllowed(current, CameraEnginePhase.SWITCHING)
        val nextGenerations = generations.advanceSelection()
        val effectiveSelection = selection.copy(
            selectionGeneration = nextGenerations.selection,
            sessionGeneration = nextGenerations.session,
        )
        transition(CameraEngineState.Switching(from = currentRoute, to = effectiveSelection))
        transition(CameraEngineState.WaitingForSurface(selection = effectiveSelection))
    }

    suspend fun pause() = serialized {
        if (mutableState.value == CameraEngineState.Closed) return@serialized
        val nextGenerations = generations.advanceSession()
        val selection = mutableState.value.selectionOrNull()?.copy(
            sessionGeneration = nextGenerations.session,
        )
        transition(CameraEngineState.Pausing(selection))
        transition(CameraEngineState.WaitingForSurface(selection))
    }

    suspend fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            withContext(NonCancellable) { shutdownComplete.await() }
            return
        }
        var terminalFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                withContext(callbackDispatcher) {
                    operationMutex.withLock {
                        generations.advanceSession()
                        mutableState.value = CameraEngineState.Closed
                        mutableResources.value = CameraResourceSnapshot()
                    }
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

    private suspend fun serialized(block: suspend () -> Unit) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        withContext(callbackDispatcher) {
            operationMutex.withLock {
                check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
                block()
            }
        }
    }

    private fun transition(next: CameraEngineState) {
        val current = mutableState.value
        CameraStateTransitions.requireAllowed(current, next)
        mutableState.value = next
    }
}
