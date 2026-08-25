package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.lens.StableOneXReferenceResolver
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.StableLensReferenceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LensInventoryReadiness {
    DISCOVERING_INITIAL,
    READY,
    REFRESH_PENDING,
}

enum class LensInventorySource {
    CACHE,
    INITIAL_RECONCILIATION,
    EXPLICIT_RESCAN,
}

data class LensInventoryStatus(
    val readiness: LensInventoryReadiness,
    val source: LensInventorySource?,
    val structuralPublicationCount: Long,
)

internal data class LensInventoryCompletion(
    val topologyToPersist: CameraTopologySnapshot?,
    val referenceToPersist: StableLensReferenceSnapshot?,
    val structuralPublished: Boolean,
)

/**
 * Normal-UI inventory gate. Discovery/topology diagnostics may evolve incrementally, while this
 * coordinator publishes only coherent canonical inventory snapshots to the lens strip.
 */
internal class LensInventoryCoordinator(
    private val environment: CameraEnvironmentFingerprint,
    private val runtimeApiLevel: Int,
) {
    private val mutableTopology = MutableStateFlow<CameraTopologySnapshot?>(null)
    private val mutableStableReference = MutableStateFlow<CanonicalLensFingerprint?>(null)
    private val mutableStatus = MutableStateFlow(
        LensInventoryStatus(
            readiness = LensInventoryReadiness.DISCOVERING_INITIAL,
            source = null,
            structuralPublicationCount = 0L,
        ),
    )
    private var latestCandidate: CameraTopologySnapshot? = null

    val topology: StateFlow<CameraTopologySnapshot?> = mutableTopology.asStateFlow()
    val stableOneXReference: StateFlow<CanonicalLensFingerprint?> = mutableStableReference.asStateFlow()
    val status: StateFlow<LensInventoryStatus> = mutableStatus.asStateFlow()

    @Synchronized
    fun acceptCompatibleCache(
        snapshot: CameraTopologySnapshot,
        persistedReference: CanonicalLensFingerprint?,
    ): LensInventoryCompletion {
        if (!compatible(snapshot) || mutableTopology.value != null) {
            return LensInventoryCompletion(null, null, false)
        }
        latestCandidate = snapshot
        val reference = resolveReference(snapshot, persistedReference)
        mutableStableReference.value = reference
        mutableTopology.value = snapshot
        mutableStatus.value = LensInventoryStatus(
            readiness = LensInventoryReadiness.READY,
            source = LensInventorySource.CACHE,
            structuralPublicationCount = incrementPublicationCount(),
        )
        return LensInventoryCompletion(
            topologyToPersist = null,
            referenceToPersist = referenceSnapshot(reference),
            structuralPublished = true,
        )
    }

    /** Receives every internal reconciliation candidate without exposing it to normal UI. */
    @Synchronized
    fun observeCandidate(snapshot: CameraTopologySnapshot?) {
        if (snapshot != null && compatible(snapshot)) latestCandidate = snapshot
    }

    /**
     * Completes the one automatic reconciliation. First install publishes exactly once here. A warm
     * cache remains structurally frozen while the newer coherent snapshot is persisted for next launch.
     */
    @Synchronized
    fun completeAutomaticReconciliation(
        finalSnapshot: CameraTopologySnapshot? = latestCandidate,
    ): LensInventoryCompletion {
        val candidate = finalSnapshot?.takeIf(::compatible) ?: latestCandidate?.takeIf(::compatible)
            ?: return LensInventoryCompletion(null, null, false)
        latestCandidate = candidate
        val current = mutableTopology.value
        return if (current == null) {
            val reference = resolveReference(candidate, mutableStableReference.value)
            mutableStableReference.value = reference
            mutableTopology.value = candidate
            mutableStatus.value = LensInventoryStatus(
                readiness = LensInventoryReadiness.READY,
                source = LensInventorySource.INITIAL_RECONCILIATION,
                structuralPublicationCount = incrementPublicationCount(),
            )
            LensInventoryCompletion(
                topologyToPersist = candidate,
                referenceToPersist = referenceSnapshot(reference),
                structuralPublished = true,
            )
        } else {
            val nextLaunchReference = resolveReference(candidate, mutableStableReference.value)
            LensInventoryCompletion(
                topologyToPersist = candidate,
                referenceToPersist = referenceSnapshot(nextLaunchReference),
                structuralPublished = false,
            )
        }
    }

    private fun resolveReference(
        snapshot: CameraTopologySnapshot,
        preferred: CanonicalLensFingerprint?,
    ): CanonicalLensFingerprint? = StableOneXReferenceResolver.resolve(
        topology = snapshot,
        candidates = snapshot.canonicalLenses,
        preferred = preferred,
        runtimeApiLevel = runtimeApiLevel,
    )

    private fun referenceSnapshot(reference: CanonicalLensFingerprint?): StableLensReferenceSnapshot? =
        reference?.let {
            StableLensReferenceSnapshot(
                schema = CameraSchemaVersions.LENS_REFERENCE,
                environment = environment,
                canonicalFingerprint = it,
            )
        }

    private fun compatible(snapshot: CameraTopologySnapshot): Boolean =
        snapshot.environment == environment && snapshot.schema == CameraSchemaVersions.TOPOLOGY

    private fun incrementPublicationCount(): Long {
        val current = mutableStatus.value.structuralPublicationCount
        check(current < Long.MAX_VALUE) { "Lens inventory publication count exhausted" }
        return current + 1L
    }
}
