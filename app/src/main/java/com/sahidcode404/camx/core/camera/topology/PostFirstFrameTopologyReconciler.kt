package com.sahidcode404.camx.core.camera.topology

import android.os.SystemClock
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface AdvertisedTopologyEvidenceProvider {
    /** Emits zero or more bounded current-evidence batches. A provider failure must not cancel peers. */
    suspend fun collect(emit: suspend (List<CameraEvidenceSnapshot>) -> Unit)
}

internal enum class EvidenceMergeResult {
    CHANGED,
    UNCHANGED,
    REJECTED,
}

/**
 * Holds only the current evidence record for each semantic evidence address.
 *
 * Stage-A sparse/minimal records and Stage-B enriched records therefore never coexist as historical
 * duplicates. Richer evidence wins independent of arrival order; equally rich conflicts use a
 * deterministic content key so provider scheduling cannot change the final bounded evidence set.
 */
internal class CurrentTopologyEvidenceAccumulator(
    private val environment: CameraEnvironmentFingerprint,
) {
    private data class EvidenceAddress(
        val source: CameraRouteSource,
        val transportId: String,
        val physicalId: String?,
        val logicalParentId: String?,
    )

    private val current = LinkedHashMap<EvidenceAddress, CameraMetadataEvidence>()
    private val completedAtBySource = LinkedHashMap<CameraRouteSource, Long>()

    val size: Int
        get() = current.size

    fun merge(batch: List<CameraEvidenceSnapshot>): EvidenceMergeResult {
        if (batch.isEmpty()) return EvidenceMergeResult.UNCHANGED
        if (batch.any { it.environment != environment }) return EvidenceMergeResult.REJECTED

        val proposed = LinkedHashMap(current)
        val proposedCompleted = LinkedHashMap(completedAtBySource)
        var changed = false
        for (snapshot in batch) {
            proposedCompleted[snapshot.source] = maxOf(
                proposedCompleted[snapshot.source] ?: 0L,
                snapshot.completedAtElapsedRealtimeNs,
            )
            for (candidate in snapshot.evidence) {
                if (candidate.source != snapshot.source) return EvidenceMergeResult.REJECTED
                val address = candidate.address()
                val existing = proposed[address]
                val selected = if (existing == null) candidate else preferred(existing, candidate)
                if (existing != selected) {
                    proposed[address] = selected
                    changed = true
                }
            }
            if (proposed.size > CameraTopologyResolver.MAX_TOTAL_EVIDENCE) {
                return EvidenceMergeResult.REJECTED
            }
        }

        completedAtBySource.clear()
        completedAtBySource.putAll(proposedCompleted)
        if (!changed) return EvidenceMergeResult.UNCHANGED
        current.clear()
        current.putAll(proposed)
        return EvidenceMergeResult.CHANGED
    }

    fun snapshots(): List<CameraEvidenceSnapshot> = CameraRouteSource.entries.mapNotNull { source ->
        val evidence = current.values
            .asSequence()
            .filter { it.source == source }
            .sortedBy(::stableContentKey)
            .toList()
        if (evidence.isEmpty()) return@mapNotNull null
        CameraEvidenceSnapshot(
            source = source,
            environment = environment,
            evidence = Collections.unmodifiableList(ArrayList(evidence)),
            completedAtElapsedRealtimeNs = completedAtBySource[source] ?: 0L,
        )
    }

    private fun CameraMetadataEvidence.address() = EvidenceAddress(
        source = source,
        transportId = transportId.value,
        physicalId = physicalId?.value,
        logicalParentId = logicalParentId?.value,
    )

    private fun preferred(
        existing: CameraMetadataEvidence,
        candidate: CameraMetadataEvidence,
    ): CameraMetadataEvidence {
        val existingRichness = richness(existing)
        val candidateRichness = richness(candidate)
        return when {
            candidateRichness > existingRichness -> candidate
            candidateRichness < existingRichness -> existing
            stableContentKey(candidate) < stableContentKey(existing) -> candidate
            else -> existing
        }
    }

    private fun richness(value: CameraMetadataEvidence): Int {
        var score = 0
        if (value.facing != LensFacing.UNKNOWN) score += 1
        score += value.focalLengthsMillimetres.size * 2
        if (value.sensorPhysicalWidthMillimetres != null) score += 2
        if (value.sensorPhysicalHeightMillimetres != null) score += 2
        if (value.activeArray != null) score += 2
        if (value.pixelArray != null) score += 2
        if (value.sensorOrientationDegrees != null) score += 2
        score += value.apertureValues.size * 2
        if (value.colorFilterArrangement != null) score += 2
        score += value.capabilities.previewStreams.size
        score += value.capabilities.fpsRanges.size * 2
        score += value.capabilities.rawSizes.size * 2
        return score
    }

    private fun stableContentKey(value: CameraMetadataEvidence): String = buildString {
        append(value.source.ordinal).append('|')
        append(value.transportId.value).append('|')
        append(value.physicalId?.value.orEmpty()).append('|')
        append(value.logicalParentId?.value.orEmpty()).append('|')
        append(value.facing.ordinal).append('|')
        append(value.focalLengthsMillimetres.sorted().joinToString(",") { it.toRawBits().toUInt().toString(16) })
        append('|').append(value.sensorPhysicalWidthMillimetres?.toRawBits()?.toUInt()?.toString(16).orEmpty())
        append('|').append(value.sensorPhysicalHeightMillimetres?.toRawBits()?.toUInt()?.toString(16).orEmpty())
        append('|').append(value.activeArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|').append(value.pixelArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|').append(value.sensorOrientationDegrees?.toString().orEmpty())
        append('|').append(value.apertureValues.sorted().joinToString(",") { it.toRawBits().toUInt().toString(16) })
        append('|').append(value.colorFilterArrangement?.toString().orEmpty())
        append('|').append(value.capabilities.previewStreams.sortedWith(compareBy(
            { it.type.ordinal },
            { it.size.width },
            { it.size.height },
            { it.minimumFrameDurationNs ?: Long.MAX_VALUE },
        )).joinToString(",") { "${it.type.ordinal}:${it.size.width}x${it.size.height}:${it.minimumFrameDurationNs}" })
        append('|').append(value.capabilities.fpsRanges.sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .joinToString(",") { "${it.minimum}-${it.maximum}" })
        append('|').append(value.capabilities.rawSizes.sortedWith(compareBy({ it.width }, { it.height }))
            .joinToString(",") { "${it.width}x${it.height}" })
    }
}

/**
 * One-shot, post-first-frame incremental reconciliation.
 *
 * Independent metadata providers execute concurrently on low-frequency background work. Every
 * credible bounded batch updates the current evidence set and may publish an improved immutable
 * topology immediately. CameraSessionController is never touched.
 */
internal class PostFirstFrameTopologyReconciler(
    private val environment: CameraEnvironmentFingerprint,
    private val repository: CameraTopologyRepository,
    private val providers: List<AdvertisedTopologyEvidenceProvider>,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        require(providers.isNotEmpty()) { "At least one advertised topology provider is required" }
        require(providers.size <= CameraTopologyResolver.MAX_PROVENANCE_SOURCES) {
            "Advertised topology provider count exceeds the provenance bound"
        }
    }

    fun startAfterFirstFrame() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        scope.launch {
            val previous = repository.topology.value
            val permit = repository.beginReconciliation(environment)
            val evidence = CurrentTopologyEvidenceAccumulator(environment)
            val publicationMutex = Mutex()
            val providerOutcomeMutex = Mutex()
            var publishedAnyBatch = false
            var completedProviders = 0
            var failedProviders = 0

            suspend fun publishBatch(batch: List<CameraEvidenceSnapshot>) {
                if (closed.get()) return
                publicationMutex.withLock {
                    if (closed.get()) return@withLock
                    when (evidence.merge(batch)) {
                        EvidenceMergeResult.REJECTED -> {
                            throw IllegalArgumentException("Advertised evidence batch exceeds bounds or environment")
                        }
                        EvidenceMergeResult.UNCHANGED -> return@withLock
                        EvidenceMergeResult.CHANGED -> Unit
                    }
                    val resolved = try {
                        CameraTopologyResolver.resolve(
                            environment = environment,
                            snapshots = evidence.snapshots(),
                            generatedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
                            previousTrustedTopology = previous,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IllegalArgumentException) {
                        throw IllegalArgumentException("Current advertised evidence cannot be reconciled")
                    }
                    if (!closed.get() && repository.publish(resolved, permit)) publishedAnyBatch = true
                }
            }

            coroutineScope {
                providers.forEach { provider ->
                    launch {
                        try {
                            provider.collect(::publishBatch)
                            providerOutcomeMutex.withLock { completedProviders += 1 }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            providerOutcomeMutex.withLock { failedProviders += 1 }
                        }
                    }
                }
            }

            if (closed.get() || publishedAnyBatch) return@launch
            val allProvidersCompletedSuccessfully = providerOutcomeMutex.withLock {
                completedProviders == providers.size && failedProviders == 0
            }
            if (!allProvidersCompletedSuccessfully) {
                // A temporary backend failure is absence of evidence, not proof that cached lenses vanished.
                return@launch
            }

            // Only a successful full reconciliation in which every provider completed and all proved
            // empty may intentionally clear a compatible previous topology.
            val empty = CameraTopologyResolver.resolve(
                environment = environment,
                snapshots = emptyList(),
                generatedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
                previousTrustedTopology = previous,
            )
            if (!closed.get()) repository.publish(empty, permit)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
    }
}
