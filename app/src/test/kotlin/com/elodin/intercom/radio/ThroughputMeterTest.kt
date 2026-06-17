package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputMeterTest {
    private var clock = 0L

    private fun meter(windowMs: Long) = ThroughputMeter(windowMs) { clock }

    @Test
    fun emitsNothingBeforeWindowElapses() {
        val meter = meter(100L)
        clock = 50L
        assertNull(meter.onSample(1, 172, 0L))
    }

    @Test
    fun computesRatesOverTheWindow() {
        val meter = meter(100L)
        assertNull(meter.onSample(1, 100, 10L))
        clock = 100L
        val snap = meter.onSample(1, 100, 10L)!!
        assertEquals(2L, snap.ops)
        assertEquals(2L, snap.frames)
        assertEquals(200L, snap.bytes)
        assertEquals(2000L, snap.bps)
        assertEquals(20L, snap.busyPct)
    }

    @Test
    fun resetsBetweenWindows() {
        val meter = meter(100L)
        assertNull(meter.onSample(1, 100, 0L))
        clock = 100L
        assertNotNull(meter.onSample(1, 100, 0L))
        clock = 250L
        val snap = meter.onSample(5, 500, 0L)!!
        assertEquals(1L, snap.ops)
        assertEquals(5L, snap.frames)
    }
}
