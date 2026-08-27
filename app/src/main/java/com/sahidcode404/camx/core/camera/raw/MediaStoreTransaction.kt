package com.sahidcode404.camx.core.camera.raw

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Linearizes lifecycle revocation against the last reversible storage step. Whichever operation
 * changes OPEN first wins; a claimed permit cannot be revoked or reused by another transaction.
 */
internal class RawPublicationPermit {
    private val state = AtomicReference(State.OPEN)

    fun claim(): Boolean = state.compareAndSet(State.OPEN, State.CLAIMED)

    fun revoke(): Boolean = state.compareAndSet(State.OPEN, State.REVOKED)

    private enum class State { OPEN, CLAIMED, REVOKED }
}

data class MediaStoreCommit<Row : Any>(
    val row: Row,
    val byteCount: Long,
)

data class PendingRowRecoveryIdentity(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAXIMUM_LENGTH) {
            "Pending-row recovery identity must be nonblank and bounded"
        }
    }

    companion object {
        const val MAXIMUM_LENGTH = 256
    }
}

class PendingRowCleanupFailure(
    val recoveryIdentity: PendingRowRecoveryIdentity,
    cause: Throwable,
) : Exception("Pending MediaStore row cleanup failed: ${recoveryIdentity.value}", cause)

/**
 * Generic pending-row transaction. [authorizePublish] atomically claims the last reversible
 * ownership decision; a successful claim is the explicit irreversible commit-authorization
 * boundary and [publish] follows synchronously.
 */
class MediaStoreTransaction<Row : Any>(
    private val insertPending: () -> Row?,
    private val writeAndClose: (Row) -> Long,
    private val reopenAndValidate: (Row, Long) -> Boolean,
    private val authorizePublish: () -> Boolean = { true },
    private val publish: (Row) -> Unit,
    private val delete: (Row) -> Unit,
    private val recoveryIdentity: (Row) -> PendingRowRecoveryIdentity = { row ->
        PendingRowRecoveryIdentity(row.toString().take(PendingRowRecoveryIdentity.MAXIMUM_LENGTH))
    },
) {
    fun execute(): Result<MediaStoreCommit<Row>> {
        val row = try {
            checkNotNull(insertPending()) { "MediaStore insert returned no row" }
        } catch (failure: Throwable) {
            failure.rethrowIfNonRecoverable()
            return Result.failure(failure)
        }
        try {
            val byteCount = writeAndClose(row)
            check(byteCount > 0L) { "DNG write produced no bytes" }
            check(reopenAndValidate(row, byteCount)) { "Reopened DNG failed basic validation" }
            if (!authorizePublish()) throw CancellationException("RAW publication ownership revoked")
            // The atomic claim won. Cancellation may no longer relabel its publication as stale.
            publish(row)
            return Result.success(MediaStoreCommit(row, byteCount))
        } catch (operationFailure: Throwable) {
            try {
                delete(row)
            } catch (cleanupFailure: Throwable) {
                val diagnostic = PendingRowCleanupFailure(recoveryIdentity(row), cleanupFailure)
                operationFailure.addSuppressed(diagnostic)
            }
            operationFailure.rethrowIfNonRecoverable()
            return Result.failure(operationFailure)
        }
    }

    private fun Throwable.rethrowIfNonRecoverable() {
        if (this is CancellationException || this is VirtualMachineError || this is ThreadDeath) throw this
    }
}
