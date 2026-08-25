package com.sahidcode404.camx.core.camera.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes short authoritative mutations on the camera-control dispatcher.
 *
 * Once a caller has entered this ownership gate, cancellation cannot split the authoritative state
 * mutation from the controller cleanup transaction that immediately follows it. The block itself is
 * deliberately non-suspending: platform callback waits, timeouts, joins, deferred completion, and
 * arbitrary long work are forbidden while this mutex is held.
 *
 * Cancellation remains effective in coordinator work before the controller is invoked (surface wait,
 * policy preparation, obsolete targets). Entering this gate is the ownership-commit boundary.
 */
internal class CameraStateMutationGate(
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: () -> T): T = withContext(NonCancellable + dispatcher) {
        mutex.withLock { block() }
    }
}
