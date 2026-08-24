package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.frozenCopy
import java.security.MessageDigest
import java.util.Locale

/** Pure, deterministic, conservative topology resolver. */
object CameraTopologyResolver {
    const val SCHEMA = CameraSchemaVersions.TOPOLOGY

    fun resolve(
        environment: CameraEnvironmentFingerprint,
        snapshots: List<CameraEvidenceSnapshot>,
        generatedAtElapsedRealtimeNs: Long,
        previousTrustedTopology: CameraTopologySnapshot? = null,
    ): CameraTopologySnapshot {
        require(snapshots.all { it.environment == environment }) {
            "Cannot combine evidence from different camera environments"
        }

        val evidence = snapshots
            .asSequence()
            .flatMap { it.evidence.asSequence() }
            .map(CameraMetadataEvidence::frozenCopy)
            .sortedBy { item -> item.deterministicKey() }
            .toList()
        val routeGroups = evidence.groupBy { item ->
            item.transportId.value to item.physicalId?.value
        }
        val relationshipPhysicalIds = evidence.mapNotNull { it.physicalId?.value }.toSet()
        val compatiblePreviousTopology = previousTrustedTopology
            ?.takeIf { it.environment == environment && it.schema == SCHEMA }
        val previousRoutesById = compatiblePreviousTopology
            ?.routes
            ?.associateBy(CameraRoute::id)
            .orEmpty()
        val routesWithEvidence = routeGroups.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second.orEmpty() }))
            .map { (_, values) ->
                val preferred = values.minBy { it.sourcePriority() }
                val routeIdentity = buildString {
                    append(preferred.transportId.value)
                    append('|')
                    append(preferred.physicalId?.value.orEmpty())
                }
                val advertisedRoute = CameraRoute(
                    id = CameraRouteId("route:${stableHash(routeIdentity)}"),
                    source = preferred.source,
                    openCameraId = preferred.transportId,
                    physicalCameraId = preferred.physicalId,
                    capabilities = mergeCapabilities(values.map { it.capabilities }),
                    metadataTrust = CameraTrust.ADVERTISED,
                    sources = values.map { it.source }.toSet(),
                )
                val previous = previousRoutesById[advertisedRoute.id]
                    ?.takeIf {
                        it.openCameraId == advertisedRoute.openCameraId &&
                            it.physicalCameraId == advertisedRoute.physicalCameraId
                    }
                val route = if (previous == null) advertisedRoute else advertisedRoute.copy(
                    metadataTrust = previous.metadataTrust,
                    previewTrust = previous.previewTrust,
                    rawTrust = previous.rawTrust,
                )
                route to values
            }

        val candidateProfiles = routesWithEvidence.map { (route, routeEvidence) ->
            val opticalKey = strongOpticalKey(routeEvidence)
            val relationshipAnchor = relationshipAnchor(routeEvidence, relationshipPhysicalIds)
            // Uncertain evidence remains separate. Transport identity is used only as a conservative
            // fallback that prevents an unsafe merge; it never claims two routes are one lens.
            val canonicalKey = if (opticalKey != null && relationshipAnchor != null) {
                "related:$relationshipAnchor|$opticalKey"
            } else {
                "separate:${route.id.value}"
            }
            val canonicalFingerprint = CanonicalLensFingerprint("lens:${stableHash(canonicalKey)}")
            CameraProfile(
                fingerprint = CameraProfileFingerprint(
                    "profile:${stableHash(route.id.value)}",
                ),
                canonicalFingerprint = canonicalFingerprint,
                route = route,
            )
        }

        val previousCanonicalByRoute = compatiblePreviousTopology
            ?.canonicalLenses
            ?.flatMap { lens -> lens.profiles.map { profile -> profile.route.id to lens.fingerprint } }
            ?.toMap()
            .orEmpty()
        val candidateGroups = candidateProfiles
            .groupBy(CameraProfile::canonicalFingerprint)
            .entries
            .sortedBy { it.key.value }
        val preservedCandidateByGroup = candidateGroups.associate { (candidate, groupedProfiles) ->
            candidate to groupedProfiles.mapNotNull { previousCanonicalByRoute[it.route.id] }.distinct()
        }
        val preservationUseCount = preservedCandidateByGroup.values
            .filter { it.size == 1 }
            .groupingBy { it.single() }
            .eachCount()
        val evidenceByRoute = routesWithEvidence.associate { (route, values) -> route.id to values }
        val canonicalLenses = candidateGroups.map { (candidateFingerprint, groupedProfiles) ->
            val previousCandidates = preservedCandidateByGroup.getValue(candidateFingerprint)
            val fingerprint = previousCandidates.singleOrNull()
                ?.takeIf { preservationUseCount[it] == 1 }
                ?: candidateFingerprint
            val stableProfiles = groupedProfiles.map { profile ->
                profile.copy(canonicalFingerprint = fingerprint)
            }
            val facings = groupedProfiles
                .flatMap { evidenceByRoute.getValue(it.route.id) }
                .map(CameraMetadataEvidence::facing)
                .filterNot { it == LensFacing.UNKNOWN }
                .distinct()
            CanonicalLens(
                fingerprint = fingerprint,
                facing = facings.singleOrNull() ?: LensFacing.UNKNOWN,
                profiles = stableProfiles.sortedBy { it.fingerprint.value },
            )
        }

        return CameraTopologySnapshot(
            schema = SCHEMA,
            environment = environment,
            routes = candidateProfiles.map(CameraProfile::route).sortedBy { it.id.value },
            canonicalLenses = canonicalLenses,
            generatedAtElapsedRealtimeNs = generatedAtElapsedRealtimeNs,
            evidence = evidence,
        ).frozenCopy()
    }

    private fun CameraMetadataEvidence.deterministicKey(): String = buildString {
        append(transportId.value)
        append('|')
        append(physicalId?.value.orEmpty())
        append('|')
        append(logicalParentId?.value.orEmpty())
        append('|')
        append(source.ordinal)
        append('|')
        append(facing.ordinal)
        append('|')
        append(floatListKey(focalLengthsMillimetres).joinToString(","))
        append('|')
        append(sensorPhysicalWidthMillimetres?.let(::floatKey).orEmpty())
        append('|')
        append(sensorPhysicalHeightMillimetres?.let(::floatKey).orEmpty())
        append('|')
        append(activeArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|')
        append(pixelArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|')
        append(sensorOrientationDegrees?.toString().orEmpty())
        append('|')
        append(floatListKey(apertureValues).joinToString(","))
        append('|')
        append(colorFilterArrangement?.toString().orEmpty())
        append('|')
        append(capabilities.previewStreams.sortedWith(
            compareBy({ it.type.ordinal }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs }),
        ).joinToString(",") { "${it.type}:${it.size.width}x${it.size.height}:${it.minimumFrameDurationNs}" })
        append('|')
        append(capabilities.fpsRanges.sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .joinToString(",") { "${it.minimum}-${it.maximum}" })
        append('|')
        append(capabilities.rawSizes.sortedWith(compareBy({ it.width }, { it.height }))
            .joinToString(",") { "${it.width}x${it.height}" })
    }

    private fun CameraMetadataEvidence.sourcePriority(): Int = when (source) {
        CameraRouteSource.JAVA_PHYSICAL -> 0
        CameraRouteSource.JAVA_PUBLIC -> 1
        CameraRouteSource.NDK_ADVERTISED -> 2
        CameraRouteSource.NDK_DEEP -> 3
    }

    private fun mergeCapabilities(values: List<CameraCapabilities>) = CameraCapabilities(
        previewStreams = values.flatMap { it.previewStreams }.distinct().sortedWith(
            compareBy({ it.type.ordinal }, { it.size.width }, { it.size.height }),
        ),
        fpsRanges = values.flatMap { it.fpsRanges }.distinct().sortedWith(
            compareBy({ it.minimum }, { it.maximum }),
        ),
        rawSizes = values.flatMap { it.rawSizes }.distinct().sortedWith(
            compareBy({ it.width }, { it.height }),
        ),
    )

    private fun strongOpticalKey(values: List<CameraMetadataEvidence>): String? {
        val complete = values.firstOrNull { item ->
            item.focalLengthsMillimetres.isNotEmpty() &&
                item.sensorPhysicalWidthMillimetres != null &&
                item.sensorPhysicalHeightMillimetres != null &&
                item.activeArray != null &&
                item.pixelArray != null &&
                item.sensorOrientationDegrees != null
        } ?: return null
        if (values.any { candidate -> !opticallyCompatible(complete, candidate) }) return null
        return buildString {
            append(complete.facing.name)
            append('|')
            append(complete.focalLengthsMillimetres.sorted().joinToString(",") {
                String.format(Locale.ROOT, "%.4f", it)
            })
            append('|')
            append(String.format(Locale.ROOT, "%.4f", complete.sensorPhysicalWidthMillimetres))
            append('x')
            append(String.format(Locale.ROOT, "%.4f", complete.sensorPhysicalHeightMillimetres))
            append('|')
            append(complete.activeArray)
            append('|')
            append(complete.pixelArray)
            append('|')
            append(complete.colorFilterArrangement ?: "unknown")
            append('|')
            append(complete.sensorOrientationDegrees)
            append('|')
            append(complete.apertureValues.sorted().joinToString(",") {
                String.format(Locale.ROOT, "%.4f", it)
            })
        }
    }

    private fun relationshipAnchor(
        values: List<CameraMetadataEvidence>,
        relationshipPhysicalIds: Set<String>,
    ): String? {
        val explicitPhysicalIds = values.mapNotNull { it.physicalId?.value }.distinct()
        if (explicitPhysicalIds.size == 1) return explicitPhysicalIds.single()
        if (explicitPhysicalIds.size > 1) return null
        val transportIds = values.map { it.transportId.value }.distinct()
        return transportIds.singleOrNull()?.takeIf { it in relationshipPhysicalIds }
    }

    private fun opticallyCompatible(
        reference: CameraMetadataEvidence,
        candidate: CameraMetadataEvidence,
    ): Boolean {
        if (candidate.facing != LensFacing.UNKNOWN && candidate.facing != reference.facing) return false
        if (candidate.focalLengthsMillimetres.isNotEmpty() &&
            floatListKey(candidate.focalLengthsMillimetres) != floatListKey(reference.focalLengthsMillimetres)
        ) return false
        if (floatsConflict(candidate.sensorPhysicalWidthMillimetres, reference.sensorPhysicalWidthMillimetres)) {
            return false
        }
        if (floatsConflict(candidate.sensorPhysicalHeightMillimetres, reference.sensorPhysicalHeightMillimetres)) {
            return false
        }
        if (candidate.activeArray != null && candidate.activeArray != reference.activeArray) return false
        if (candidate.pixelArray != null && candidate.pixelArray != reference.pixelArray) return false
        if (candidate.sensorOrientationDegrees != null &&
            candidate.sensorOrientationDegrees != reference.sensorOrientationDegrees
        ) return false
        if (candidate.colorFilterArrangement != null &&
            candidate.colorFilterArrangement != reference.colorFilterArrangement
        ) return false
        if (candidate.apertureValues.isNotEmpty() &&
            floatListKey(candidate.apertureValues) != floatListKey(reference.apertureValues)
        ) return false
        return true
    }

    private fun floatsConflict(left: Float?, right: Float?): Boolean =
        left != null && right != null && floatKey(left) != floatKey(right)

    private fun floatListKey(values: List<Float>): List<String> = values.sorted().map(::floatKey)

    private fun floatKey(value: Float): String = String.format(Locale.ROOT, "%.4f", value)

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
