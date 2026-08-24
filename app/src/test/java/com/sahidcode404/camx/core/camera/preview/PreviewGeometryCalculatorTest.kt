package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewGeometryInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryCalculatorTest {
    @Test
    fun rearPortraitStreamIsRotatedAndCenterCropped() {
        val result = PreviewGeometryCalculator.calculate(
            PreviewGeometryInput(
                viewSize = IntSize(1080, 2400),
                streamSize = IntSize(1920, 1080),
                sensorOrientationDegrees = 90,
                displayRotation = DisplayRotation.ROTATION_0,
                lensFacing = LensFacing.BACK,
                mirrorFrontPreview = true,
            ),
        )
        assertEquals(90, result.clockwiseRotationDegrees)
        assertEquals(1.25f, result.scale, 0.0001f)
        assertFalse(result.mirrorHorizontally)
    }

    @Test
    fun frontMirrorIsExplicitPolicy() {
        val input = PreviewGeometryInput(
            viewSize = IntSize(1000, 1000),
            streamSize = IntSize(1000, 1000),
            sensorOrientationDegrees = 270,
            displayRotation = DisplayRotation.ROTATION_90,
            lensFacing = LensFacing.FRONT,
            mirrorFrontPreview = true,
        )
        assertTrue(PreviewGeometryCalculator.calculate(input).mirrorHorizontally)
        assertFalse(
            PreviewGeometryCalculator.calculate(input.copy(mirrorFrontPreview = false)).mirrorHorizontally,
        )
    }
}
