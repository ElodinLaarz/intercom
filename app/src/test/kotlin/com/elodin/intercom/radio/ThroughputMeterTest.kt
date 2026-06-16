package com.elodin.intercom.radio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertNull(meter.onSample(1, 100, 10L)) // t=0, window opens
        clock = 100L
        val line = meter.onSample(1, 100, 10L)!! // t=100, window closes
        assertTrue(line, line.contains("ops=2"))
        assertTrue(line, line.contains("frames=2"))
        assertTrue(line, line.contains("bytes=200"))
        assertTrue(line, line.contains("Bps=2000")) // 200 bytes / 0.1 s
        assertTrue(line, line.contains("busyPct=20")) // 20 ms busy / 100 ms
    }

    @Test
    fun resetsBetweenWindows() {
        val meter = meter(100L)
        assertNull(meter.onSample(1, 100, 0L))
        clock = 100L
        assertNotNull(meter.onSample(1, 100, 0L)) // first window emits
        clock = 250L
        val line = meter.onSample(5, 500, 0L)!! // second window starts fresh
        assertTrue(line, line.contains("ops=1"))
        assertTrue(line, line.contains("frames=5"))
    }
}
