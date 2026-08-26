package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import com.sahidcode404.camx.core.camera.model.SensorRawRepresentation

/** Deterministic exact-profile resolver. Existing rawSizes are specifically RAW_SENSOR evidence. */
object SensorRawProfileResolver {
    fun resolve(capabilities: CameraCapabilities): SensorRawRepresentation? = resolve(
        capabilities.rawSizes.map { size ->
            SensorRawRepresentation(
                format = SensorRawFormat.RAW_SENSOR,
                size = size,
                dngWritable = true,
            )
        },
    )

    fun resolve(
        advertised: Collection<SensorRawRepresentation>,
    ): SensorRawRepresentation? = advertised.asSequence()
        .filter(SensorRawRepresentation::dngWritable)
        .distinct()
        .maxWithOrNull(
            compareBy<SensorRawRepresentation>(
                { it.format.fidelityRank },
                { it.size.area },
                { it.size.width },
                { it.size.height },
            ),
        )
}
