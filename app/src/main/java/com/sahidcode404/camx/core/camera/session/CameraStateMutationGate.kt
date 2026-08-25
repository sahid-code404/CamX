package com.sahidcode404.camx.core.camera.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes short authoritative mutations on the camera-control dispatcher.
 *
 * Coordinator work is cancellable until it invokes the controller. Entering this gate is the
 * ownership-commit boundary: once accepted, cancellation cannot split a detach/state mutation from
 * the synchronous close and follow-up ownership mutation performed by the controller method.
 *
 * The outer NonCancellable context intentionally keeps the caller dispatcher unchanged. This avoids
 * prompt cancellation being delivered during the return hop from the camera-control dispatcher.
 * The inner block is deliberately non-suspending: waits, timeouts, joins, deferred completion, and
 * arbitrary long work are forbidden while the mutex is held.
 */
internal class CameraStateMutationGate(
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: () -> T): T = withContext(NonCancellable) {
        withContext(dispatcher) {
            mutex.withLock { block() }
        }
    }
}
