package com.sahidcode404.camx.core.camera.raw

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreTransactionTest {
    @Test
    fun publicationClaimAndLifecycleRevocationHaveOneDeterministicWinner() {
        val claimed = RawPublicationPermit()
        assertTrue(claimed.claim())
        assertFalse(claimed.revoke())
        assertFalse(claimed.claim())

        val revoked = RawPublicationPermit()
        assertTrue(revoked.revoke())
        assertFalse(revoked.claim())
        assertFalse(revoked.revoke())
    }

    @Test
    fun writesClosesReopensValidatesAndPublishesInOrder() {
        val calls = mutableListOf<String>()
        val result = transaction(
            calls = calls,
            writeAndClose = { calls += "write-close:$it"; 128L },
            reopenAndValidate = { row, bytes -> calls += "reopen:$row:$bytes"; true },
        ).execute().getOrThrow()

        assertEquals("row", result.row)
        assertEquals(128L, result.byteCount)
        assertEquals(
            listOf("insert", "write-close:row", "reopen:row:128", "authorize", "publish:row"),
            calls,
        )
    }

    @Test
    fun insertFailureCreatesNoCleanupAuthority() {
        val calls = mutableListOf<String>()
        val result = transaction(calls, insertPending = { calls += "insert"; null }).execute()

        assertTrue(result.isFailure)
        assertEquals(listOf("insert"), calls)
    }

    @Test
    fun openWriteAndPartialZeroFailuresDeletePendingRow() {
        listOf<(String) -> Long>(
            { error("open failed") },
            { error("write failed") },
            { 0L },
        ).forEach { write ->
            val calls = mutableListOf<String>()
            val result = transaction(calls, writeAndClose = write).execute()
            assertTrue(result.isFailure)
            assertEquals("delete:row", calls.last())
            assertFalse(calls.any { it.startsWith("publish") })
        }
    }

    @Test
    fun reopenValidationFailureDeletesAndNeverPublishes() {
        val calls = mutableListOf<String>()
        val result = transaction(
            calls,
            reopenAndValidate = { _, _ -> calls += "invalid"; false },
        ).execute()

        assertTrue(result.isFailure)
        assertEquals(listOf("insert", "write-close:row", "invalid", "delete:row"), calls)
    }

    @Test
    fun revokedOwnershipCancelsImmediatelyBeforePublishAndDeletes() {
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            transaction(calls, authorizePublish = { calls += "authorize"; false }).execute()
        }
        assertEquals(
            listOf("insert", "write-close:row", "reopen:row:64", "authorize", "delete:row"),
            calls,
        )
    }

    @Test
    fun publishFailureDeletesIncompleteDestination() {
        val calls = mutableListOf<String>()
        val result = transaction(
            calls,
            publish = { calls += "publish:$it"; error("publish failed") },
        ).execute()

        assertTrue(result.isFailure)
        assertEquals("delete:row", calls.last())
    }

    @Test
    fun deleteFailureRetainsBoundedRecoveryIdentity() {
        val calls = mutableListOf<String>()
        val result = transaction(
            calls,
            writeAndClose = { error("disk full") },
            delete = { error("delete failed") },
        ).execute()

        val primary = result.exceptionOrNull()
        assertEquals("disk full", primary?.message)
        val cleanup = primary?.suppressed?.single() as PendingRowCleanupFailure
        assertEquals("recovery:row", cleanup.recoveryIdentity.value)
        assertEquals("delete failed", cleanup.cause?.message)
    }

    @Test
    fun cancellationDuringWriteDeletesThenPropagates() {
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            transaction(
                calls,
                writeAndClose = { throw CancellationException("cancelled") },
            ).execute()
        }
        assertEquals(listOf("insert", "delete:row"), calls)
    }

    @Test
    fun cancellationCleanupFailureCarriesBoundedRecoveryIdentity() {
        val calls = mutableListOf<String>()
        val cancelled = assertThrows(CancellationException::class.java) {
            transaction(
                calls,
                writeAndClose = { throw CancellationException("cancelled") },
                delete = { error("delete failed") },
            ).execute()
        }

        val cleanup = cancelled.suppressed.single() as PendingRowCleanupFailure
        assertEquals("recovery:row", cleanup.recoveryIdentity.value)
        assertEquals("delete failed", cleanup.cause?.message)
    }

    private fun transaction(
        calls: MutableList<String>,
        insertPending: () -> String? = { calls += "insert"; "row" },
        writeAndClose: (String) -> Long = { calls += "write-close:$it"; 64L },
        reopenAndValidate: (String, Long) -> Boolean = { row, bytes ->
            calls += "reopen:$row:$bytes"
            true
        },
        authorizePublish: () -> Boolean = { calls += "authorize"; true },
        publish: (String) -> Unit = { calls += "publish:$it" },
        delete: (String) -> Unit = { calls += "delete:$it" },
    ) = MediaStoreTransaction(
        insertPending = insertPending,
        writeAndClose = writeAndClose,
        reopenAndValidate = reopenAndValidate,
        authorizePublish = authorizePublish,
        publish = publish,
        delete = delete,
        recoveryIdentity = { PendingRowRecoveryIdentity("recovery:$it") },
    )
}
