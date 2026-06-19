package com.elodin.intercom.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private val detector = SilenceDetector(windowMs = 2000L, rmsFloor = 10)

    @Test
    fun allZeroPcmAcrossWindow_returnsSilent() {
        val buf = ByteArray(960 * 2) // 20ms @ 48kHz mono
        var now = 0L
        // feed silence for 2.5s
        repeat(125) {
            val silent = detector.onPcm(buf, 960, now)
            if (now >= 2000) assertTrue("should be silent after window", silent)
            now += 20
        }
    }

    @Test
    fun loudPcm_returnsNotSilent() {
        val buf = ByteArray(960 * 2)
        // fill with max-amplitude samples
        for (i in buf.indices step 2) {
            buf[i] = 0xFF.toByte()
            buf[i + 1] = 0x7F.toByte()
        }
        assertFalse(detector.onPcm(buf, 960, 0L))
    }

    @Test
    fun transitionFromLoudToSilent_flipsAfterWindow() {
        val loud = ByteArray(960 * 2)
        for (i in loud.indices step 2) {
            loud[i] = 0xFF.toByte()
            loud[i + 1] = 0x7F.toByte()
        }
        val silent = ByteArray(960 * 2)
        var now = 0L

        // loud for 1s
        repeat(50) {
            assertFalse(detector.onPcm(loud, 960, now))
            now += 20
        }
        // silent for 2.5s
        repeat(125) {
            val result = detector.onPcm(silent, 960, now)
            if (now >= 2000 + 1000) assertTrue("should flip after window", result)
            now += 20
        }
    }

    @Test
    fun briefSilenceGap_doesNotTrigger() {
        val loud = ByteArray(960 * 2)
        for (i in loud.indices step 2) {
            loud[i] = 0xFF.toByte()
            loud[i + 1] = 0x7F.toByte()
        }
        val silent = ByteArray(960 * 2)
        var now = 0L

        // loud
        assertFalse(detector.onPcm(loud, 960, now))
        now += 20
        // brief silence (1s < 2s window)
        repeat(50) {
            val result = detector.onPcm(silent, 960, now)
            now += 20
            assertFalse("should not trigger before window", result)
        }
        // back to loud
        assertFalse(detector.onPcm(loud, 960, now))
    }
}
