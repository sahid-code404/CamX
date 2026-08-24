package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.diagnostics.MediaStoreFailure
import com.sahidcode404.camx.core.camera.diagnostics.RawSessionRejected
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraStateTransitionsTest {
    @Test
    fun documentedPreviewPathIsAllowed() {
        val selection = selection("a")
        val waiting = CameraEngineState.WaitingForSurface(selection)
        val pausing = CameraEngineState.Pausing(
            selection.copy(sessionGeneration = SessionGeneration(2L)),
        )
        CameraStateTransitions.requireAllowed(waiting, pausing)
        CameraStateTransitions.requireAllowed(
            pausing,
            CameraEngineState.WaitingForSurface(pausing.selection),
        )
    }

    @Test
    fun closedOwnerCannotRestart() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.Closed,
                CameraEngineState.WaitingForSurface(null),
            )
        }
    }

    @Test
    fun rawTransactionCannotSwapSelectionOrCaptureToken() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.ConfiguringRaw(selection("a"), CaptureToken(1L)),
                CameraEngineState.CapturingRaw(selection("b"), CaptureToken(1L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.ConfiguringRaw(selection("a"), CaptureToken(1L)),
                CameraEngineState.CapturingRaw(selection("a"), CaptureToken(2L)),
            )
        }
    }

    @Test
    fun errorStatesEnforceFailurePolicy() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraEngineState.StructuralError(selection("a"), MediaStoreFailure("full"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraEngineState.RecoverableError(selection("a"), RawSessionRejected)
        }
    }

    @Test
    fun openingAndRecoveryCannotSwapSelectionIntent() {
        val first = selection("a")
        val second = selection("b")
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.WaitingForSurface(first),
                CameraEngineState.Opening(second, second.sessionGeneration),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.RecoverableError(first, MediaStoreFailure("full")),
                CameraEngineState.WaitingForSurface(second),
            )
        }
    }

    @Test
    fun pauseMayAdvanceOnlySessionGeneration() {
        val first = selection("a")
        val paused = first.copy(sessionGeneration = SessionGeneration(2L))
        CameraStateTransitions.requireAllowed(
            CameraEngineState.Previewing(first, firstFrameVerified = true),
            CameraEngineState.Pausing(paused),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.Previewing(first, firstFrameVerified = true),
                CameraEngineState.Pausing(selection("b")),
            )
        }
    }

    @Test
    fun rawSessionReplacementRequiresNewSessionButPreservesCaptureIdentity() {
        val preview = selection("a")
        val rawSelection = preview.copy(sessionGeneration = SessionGeneration(2L))
        val restoredSelection = rawSelection.copy(sessionGeneration = SessionGeneration(3L))
        val token = CaptureToken(1L)
        CameraStateTransitions.requireAllowed(
            CameraEngineState.Previewing(preview, firstFrameVerified = true),
            CameraEngineState.ConfiguringRaw(rawSelection, token),
        )
        CameraStateTransitions.requireAllowed(
            CameraEngineState.CapturingRaw(rawSelection, token),
            CameraEngineState.RestoringPreview(restoredSelection, token),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.CapturingRaw(rawSelection, token),
                CameraEngineState.RestoringPreview(rawSelection, token),
            )
        }
    }

    @Test
    fun switchingStrictlyAdvancesBothGenerations() {
        val current = selection("a")
        val waiting = CameraEngineState.WaitingForSurface(current)
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                waiting,
                CameraEngineState.Switching(current.routeId, selection("b")),
            )
        }

        val advanced = selection("b").copy(
            selectionGeneration = SelectionGeneration(2L),
            sessionGeneration = SessionGeneration(2L),
        )
        CameraStateTransitions.requireAllowed(
            waiting,
            CameraEngineState.Switching(current.routeId, advanced),
        )

        val zeroGeneration = advanced.copy(
            selectionGeneration = SelectionGeneration(0L),
            sessionGeneration = SessionGeneration(0L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.WaitingForSurface(null),
                CameraEngineState.Switching(null, zeroGeneration),
            )
        }
    }

    @Test
    fun switchCanCancelRawTransactionWithFreshGenerations() {
        val current = selection("a")
        val target = selection("b").copy(
            selectionGeneration = SelectionGeneration(2L),
            sessionGeneration = SessionGeneration(2L),
        )
        CameraStateTransitions.requireAllowed(
            CameraEngineState.PairingRaw(current, CaptureToken(1L)),
            CameraEngineState.Switching(current.routeId, target),
        )
        CameraStateTransitions.requireAllowed(
            CameraEngineState.RestoringPreview(current, CaptureToken(1L)),
            CameraEngineState.Switching(current.routeId, target),
        )
    }

    @Test
    fun openingDuplicatesMustAgreeOnSessionGeneration() {
        val active = selection("a")
        assertThrows(IllegalArgumentException::class.java) {
            CameraEngineState.Opening(active, SessionGeneration(2L))
        }
    }

    @Test
    fun firstFrameVerificationCannotRegress() {
        val active = selection("a")
        CameraStateTransitions.requireAllowed(
            CameraEngineState.Previewing(active, firstFrameVerified = false),
            CameraEngineState.Previewing(active, firstFrameVerified = true),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CameraStateTransitions.requireAllowed(
                CameraEngineState.Previewing(active, firstFrameVerified = true),
                CameraEngineState.Previewing(active, firstFrameVerified = false),
            )
        }
    }

    private fun selection(suffix: String) = ActiveCameraSelection(
        canonicalLensFingerprint = CanonicalLensFingerprint("lens:$suffix"),
        profileFingerprint = CameraProfileFingerprint("profile:$suffix"),
        routeId = CameraRouteId("route:$suffix"),
        selectionGeneration = SelectionGeneration(1L),
        sessionGeneration = SessionGeneration(1L),
    )
}
