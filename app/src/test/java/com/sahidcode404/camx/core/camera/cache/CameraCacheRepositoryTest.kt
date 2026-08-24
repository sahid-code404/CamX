package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCacheRepositoryTest {
    private val environment = CameraEnvironmentFingerprint("environment:test")

    @Test
    fun newerReplacementWinsMemoryAndPersistenceOrder() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val shouldBlock = AtomicBoolean(true)
        val writes = Collections.synchronizedList(mutableListOf<Long>())
        val persistence = object : CameraCachePersistence {
            override suspend fun readHot(environment: CameraEnvironmentFingerprint) = CacheRead.Miss
            override suspend fun readTopology(environment: CameraEnvironmentFingerprint) = CacheRead.Miss
            override suspend fun writeTopology(snapshot: CameraTopologySnapshot) = Unit
            override suspend fun writeHot(snapshot: HotStartSnapshot) {
                if (shouldBlock.compareAndSet(true, false)) {
                    firstWriteStarted.countDown()
                    check(releaseFirstWrite.await(5, TimeUnit.SECONDS))
                }
                writes += snapshot.lastVerifiedElapsedRealtimeNs
            }
        }
        val repository = CameraCacheRepository(persistence)
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val first = Thread {
            firstResult.set(awaitSuspend { repository.replaceHot(hotSnapshot(1L)) })
        }
        first.start()
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1L, repository.currentHot()?.lastVerifiedElapsedRealtimeNs)

        val second = Thread {
            secondResult.set(awaitSuspend { repository.replaceHot(hotSnapshot(2L)) })
        }
        second.start()
        repeat(100_000) {
            if (repository.currentHot()?.lastVerifiedElapsedRealtimeNs == 2L) return@repeat
            Thread.yield()
        }
        assertEquals(2L, repository.currentHot()?.lastVerifiedElapsedRealtimeNs)
        releaseFirstWrite.countDown()
        first.join(5_000L)
        second.join(5_000L)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertFalse(firstResult.get())
        assertTrue(secondResult.get())
        assertEquals(listOf(1L, 2L), writes)
    }

    @Test
    fun missAndEnvironmentMismatchCannotLeaveOldMemoryPublished() {
        val read = AtomicReference<CacheRead<HotStartSnapshot>>(CacheRead.Miss)
        val persistence = object : CameraCachePersistence {
            override suspend fun readHot(environment: CameraEnvironmentFingerprint) = read.get()
            override suspend fun readTopology(environment: CameraEnvironmentFingerprint) = CacheRead.Miss
            override suspend fun writeHot(snapshot: HotStartSnapshot) = Unit
            override suspend fun writeTopology(snapshot: CameraTopologySnapshot) = Unit
        }
        val repository = CameraCacheRepository(persistence)
        assertTrue(awaitSuspend { repository.replaceHot(hotSnapshot(1L)) })
        assertEquals(CacheRead.Miss, awaitSuspend { repository.loadHot(environment) })
        assertNull(repository.currentHot())

        val other = CameraEnvironmentFingerprint("environment:other")
        read.set(CacheRead.Hit(hotSnapshot(2L, other)))
        assertTrue(awaitSuspend { repository.loadHot(environment) } is CacheRead.Corrupt)
        assertNull(repository.currentHot())

        read.set(CacheRead.Hit(hotSnapshot(3L, schema = 99)))
        assertTrue(awaitSuspend { repository.loadHot(environment) } is CacheRead.Corrupt)
        assertNull(repository.currentHot())
    }

    @Test
    fun hotSnapshotRejectsNonOrthogonalSensorOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            hotSnapshot(1L, sensorOrientationDegrees = 45)
        }
    }

    private fun hotSnapshot(
        verifiedAt: Long,
        snapshotEnvironment: CameraEnvironmentFingerprint = environment,
        schema: Int = 1,
        sensorOrientationDegrees: Int? = 90,
    ) = HotStartSnapshot(
        schema = schema,
        environment = snapshotEnvironment,
        selectedCanonicalFingerprint = CanonicalLensFingerprint("lens:test"),
        selectedProfileFingerprint = CameraProfileFingerprint("profile:test"),
        routeId = CameraRouteId("route:test"),
        openCameraId = CameraTransportId("opaque"),
        physicalCameraId = null,
        previewConfiguration = PreviewConfiguration(
            streamType = PreviewStreamType.CAMERA2_PRIVATE,
            size = IntSize(1920, 1080),
            fps = PreviewFpsResolution(
                request = PreviewFpsRequest(false, 30, 30),
                resolvedRange = CameraFpsCapability(30, 30),
                reason = PreviewFpsFallbackReason.OVERRIDE_DISABLED,
            ),
            highResolutionViewfinder = false,
            signature = "preview:test",
        ),
        sensorOrientationDegrees = sensorOrientationDegrees,
        facing = LensFacing.BACK,
        routeTrust = CameraTrust.VERIFIED,
        previewTrust = PreviewTrust.VERIFIED,
        lastVerifiedElapsedRealtimeNs = verifiedAt,
    )

    private fun <T> awaitSuspend(block: suspend () -> T): T {
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome.set(result)
                    completed.countDown()
                }
            },
        )
        check(completed.await(5, TimeUnit.SECONDS))
        return checkNotNull(outcome.get()).getOrThrow()
    }
}
