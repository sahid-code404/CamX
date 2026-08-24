package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFpsResolverTest {
    private val ranges = listOf(
        CameraFpsCapability(15, 30),
        CameraFpsCapability(30, 30),
        CameraFpsCapability(30, 60),
    )

    @Test
    fun overrideOffDoesNotSelectCamera2Range() {
        val result = PreviewFpsResolver.resolve(
            PreviewFpsRequest(false, 30, 60),
            ranges,
            null,
        )
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.OVERRIDE_DISABLED, result.reason)
    }

    @Test
    fun exactAdvertisedRangeWins() {
        val result = PreviewFpsResolver.resolve(
            PreviewFpsRequest(true, 30, 60),
            ranges,
            null,
        )
        assertEquals(CameraFpsCapability(30, 60), result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun streamCadenceExcludesUnsupportedSixtyFps() {
        val result = PreviewFpsResolver.resolve(
            PreviewFpsRequest(true, 30, 60),
            ranges,
            40_000_000L,
        )
        assertEquals(null, result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT, result.reason)
    }

    @Test
    fun invertedRequestNeverThrowsOrInventsRange() {
        val result = PreviewFpsResolver.resolve(
            PreviewFpsRequest(true, 60, 30),
            ranges,
            null,
        )
        assertNull(result.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.INVALID_REQUEST, result.reason)
    }
}
