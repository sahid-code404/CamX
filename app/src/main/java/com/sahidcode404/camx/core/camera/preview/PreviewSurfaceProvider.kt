package com.sahidcode404.camx.core.camera.preview

import android.view.Surface
import com.sahidcode404.camx.core.camera.model.IntSize
import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class PreviewSurfaceIdentity(val value: Long) {
    init { require(value > 0L) { "Preview surface identity must be positive" } }
}

/** Process-unique while callbacks can coexist; process death also destroys every old callback. */
object PreviewSurfaceIdentityAllocator {
    private val sequence = AtomicLong(0L)

    fun next(): PreviewSurfaceIdentity = PreviewSurfaceIdentity(
        sequence.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Preview surface identity exhausted" }
            current + 1L
        },
    )
}

data class PreviewSurfaceBinding(
    val surface: Surface,
    val viewSize: IntSize,
    val identity: PreviewSurfaceIdentity,
)

interface PreviewSurfaceLease : AutoCloseable {
    val binding: PreviewSurfaceBinding

    /** Completes only when this exact lease is invalidated or replaced. */
    suspend fun awaitInvalidation()
}

interface PreviewSurfaceProvider {
    suspend fun awaitSurface(): PreviewSurfaceLease

    /** A stale identity must be ignored and must never revoke a newer lease. */
    fun invalidate(identity: PreviewSurfaceIdentity)
}
