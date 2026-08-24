package com.sahidcode404.camx.core.camera.runtime

import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import java.util.concurrent.atomic.AtomicLong

data class CameraGenerationSnapshot(
    val selection: SelectionGeneration,
    val session: SessionGeneration,
    val capture: CaptureToken?,
)

class CameraGenerationGate {
    private val selection = AtomicLong(0L)
    private val session = AtomicLong(0L)
    private val captureSequence = AtomicLong(0L)
    private val activeCapture = AtomicLong(NO_CAPTURE)

    fun snapshot(): CameraGenerationSnapshot = CameraGenerationSnapshot(
        selection = SelectionGeneration(selection.get()),
        session = SessionGeneration(session.get()),
        capture = activeCapture.get().takeIf { it != NO_CAPTURE }?.let(::CaptureToken),
    )

    fun advanceSelection(): CameraGenerationSnapshot {
        selection.updateAndGet(::nextGeneration)
        session.updateAndGet(::nextGeneration)
        activeCapture.set(NO_CAPTURE)
        return snapshot()
    }

    fun advanceSession(): CameraGenerationSnapshot {
        session.updateAndGet(::nextGeneration)
        activeCapture.set(NO_CAPTURE)
        return snapshot()
    }

    fun beginCapture(): CaptureToken {
        val token = CaptureToken(captureSequence.updateAndGet(::nextGeneration))
        check(activeCapture.compareAndSet(NO_CAPTURE, token.value)) {
            "A capture transaction is already active"
        }
        return token
    }

    fun endCapture(token: CaptureToken): Boolean = activeCapture.compareAndSet(token.value, NO_CAPTURE)

    fun accepts(
        expectedSelection: SelectionGeneration,
        expectedSession: SessionGeneration,
    ): Boolean = selection.get() == expectedSelection.value && session.get() == expectedSession.value

    fun acceptsCapture(
        expectedSelection: SelectionGeneration,
        expectedSession: SessionGeneration,
        token: CaptureToken,
    ): Boolean = accepts(expectedSelection, expectedSession) && activeCapture.get() == token.value

    private fun nextGeneration(current: Long): Long {
        check(current < Long.MAX_VALUE) { "Camera generation exhausted" }
        return current + 1L
    }

    private companion object {
        const val NO_CAPTURE = 0L
    }
}
