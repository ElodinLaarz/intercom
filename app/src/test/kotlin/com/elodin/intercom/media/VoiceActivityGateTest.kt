package com.elodin.intercom.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityGateTest {
    private fun gate() =
        VoiceActivityGate(
            onThreshold = 3_000,
            offThreshold = 1_000,
            releaseMs = 400L,
        )

    @Test
    fun staysOpenBelowOnThreshold() {
        val gate = gate()
        assertFalse(gate.update(peak = 500, nowMs = 0L))
        assertFalse(gate.update(peak = 2_999, nowMs = 10L))
    }

    @Test
    fun ducksWhenPeakCrossesOnThreshold() {
        val gate = gate()
        assertTrue(gate.update(peak = 5_000, nowMs = 0L))
    }

    @Test
    fun holdsDuckThroughBriefDips() {
        val gate = gate()
        assertTrue(gate.update(peak = 5_000, nowMs = 0L)) // engage
        // dip below offThreshold but for less than releaseMs
        assertTrue(gate.update(peak = 200, nowMs = 100L))
        assertTrue(gate.update(peak = 200, nowMs = 300L))
        // a fresh loud sample re-arms the hold
        assertTrue(gate.update(peak = 6_000, nowMs = 350L))
    }

    @Test
    fun releasesAfterSustainedSilence() {
        val gate = gate()
        assertTrue(gate.update(peak = 5_000, nowMs = 0L)) // engage
        assertTrue(gate.update(peak = 100, nowMs = 100L)) // below, timer starts
        assertFalse(gate.update(peak = 100, nowMs = 600L)) // 500ms >= releaseMs
    }

    @Test
    fun sustainBandKeepsDuckWithoutReleasing() {
        val gate = gate()
        assertTrue(gate.update(peak = 5_000, nowMs = 0L))
        // between off and on thresholds: hold, never start the release timer
        assertTrue(gate.update(peak = 1_500, nowMs = 1_000L))
        assertTrue(gate.update(peak = 1_500, nowMs = 5_000L))
    }
}
