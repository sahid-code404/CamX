package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation
import com.sahidcode404.camx.core.camera.model.SensorTimestampBasis
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.raw.RawAdmissionDecision
import com.sahidcode404.camx.core.camera.raw.RawAdmissionRejection
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.camera.raw.RawCaptureUiState
import com.sahidcode404.camx.core.camera.raw.RawShutterInput
import com.sahidcode404.camx.core.camera.raw.SensorDngWriter
import com.sahidcode404.camx.core.camera.raw.SensorRawCaptureResult
import com.sahidcode404.camx.core.camera.raw.SensorRawImage
import com.sahidcode404.camx.core.camera.diagnostics.MediaStoreFailure
import com.sahidcode404.camx.core.camera.diagnostics.RawCaptureTimeout
import com.sahidcode404.camx.core.camera.diagnostics.RawPairTimeout
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionControllerRawTest {
    @Test
    fun imageThenResultWritesOneDngClosesTemporaryResourcesAndRestoresVerifiedPreview() {
        val fixture = Fixture()
        fixture.verifyPreview()
        assertEquals(RawCaptureUiState.Ready, fixture.controller.rawCaptureState.value)

        val decision = fixture.shutter()
        assertTrue(decision is RawAdmissionDecision.Admitted)
        assertTrue(fixture.controller.state.value is CameraEngineState.ConfiguringRaw)
        assertEquals(1, fixture.platform.previewConfigurations.single().session.closeCount.get())
        assertEquals(1, fixture.platform.rawReaders.size)
        assertEquals(2, fixture.platform.rawReaders.single().maxImages)

        fixture.platform.rawConfigurations.single().configured()
        assertTrue(fixture.controller.state.value is CameraEngineState.CapturingRaw)
        assertEquals(1, fixture.platform.rawCaptures.size)
        val image = FakeRawImage(77L)
        fixture.platform.rawReaders.single().image(image)
        assertTrue(fixture.controller.state.value is CameraEngineState.PairingRaw)
        fixture.platform.rawCaptures.single().result(FakeRawResult(77L))

        fixture.assertWritingAndRestoring(image)
        fixture.restorePreview()
        fixture.assertFullyRestored(image)
    }

    @Test
    fun resultThenImageUsesSameExactPairingAndOneRequest() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        fixture.platform.rawCaptures.single().result(FakeRawResult(88L))
        assertTrue(fixture.controller.state.value is CameraEngineState.PairingRaw)
        val image = FakeRawImage(88L)
        fixture.platform.rawReaders.single().image(image)

        fixture.assertWritingAndRestoring(image)
        fixture.restorePreview()
        fixture.assertFullyRestored(image)
    }

    @Test
    fun duplicateCallbacksCannotReplaceTheFirstAcceptedEvidenceOrLeakImages() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val accepted = FakeRawImage(89L)
        val duplicate = FakeRawImage(89L)
        fixture.platform.rawReaders.single().image(accepted)
        fixture.platform.rawReaders.single().image(duplicate)
        val capture = fixture.platform.rawCaptures.single()
        capture.result(FakeRawResult(89L))
        capture.result(FakeRawResult(89L))

        assertEquals(1, duplicate.closeCount.get())
        fixture.assertWritingAndRestoring(accepted)
        fixture.restorePreview()
        fixture.assertFullyRestored(accepted)
    }

    @Test
    fun overlappingShutterIsRejectedAndCreatesNoSecondReaderOrRequest() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()

        val second = fixture.shutter()

        assertEquals(
            RawAdmissionDecision.Rejected(RawAdmissionRejection.CAPTURE_ALREADY_ACTIVE),
            second,
        )
        assertEquals(1, fixture.platform.rawReaders.size)
        assertTrue(fixture.platform.rawCaptures.isEmpty())
    }

    @Test
    fun shutterDeadlineBoundsRawSessionConfigurationAndLateSessionClosesOnce() {
        val fixture = Fixture(rawTimeoutMillis = 100L)
        fixture.verifyPreview()
        fixture.shutter()
        val lateConfiguration = fixture.platform.rawConfigurations.single()

        fixture.awaitRestoring()
        lateConfiguration.configured()

        assertEquals(
            RawCaptureOutcome.Failed(RawCaptureTimeout),
            fixture.controller.lastRawOutcome.value,
        )
        assertEquals(1, fixture.platform.rawReaders.single().closeCount.get())
        assertEquals(1, lateConfiguration.session.closeCount.get())
        fixture.restorePreview()
        assertEquals(RawCaptureUiState.Ready, fixture.controller.rawCaptureState.value)
    }

    @Test
    fun imageOnlyAndResultOnlyDeadlinesRestorePreviewAndCloseEveryOrphan() {
        val imageOnly = Fixture(rawTimeoutMillis = 100L)
        imageOnly.verifyPreview()
        imageOnly.shutter()
        imageOnly.platform.rawConfigurations.single().configured()
        val orphan = FakeRawImage(90L)
        imageOnly.platform.rawReaders.single().image(orphan)
        imageOnly.awaitRestoring()
        assertEquals(1, orphan.closeCount.get())
        assertEquals(
            RawCaptureOutcome.Failed(RawPairTimeout),
            imageOnly.controller.lastRawOutcome.value,
        )
        imageOnly.restorePreview()

        val resultOnly = Fixture(rawTimeoutMillis = 100L)
        resultOnly.verifyPreview()
        resultOnly.shutter()
        resultOnly.platform.rawConfigurations.single().configured()
        resultOnly.platform.rawCaptures.single().result(FakeRawResult(90L))
        resultOnly.awaitRestoring()
        assertEquals(
            RawCaptureOutcome.Failed(RawPairTimeout),
            resultOnly.controller.lastRawOutcome.value,
        )
        assertEquals(0, resultOnly.controller.resources.value.pendingRawResults)
        resultOnly.restorePreview()
    }

    @Test
    fun restoreConfigurationDeadlineRetriesOnceAndLateSessionCannotTakeOwnership() {
        val fixture = Fixture(rawTimeoutMillis = 100L)
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(92L)
        fixture.platform.rawReaders.single().image(image)
        fixture.platform.rawCaptures.single().result(FakeRawResult(92L))
        fixture.assertWritingAndRestoring(image)
        val staleRestore = fixture.platform.restoreConfigurations.single()

        fixture.awaitRestoreConfigurationCount(2)
        staleRestore.configured()
        assertEquals(1, staleRestore.session.closeCount.get())

        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()
        fixture.assertFullyRestored(image)
    }

    @Test
    fun restoredPreviewFirstFrameDeadlineRetriesAndLateFrameCannotVerifyOldSession() {
        val fixture = Fixture(rawTimeoutMillis = 100L)
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(93L)
        fixture.platform.rawReaders.single().image(image)
        fixture.platform.rawCaptures.single().result(FakeRawResult(93L))
        fixture.assertWritingAndRestoring(image)
        val firstRestore = fixture.platform.restoreConfigurations.single()
        firstRestore.configured()
        val lateFrame = fixture.platform.repeats.last()

        fixture.awaitRestoreConfigurationCount(2)
        assertEquals(1, firstRestore.session.closeCount.get())
        lateFrame.frame()
        assertTrue(fixture.controller.state.value is CameraEngineState.RestoringPreview)

        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()
        fixture.assertFullyRestored(image)
    }

    @Test
    fun exhaustedRestoreDeadlinesReleaseEverythingAndAReplacementPreviewCanStart() {
        val fixture = Fixture(rawTimeoutMillis = 100L)
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(94L)
        fixture.platform.rawReaders.single().image(image)
        fixture.platform.rawCaptures.single().result(FakeRawResult(94L))
        fixture.assertWritingAndRestoring(image)

        fixture.awaitRecoverableError()
        fixture.platform.restoreConfigurations.forEach { it.configured() }

        val failed = fixture.controller.state.value as CameraEngineState.RecoverableError
        assertEquals(RawCaptureTimeout, failed.failure)
        assertEquals(2, fixture.platform.restoreConfigurations.size)
        fixture.platform.restoreConfigurations.forEach { restore ->
            assertEquals(1, restore.session.closeCount.get())
        }
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
        assertEquals(RawCaptureUiState.Unavailable, fixture.controller.rawCaptureState.value)

        fixture.startPreview("g", 7L)
        fixture.platform.opens.last().opened()
        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()
        val preview = fixture.controller.state.value as CameraEngineState.Previewing
        assertTrue(preview.firstFrameVerified)
        assertEquals(CameraRouteId("route:g"), preview.selection.routeId)
    }

    @Test
    fun pauseDuringRawConfigurationCancelsTokenAndLateSessionClosesExactlyOnce() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        val rawConfiguration = fixture.platform.rawConfigurations.single()
        val reader = fixture.platform.rawReaders.single()

        runSuspend { fixture.controller.pause() }
        rawConfiguration.configured()

        assertEquals(1, reader.closeCount.get())
        assertEquals(1, rawConfiguration.session.closeCount.get())
        assertEquals(1, fixture.platform.opens.single().device.closeCount.get())
        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        assertEquals(0, fixture.controller.resources.value.imageReaders)
    }

    @Test
    fun unsupportedExactProfileNeverCreatesRawResource() {
        val fixture = Fixture(rawSupported = false)
        fixture.verifyPreview()

        val decision = fixture.shutter()

        assertEquals(
            RawAdmissionDecision.Rejected(RawAdmissionRejection.SENSOR_RAW_UNSUPPORTED),
            decision,
        )
        assertTrue(fixture.platform.rawReaders.isEmpty())
        assertEquals(RawCaptureUiState.Unavailable, fixture.controller.rawCaptureState.value)
    }

    @Test
    fun storageFailureRestoresPreviewWithoutCameraOrProfileTrustFailure() {
        val fixture = Fixture(
            writerOutcome = RawCaptureOutcome.Failed(MediaStoreFailure("disk full")),
        )
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(91L)
        fixture.platform.rawReaders.single().image(image)
        fixture.platform.rawCaptures.single().result(FakeRawResult(91L))
        fixture.restorePreview()

        assertTrue(fixture.controller.lastRawOutcome.value is RawCaptureOutcome.Failed)
        assertTrue(fixture.controller.state.value is CameraEngineState.Previewing)
        assertFalse(fixture.controller.state.value is CameraEngineState.StructuralError)
        assertEquals(RawCaptureUiState.Ready, fixture.controller.rawCaptureState.value)
    }

    @Test
    fun captureFailureClosesTemporaryResourcesAndRestoresVerifiedPreview() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()

        fixture.platform.rawCaptures.single().failed()
        assertTrue(fixture.controller.state.value is CameraEngineState.RestoringPreview)
        fixture.restorePreview()

        assertEquals(
            RawCaptureOutcome.Failed(RawCaptureTimeout),
            fixture.controller.lastRawOutcome.value,
        )
        assertEquals(1, fixture.platform.rawReaders.single().closeCount.get())
        assertEquals(1, fixture.platform.rawConfigurations.single().session.closeCount.get())
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        assertEquals(RawCaptureUiState.Ready, fixture.controller.rawCaptureState.value)
    }

    @Test
    fun transientReaderAllocationFailureDoesNotRejectTheRawProfile() {
        val fixture = Fixture(rawReaderFailure = IllegalStateException("reader temporarily unavailable"))
        fixture.verifyPreview()

        fixture.shutter()
        assertTrue(fixture.controller.state.value is CameraEngineState.RestoringPreview)
        fixture.restorePreview()

        assertEquals(
            RawCaptureOutcome.Failed(RawCaptureTimeout),
            fixture.controller.lastRawOutcome.value,
        )
        assertEquals(RawCaptureUiState.Ready, fixture.controller.rawCaptureState.value)
        assertTrue(fixture.platform.rawReaders.isEmpty())
    }

    @Test
    fun structuralRawConfigurationFailureIsRawOnlyAndStillRestoresPreview() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()

        fixture.platform.rawConfigurations.single().failed()
        assertTrue(fixture.controller.state.value is CameraEngineState.RestoringPreview)
        fixture.restorePreview()

        assertTrue(fixture.controller.state.value is CameraEngineState.Previewing)
        assertEquals(RawCaptureUiState.Unavailable, fixture.controller.rawCaptureState.value)
        assertTrue(fixture.controller.lastRawOutcome.value is RawCaptureOutcome.Failed)
        assertEquals(1, fixture.platform.rawConfigurations.single().session.closeCount.get())
        assertEquals(
            RawAdmissionDecision.Rejected(RawAdmissionRejection.SENSOR_RAW_UNSUPPORTED),
            fixture.shutter(),
        )
    }

    @Test
    fun physicalRawTargetsExactPhysicalProfileWhileLogicalDeviceStaysOpen() {
        val physical = PhysicalCameraId("opaque-physical-a")
        val fixture = Fixture(physicalCameraId = physical)
        fixture.verifyPreview()
        fixture.shutter()

        assertEquals(physical, fixture.platform.rawConfigurations.single().physicalCameraId)
        assertEquals(physical, fixture.platform.lastMetadataPhysicalId)
        assertEquals(1, fixture.platform.opens.size)
        assertEquals(CameraTransportId("opaque-parent"), fixture.platform.opens.single().cameraId)
    }

    @Test
    fun rawAThenBThenCLateRawCallbacksCannotRestoreAOverLatestC() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        val staleRaw = fixture.platform.rawConfigurations.single()

        fixture.startPreview("b", 2L)
        val openB = fixture.platform.opens[1]
        fixture.startPreview("c", 3L)
        val openC = fixture.platform.opens[2]
        staleRaw.configured()
        openB.opened()
        openC.opened()
        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()

        val verified = fixture.controller.state.value as CameraEngineState.Previewing
        assertTrue(verified.firstFrameVerified)
        assertEquals(CameraRouteId("route:c"), verified.selection.routeId)
        assertEquals(1, staleRaw.session.closeCount.get())
        assertEquals(1, openB.device.closeCount.get())
        assertEquals(0, openC.device.closeCount.get())
        assertEquals(3, fixture.platform.opens.size)
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        assertEquals(null, fixture.controller.lastRawOutcome.value)
    }

    @Test
    fun lateImageAndResultAfterWriteCannotMutateRestoringOrLeak() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(101L)
        fixture.platform.rawReaders.single().image(image)
        val capture = fixture.platform.rawCaptures.single()
        capture.result(FakeRawResult(101L))
        assertTrue(fixture.controller.state.value is CameraEngineState.RestoringPreview)

        val late = FakeRawImage(101L)
        fixture.platform.rawReaders.single().image(late)
        capture.result(FakeRawResult(101L))

        assertEquals(1, late.closeCount.get())
        assertTrue(fixture.controller.state.value is CameraEngineState.RestoringPreview)
        fixture.restorePreview()
        fixture.assertFullyRestored(image)
    }

    @Test
    fun pauseDuringDngWriteCancelsPublicationClosesImageAndAllowsNextPreview() {
        val fixture = Fixture(blockWriter = true)
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(111L)
        fixture.platform.rawReaders.single().image(image)
        fixture.platform.rawCaptures.single().result(FakeRawResult(111L))
        assertTrue(fixture.writerStarted)
        assertTrue(fixture.controller.state.value is CameraEngineState.WritingDng)

        runSuspend { fixture.controller.pause() }

        assertEquals(1, image.closeCount.get())
        assertEquals(null, fixture.controller.lastRawOutcome.value)
        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        fixture.startPreview("d", 4L)
        fixture.platform.opens.last().opened()
        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()
        assertEquals(
            CameraRouteId("route:d"),
            (fixture.controller.state.value as CameraEngineState.Previewing).selection.routeId,
        )
    }

    @Test
    fun pauseDuringRestoreMakesLateRestoreSessionStaleAndNextPreviewWorks() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        fixture.platform.rawConfigurations.single().configured()
        val image = FakeRawImage(121L)
        fixture.platform.rawReaders.single().image(image)
        fixture.platform.rawCaptures.single().result(FakeRawResult(121L))
        val staleRestore = fixture.platform.restoreConfigurations.single()

        runSuspend { fixture.controller.pause() }
        staleRestore.configured()

        assertEquals(1, staleRestore.session.closeCount.get())
        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
        fixture.startPreview("e", 5L)
        fixture.platform.opens.last().opened()
        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()
        val preview = fixture.controller.state.value as CameraEngineState.Previewing
        assertTrue(preview.firstFrameVerified)
        assertEquals(CameraRouteId("route:e"), preview.selection.routeId)
    }

    @Test
    fun surfaceInvalidationDuringRawClosesStaleResourcesAndReplacementPreviewStarts() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        val staleRaw = fixture.platform.rawConfigurations.single()

        runSuspend { fixture.controller.surfaceInvalidated(PreviewSurfaceIdentity(1L)) }
        staleRaw.configured()

        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
        assertEquals(1, fixture.platform.rawReaders.single().closeCount.get())
        assertEquals(1, staleRaw.session.closeCount.get())
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        fixture.startPreview("f", 6L)
        fixture.platform.opens.last().opened()
        fixture.platform.restoreConfigurations.last().configured()
        fixture.platform.repeats.last().frame()
        val preview = fixture.controller.state.value as CameraEngineState.Previewing
        assertTrue(preview.firstFrameVerified)
        assertEquals(CameraRouteId("route:f"), preview.selection.routeId)
    }

    @Test
    fun shutdownDuringRawClosesEveryOwnedResourceAndLateCallbacksStayStale() {
        val fixture = Fixture()
        fixture.verifyPreview()
        fixture.shutter()
        val staleRaw = fixture.platform.rawConfigurations.single()

        runSuspend { fixture.controller.shutdown() }
        staleRaw.configured()

        assertEquals(CameraEngineState.Closed, fixture.controller.state.value)
        assertEquals(1, fixture.platform.rawReaders.single().closeCount.get())
        assertEquals(1, staleRaw.session.closeCount.get())
        assertEquals(1, fixture.platform.opens.single().device.closeCount.get())
        assertEquals(0, fixture.controller.resources.value.activeRawTransactions)
        assertEquals(0, fixture.controller.resources.value.cameraWorkers)
    }

    private class Fixture(
        private val rawSupported: Boolean = true,
        private val writerOutcome: RawCaptureOutcome? = null,
        private val physicalCameraId: PhysicalCameraId? = null,
        private val blockWriter: Boolean = false,
        rawReaderFailure: RuntimeException? = null,
        private val rawTimeoutMillis: Long = 2_000L,
    ) {
        val platform = FakePlatform(rawSupported, rawReaderFailure)
        private var writingState: CameraEngineState? = null
        var writerStarted = false
            private set
        val controller: CameraSessionController
        val writer: SensorDngWriter = SensorDngWriter { context, image, result, authorize ->
            writingState = controller.state.value
            assertEquals(context.rawSize, image.size)
            assertEquals(image.timestampNs, result.sensorTimestampNs)
            if (blockWriter) {
                writerStarted = true
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { runCatching { image.close() } }
                }
            }
            assertTrue(authorize())
            image.close()
            writerOutcome ?: RawCaptureOutcome.Saved(
                "content://camx/${context.captureToken.value}",
                4096L,
            )
        }
        init {
            controller = CameraSessionController(
                platform = platform,
                dispatcher = Dispatchers.Unconfined,
                elapsedRealtimeNs = { 100L },
                sensorDngWriter = writer,
                rawTimeoutMillis = rawTimeoutMillis,
            )
        }

        fun verifyPreview() {
            runSuspend {
                controller.startPreviewForTest(
                    selection = selection("a"),
                    route = route("a", rawSupported, physicalCameraId),
                    surfaceIdentity = PreviewSurfaceIdentity(1L),
                    surfaceToken = "preview-surface",
                    closeSurface = platform.surface::close,
                    configuration = configuration(),
                    settings = SettingsSnapshot(),
                )
            }
            platform.opens.single().opened()
            platform.previewConfigurations.single().configured()
            platform.repeats.single().frame()
            assertTrue((controller.state.value as CameraEngineState.Previewing).firstFrameVerified)
        }

        fun startPreview(name: String, surfaceIdentity: Long) {
            runSuspend {
                controller.startPreviewForTest(
                    selection = selection(name),
                    route = route(name, rawSupported = false),
                    surfaceIdentity = PreviewSurfaceIdentity(surfaceIdentity),
                    surfaceToken = "preview-surface-$name",
                    closeSurface = {},
                    configuration = configuration(),
                    settings = SettingsSnapshot(),
                )
            }
        }

        fun shutter(): RawAdmissionDecision = runSuspend {
            controller.captureRaw(
                RawShutterInput(
                    displayRotation = DisplayRotation.ROTATION_270,
                    sensorOrientationDegrees = 90,
                    lensFacing = LensFacing.BACK,
                ),
            )
        }

        fun awaitRestoring() {
            runBlocking {
                withTimeout(1_000L) {
                    controller.state.first { it is CameraEngineState.RestoringPreview }
                    while (platform.restoreConfigurations.isEmpty()) delay(1L)
                }
            }
        }

        fun awaitRestoreConfigurationCount(expected: Int) {
            runBlocking {
                withTimeout(1_000L) {
                    while (platform.restoreConfigurations.size < expected) delay(1L)
                }
            }
        }

        fun awaitRecoverableError() {
            runBlocking {
                withTimeout(1_000L) {
                    controller.state.first { it is CameraEngineState.RecoverableError }
                }
            }
        }

        fun assertWritingAndRestoring(image: FakeRawImage) {
            assertTrue(writingState is CameraEngineState.WritingDng)
            assertTrue(controller.state.value is CameraEngineState.RestoringPreview)
            assertEquals(1, image.closeCount.get())
            assertEquals(1, platform.rawReaders.single().closeCount.get())
            assertEquals(1, platform.rawConfigurations.single().session.closeCount.get())
            assertEquals(1, platform.opens.size)
            assertEquals(1, platform.restoreConfigurations.size)
            assertTrue(controller.lastRawOutcome.value is RawCaptureOutcome.Saved)
            assertTrue(controller.rawCaptureState.value is RawCaptureUiState.Recovering)
        }

        fun restorePreview() {
            platform.restoreConfigurations.single().configured()
            val restoredRepeat = platform.repeats.last()
            assertTrue(controller.state.value is CameraEngineState.RestoringPreview)
            restoredRepeat.frame()
        }

        fun assertFullyRestored(image: FakeRawImage) {
            assertTrue((controller.state.value as CameraEngineState.Previewing).firstFrameVerified)
            assertEquals(RawCaptureUiState.Ready, controller.rawCaptureState.value)
            assertEquals(1, image.closeCount.get())
            assertEquals(0, controller.resources.value.activeRawTransactions)
            assertEquals(0, controller.resources.value.imageReaders)
            assertEquals(0, controller.resources.value.openImages)
            assertEquals(0, controller.resources.value.pendingRawImages)
            assertEquals(0, controller.resources.value.pendingRawResults)
            assertEquals(1, controller.resources.value.cameraDevices)
            assertEquals(1, controller.resources.value.captureSessions)
            assertEquals(1, platform.rawCaptures.size)
        }
    }

    private class FakePlatform(
        private val rawSupported: Boolean,
        private val rawReaderFailure: RuntimeException?,
    ) : CameraOwnerPlatform {
        val opens = mutableListOf<OpenCall>()
        val previewConfigurations = mutableListOf<PreviewConfigurationCall>()
        val restoreConfigurations = mutableListOf<PreviewConfigurationCall>()
        val rawReaders = mutableListOf<FakeRawReader>()
        val rawConfigurations = mutableListOf<RawConfigurationCall>()
        val rawCaptures = mutableListOf<RawCaptureCall>()
        val repeats = mutableListOf<RepeatCall>()
        val surface = CloseCounter()
        var lastMetadataPhysicalId: PhysicalCameraId? = null

        override fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks) {
            opens += OpenCall(cameraId, callbacks)
        }

        override fun configurePreview(
            device: CameraDeviceHandle,
            surfaceToken: Any,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: CameraSessionCallbacks,
        ) {
            val call = PreviewConfigurationCall(callbacks)
            if (previewConfigurations.isEmpty()) previewConfigurations += call else restoreConfigurations += call
        }

        override fun startRepeating(
            session: CameraCaptureSessionHandle,
            request: PreparedPreviewRequest,
            onFrame: () -> Unit,
        ) {
            repeats += RepeatCall(onFrame)
        }

        override fun sensorRawMetadata(
            device: CameraDeviceHandle,
            physicalCameraId: com.sahidcode404.camx.core.camera.model.PhysicalCameraId?,
            representation: SensorRawRepresentation,
        ): SensorRawMetadata? {
            lastMetadataPhysicalId = physicalCameraId
            return if (rawSupported) SensorRawMetadata(SensorTimestampBasis.REALTIME) else null
        }

        override fun createRawReader(
            context: com.sahidcode404.camx.core.camera.model.RawCaptureContext,
            callbacks: RawImageCallbacks,
        ): RawImageReaderHandle {
            rawReaderFailure?.let { throw it }
            return FakeRawReader(callbacks).also(rawReaders::add)
        }

        override fun configureTemporaryRaw(
            device: CameraDeviceHandle,
            previewSurfaceToken: Any,
            physicalCameraId: com.sahidcode404.camx.core.camera.model.PhysicalCameraId?,
            reader: RawImageReaderHandle,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: RawSessionCallbacks,
        ) {
            rawConfigurations += RawConfigurationCall(physicalCameraId, callbacks)
        }

        override fun captureOneRaw(
            session: CameraCaptureSessionHandle,
            request: PreparedRawRequest,
            callbacks: RawCaptureCallbacks,
        ) {
            rawCaptures += RawCaptureCall(callbacks)
        }
    }

    private class OpenCall(
        val cameraId: CameraTransportId,
        private val callbacks: CameraOpenCallbacks,
    ) {
        val device = FakeDevice(cameraId.value)
        private val delivery = CloseOnceCameraResource<CameraDeviceHandle>(device, CameraDeviceHandle::close)
        fun opened() = callbacks.onOpened(delivery)
    }

    private class PreviewConfigurationCall(private val callbacks: CameraSessionCallbacks) {
        val session = FakeSession()
        private val delivery = CloseOnceCameraResource<CameraCaptureSessionHandle>(
            session,
            CameraCaptureSessionHandle::close,
        )
        fun configured() = callbacks.onConfigured(delivery, FakePreviewRequest)
    }

    private class RawConfigurationCall(
        val physicalCameraId: PhysicalCameraId?,
        private val callbacks: RawSessionCallbacks,
    ) {
        val session = FakeSession()
        private val delivery = CloseOnceCameraResource<CameraCaptureSessionHandle>(
            session,
            CameraCaptureSessionHandle::close,
        )
        fun configured() = callbacks.onConfigured(delivery, FakePreviewRequest, FakeRawRequest)
        fun failed() = callbacks.onConfigureFailed(delivery)
    }

    private class RawCaptureCall(private val callbacks: RawCaptureCallbacks) {
        fun result(result: SensorRawCaptureResult) = callbacks.onCompleted(result)
        fun failed() = callbacks.onFailed()
    }

    private class FakeRawReader(
        private val callbacks: RawImageCallbacks,
    ) : RawImageReaderHandle {
        val maxImages = 2
        val closeCount = AtomicInteger()
        override val surfaceToken: Any = Any()
        fun image(image: SensorRawImage) = callbacks.onImage(
            CloseOnceCameraResource(image, SensorRawImage::close),
        )
        override fun close() {
            check(closeCount.incrementAndGet() == 1)
        }
    }

    private class FakeRawImage(
        override val timestampNs: Long,
    ) : SensorRawImage {
        val closeCount = AtomicInteger()
        override val format = SensorRawFormat.RAW_SENSOR
        override val size = IntSize(4, 3)
        override fun close() {
            check(closeCount.incrementAndGet() == 1)
        }
    }

    private data class FakeRawResult(
        override val sensorTimestampNs: Long,
    ) : SensorRawCaptureResult

    private class FakeDevice(val name: String) : CameraDeviceHandle {
        val closeCount = AtomicInteger()
        override fun close() {
            check(closeCount.incrementAndGet() == 1)
        }
    }

    private class FakeSession : CameraCaptureSessionHandle {
        val closeCount = AtomicInteger()
        override fun close() {
            check(closeCount.incrementAndGet() == 1)
        }
    }

    private class RepeatCall(private val onFrame: () -> Unit) {
        fun frame() = onFrame()
    }

    private class CloseCounter {
        val closeCount = AtomicInteger()
        fun close() {
            check(closeCount.incrementAndGet() == 1)
        }
    }

    private data object FakePreviewRequest : PreparedPreviewRequest
    private data object FakeRawRequest : PreparedRawRequest

    private companion object {
        fun selection(name: String) = ActiveCameraSelection(
            canonicalLensFingerprint = CanonicalLensFingerprint("lens:$name"),
            profileFingerprint = CameraProfileFingerprint("profile:$name"),
            routeId = CameraRouteId("route:$name"),
            selectionGeneration = SelectionGeneration(0L),
            sessionGeneration = SessionGeneration(0L),
        )

        fun route(
            name: String,
            rawSupported: Boolean,
            physicalCameraId: PhysicalCameraId? = null,
        ) = CameraRoute(
            id = CameraRouteId("route:$name"),
            source = CameraRouteSource.JAVA_PUBLIC,
            openCameraId = CameraTransportId(if (physicalCameraId == null) "opaque:$name" else "opaque-parent"),
            physicalCameraId = physicalCameraId,
            capabilities = CameraCapabilities(
                rawSizes = if (rawSupported) listOf(IntSize(4, 3)) else emptyList(),
            ),
            metadataTrust = CameraTrust.ADVERTISED,
        )

        fun configuration() = PreviewConfiguration(
            streamType = PreviewStreamType.CAMERA2_PRIVATE,
            size = IntSize(1920, 1080),
            fps = PreviewFpsResolution(
                request = PreviewFpsRequest(false, 30, 30),
                resolvedRange = CameraFpsCapability(30, 30),
                reason = PreviewFpsFallbackReason.OVERRIDE_DISABLED,
            ),
            highResolutionViewfinder = false,
            signature = "raw-test",
        )

        fun <T> runSuspend(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(
                object : Continuation<T> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<T>) {
                        outcome = result
                    }
                },
            )
            return checkNotNull(outcome) { "Deterministic RAW test unexpectedly suspended" }.getOrThrow()
        }
    }
}
