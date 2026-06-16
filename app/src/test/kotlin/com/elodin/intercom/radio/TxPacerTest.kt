package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxPacerTest {
    private var clock = 0L

    private fun pacer() = TxPacer { clock }

    @Test
    fun healthyRateSendsEveryFrame() {
        val pacer = pacer()
        var sent = 0
        repeat(10) {
            if (pacer.shouldSend()) {
                sent += 1
                pacer.onWriteComplete(1L)
            }
            clock += 20L
        }
        assertEquals(10, sent)
    }

    @Test
    fun dropsFramesOfferedFasterThanCadence() {
        val pacer = pacer()
        var sent = 0
        repeat(20) {
            if (pacer.shouldSend()) sent += 1
            clock += 10L // offering 100 fps against a 50 fps cadence
        }
        assertEquals(10, sent)
    }

    @Test
    fun doesNotBurstToCatchUpAfterAStall() {
        val pacer = pacer()
        assertTrue(pacer.shouldSend()) // t=0, next due t=20

        clock = 1_000L // write() blocked ~1 s
        assertTrue(pacer.shouldSend()) // one frame, resynced to the present

        var burst = 0
        repeat(5) {
            if (pacer.shouldSend()) burst += 1
            clock += 1L
        }
        assertEquals(0, burst) // no replay of the missed second
    }

    @Test
    fun degradedRouteHalvesTheRate() {
        val pacer = pacer()
        pacer.setRouteDegraded(true)
        var sent = 0
        repeat(20) {
            if (pacer.shouldSend()) sent += 1
            clock += 20L // 50 fps offered, 25 fps allowed
        }
        assertEquals(10, sent)
    }

    @Test
    fun stalledWritesWidenIntervalThenRecover() {
        val pacer = pacer()
        val base = pacer.snapshot().intervalMs

        pacer.onWriteComplete(100L)
        assertEquals(40L, pacer.snapshot().intervalMs)
        pacer.onWriteComplete(100L)
        assertEquals(80L, pacer.snapshot().intervalMs)

        repeat(100) { pacer.onWriteComplete(1L) }
        assertEquals(base, pacer.snapshot().intervalMs)
    }

    @Test
    fun backoffIsCappedAtMax() {
        val pacer = pacer()
        repeat(20) { pacer.onWriteComplete(1_000L) }
        assertEquals(TxPacer.MAX_INTERVAL_MS, pacer.snapshot().intervalMs)
    }

    @Test
    fun countsOfferedSentAndDropped() {
        val pacer = pacer()
        repeat(20) {
            pacer.shouldSend()
            clock += 10L
        }
        val snap = pacer.snapshot()
        assertEquals(20L, snap.offered)
        assertEquals(10L, snap.sent)
        assertEquals(10L, snap.dropped)
    }

    @Test
    fun degradedRouteNeverDropsBelowBaseOnRecovery() {
        val pacer = pacer()
        pacer.setRouteDegraded(true)
        pacer.onWriteComplete(1_000L) // widen well past base
        repeat(100) { pacer.onWriteComplete(1L) }
        assertEquals(TxPacer.HEADSET_INTERVAL_MS, pacer.snapshot().intervalMs)
    }

    @Test
    fun clearingDegradedRouteLetsRecoveryReachFullRate() {
        val pacer = pacer()
        pacer.setRouteDegraded(true)
        pacer.onWriteComplete(1_000L) // widen under SCO load
        pacer.setRouteDegraded(false) // headset gone — floor back to full rate

        repeat(100) { pacer.onWriteComplete(1L) } // healthy writes ramp it down
        assertEquals(TxPacer.NORMAL_INTERVAL_MS, pacer.snapshot().intervalMs)
    }
}
