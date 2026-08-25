package com.sahidcode404.camx.core.camera.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Serializes short authoritative mutations on the camera-control dispatcher.
 *
 * Waiting to acquire the gate remains cancellable so an obsolete orchestration intent can disappear
 * before it owns camera state. Once the mutex is acquired, the caller has crossed the ownership
 * boundary: dispatcher handoff and the non-suspending mutation finish in a NonCancellable context.
 *
 * The mutation block itself must stay short and non-suspending. Platform waits, timeouts, joins,
 * deferred completion, discovery, disk IO, and arbitrary long work are forbidden while this mutex is
 * held.
 */
internal class CameraStateMutationGate(
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: () -> T): T {
        mutex.lock() // cancellable until this caller becomes the authoritative mutation owner
        return try {
            withContext(NonCancellable + dispatcher) { block() }
        } finally {
            mutex.unlock()
        }
    }
}
