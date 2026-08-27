package com.sahidcode404.camx.core.camera.raw

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sahidcode404.camx.core.camera.diagnostics.DngWriteFailure
import com.sahidcode404.camx.core.camera.diagnostics.DngUnsupported
import com.sahidcode404.camx.core.camera.diagnostics.MediaStoreFailure
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.SensorRawFormat
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Android-only carrier created by the session owner after exact timestamp pairing. */
internal interface AndroidSensorRawImageSource : SensorRawImage {
    val androidImage: Image
}

/** Keeps both the exact TotalCaptureResult callback and the physical metadata view used by DNG. */
internal interface AndroidSensorRawResultSource : SensorRawCaptureResult {
    val characteristics: CameraCharacteristics
    val totalCaptureResult: TotalCaptureResult
    val dngCaptureResult: CaptureResult
}

internal class AndroidSensorDngWriter(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : SensorDngWriter {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun write(
        context: RawCaptureContext,
        image: SensorRawImage,
        result: SensorRawCaptureResult,
        authorizePublish: () -> Boolean,
    ): RawCaptureOutcome {
        return try {
            withContext(ioDispatcher) {
                val semanticFailure = SensorDngSemantics.validate(context, image, result)
                if (semanticFailure != null) {
                    return@withContext RawCaptureOutcome.Failed(
                        DngUnsupported("Sensor DNG semantic rejection: $semanticFailure"),
                    )
                }
                if (context.rawFormat != SensorRawFormat.RAW_SENSOR ||
                    image !is AndroidSensorRawImageSource ||
                    result !is AndroidSensorRawResultSource
                ) {
                    return@withContext RawCaptureOutcome.Failed(
                        DngUnsupported("No Android Sensor DNG writer exists for ${context.rawFormat}"),
                    )
                }
                val writerJob = currentCoroutineContext()[Job]
                writeAndroidDng(
                    context,
                    image,
                    result,
                    authorizePublish,
                    writeIsActive = { writerJob?.isActive != false },
                )
            }
        } catch (cancelled: CancellationException) {
            val pendingRow = cancelled.suppressed
                .filterIsInstance<PendingRowCleanupFailure>()
                .firstOrNull()
            if (pendingRow == null) {
                RawCaptureOutcome.Cancelled
            } else {
                RawCaptureOutcome.Failed(MediaStoreFailure(cancelled.safeReason()))
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            RawCaptureOutcome.Failed(DngWriteFailure(failure.safeReason()))
        } finally {
            runCatching { image.close() }
        }
    }

    private fun writeAndroidDng(
        context: RawCaptureContext,
        image: AndroidSensorRawImageSource,
        result: AndroidSensorRawResultSource,
        authorizePublish: () -> Boolean,
        writeIsActive: () -> Boolean,
    ): RawCaptureOutcome {
        val timestamp = wallClockMillis().coerceAtLeast(0L)
        val finalName = String.format(
            Locale.ROOT,
            "CamX_RAW_%d_%d.dng",
            timestamp,
            context.captureToken.value,
        )
        val pendingName = ".pending_$finalName"
        var encodingFailure: Throwable? = null
        val transaction = MediaStoreTransaction(
            insertPending = { insertPending(pendingName, finalName, timestamp) },
            writeAndClose = { uri ->
                val stream = resolver.openOutputStream(uri, "w")
                    ?: error("MediaStore returned no DNG output stream")
                val counter = CountingOutputStream(
                    BufferedOutputStream(stream),
                    writeIsActive,
                )
                try {
                    val orientation = DngOrientation.tiffOrientation(
                        context.sensorOrientationDegrees,
                        context.lensFacing,
                        context.displayRotationAtShutter,
                    )
                    try {
                        DngCreator(result.characteristics, result.dngCaptureResult).use { creator ->
                            creator.setOrientation(orientation)
                            creator.writeImage(counter, image.androidImage)
                        }
                    } catch (unsupported: IllegalArgumentException) {
                        throw DngInteroperabilityException(
                            "Exact camera metadata cannot create a Sensor DNG",
                            unsupported,
                        )
                    }
                    counter.flush()
                    counter.byteCount
                } catch (failure: Throwable) {
                    encodingFailure = failure
                    throw failure
                } finally {
                    counter.close()
                }
            },
            reopenAndValidate = { uri, bytes ->
                validateReopenedDng(uri, bytes, writeIsActive)
            },
            authorizePublish = authorizePublish,
            publish = { uri -> publish(uri, finalName) },
            delete = { uri -> check(resolver.delete(uri, null, null) > 0) { "Pending DNG row was not deleted" } },
            recoveryIdentity = { uri -> PendingRowRecoveryIdentity(uri.toString().take(256)) },
        )
        val committed = transaction.execute()
        return committed.fold(
            onSuccess = { commit ->
                RawCaptureOutcome.Saved(commit.row.toString(), commit.byteCount)
            },
            onFailure = { failure ->
                if (failure is DngInteroperabilityException) {
                    RawCaptureOutcome.Failed(DngUnsupported(failure.safeReason()))
                } else if (encodingFailure != null) {
                    RawCaptureOutcome.Failed(DngWriteFailure(failure.safeReason()))
                } else {
                    RawCaptureOutcome.Failed(MediaStoreFailure(failure.safeReason()))
                }
            },
        )
    }

    private fun insertPending(pendingName: String, finalName: String, timestamp: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, if (Build.VERSION.SDK_INT >= 29) finalName else pendingName)
            put(MediaStore.MediaColumns.MIME_TYPE, DNG_MIME_TYPE)
            put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) addModernPendingValues()
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun ContentValues.addModernPendingValues() {
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/CamX")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun publish(uri: Uri, finalName: String) {
        val values = ContentValues().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            } else {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            }
        }
        check(resolver.update(uri, values, null, null) == 1) { "DNG publication update failed" }
    }

    private fun validateReopenedDng(
        uri: Uri,
        writtenBytes: Long,
        readIsActive: () -> Boolean,
    ): Boolean {
        if (writtenBytes <= TIFF_HEADER_BYTES) return false
        return resolver.openInputStream(uri)?.buffered()?.use { input ->
            val header = ByteArray(TIFF_HEADER_BYTES)
            var offset = 0
            while (offset < header.size) {
                if (!readIsActive()) throw CancellationException("Sensor DNG validation ownership revoked")
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) return@use false
                offset += read
            }
            if (!header.contentEquals(LITTLE_ENDIAN_TIFF) && !header.contentEquals(BIG_ENDIAN_TIFF)) {
                return@use false
            }
            var reopenedBytes = header.size.toLong()
            val buffer = ByteArray(VALIDATION_BUFFER_BYTES)
            while (true) {
                if (!readIsActive()) throw CancellationException("Sensor DNG validation ownership revoked")
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                reopenedBytes += read.toLong()
                if (reopenedBytes > writtenBytes) return@use false
            }
            reopenedBytes == writtenBytes
        } == true
    }

    private fun Throwable.safeReason(): String {
        val primary = message ?: javaClass.simpleName
        val pendingRow = suppressed
            .filterIsInstance<PendingRowCleanupFailure>()
            .firstOrNull()
            ?.recoveryIdentity
            ?.value
        return if (pendingRow == null) {
            primary.take(MAXIMUM_FAILURE_REASON)
        } else {
            "$primary; pendingRow=$pendingRow".take(MAXIMUM_FAILURE_REASON)
        }
    }

    private class CountingOutputStream(
        delegate: OutputStream,
        private val writeIsActive: () -> Boolean,
    ) : FilterOutputStream(delegate) {
        var byteCount: Long = 0L
            private set

        override fun write(value: Int) {
            ensureWriteActive()
            out.write(value)
            byteCount += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureWriteActive()
            out.write(buffer, offset, length)
            byteCount += length.toLong()
        }

        override fun flush() {
            ensureWriteActive()
            out.flush()
        }

        override fun close() {
            // Cleanup must close the descriptor even after cancellation; the pending row is deleted.
            out.close()
        }

        private fun ensureWriteActive() {
            if (!writeIsActive()) throw CancellationException("Sensor DNG write ownership revoked")
        }
    }

    private class DngInteroperabilityException(message: String, cause: Throwable) :
        Exception(message, cause)

    private companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
        const val TIFF_HEADER_BYTES = 4
        const val VALIDATION_BUFFER_BYTES = 32 * 1024
        const val MAXIMUM_FAILURE_REASON = 256
        val LITTLE_ENDIAN_TIFF = byteArrayOf(0x49, 0x49, 0x2a, 0x00)
        val BIG_ENDIAN_TIFF = byteArrayOf(0x4d, 0x4d, 0x00, 0x2a)
    }
}
