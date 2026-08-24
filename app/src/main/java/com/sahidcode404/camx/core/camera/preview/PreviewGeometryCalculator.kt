package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.model.PreviewGeometryInput
import kotlin.math.max

object PreviewGeometryCalculator {
    fun calculate(input: PreviewGeometryInput): PreviewGeometry {
        require(Math.floorMod(input.sensorOrientationDegrees, 90) == 0) {
            "Sensor orientation must be orthogonal"
        }
        val rotation = when (input.lensFacing) {
            LensFacing.FRONT -> Math.floorMod(
                input.sensorOrientationDegrees + input.displayRotation.degrees,
                360,
            )
            LensFacing.BACK, LensFacing.EXTERNAL, LensFacing.UNKNOWN -> Math.floorMod(
                input.sensorOrientationDegrees - input.displayRotation.degrees,
                360,
            )
        }
        val swapAxes = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapAxes) input.streamSize.height else input.streamSize.width
        val rotatedHeight = if (swapAxes) input.streamSize.width else input.streamSize.height
        val scale = max(
            input.viewSize.width.toFloat() / rotatedWidth.toFloat(),
            input.viewSize.height.toFloat() / rotatedHeight.toFloat(),
        )
        val renderedWidth = rotatedWidth * scale
        val renderedHeight = rotatedHeight * scale
        return PreviewGeometry(
            clockwiseRotationDegrees = rotation,
            scale = scale,
            translatedX = (input.viewSize.width - renderedWidth) / 2f,
            translatedY = (input.viewSize.height - renderedHeight) / 2f,
            mirrorHorizontally = input.lensFacing == LensFacing.FRONT && input.mirrorFrontPreview,
        )
    }
}
