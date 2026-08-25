package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.cache.DiscoveryCacheResetResult
import com.sahidcode404.camx.core.camera.topology.ReconciliationRequestResult
import java.util.concurrent.atomic.AtomicBoolean

enum class DeepRescanRequestResult {
    STARTED,
    NOT_READY,
    ALREADY_RUNNING,
    CLOSED,
}

/**
 * Serializes development-only discovery actions. It never owns Camera2 resources and never queues
 * an unbounded number of rescans; a second request while one is active is deterministically rejected.
 */
internal class DeepRescanCoordinator(
    private val firstFrameVerified: () -> Boolean,
    private val reconciliationRunning: () -> Boolean,
    private val setExplicitDeepRescan: (Boolean) -> Unit,
    private val requestReconciliation: ((() -> Unit) -> ReconciliationRequestResult),
    private val resetCaches: suspend () -> DiscoveryCacheResetResult,
) {
    private val diagnosticOperationActive = AtomicBoolean(false)

    fun requestDeepRescan(): DeepRescanRequestResult {
        if (!firstFrameVerified()) return DeepRescanRequestResult.NOT_READY
        if (!diagnosticOperationActive.compareAndSet(false, true)) {
            return DeepRescanRequestResult.ALREADY_RUNNING
        }
        setExplicitDeepRescan(true)
        val result = requestReconciliation {
            setExplicitDeepRescan(false)
            diagnosticOperationActive.set(false)
        }
        if (result != ReconciliationRequestResult.STARTED) {
            setExplicitDeepRescan(false)
            diagnosticOperationActive.set(false)
        }
        return when (result) {
            ReconciliationRequestResult.STARTED -> DeepRescanRequestResult.STARTED
            ReconciliationRequestResult.NOT_ARMED -> DeepRescanRequestResult.NOT_READY
            ReconciliationRequestResult.ALREADY_RUNNING -> DeepRescanRequestResult.ALREADY_RUNNING
            ReconciliationRequestResult.CLOSED -> DeepRescanRequestResult.CLOSED
        }
    }

    suspend fun resetDiscoveryCache(): DiscoveryCacheResetResult {
        if (reconciliationRunning()) return DiscoveryCacheResetResult.FAILED
        if (!diagnosticOperationActive.compareAndSet(false, true)) return DiscoveryCacheResetResult.FAILED
        return try {
            resetCaches()
        } finally {
            diagnosticOperationActive.set(false)
        }
    }

    fun operationActive(): Boolean = diagnosticOperationActive.get()
}
