package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.RawPair
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

interface SensorRawImage : AutoCloseable {
    val timestampNs: Long
    val format: SensorRawFormat
    val size: IntSize
}

interface SensorRawCaptureResult {
    val sensorTimestampNs: Long
}

enum class RawEvidenceDisposition {
    BUFFERED,
    PAIRED,
    INVALID_CLOSED,
    STALE_CLOSED,
    STALE_IGNORED,
}

/**
 * Bounded one-shot join owner. It accepts either callback order, pairs only exact positive sensor
 * timestamps, and deterministically closes every image that does not transfer in the returned pair.
 */
class OneShotRawAcquisition<I, R>(
    val context: RawCaptureContext,
    maximumPendingEntries: Int = MAXIMUM_PENDING_ENTRIES,
) : AutoCloseable where I : SensorRawImage, R : SensorRawCaptureResult {
    private val pairer = RawTimestampPairer<I, R>(
        maximumEntries = maximumPendingEntries,
        timeoutMillis = context.timeoutMillis,
    )
    private val completion = CompletableDeferred<RawPair<I, R>>()
    private val closed = AtomicBoolean(false)
    private var paired = false

    @Synchronized
    fun offerImage(image: I): RawEvidenceDisposition {
        if (closed.get() || paired) {
            image.closeQuietly()
            return RawEvidenceDisposition.STALE_CLOSED
        }
        if (image.timestampNs <= 0L ||
            image.format != context.rawFormat ||
            image.size != context.rawSize
        ) {
            image.closeQuietly()
            return RawEvidenceDisposition.INVALID_CLOSED
        }
        val pair = pairer.offerImage(image.timestampNs, image)
            ?: return RawEvidenceDisposition.BUFFERED
        completePair(pair)
        return RawEvidenceDisposition.PAIRED
    }

    @Synchronized
    fun offerResult(result: R): RawEvidenceDisposition {
        if (closed.get() || paired) return RawEvidenceDisposition.STALE_IGNORED
        if (result.sensorTimestampNs <= 0L) return RawEvidenceDisposition.STALE_IGNORED
        val pair = pairer.offerResult(result.sensorTimestampNs, result)
            ?: return RawEvidenceDisposition.BUFFERED
        completePair(pair)
        return RawEvidenceDisposition.PAIRED
    }

    suspend fun awaitPair(): RawPair<I, R> {
        try {
            return withTimeout(context.timeoutMillis) { completion.await() }
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw timeout
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    @Synchronized
    fun pendingCounts(): Pair<Int, Int> = pairer.pendingCounts()

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pairer.close()
        if (!completion.isCompleted) completion.cancel()
    }

    private fun completePair(pair: RawPair<I, R>) {
        paired = true
        pairer.close()
        if (!completion.complete(pair)) pair.close()
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        const val MAXIMUM_PENDING_ENTRIES = 2
    }
}
