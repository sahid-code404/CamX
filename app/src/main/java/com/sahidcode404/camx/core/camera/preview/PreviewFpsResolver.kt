package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import kotlin.math.abs

object PreviewFpsResolver {
    fun resolve(
        request: PreviewFpsRequest,
        reportedRanges: List<CameraFpsCapability>,
        streamMinimumFrameDurationNs: Long?,
    ): PreviewFpsResolution {
        if (!request.overrideEnabled) {
            return PreviewFpsResolution(
                request = request,
                resolvedRange = null,
                reason = PreviewFpsFallbackReason.OVERRIDE_DISABLED,
            )
        }
        if (request.requestedMinimum <= 0 || request.requestedMaximum < request.requestedMinimum) {
            return PreviewFpsResolution(
                request = request,
                resolvedRange = null,
                reason = PreviewFpsFallbackReason.INVALID_REQUEST,
            )
        }
        if (reportedRanges.isEmpty()) {
            return PreviewFpsResolution(
                request = request,
                resolvedRange = null,
                reason = PreviewFpsFallbackReason.NO_REPORTED_RANGES,
            )
        }

        val uniqueRanges = reportedRanges.distinct().sortedWith(
            compareBy<CameraFpsCapability>({ it.minimum }, { it.maximum }),
        )
        val streamMaximumFps = streamMinimumFrameDurationNs
            ?.takeIf { it > 0L }
            ?.let { duration -> (1_000_000_000L / duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        val cadenceCompatible = if (streamMaximumFps == null) {
            uniqueRanges
        } else {
            uniqueRanges.filter { it.maximum <= streamMaximumFps }
        }
        if (cadenceCompatible.isEmpty()) {
            return PreviewFpsResolution(
                request = request,
                resolvedRange = null,
                reason = PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT,
            )
        }

        val exact = cadenceCompatible.firstOrNull { range ->
            range.minimum == request.requestedMinimum && range.maximum == request.requestedMaximum
        }
        if (exact != null) {
            return PreviewFpsResolution(
                request = request,
                resolvedRange = exact,
                reason = PreviewFpsFallbackReason.EXACT_MATCH,
            )
        }

        val closest = cadenceCompatible.minWith(
            compareBy<CameraFpsCapability>(
                { abs(it.minimum.toLong() - request.requestedMinimum.toLong()) + abs(it.maximum.toLong() - request.requestedMaximum.toLong()) },
                { abs(it.maximum.toLong() - request.requestedMaximum.toLong()) },
                { -it.maximum },
                { -it.minimum },
            ),
        )
        return PreviewFpsResolution(
            request = request,
            resolvedRange = closest,
            reason = if (streamMaximumFps != null && uniqueRanges.size != cadenceCompatible.size) {
                PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT
            } else {
                PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE
            },
        )
    }
}
