package com.elodin.intercom.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSeqFilterTest {
    @Test
    fun acceptsFirstFrameOfMatchingEpoch() {
        val filter = MediaSeqFilter(epoch = 7)
        assertTrue(filter.accept(frameEpoch = 7, seq = 100))
    }

    @Test
    fun rejectsWrongEpoch() {
        val filter = MediaSeqFilter(epoch = 7)
        assertFalse(filter.accept(frameEpoch = 8, seq = 0))
        // and a later correct-epoch frame still seeds cleanly
        assertTrue(filter.accept(frameEpoch = 7, seq = 0))
    }

    @Test
    fun acceptsStrictlyIncreasingSeq() {
        val filter = MediaSeqFilter(epoch = 1)
        assertTrue(filter.accept(1, 0))
        assertTrue(filter.accept(1, 1))
        assertTrue(filter.accept(1, 2))
    }

    @Test
    fun rejectsDuplicateAndOlderSeq() {
        val filter = MediaSeqFilter(epoch = 1)
        assertTrue(filter.accept(1, 5))
        assertFalse(filter.accept(1, 5)) // duplicate
        assertFalse(filter.accept(1, 4)) // older
        assertTrue(filter.accept(1, 6)) // forward again
    }

    @Test
    fun toleratesUnsignedWrap() {
        val filter = MediaSeqFilter(epoch = 1)
        val nearMax = 0xFFFF_FFFF.toInt() // -1 as signed, 2^32-1 unsigned
        assertTrue(filter.accept(1, nearMax))
        assertTrue(filter.accept(1, 0)) // wrap: +1 forward, not a giant jump back
        assertTrue(filter.accept(1, 1))
        assertFalse(filter.accept(1, nearMax)) // now stale
    }
}
