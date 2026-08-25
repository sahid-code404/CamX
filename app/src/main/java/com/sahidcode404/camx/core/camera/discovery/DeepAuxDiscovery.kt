package com.sahidcode404.camx.core.camera.discovery

import android.os.Build
import android.os.SystemClock
import com.sahidcode404.camx.core.camera.diagnostics.Available
import com.sahidcode404.camx.core.camera.diagnostics.NativeCore
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CancellationException

internal const val DEEP_AUX_DEFAULT_LOW_NAMESPACE_MAX = 31
internal const val DEEP_AUX_DEFAULT_NEIGHBOR_RADIUS = 4
internal const val DEEP_AUX_DEFAULT_MAXIMUM_NUMERIC_ID = 1024
internal const val DEEP_AUX_DEFAULT_MAXIMUM_CANDIDATES = 96
internal const val DEEP_AUX_HARD_MAXIMUM_CANDIDATES = 128
internal const val DEEP_AUX_HARD_LOW_NAMESPACE_MAX = 63
internal const val DEEP_AUX_HARD_NEIGHBOR_RADIUS = 8
internal const val DEEP_AUX_MAX_ID_LENGTH = 128
private const val DEEP_AUX_MAX_INPUT_IDS = 512
private const val DEEP_AUX_MAX_NUMERIC_DIGITS = 10
private const val CAMERA_NDK_MIN_API = 24

enum class DeepAuxWave {
    HOT,
    NEARBY,
    LOW_NAMESPACE,
}

data class DeepAuxDiscoveryLimits(
    val lowNumericNamespaceMax: Int = DEEP_AUX_DEFAULT_LOW_NAMESPACE_MAX,
    val neighborRadius: Int = DEEP_AUX_DEFAULT_NEIGHBOR_RADIUS,
    val maximumNumericId: Int = DEEP_AUX_DEFAULT_MAXIMUM_NUMERIC_ID,
    val maximumCandidateCount: Int = DEEP_AUX_DEFAULT_MAXIMUM_CANDIDATES,
)

data class DeepAuxDiscoveryRequest(
    val previouslySessionVerifiedDeepIds: Collection<String> = emptyList(),
    val previouslySuccessfulDeepIds: Collection<String> = emptyList(),
    val cachedDiscoveredIds: Collection<String> = emptyList(),
    val advertisedIds: Collection<String> = emptyList(),
    val limits: DeepAuxDiscoveryLimits = DeepAuxDiscoveryLimits(),
)

data class DeepAuxCandidate(
    val transportId: String,
    val wave: DeepAuxWave,
)

data class DeepAuxPlan(val candidates: List<DeepAuxCandidate>) {
    fun wave(wave: DeepAuxWave): List<DeepAuxCandidate> = candidates.filter { it.wave == wave }
}

/** Numeric values are scan addresses only. This planner never assigns a photographic role. */
internal object DeepAuxCandidatePlanner {
    fun plan(request: DeepAuxDiscoveryRequest): DeepAuxPlan {
        val maximum = request.limits.maximumCandidateCount.coerceIn(0, DEEP_AUX_HARD_MAXIMUM_CANDIDATES)
        if (maximum == 0) return DeepAuxPlan(emptyList())
        val maximumNumeric = request.limits.maximumNumericId.coerceIn(0, DEEP_AUX_DEFAULT_MAXIMUM_NUMERIC_ID)
        val lowMax = request.limits.lowNumericNamespaceMax.coerceIn(
            0,
            minOf(maximumNumeric, DEEP_AUX_HARD_LOW_NAMESPACE_MAX),
        )
        val radius = request.limits.neighborRadius.coerceIn(0, DEEP_AUX_HARD_NEIGHBOR_RADIUS)
        val selected = LinkedHashMap<String, DeepAuxWave>(maximum)

        fun addExact(values: Collection<String>, wave: DeepAuxWave) {
            values.asSequence()
                .take(DEEP_AUX_MAX_INPUT_IDS)
                .filter(::isSafeExactId)
                .distinct()
                .sortedWith(opaqueComparator)
                .forEach { id -> if (selected.size < maximum) selected.putIfAbsent(id, wave) }
        }

        // Learned exact opaque IDs are hot even when they are nonnumeric.
        addExact(request.previouslySessionVerifiedDeepIds, DeepAuxWave.HOT)
        addExact(request.previouslySuccessfulDeepIds, DeepAuxWave.HOT)
        addExact(request.cachedDiscoveredIds, DeepAuxWave.HOT)

        val advertisedNumeric = request.advertisedIds.asSequence()
            .take(DEEP_AUX_MAX_INPUT_IDS)
            .mapNotNull { it.asBoundedNumericId(maximumNumeric) }
            .distinct()
            .sorted()
            .toList()
        advertisedNumeric.forEach { value ->
            if (selected.size < maximum) selected.putIfAbsent(value.toString(), DeepAuxWave.NEARBY)
        }

        val knownNumeric = sequenceOf(
            request.previouslySessionVerifiedDeepIds,
            request.previouslySuccessfulDeepIds,
            request.cachedDiscoveredIds,
            request.advertisedIds,
        ).flatMap(Collection<String>::asSequence)
            .take(DEEP_AUX_MAX_INPUT_IDS)
            .mapNotNull { it.asBoundedNumericId(maximumNumeric) }
            .distinct()
            .sorted()
            .toList()
        for (known in knownNumeric) {
            for (distance in 1..radius) {
                val below = known - distance
                val above = known + distance
                if (below >= 0 && selected.size < maximum) selected.putIfAbsent(below.toString(), DeepAuxWave.NEARBY)
                if (above <= maximumNumeric && selected.size < maximum) selected.putIfAbsent(above.toString(), DeepAuxWave.NEARBY)
            }
        }

        for (value in 0..lowMax) {
            if (selected.size >= maximum) break
            selected.putIfAbsent(value.toString(), DeepAuxWave.LOW_NAMESPACE)
        }
        return DeepAuxPlan(immutableList(selected.map { DeepAuxCandidate(it.key, it.value) }))
    }

    internal fun isSafeExactId(value: String): Boolean =
        value.isNotBlank() && value.length <= DEEP_AUX_MAX_ID_LENGTH && value.none(Char::isISOControl)

    private fun String.asBoundedNumericId(maximum: Int): Int? {
        if (isEmpty() || length > DEEP_AUX_MAX_NUMERIC_DIGITS || any { it !in '0'..'9' }) return null
        return toIntOrNull()?.takeIf { it in 0..maximum }
    }

    private val opaqueComparator = Comparator<String> { left, right ->
        val leftKey = stableOpaqueKey(left)
        val rightKey = stableOpaqueKey(right)
        leftKey.compareTo(rightKey).takeIf { it != 0 } ?: left.compareTo(right)
    }

    private fun stableOpaqueKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("deep-plan|$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}

enum class DeepAuxOutcomeKind {
    VALID_METADATA,
    NOT_FOUND_OR_UNAVAILABLE,
    ACCESS_DENIED,
    SERVICE_ERROR,
    MALFORMED_METADATA,
    BOUND_EXCEEDED,
    RUNTIME_UNAVAILABLE,
}

data class DeepAuxCandidateOutcome(
    val candidate: DeepAuxCandidate,
    val outcome: DeepAuxOutcomeKind,
)

data class NdkDeepEvidenceReport(
    val snapshot: CameraEvidenceSnapshot,
    val outcomes: List<DeepAuxCandidateOutcome>,
    val failures: List<NdkAdvertisedEvidenceFailure>,
)

internal object NdkDeepNativeBridge {
    fun collect(deviceApi: Int, candidates: Array<String>): ByteArray? {
        if (deviceApi < CAMERA_NDK_MIN_API || candidates.size > DEEP_AUX_HARD_MAXIMUM_CANDIDATES) return null
        if (NativeCore.availability != Available) return null
        if (candidates.any { !DeepAuxCandidatePlanner.isSafeExactId(it) }) return null
        return runCatching { nativeCollectCandidates(deviceApi, candidates) }.getOrNull()
    }

    private external fun nativeCollectCandidates(androidApi: Int, candidateIds: Array<String>): ByteArray?
}

/** Metadata-only wave executor. No CameraDevice, session, request, or frame probe exists here. */
internal class NdkDeepAuxDiscoveryBackend(
    private val environment: CameraEnvironmentFingerprint,
    private val metadataBudget: DiscoveryMetadataBudget,
    private val deviceApi: () -> Int = { Build.VERSION.SDK_INT },
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val rawCollector: (Int, Array<String>) -> ByteArray? = NdkDeepNativeBridge::collect,
) {
    suspend fun discoverIncrementally(
        request: DeepAuxDiscoveryRequest,
        emit: suspend (NdkDeepEvidenceReport) -> Unit,
    ): NdkDeepEvidenceReport {
        val plan = DeepAuxCandidatePlanner.plan(request)
        val allEvidence = LinkedHashMap<String, CameraMetadataEvidence>()
        val allFailures = ArrayList<NdkAdvertisedEvidenceFailure>()
        val allOutcomes = ArrayList<DeepAuxCandidateOutcome>()

        for (wave in DeepAuxWave.entries) {
            val candidates = plan.wave(wave)
            if (candidates.isEmpty()) continue
            val ids = candidates.map { it.transportId }.toTypedArray()
            val payload = try {
                metadataBudget.withNativeMetadata { rawCollector(deviceApi(), ids) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val decoded = NdkAdvertisedSnapshotCodec.decode(payload, CameraRouteSource.NDK_DEEP)
            val report = if (decoded == null || !decoded.runtimeAvailable) {
                val outcomes = candidates.map { DeepAuxCandidateOutcome(it, DeepAuxOutcomeKind.RUNTIME_UNAVAILABLE) }
                NdkDeepEvidenceReport(
                    snapshot = snapshot(emptyList()),
                    outcomes = immutableList(outcomes),
                    failures = emptyList(),
                )
            } else {
                val evidenceById = decoded.evidence.associateBy { it.transportId.value }
                val failuresById = decoded.failures.groupBy { it.transportId }
                val outcomes = candidates.map { candidate ->
                    val kind = when {
                        candidate.transportId in evidenceById -> DeepAuxOutcomeKind.VALID_METADATA
                        else -> failuresById[candidate.transportId]
                            ?.firstOrNull()
                            ?.kind
                            ?.toDeepOutcome()
                            ?: DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE
                    }
                    DeepAuxCandidateOutcome(candidate, kind)
                }
                NdkDeepEvidenceReport(
                    snapshot = snapshot(decoded.evidence),
                    outcomes = immutableList(outcomes),
                    failures = immutableList(decoded.failures),
                )
            }
            report.snapshot.evidence.forEach { allEvidence[it.transportId.value] = it }
            allFailures += report.failures
            allOutcomes += report.outcomes
            emit(report)
        }

        return NdkDeepEvidenceReport(
            snapshot = snapshot(allEvidence.values.sortedBy { it.transportId.value }),
            outcomes = immutableList(allOutcomes),
            failures = immutableList(allFailures),
        )
    }

    suspend fun discover(request: DeepAuxDiscoveryRequest): NdkDeepEvidenceReport =
        discoverIncrementally(request) {}

    private fun snapshot(evidence: Collection<CameraMetadataEvidence>) = CameraEvidenceSnapshot(
        source = CameraRouteSource.NDK_DEEP,
        environment = environment,
        evidence = immutableList(evidence),
        completedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
    )

    private fun NdkAdvertisedEvidenceFailureKind.toDeepOutcome(): DeepAuxOutcomeKind = when (this) {
        NdkAdvertisedEvidenceFailureKind.ACCESS_DENIED -> DeepAuxOutcomeKind.ACCESS_DENIED
        NdkAdvertisedEvidenceFailureKind.SERVICE_ERROR -> DeepAuxOutcomeKind.SERVICE_ERROR
        NdkAdvertisedEvidenceFailureKind.MALFORMED_METADATA,
        NdkAdvertisedEvidenceFailureKind.MALFORMED_NATIVE_PAYLOAD,
        -> DeepAuxOutcomeKind.MALFORMED_METADATA
        NdkAdvertisedEvidenceFailureKind.CAMERA_ID_LIMIT_EXCEEDED,
        NdkAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED,
        -> DeepAuxOutcomeKind.BOUND_EXCEEDED
        else -> DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
