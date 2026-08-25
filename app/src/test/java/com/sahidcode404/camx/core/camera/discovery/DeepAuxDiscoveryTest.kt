package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepAuxDiscoveryTest {
    private val environment = CameraEnvironmentFingerprint("deep-aux-test")

    @Test
    fun `verified then successful then cached exact ids lead hot wave`() {
        val plan = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySessionVerifiedDeepIds = listOf("verified-opaque"),
                previouslySuccessfulDeepIds = listOf("successful-opaque"),
                cachedDiscoveredIds = listOf("cached-opaque"),
                advertisedIds = emptyList(),
                limits = DeepAuxDiscoveryLimits(lowNumericNamespaceMax = 0, neighborRadius = 0),
            ),
        )
        assertEquals(
            listOf("verified-opaque", "successful-opaque", "cached-opaque"),
            plan.wave(DeepAuxWave.HOT).map { it.transportId },
        )
    }

    @Test
    fun `nonnumeric learned ids remain opaque hot candidates`() {
        val plan = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySuccessfulDeepIds = listOf("vendor:hidden/AUX"),
                limits = DeepAuxDiscoveryLimits(lowNumericNamespaceMax = 0, neighborRadius = 0),
            ),
        )
        assertTrue(plan.candidates.any { it.transportId == "vendor:hidden/AUX" && it.wave == DeepAuxWave.HOT })
    }

    @Test
    fun `numeric advertised values produce bounded nearby addresses without role semantics`() {
        val plan = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                advertisedIds = listOf("5"),
                limits = DeepAuxDiscoveryLimits(
                    lowNumericNamespaceMax = 0,
                    neighborRadius = 2,
                    maximumNumericId = 10,
                    maximumCandidateCount = 20,
                ),
            ),
        )
        val nearby = plan.wave(DeepAuxWave.NEARBY).map { it.transportId }.toSet()
        assertEquals(setOf("3", "4", "5", "6", "7"), nearby)
        assertTrue(plan.candidates.all { it.transportId.isNotBlank() })
    }

    @Test
    fun `default low namespace includes zero through thirty one when space remains`() {
        val plan = DeepAuxCandidatePlanner.plan(DeepAuxDiscoveryRequest())
        val ids = plan.candidates.map { it.transportId }.toSet()
        assertTrue((0..31).all { it.toString() in ids })
    }

    @Test
    fun `candidate hard limit is never exceeded`() {
        val plan = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySessionVerifiedDeepIds = (0..300).map { "opaque-$it" },
                limits = DeepAuxDiscoveryLimits(maximumCandidateCount = Int.MAX_VALUE),
            ),
        )
        assertEquals(DEEP_AUX_HARD_MAXIMUM_CANDIDATES, plan.candidates.size)
    }

    @Test
    fun `planner is deterministic across input permutations`() {
        val first = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySuccessfulDeepIds = listOf("x", "y"),
                advertisedIds = listOf("12", "3", "9"),
            ),
        )
        val second = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySuccessfulDeepIds = listOf("y", "x"),
                advertisedIds = listOf("9", "12", "3"),
            ),
        )
        assertEquals(first, second)
    }

    @Test
    fun `deep decoder labels evidence NDK deep rather than advertised`() {
        val decoded = NdkAdvertisedSnapshotCodec.decode(
            payload(records = listOf(Record("17"))),
            CameraRouteSource.NDK_DEEP,
        )!!
        assertEquals(CameraRouteSource.NDK_DEEP, decoded.evidence.single().source)
    }

    @Test
    fun `deep backend emits hot nearby low waves incrementally on one native lane`() =
        runBlocking(Dispatchers.Unconfined) {
            val calls = ArrayList<List<String>>()
            val emissions = ArrayList<NdkDeepEvidenceReport>()
            val backend = NdkDeepAuxDiscoveryBackend(
                environment = environment,
                metadataBudget = DiscoveryMetadataBudget(),
                deviceApi = { 24 },
                clockNanos = { 42L },
                rawCollector = { _, ids ->
                    val requested = ids.toList()
                    calls += requested
                    payload(records = requested.take(1).map(::Record))
                },
            )
            val request = DeepAuxDiscoveryRequest(
                previouslySessionVerifiedDeepIds = listOf("verified"),
                advertisedIds = listOf("10"),
                limits = DeepAuxDiscoveryLimits(
                    lowNumericNamespaceMax = 1,
                    neighborRadius = 1,
                    maximumNumericId = 20,
                    maximumCandidateCount = 20,
                ),
            )

            val final = backend.discoverIncrementally(request) { emissions += it }

            assertEquals(3, calls.size)
            assertEquals(listOf("verified"), calls[0])
            assertTrue(calls[1].containsAll(listOf("9", "10", "11")))
            assertTrue(calls[2].containsAll(listOf("0", "1")))
            assertEquals(3, emissions.size)
            assertTrue(final.snapshot.evidence.all { it.source == CameraRouteSource.NDK_DEEP })
        }

    @Test
    fun `deep valid outside advertised list becomes metadata evidence only`() =
        runBlocking(Dispatchers.Unconfined) {
            val backend = NdkDeepAuxDiscoveryBackend(
                environment = environment,
                metadataBudget = DiscoveryMetadataBudget(),
                deviceApi = { 24 },
                rawCollector = { _, ids ->
                    if ("23" in ids) payload(records = listOf(Record("23"))) else payload()
                },
            )
            val report = backend.discover(
                DeepAuxDiscoveryRequest(
                    advertisedIds = listOf("0", "1"),
                    previouslySuccessfulDeepIds = listOf("23"),
                    limits = DeepAuxDiscoveryLimits(lowNumericNamespaceMax = 1, neighborRadius = 0),
                ),
            )
            assertTrue(report.snapshot.evidence.any { it.transportId.value == "23" })
            assertFalse(report.snapshot.evidence.any { it.source != CameraRouteSource.NDK_DEEP })
        }

    @Test
    fun `typed deep native failures are preserved`() = runBlocking(Dispatchers.Unconfined) {
        val backend = NdkDeepAuxDiscoveryBackend(
            environment = environment,
            metadataBudget = DiscoveryMetadataBudget(),
            deviceApi = { 24 },
            rawCollector = { _, _ -> payload(failures = listOf(7 to "hidden")) },
        )
        val report = backend.discover(
            DeepAuxDiscoveryRequest(
                previouslySuccessfulDeepIds = listOf("hidden"),
                limits = DeepAuxDiscoveryLimits(lowNumericNamespaceMax = 0, neighborRadius = 0),
            ),
        )
        assertTrue(report.outcomes.any {
            it.candidate.transportId == "hidden" && it.outcome == DeepAuxOutcomeKind.ACCESS_DENIED
        })
    }

    private data class Record(
        val id: String,
        val facing: Int = 2,
        val focal: Float = 4.2f,
    )

    private fun payload(
        records: List<Record> = emptyList(),
        failures: List<Pair<Int, String>> = emptyList(),
    ): ByteArray {
        val writer = Writer()
        writer.raw(byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte()))
        writer.u16(1)
        writer.u8(0)
        writer.u8(0)
        writer.u16(records.size)
        writer.u16(failures.size)
        records.forEach { record ->
            writer.string(record.id)
            writer.u8(record.facing)
            writer.u8(8) // orientation present
            writer.u16(1) // focal
            writer.u16(0)
            writer.u16(1) // private preview
            writer.u16(0)
            writer.u16(0)
            writer.i32(90)
            writer.f32(record.focal)
            writer.i32(1280)
            writer.i32(720)
            writer.i64(33_333_333L)
        }
        failures.forEach { (kind, id) -> writer.u8(kind); writer.string(id) }
        return writer.bytes()
    }

    private class Writer {
        private val output = ByteArrayOutputStream()
        fun bytes(): ByteArray = output.toByteArray()
        fun raw(value: ByteArray) { output.write(value) }
        fun u8(value: Int) { output.write(value and 0xff) }
        fun u16(value: Int) { u8(value); u8(value ushr 8) }
        fun i32(value: Int) { repeat(4) { offset -> u8(value ushr (offset * 8)) } }
        fun i64(value: Long) { repeat(8) { offset -> u8((value ushr (offset * 8)).toInt()) } }
        fun f32(value: Float) = i32(value.toRawBits())
        fun string(value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            u16(raw.size)
            raw(raw)
        }
    }
}
