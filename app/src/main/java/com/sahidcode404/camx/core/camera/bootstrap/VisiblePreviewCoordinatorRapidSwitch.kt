package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.LensSwitchDiagnostics
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.session.CameraEngineState

/**
 * Small state holder for latest-intent coalescing and bounded switch metrics.
 * It contains canonical/profile identity only; no raw Camera2 transport id is exposed.
 */
internal class VisiblePreviewRapidSwitchState(
    private val clockNanos: () -> Long,
    private val sink: (LensSwitchDiagnostics) -> Unit,
) {
    var latestRequestedLens: CanonicalLensFingerprint? = null
        private set
    var lastVerifiedSelection: ActiveCameraSelection? = null
        private set

    private var latestTapNs: Long? = null
    private var acceptedNs: Long? = null
    private var superseded = 0L
    private var opens = 0L
    private var retries = 0L
    private var fallbacks = 0L
    private var lastOpeningKey: String? = null
    private var snapshot = LensSwitchDiagnostics()

    fun request(lens: CanonicalLensFingerprint, tapNs: Long) {
        val previous = latestRequestedLens
        if (previous != null && previous != lens) superseded += 1L
        latestRequestedLens = lens
        latestTapNs = tapNs.coerceAtLeast(0L)
        acceptedNs = now()
        lastOpeningKey = null
        snapshot = LensSwitchDiagnostics(
            tapToAcceptedMs = elapsed(latestTapNs, acceptedNs),
            supersededIntentCount = superseded,
            actualOpenCount = opens,
            transientRetryCount = retries,
            fallbackToLastVerifiedCount = fallbacks,
        )
        publish()
    }

    /** Redirects the same user intent to a recovery target without pretending it was another tap. */
    fun redirect(lens: CanonicalLensFingerprint) {
        latestRequestedLens = lens
        lastOpeningKey = null
    }

    fun recordVerifiedSelection(selection: ActiveCameraSelection) {
        lastVerifiedSelection = selection
    }

    fun clearLatestIf(lens: CanonicalLensFingerprint?) {
        if (lens != null && latestRequestedLens == lens) latestRequestedLens = null
    }

    fun clearPending() {
        latestRequestedLens = null
    }

    fun markCleanupComplete() {
        if (latestTapNs == null) return
        updateDuration { base, value -> base.copy(tapToCleanupCompleteMs = value) }
    }

    fun markRetry() {
        retries += 1L
        snapshot = snapshot.copy(transientRetryCount = retries)
        publish()
    }

    fun markFallback() {
        fallbacks += 1L
        snapshot = snapshot.copy(fallbackToLastVerifiedCount = fallbacks)
        publish()
    }

    fun observe(state: CameraEngineState) {
        if (latestTapNs == null) return
        when (state) {
            is CameraEngineState.Opening -> {
                val key = buildString {
                    append(state.selection.routeId.value)
                    append(':').append(state.selection.selectionGeneration.value)
                    append(':').append(state.selection.sessionGeneration.value)
                }
                if (lastOpeningKey != key) {
                    lastOpeningKey = key
                    opens += 1L
                }
                snapshot = snapshot.copy(
                    tapToOpenRequestedMs = elapsed(latestTapNs, now()),
                    actualOpenCount = opens,
                )
                publish()
            }
            is CameraEngineState.ConfiguringPreview -> updateDuration { base, value ->
                base.copy(tapToCameraOpenedMs = value)
            }
            is CameraEngineState.Previewing -> if (state.firstFrameVerified) {
                val duration = elapsed(latestTapNs, now())
                snapshot = snapshot.copy(
                    tapToSessionConfiguredMs = snapshot.tapToSessionConfiguredMs ?: duration,
                    tapToFirstFrameMs = duration,
                    tapToPreviewVerifiedMs = duration,
                )
                publish()
            } else {
                updateDuration { base, value -> base.copy(tapToSessionConfiguredMs = value) }
            }
            else -> Unit
        }
    }

    fun snapshot(): LensSwitchDiagnostics = snapshot

    private fun updateDuration(
        block: (LensSwitchDiagnostics, Long?) -> LensSwitchDiagnostics,
    ) {
        snapshot = block(snapshot, elapsed(latestTapNs, now()))
        publish()
    }

    private fun publish() {
        snapshot = snapshot.copy(
            supersededIntentCount = superseded,
            actualOpenCount = opens,
            transientRetryCount = retries,
            fallbackToLastVerifiedCount = fallbacks,
        )
        sink(snapshot)
    }

    private fun now(): Long = clockNanos().coerceAtLeast(0L)

    private fun elapsed(start: Long?, end: Long?): Long? {
        if (start == null || end == null || end < start) return null
        return (end - start) / 1_000_000L
    }
}
