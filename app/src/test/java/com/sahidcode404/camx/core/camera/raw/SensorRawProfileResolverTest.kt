package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorRawProfileResolverTest {
    @Test
    fun existingExactProfileRawSizesResolveAsLargestRawSensor() {
        val resolved = SensorRawProfileResolver.resolve(
            CameraCapabilities(
                rawSizes = listOf(IntSize(4000, 3000), IntSize(8000, 6000), IntSize(6000, 8000)),
            ),
        )

        assertEquals(SensorRawFormat.RAW_SENSOR, resolved?.format)
        assertEquals(IntSize(8000, 6000), resolved?.size)
        assertEquals(true, resolved?.dngWritable)
    }

    @Test
    fun onlyProvenWritableSensorRepresentationCanBeAdmitted() {
        val unresolvedRaw14 = SensorRawRepresentation(
            SensorRawFormat.RAW_14,
            IntSize(9000, 7000),
            dngWritable = false,
        )
        val writableRaw12 = SensorRawRepresentation(
            SensorRawFormat.RAW_12,
            IntSize(4000, 3000),
            dngWritable = true,
        )

        assertEquals(writableRaw12, SensorRawProfileResolver.resolve(listOf(unresolvedRaw14, writableRaw12)))
        assertNull(SensorRawProfileResolver.resolve(listOf(unresolvedRaw14)))
        assertNull(SensorRawProfileResolver.resolve(CameraCapabilities()))
    }

    @Test
    fun formatFidelityPrecedesDimensionsAndTiesAreDeterministic() {
        val resolved = SensorRawProfileResolver.resolve(
            listOf(
                SensorRawRepresentation(SensorRawFormat.RAW_10, IntSize(10_000, 8_000), true),
                SensorRawRepresentation(SensorRawFormat.RAW_12, IntSize(4000, 3000), true),
                SensorRawRepresentation(SensorRawFormat.RAW_12, IntSize(3000, 4000), true),
            ),
        )

        assertEquals(SensorRawFormat.RAW_12, resolved?.format)
        assertEquals(IntSize(4000, 3000), resolved?.size)
    }
}
