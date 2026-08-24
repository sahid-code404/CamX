package com.sahidcode404.camx.core.camera.discovery

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceHolder
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

const val AUX_MAX_PUBLIC_IDS = 64
const val AUX_MAX_PHYSICAL_IDS_PER_LOGICAL = 64
const val AUX_MAX_FOCAL_LENGTHS = 16
const val AUX_MAX_APERTURES = 16
const val AUX_MAX_PREVIEW_STREAMS = 128
const val AUX_MAX_FPS_RANGES = 64
const val AUX_MAX_RAW_SIZES = 64

/** Bounded immutable metadata record. Camera identifiers remain opaque strings. */
data class JavaAdvertisedCameraRecord(
    val queriedId: String,
    val facing: LensFacing,
    val focalLengthsMillimetres: List<Float>,
    val sensorPhysicalWidthMillimetres: Float?,
    val sensorPhysicalHeightMillimetres: Float?,
    val activeArray: IntSize?,
    val pixelArray: IntSize?,
    val sensorOrientationDegrees: Int?,
    val apertureValues: List<Float>,
    val colorFilterArrangement: Int?,
    val capabilities: CameraCapabilities,
    val physicalIds: List<String>,
)

internal interface JavaAdvertisedCameraMetadataSource {
    fun advertisedIds(): List<String>
    fun read(id: String): JavaAdvertisedCameraRecord?
}

enum class JavaAdvertisedEvidenceFailureKind {
    ID_ENUMERATION_UNAVAILABLE,
    PUBLIC_ID_LIMIT_EXCEEDED,
    INVALID_PUBLIC_ID,
    CHARACTERISTICS_UNAVAILABLE,
    PHYSICAL_ID_LIMIT_EXCEEDED,
    INVALID_PHYSICAL_ID,
    PHYSICAL_CHARACTERISTICS_UNAVAILABLE,
    METADATA_BOUND_EXCEEDED,
}

data class JavaAdvertisedEvidenceFailure(
    val kind: JavaAdvertisedEvidenceFailureKind,
    val transportId: String? = null,
    val physicalId: String? = null,
)

data class JavaAdvertisedEvidenceReport(
    val snapshot: CameraEvidenceSnapshot,
    val failures: List<JavaAdvertisedEvidenceFailure>,
)

/**
 * Full public Camera2 advertised evidence backend for CAMX-107.
 *
 * STARTUP_SEED deliberately returns no work: the frozen CAMX-102 seed path remains the first-frame
 * bootstrap. ADVERTISED/DEEP are bounded metadata-only passes and never acquire a CameraDevice.
 */
class AndroidAdvertisedCameraEvidenceBackend(
    cameraManager: CameraManager,
    private val environment: CameraEnvironmentFingerprint,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val source: JavaAdvertisedCameraMetadataSource = AndroidJavaAdvertisedCameraMetadataSource(cameraManager),
) : CameraEvidenceBackend {
    override suspend fun discover(depth: DiscoveryDepth): CameraEvidenceSnapshot = discoverReport(depth).snapshot

    suspend fun discoverReport(depth: DiscoveryDepth): JavaAdvertisedEvidenceReport {
        if (depth == DiscoveryDepth.STARTUP_SEED) return report(emptyList(), emptyList())
        coroutineContext.ensureActive()
        val failures = ArrayList<JavaAdvertisedEvidenceFailure>()
        val ids = try {
            source.advertisedIds().toList()
        } catch (_: Exception) {
            failures += JavaAdvertisedEvidenceFailure(JavaAdvertisedEvidenceFailureKind.ID_ENUMERATION_UNAVAILABLE)
            return report(emptyList(), failures)
        }
        if (ids.size > AUX_MAX_PUBLIC_IDS) {
            failures += JavaAdvertisedEvidenceFailure(JavaAdvertisedEvidenceFailureKind.PUBLIC_ID_LIMIT_EXCEEDED)
            return report(emptyList(), failures)
        }

        val evidence = ArrayList<CameraMetadataEvidence>()
        val stableIds = ids.asSequence().distinct().sortedWith(::opaqueCompare).toList()
        for (rawId in stableIds) {
            coroutineContext.ensureActive()
            if (rawId.isBlank()) {
                failures += JavaAdvertisedEvidenceFailure(
                    JavaAdvertisedEvidenceFailureKind.INVALID_PUBLIC_ID,
                    transportId = rawId,
                )
                continue
            }
            val publicRecord = readBounded(rawId, failures, physical = false) ?: continue
            val parentId = CameraTransportId(rawId)
            evidence += publicRecord.toEvidence(
                source = CameraRouteSource.JAVA_PUBLIC,
                transportId = parentId,
            )

            val physicalIds = publicRecord.physicalIds
            if (physicalIds.size > AUX_MAX_PHYSICAL_IDS_PER_LOGICAL) {
                failures += JavaAdvertisedEvidenceFailure(
                    JavaAdvertisedEvidenceFailureKind.PHYSICAL_ID_LIMIT_EXCEEDED,
                    transportId = rawId,
                )
                continue
            }
            for (rawPhysicalId in physicalIds.asSequence().distinct().sortedWith(::opaqueCompare)) {
                coroutineContext.ensureActive()
                if (rawPhysicalId.isBlank()) {
                    failures += JavaAdvertisedEvidenceFailure(
                        JavaAdvertisedEvidenceFailureKind.INVALID_PHYSICAL_ID,
                        transportId = rawId,
                        physicalId = rawPhysicalId,
                    )
                    continue
                }
                val physicalRecord = readBounded(rawPhysicalId, failures, physical = true)
                val physicalId = PhysicalCameraId(rawPhysicalId)
                evidence += if (physicalRecord == null) {
                    // Public logical membership is useful evidence even when the physical metadata query is
                    // inaccessible. Do not fabricate independent open support or copy parent capabilities.
                    CameraMetadataEvidence(
                        source = CameraRouteSource.JAVA_PHYSICAL,
                        transportId = parentId,
                        physicalId = physicalId,
                        logicalParentId = parentId,
                        facing = publicRecord.facing,
                    )
                } else {
                    physicalRecord.toEvidence(
                        source = CameraRouteSource.JAVA_PHYSICAL,
                        transportId = parentId,
                        physicalId = physicalId,
                        logicalParentId = parentId,
                    )
                }
            }
        }
        return report(evidence.sortedBy(::evidenceKey), failures)
    }

    private fun readBounded(
        id: String,
        failures: MutableList<JavaAdvertisedEvidenceFailure>,
        physical: Boolean,
    ): JavaAdvertisedCameraRecord? {
        val record = try {
            source.read(id)
        } catch (_: Exception) {
            null
        }
        if (record == null) {
            failures += JavaAdvertisedEvidenceFailure(
                if (physical) JavaAdvertisedEvidenceFailureKind.PHYSICAL_CHARACTERISTICS_UNAVAILABLE
                else JavaAdvertisedEvidenceFailureKind.CHARACTERISTICS_UNAVAILABLE,
                transportId = if (physical) null else id,
                physicalId = if (physical) id else null,
            )
            return null
        }
        if (record.queriedId != id ||
            record.focalLengthsMillimetres.size > AUX_MAX_FOCAL_LENGTHS ||
            record.apertureValues.size > AUX_MAX_APERTURES ||
            record.capabilities.previewStreams.size > AUX_MAX_PREVIEW_STREAMS ||
            record.capabilities.fpsRanges.size > AUX_MAX_FPS_RANGES ||
            record.capabilities.rawSizes.size > AUX_MAX_RAW_SIZES
        ) {
            failures += JavaAdvertisedEvidenceFailure(
                JavaAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED,
                transportId = if (physical) null else id,
                physicalId = if (physical) id else null,
            )
            return null
        }
        return record
    }

    private fun report(
        evidence: List<CameraMetadataEvidence>,
        failures: List<JavaAdvertisedEvidenceFailure>,
    ) = JavaAdvertisedEvidenceReport(
        snapshot = CameraEvidenceSnapshot(
            source = CameraRouteSource.JAVA_PUBLIC,
            environment = environment,
            evidence = immutableList(evidence),
            completedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
        ),
        failures = immutableList(failures),
    )

    private fun JavaAdvertisedCameraRecord.toEvidence(
        source: CameraRouteSource,
        transportId: CameraTransportId,
        physicalId: PhysicalCameraId? = null,
        logicalParentId: CameraTransportId? = null,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = transportId,
        physicalId = physicalId,
        logicalParentId = logicalParentId,
        facing = facing,
        focalLengthsMillimetres = immutableList(focalLengthsMillimetres),
        sensorPhysicalWidthMillimetres = sensorPhysicalWidthMillimetres,
        sensorPhysicalHeightMillimetres = sensorPhysicalHeightMillimetres,
        activeArray = activeArray,
        pixelArray = pixelArray,
        sensorOrientationDegrees = sensorOrientationDegrees,
        apertureValues = immutableList(apertureValues),
        colorFilterArrangement = colorFilterArrangement,
        capabilities = capabilities.copy(
            previewStreams = immutableList(capabilities.previewStreams),
            fpsRanges = immutableList(capabilities.fpsRanges),
            rawSizes = immutableList(capabilities.rawSizes),
        ),
    )

    private companion object {
        fun opaqueCompare(left: String, right: String): Int = stableOpaqueKey(left).compareTo(stableOpaqueKey(right))

        fun stableOpaqueKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun evidenceKey(value: CameraMetadataEvidence): String = buildString {
            append(stableOpaqueKey(value.transportId.value))
            append('|')
            append(value.physicalId?.value?.let(::stableOpaqueKey).orEmpty())
            append('|')
            append(value.source.ordinal)
        }

        fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}

internal class AndroidJavaAdvertisedCameraMetadataSource(
    private val cameraManager: CameraManager,
) : JavaAdvertisedCameraMetadataSource {
    override fun advertisedIds(): List<String> = cameraManager.cameraIdList.toList()

    override fun read(id: String): JavaAdvertisedCameraRecord? {
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val capabilitiesArray = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty()
        val rawAdvertised = capabilitiesArray.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val logicalAdvertised = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            capabilitiesArray.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

        val previewStreams = map?.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            .asSequence()
            .take(AUX_MAX_PREVIEW_STREAMS + 1)
            .filter { it.width > 0 && it.height > 0 }
            .map { size ->
                val duration = runCatching {
                    map?.getOutputMinFrameDuration(SurfaceHolder::class.java, size) ?: 0L
                }.getOrDefault(0L)
                CameraStreamCapability(
                    type = PreviewStreamType.CAMERA2_PRIVATE,
                    size = IntSize(size.width, size.height),
                    minimumFrameDurationNs = duration.takeIf { it > 0L },
                )
            }
            .distinct()
            .sortedWith(compareBy({ it.size.area }, { it.size.width }, { it.size.height }))
            .toList()
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .asSequence()
            .take(AUX_MAX_FPS_RANGES + 1)
            .filter { it.lower > 0 && it.upper >= it.lower }
            .map { CameraFpsCapability(it.lower, it.upper) }
            .distinct()
            .sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .toList()
        val rawSizes = if (rawAdvertised) {
            map?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
                .asSequence()
                .take(AUX_MAX_RAW_SIZES + 1)
                .filter { it.width > 0 && it.height > 0 }
                .map { IntSize(it.width, it.height) }
                .distinct()
                .sortedWith(compareBy({ it.area }, { it.width }, { it.height }))
                .toList()
        } else emptyList()

        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS).orEmpty()
            .asSequence().filter { it.isFinite() && it > 0f }.distinct().sorted().take(AUX_MAX_FOCAL_LENGTHS + 1).toList()
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES).orEmpty()
            .asSequence().filter { it.isFinite() && it > 0f }.distinct().sorted().take(AUX_MAX_APERTURES + 1).toList()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
        val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?.takeIf { it in 0..270 && it % 90 == 0 }
        val physicalIds = if (logicalAdvertised) {
            characteristics.physicalCameraIds.asSequence()
                .take(AUX_MAX_PHYSICAL_IDS_PER_LOGICAL + 1)
                .toList()
        } else emptyList()

        return JavaAdvertisedCameraRecord(
            queriedId = id,
            facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            },
            focalLengthsMillimetres = immutableList(focalLengths),
            sensorPhysicalWidthMillimetres = physicalSize?.width,
            sensorPhysicalHeightMillimetres = physicalSize?.height,
            activeArray = activeRect?.takeIf { it.width() > 0 && it.height() > 0 }?.let { IntSize(it.width(), it.height()) },
            pixelArray = pixelSize?.takeIf { it.width > 0 && it.height > 0 }?.let { IntSize(it.width, it.height) },
            sensorOrientationDegrees = orientation,
            apertureValues = immutableList(apertures),
            colorFilterArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            capabilities = CameraCapabilities(
                previewStreams = immutableList(previewStreams),
                fpsRanges = immutableList(fpsRanges),
                rawSizes = immutableList(rawSizes),
            ),
            physicalIds = immutableList(physicalIds),
        )
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
