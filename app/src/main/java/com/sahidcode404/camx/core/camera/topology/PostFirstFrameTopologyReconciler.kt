package com.sahidcode404.camx.core.camera.topology

import android.os.SystemClock
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
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
    /** Emits one or more bounded evidence batches. A provider failure must not cancel its peers. */
    suspend fun collect(emit: suspend (List<CameraEvidenceSnapshot>) -> Unit)
}

/**
 * One-shot, post-first-frame incremental reconciliation.
 *
 * Independent metadata providers execute concurrently on low-frequency background work. Every
 * credible bounded batch is merged with evidence already observed in this reconciliation and may
 * publish an improved immutable topology immediately. CameraSessionController is never touched.
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
            val snapshots = ArrayList<CameraEvidenceSnapshot>()
            val publicationMutex = Mutex()
            var publishedAnyBatch = false

            suspend fun publishBatch(batch: List<CameraEvidenceSnapshot>) {
                if (batch.isEmpty() || closed.get()) return
                publicationMutex.withLock {
                    if (closed.get()) return@withLock
                    require(batch.all { it.environment == environment }) {
                        "Advertised evidence batch environment mismatch"
                    }
                    val proposedTotal = snapshots.sumOf { it.evidence.size } + batch.sumOf { it.evidence.size }
                    if (proposedTotal > CameraTopologyResolver.MAX_TOTAL_EVIDENCE) return@withLock
                    snapshots += batch
                    val resolved = try {
                        CameraTopologyResolver.resolve(
                            environment = environment,
                            snapshots = snapshots,
                            generatedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
                            previousTrustedTopology = previous,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IllegalArgumentException) {
                        return@withLock
                    }
                    if (!closed.get() && repository.publish(resolved, permit)) publishedAnyBatch = true
                }
            }

            coroutineScope {
                providers.forEach { provider ->
                    launch {
                        try {
                            provider.collect(::publishBatch)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // Backend failure is isolated. Healthy peers may continue publishing.
                        }
                    }
                }
            }

            if (closed.get() || publishedAnyBatch) return@launch
            // If every provider failed or returned no evidence, publish an empty current topology so
            // stale cached cameras are not misrepresented as freshly discovered.
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
