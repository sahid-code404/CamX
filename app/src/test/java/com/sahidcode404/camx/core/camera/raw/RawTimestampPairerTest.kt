package com.sahidcode404.camx.core.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTimestampPairerTest {
    @Test
    fun pairsBothCallbackOrders() {
        RawTimestampPairer<FakeImage, String>(2).use { pairer ->
            val first = FakeImage()
            assertNull(pairer.offerImage(10L, first))
            val pair = pairer.offerResult(10L, "result")
            assertEquals(first, pair?.takeImage())
            assertFalse(first.closed)
            first.close()

            val second = FakeImage()
            assertNull(pairer.offerResult(20L, "second"))
            val secondPair = pairer.offerImage(20L, second)
            assertEquals("second", secondPair?.result)
            secondPair?.close()
            assertTrue(second.closed)
        }
    }

    @Test
    fun overflowInvalidAndCloseReleaseEveryOrphan() {
        val pairer = RawTimestampPairer<FakeImage, String>(1)
        val invalid = FakeImage()
        val old = FakeImage()
        val current = FakeImage()
        pairer.offerImage(0L, invalid)
        pairer.offerImage(10L, old)
        pairer.offerImage(20L, current)
        assertTrue(invalid.closed)
        assertTrue(old.closed)
        assertFalse(current.closed)
        pairer.close()
        assertTrue(current.closed)
    }

    @Test
    fun lateImageAfterCloseIsReleasedAndCloseIsIdempotent() {
        val pairer = RawTimestampPairer<FakeImage, String>(timeoutMillis = 50L)
        pairer.close()
        pairer.close()
        val late = FakeImage()
        assertNull(pairer.offerImage(30L, late))
        assertNull(pairer.offerResult(30L, "late"))
        assertTrue(late.closed)
        assertEquals(50L, pairer.timeoutMillis)
    }

    @Test
    fun rejectsTimeoutBeyondSharedRawContract() {
        assertThrows(IllegalArgumentException::class.java) {
            RawTimestampPairer<FakeImage, String>(timeoutMillis = 60_001L)
        }
    }

    @Test
    fun duplicateTimestampClosesReplacedImageExactlyOnce() {
        val pairer = RawTimestampPairer<FakeImage, String>(2)
        val first = FakeImage()
        val replacement = FakeImage()

        pairer.offerImage(10L, first)
        pairer.offerImage(10L, replacement)
        checkNotNull(pairer.offerResult(10L, "latest")).close()
        pairer.close()

        assertTrue(first.closed)
        assertTrue(replacement.closed)
    }

    @Test
    fun duplicateResultTimestampUsesLatestExactResultAndInvalidTimestampsNeverPair() {
        val pairer = RawTimestampPairer<FakeImage, String>(2)
        val invalid = FakeImage()
        val exact = FakeImage()

        assertNull(pairer.offerImage(-1L, invalid))
        assertNull(pairer.offerResult(0L, "zero"))
        assertNull(pairer.offerResult(-1L, "negative"))
        assertNull(pairer.offerResult(41L, "first"))
        assertNull(pairer.offerResult(41L, "latest"))
        val pair = checkNotNull(pairer.offerImage(41L, exact))

        assertEquals("latest", pair.result)
        assertTrue(invalid.closed)
        pair.close()
        assertTrue(exact.closed)
    }

    private class FakeImage : AutoCloseable {
        var closed = false
        override fun close() { check(!closed); closed = true }
    }
}
