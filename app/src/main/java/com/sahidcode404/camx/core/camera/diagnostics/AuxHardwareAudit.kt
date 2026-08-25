package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.cache.DiscoveryCacheResetResult
import com.sahidcode404.camx.core.camera.discovery.DeepAuxOutcomeKind
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceFailureKind
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationKind
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.NdkDeepEvidenceReport
import com.sahidcode404.camx.core.camera.lens.CameraLensProjection
import com.sahidcode404.camx.core.camera.lens.LensProfileEligibility
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuxDiscoveryPipelineCounters(
    val javaAdvertisedIds: Int = 0,
    val javaPublicEvidence: Int = 0,
    val logicalCameraCount: Int = 0,
    val physicalMemberRelationships: Int = 0,
    val physicalMetadataSuccesses: Int = 0,
    val physicalMetadataFailures: Int = 0,
    val ndkAdvertisedEvidence: Int = 0,
    val deepCandidateAddressesAttempted: Int = 0,
    val deepValidMetadata: Int = 0,
    val deepTerminalNegative: Int = 0,
    val deepAccessDenied: Int = 0,
    val deepTemporaryOrServiceFailure: Int = 0,
    val javaDeepCertificationAttempts: Int = 0,
    val javaDeepCertified: Int = 0,
    val javaDeepCertificationFailuresByType: Map<String, Int> = emptyMap(),
    val firstFrameToLevel2FirstPublicationMs: Long? = null,
    val firstFrameToFirstNdkDeepValidMs: Long? = null,
    val firstFrameToFirstJavaDeepCertificationMs: Long? = null,
    val firstFrameToFirstNewSelectableLensMs: Long? = null,
    val fullDeepReconciliationDurationMs: Long? = null,
    val incrementalTopologyPublications: Long = 0L,
)

data class AuxProfileAudit(
    val fingerprint: String,
    val provenance: List<String>,
    val routeKind: String,
    val metadataTrust: String,
    val previewTrust: String,
    val javaPublic: Boolean,
    val javaPhysical: Boolean,
    val javaDeepProbed: Boolean,
    val ndkAdvertised: Boolean,
    val ndkDeep: Boolean,
    val selectable: Boolean,
    val rejectionReason: String?,
    val structurallyFailed: Boolean,
)

data class AuxLensAudit(
    val fingerprint: String,
    val facing: String,
    val opticalMetadata: String,
    val profileCount: Int,
    val preferredProfile: String?,
    val verificationStatus: String,
    val profiles: List<AuxProfileAudit>,
)

data class AuxDeepCandidateAudit(
    val fingerprint: String,
    val ndkOutcome: String,
    val javaCertification: String?,
    val routeResolved: Boolean,
    val profileSelectable: Boolean,
    val previewVerified: Boolean,
)

data class AuxHardwareAuditSnapshot(
    val counters: AuxDiscoveryPipelineCounters = AuxDiscoveryPipelineCounters(),
    val resolvedRoutes: Int = 0,
    val resolvedProfiles: Int = 0,
    val canonicalLenses: Int = 0,
    val selectableCanonicalLenses: Int = 0,
    val nonselectableCanonicalLenses: Int = 0,
    val sessionVerifiedLenses: Int = 0,
    val lenses: List<AuxLensAudit> = emptyList(),
    val deepCandidates: List<AuxDeepCandidateAudit> = emptyList(),
    val deepRescanResult: DeepRescanRequestResult? = null,
    val cacheResetResult: DiscoveryCacheResetResult? = null,
)

internal data class AuxDiscoveryTrackerSnapshot(
    val counters: AuxDiscoveryPipelineCounters,
    val deepOutcomes: Map<String, DeepAuxOutcomeKind>,
    val javaCertification: Map<String, JavaDeepCertificationKind>,
    val deepRescanResult: DeepRescanRequestResult?,
    val cacheResetResult: DiscoveryCacheResetResult?,
)

/** Bounded in-memory diagnostics only. Raw opaque IDs never leave this internal tracker. */
internal class AuxDiscoveryAuditTracker(
    private val clockNanos: () -> Long,
) {
    private val changesMutable = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changesMutable.asStateFlow()

    private val javaAdvertisedIds = LinkedHashSet<String>()
    private val javaPublicEvidence = LinkedHashSet<String>()
    private val logicalParents = LinkedHashSet<String>()
    private val physicalRelationships = LinkedHashSet<String>()
    private val physicalMetadataSuccesses = LinkedHashSet<String>()
    private val physicalMetadataFailures = LinkedHashSet<String>()
    private val ndkAdvertisedEvidence = LinkedHashSet<String>()
    private val deepOutcomes = LinkedHashMap<String, DeepAuxOutcomeKind>()
    private val javaCertification = LinkedHashMap<String, JavaDeepCertificationKind>()

    private var firstFrameNs: Long? = null
    private var runStartedNs: Long? = null
    private var baselineSelectable = 0
    private var baselinePublicationCount = 0L
    private var level2FirstPublicationNs: Long? = null
    private var firstNdkDeepValidNs: Long? = null
    private var firstJavaDeepCertificationNs: Long? = null
    private var firstNewSelectableNs: Long? = null
    private var runFinishedNs: Long? = null
    private var latestPublicationCount = 0L
    private var deepRescanResult: DeepRescanRequestResult? = null
    private var cacheResetResult: DiscoveryCacheResetResult? = null

    @Synchronized
    fun markFirstFrame(atNanos: Long = clockNanos()) {
        if (firstFrameNs == null) firstFrameNs = atNanos.coerceAtLeast(0L)
        changed()
    }

    @Synchronized
    fun beginRun(selectableCount: Int, publicationCount: Long) {
        javaAdvertisedIds.clear()
        javaPublicEvidence.clear()
        logicalParents.clear()
        physicalRelationships.clear()
        physicalMetadataSuccesses.clear()
        physicalMetadataFailures.clear()
        ndkAdvertisedEvidence.clear()
        deepOutcomes.clear()
        javaCertification.clear()
        runStartedNs = clockNanos().coerceAtLeast(0L)
        runFinishedNs = null
        level2FirstPublicationNs = null
        firstNdkDeepValidNs = null
        firstJavaDeepCertificationNs = null
        firstNewSelectableNs = null
        baselineSelectable = selectableCount.coerceAtLeast(0)
        baselinePublicationCount = publicationCount.coerceAtLeast(0L)
        latestPublicationCount = baselinePublicationCount
        changed()
    }

    @Synchronized
    fun onJavaAdvertised(report: JavaAdvertisedEvidenceReport) {
        markLevel2Publication()
        report.snapshots.forEach { snapshot ->
            snapshot.evidence.forEach { evidence ->
                val address = address(evidence.source, evidence.transportId.value, evidence.physicalId?.value)
                if (evidence.source == CameraRouteSource.JAVA_PUBLIC) {
                    javaAdvertisedIds += evidence.transportId.value
                    javaPublicEvidence += address
                }
                if (evidence.source == CameraRouteSource.JAVA_PHYSICAL) {
                    evidence.logicalParentId?.value?.let(logicalParents::add)
                    physicalRelationships += address
                    physicalMetadataSuccesses += address
                }
            }
        }
        report.failures.forEach { failure ->
            failure.transportId?.let(javaAdvertisedIds::add)
            if (failure.kind == JavaAdvertisedEvidenceFailureKind.PHYSICAL_CHARACTERISTICS_UNAVAILABLE ||
                failure.kind == JavaAdvertisedEvidenceFailureKind.INVALID_PHYSICAL_ID ||
                failure.kind == JavaAdvertisedEvidenceFailureKind.PHYSICAL_ID_LIMIT_EXCEEDED
            ) {
                physicalMetadataFailures += "${failure.transportId.orEmpty()}|${failure.physicalId.orEmpty()}|${failure.kind.name}"
            }
        }
        changed()
    }

    @Synchronized
    fun onNdkAdvertised(report: NdkAdvertisedEvidenceReport) {
        markLevel2Publication()
        report.snapshot.evidence.forEach { evidence ->
            ndkAdvertisedEvidence += address(evidence.source, evidence.transportId.value, evidence.physicalId?.value)
        }
        changed()
    }

    @Synchronized
    fun onNdkDeep(report: NdkDeepEvidenceReport) {
        report.outcomes.forEach { outcome ->
            deepOutcomes[outcome.candidate.transportId] = outcome.outcome
            if (outcome.outcome == DeepAuxOutcomeKind.VALID_METADATA && firstNdkDeepValidNs == null) {
                firstNdkDeepValidNs = clockNanos().coerceAtLeast(0L)
            }
        }
        changed()
    }

    @Synchronized
    fun onJavaDeep(report: JavaDeepCertificationReport) {
        report.outcomes.forEach { outcome ->
            javaCertification[outcome.candidate.transportId] = outcome.kind
            if (outcome.kind == JavaDeepCertificationKind.CERTIFIED && firstJavaDeepCertificationNs == null) {
                firstJavaDeepCertificationNs = clockNanos().coerceAtLeast(0L)
            }
        }
        changed()
    }

    @Synchronized
    fun onTopologyState(selectableCount: Int, publicationCount: Long) {
        latestPublicationCount = maxOf(latestPublicationCount, publicationCount)
        if (selectableCount > baselineSelectable && firstNewSelectableNs == null && runStartedNs != null) {
            firstNewSelectableNs = clockNanos().coerceAtLeast(0L)
        }
        changed()
    }

    @Synchronized
    fun finishRun() {
        runFinishedNs = clockNanos().coerceAtLeast(0L)
        changed()
    }

    @Synchronized
    fun recordDeepRescanResult(result: DeepRescanRequestResult) {
        deepRescanResult = result
        changed()
    }

    @Synchronized
    fun recordCacheResetResult(result: DiscoveryCacheResetResult) {
        cacheResetResult = result
        changed()
    }

    @Synchronized
    fun snapshot(): AuxDiscoveryTrackerSnapshot {
        val terminal = deepOutcomes.values.count { outcome ->
            outcome == DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE ||
                outcome == DeepAuxOutcomeKind.INVALID_OPERATION ||
                outcome == DeepAuxOutcomeKind.MALFORMED_METADATA ||
                outcome == DeepAuxOutcomeKind.BOUND_EXCEEDED
        }
        val temporary = deepOutcomes.values.count { outcome ->
            outcome == DeepAuxOutcomeKind.SERVICE_ERROR ||
                outcome == DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE ||
                outcome == DeepAuxOutcomeKind.RUNTIME_UNAVAILABLE
        }
        val certificationFailures = javaCertification.values
            .filter { it != JavaDeepCertificationKind.CERTIFIED }
            .groupingBy { it.name }
            .eachCount()
            .toSortedMap()
        val counters = AuxDiscoveryPipelineCounters(
            javaAdvertisedIds = javaAdvertisedIds.size,
            javaPublicEvidence = javaPublicEvidence.size,
            logicalCameraCount = logicalParents.size,
            physicalMemberRelationships = physicalRelationships.size,
            physicalMetadataSuccesses = physicalMetadataSuccesses.size,
            physicalMetadataFailures = physicalMetadataFailures.size,
            ndkAdvertisedEvidence = ndkAdvertisedEvidence.size,
            deepCandidateAddressesAttempted = deepOutcomes.size,
            deepValidMetadata = deepOutcomes.values.count { it == DeepAuxOutcomeKind.VALID_METADATA },
            deepTerminalNegative = terminal,
            deepAccessDenied = deepOutcomes.values.count { it == DeepAuxOutcomeKind.ACCESS_DENIED },
            deepTemporaryOrServiceFailure = temporary,
            javaDeepCertificationAttempts = javaCertification.size,
            javaDeepCertified = javaCertification.values.count { it == JavaDeepCertificationKind.CERTIFIED },
            javaDeepCertificationFailuresByType = Collections.unmodifiableMap(LinkedHashMap(certificationFailures)),
            firstFrameToLevel2FirstPublicationMs = elapsedFromFirstFrame(level2FirstPublicationNs),
            firstFrameToFirstNdkDeepValidMs = elapsedFromFirstFrame(firstNdkDeepValidNs),
            firstFrameToFirstJavaDeepCertificationMs = elapsedFromFirstFrame(firstJavaDeepCertificationNs),
            firstFrameToFirstNewSelectableLensMs = elapsedFromFirstFrame(firstNewSelectableNs),
            fullDeepReconciliationDurationMs = elapsed(runStartedNs, runFinishedNs),
            incrementalTopologyPublications = (latestPublicationCount - baselinePublicationCount).coerceAtLeast(0L),
        )
        return AuxDiscoveryTrackerSnapshot(
            counters = counters,
            deepOutcomes = Collections.unmodifiableMap(LinkedHashMap(deepOutcomes)),
            javaCertification = Collections.unmodifiableMap(LinkedHashMap(javaCertification)),
            deepRescanResult = deepRescanResult,
            cacheResetResult = cacheResetResult,
        )
    }

    private fun markLevel2Publication() {
        if (level2FirstPublicationNs == null) level2FirstPublicationNs = clockNanos().coerceAtLeast(0L)
    }

    private fun elapsedFromFirstFrame(eventNs: Long?): Long? = elapsed(firstFrameNs, eventNs)

    private fun elapsed(startNs: Long?, endNs: Long?): Long? {
        if (startNs == null || endNs == null || endNs < startNs) return null
        return (endNs - startNs) / 1_000_000L
    }

    private fun address(source: CameraRouteSource, id: String, physical: String?): String =
        "${source.name}|$id|${physical.orEmpty()}"

    private fun changed() {
        val current = changesMutable.value
        changesMutable.value = if (current == Long.MAX_VALUE) 0L else current + 1L
    }
}

/** Pure projection of internal discovery state into sanitized, deterministic hardware-audit output. */
internal object AuxHardwareAudit {
    fun build(
        topology: CameraTopologySnapshot?,
        projection: CameraLensProjection,
        tracker: AuxDiscoveryTrackerSnapshot,
    ): AuxHardwareAuditSnapshot {
        if (topology == null) {
            return AuxHardwareAuditSnapshot(
                counters = tracker.counters,
                deepRescanResult = tracker.deepRescanResult,
                cacheResetResult = tracker.cacheResetResult,
            )
        }
        val itemByLens = projection.items.associateBy { it.canonicalFingerprint }
        val lenses = topology.canonicalLenses.sortedBy { it.fingerprint.value }.map { lens ->
            val profiles = lens.profiles.sortedBy { it.fingerprint.value }.map { profile ->
                profileAudit(profile, projection)
            }
            val preferred = projection.targets[lens.fingerprint]?.profileFingerprint?.value
            AuxLensAudit(
                fingerprint = sanitized("lens", lens.fingerprint.value),
                facing = lens.facing.name,
                opticalMetadata = opticalMetadata(topology, lens.profiles),
                profileCount = lens.profiles.size,
                preferredProfile = preferred?.let { sanitized("profile", it) },
                verificationStatus = itemByLens[lens.fingerprint]?.status?.name ?: "DIAGNOSTIC_ONLY",
                profiles = Collections.unmodifiableList(ArrayList(profiles)),
            )
        }
        val selectableProfiles = topology.canonicalLenses.asSequence()
            .flatMap { it.profiles.asSequence() }
            .filter { projection.eligibilityByProfile[it.fingerprint] is LensProfileEligibility.Eligible }
            .toList()
        val deepCandidates = tracker.deepOutcomes.keys.sorted().map { id ->
            val candidateProfiles = topology.canonicalLenses.asSequence()
                .flatMap { lens -> lens.profiles.asSequence().map { lens to it } }
                .filter { (_, profile) -> profile.route.openCameraId.value == id }
                .toList()
            val routeResolved = candidateProfiles.isNotEmpty()
            val profileSelectable = candidateProfiles.any { (_, profile) ->
                projection.eligibilityByProfile[profile.fingerprint] is LensProfileEligibility.Eligible
            }
            val previewVerified = candidateProfiles.any { (lens, profile) ->
                itemByLens[lens.fingerprint]?.status == LensTestStatus.VERIFIED &&
                    projection.targets[lens.fingerprint]?.profileFingerprint == profile.fingerprint
            }
            AuxDeepCandidateAudit(
                fingerprint = sanitized("deep", id),
                ndkOutcome = tracker.deepOutcomes.getValue(id).name,
                javaCertification = tracker.javaCertification[id]?.name,
                routeResolved = routeResolved,
                profileSelectable = profileSelectable,
                previewVerified = previewVerified,
            )
        }
        val profiles = topology.canonicalLenses.sumOf { it.profiles.size }
        val verified = projection.items.count { it.status == LensTestStatus.VERIFIED }
        return AuxHardwareAuditSnapshot(
            counters = tracker.counters,
            resolvedRoutes = topology.routes.size,
            resolvedProfiles = profiles,
            canonicalLenses = topology.canonicalLenses.size,
            selectableCanonicalLenses = projection.items.size,
            nonselectableCanonicalLenses = (topology.canonicalLenses.size - projection.items.size).coerceAtLeast(0),
            sessionVerifiedLenses = verified,
            lenses = Collections.unmodifiableList(ArrayList(lenses)),
            deepCandidates = Collections.unmodifiableList(ArrayList(deepCandidates)),
            deepRescanResult = tracker.deepRescanResult,
            cacheResetResult = tracker.cacheResetResult,
        )
    }

    private fun profileAudit(profile: CameraProfile, projection: CameraLensProjection): AuxProfileAudit {
        val route = profile.route
        val eligibility = projection.eligibilityByProfile[profile.fingerprint]
        val rejection = (eligibility as? LensProfileEligibility.Rejected)?.reason
        return AuxProfileAudit(
            fingerprint = sanitized("profile", profile.fingerprint.value),
            provenance = route.sources.map { it.name }.sorted(),
            routeKind = if (route.physicalCameraId == null) "DIRECT" else "PHYSICAL_TARGET",
            metadataTrust = route.metadataTrust.name,
            previewTrust = route.previewTrust.name,
            javaPublic = CameraRouteSource.JAVA_PUBLIC in route.sources,
            javaPhysical = CameraRouteSource.JAVA_PHYSICAL in route.sources,
            javaDeepProbed = CameraRouteSource.JAVA_DEEP_PROBED in route.sources,
            ndkAdvertised = CameraRouteSource.NDK_ADVERTISED in route.sources,
            ndkDeep = CameraRouteSource.NDK_DEEP in route.sources,
            selectable = eligibility is LensProfileEligibility.Eligible,
            rejectionReason = rejection?.name,
            structurallyFailed = rejection?.name == "STRUCTURALLY_FAILED_PROFILE",
        )
    }

    private fun opticalMetadata(topology: CameraTopologySnapshot, profiles: List<CameraProfile>): String {
        val routeIds = profiles.map { it.route.id }.toSet()
        val routes = topology.routes.filter { it.id in routeIds }
        val evidence = topology.evidence.filter { item -> routes.any { route ->
            item.transportId == route.openCameraId && item.physicalId == route.physicalCameraId
        } }
        val focals = evidence.flatMap { it.focalLengthsMillimetres }.distinct().sorted()
        val widths = evidence.mapNotNull { it.sensorPhysicalWidthMillimetres }.distinct().sorted()
        val focalText = if (focals.isEmpty()) "focal=?" else "focal=${focals.joinToString(",")}mm"
        val sensorText = if (widths.isEmpty()) "sensorWidth=?" else "sensorWidth=${widths.joinToString(",")}mm"
        return "$focalText $sensorText"
    }

    private fun sanitized(kind: String, value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("aux-audit|$kind|$value".toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (index in 0 until 8) append("%02x".format(bytes[index]))
        }
    }
}
