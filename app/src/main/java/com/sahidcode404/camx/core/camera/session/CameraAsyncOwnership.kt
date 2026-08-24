package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicReference

internal enum class PendingCameraStage {
    OPEN,
    PREVIEW_CONFIGURATION,
    FIRST_FRAME,
    CLEANUP,
}

internal data class CameraOperationIdentity(
    val selection: ActiveCameraSelection,
    val surface: PreviewSurfaceIdentity,
    val previewAttempt: PreviewConfigurationAttemptKind,
    val captureToken: CaptureToken? = null,
)

/** Opaque, owner-bound, stage-bound, generation-bound, and single-consumption callback permit. */
internal class PendingCameraOperationPermit internal constructor(
    internal val ownerIdentity: Any,
    internal val sequence: Long,
    internal val stage: PendingCameraStage,
    internal val intent: CameraOperationIdentity,
)

internal enum class CameraCallbackDecision {
    ACCEPTED,
    STALE,
}

internal sealed interface ResourceAdoption<out T> {
    data class Adopted<T>(val resource: T) : ResourceAdoption<T>

    data object StaleClosed : ResourceAdoption<Nothing>
}

/** A callback-delivered resource can be adopted once or stale-closed once, never both. */
internal class CloseOnceCameraResource<T>(
    private val resource: T,
    private val close: (T) -> Unit,
) {
    private val disposition = AtomicReference(Disposition.PENDING)

    fun adopt(): T {
        check(disposition.compareAndSet(Disposition.PENDING, Disposition.ADOPTED)) {
            "Camera callback resource was already resolved"
        }
        return resource
    }

    fun closeIfUnadopted(): Boolean {
        if (!disposition.compareAndSet(Disposition.PENDING, Disposition.STALE_CLOSED)) return false
        close(resource)
        return true
    }

    internal fun disposition(): Disposition = disposition.get()

    internal enum class Disposition {
        PENDING,
        ADOPTED,
        STALE_CLOSED,
    }
}

/**
 * Synchronous state used only from [CameraStateMutationGate]. It publishes current intent before a
 * future Camera2 command is issued; asynchronous callbacks must re-enter the mutation gate and
 * consume the exact permit before they can publish state or adopt a delivered resource.
 */
internal class CameraAsyncOwnership {
    private val ownerIdentity = Any()
    private val pending = EnumMap<PendingCameraStage, PendingCameraOperationPermit>(
        PendingCameraStage::class.java,
    )
    private var nextSequence = 0L
    private var currentIntent: CameraOperationIdentity? = null
    private var shutdown = false

    fun publishIntent(intent: CameraOperationIdentity) {
        check(!shutdown) { "Camera async owner is shut down" }
        currentIntent = intent
        pending.clear()
    }

    fun invalidatePending() {
        if (shutdown) return
        currentIntent = null
        pending.clear()
    }

    fun shutdown(): Boolean {
        if (shutdown) return false
        shutdown = true
        currentIntent = null
        pending.clear()
        return true
    }

    fun begin(stage: PendingCameraStage): PendingCameraOperationPermit {
        check(!shutdown) { "Camera async owner is shut down" }
        val intent = checkNotNull(currentIntent) { "No authoritative camera intent is published" }
        check(nextSequence < Long.MAX_VALUE) { "Camera operation sequence exhausted" }
        val permit = PendingCameraOperationPermit(
            ownerIdentity = ownerIdentity,
            sequence = ++nextSequence,
            stage = stage,
            intent = intent,
        )
        pending[stage] = permit
        return permit
    }

    fun completeSignal(permit: PendingCameraOperationPermit): CameraCallbackDecision = consume(permit)

    fun <T> adoptOrClose(
        permit: PendingCameraOperationPermit,
        delivered: CloseOnceCameraResource<T>,
    ): ResourceAdoption<T> = if (consume(permit) == CameraCallbackDecision.ACCEPTED) {
        ResourceAdoption.Adopted(delivered.adopt())
    } else {
        delivered.closeIfUnadopted()
        ResourceAdoption.StaleClosed
    }

    internal fun authoritativeIntent(): CameraOperationIdentity? = currentIntent

    private fun consume(permit: PendingCameraOperationPermit): CameraCallbackDecision {
        val accepted = !shutdown &&
            permit.ownerIdentity === ownerIdentity &&
            permit.intent == currentIntent &&
            pending[permit.stage] === permit
        if (!accepted) return CameraCallbackDecision.STALE
        pending.remove(permit.stage)
        return CameraCallbackDecision.ACCEPTED
    }
}
