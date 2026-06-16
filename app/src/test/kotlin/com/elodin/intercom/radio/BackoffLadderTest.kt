package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackoffLadderTest {
    @Test
    fun yieldsCanonicalRungsThenExhausts() {
        val ladder = BackoffLadder()

        assertEquals(
            listOf(150L, 300L, 600L, 1_000L, 1_500L),
            (0..4).map { ladder.delayBeforeRetryMs(it) },
        )
        assertNull(ladder.delayBeforeRetryMs(5))
    }

    @Test
    fun honoursCustomRungs() {
        val ladder = BackoffLadder(longArrayOf(10L, 20L))

        assertEquals(10L, ladder.delayBeforeRetryMs(0))
        assertEquals(20L, ladder.delayBeforeRetryMs(1))
        assertNull(ladder.delayBeforeRetryMs(2))
    }

    @Test
    fun copiesCustomRungs() {
        val rungs = longArrayOf(10L, 20L)
        val ladder = BackoffLadder(rungs)

        rungs[0] = 99L

        assertEquals(10L, ladder.delayBeforeRetryMs(0))
    }

    @Test
    fun negativeIndexHasNoDelay() {
        assertNull(BackoffLadder().delayBeforeRetryMs(-1))
    }
}
