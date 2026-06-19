package com.elodin.intercom.media

/**
 * Pure silence/no-audio detector — unit-testable without Android.
 * Tracks rolling RMS and returns true when the captured stream has
 * been effectively all-zero for [windowMs].
 *
 * Operates on the LE [ByteArray] it is already fed from the capture
 * buffer to avoid a redundant [ShortArray] copy on the capture thread.
 * [nowMs] is injected so tests need no real clock.
 */
internal class SilenceDetector(
    private val windowMs: Long,
    private val rmsFloor: Int,
) {
    private var silentSinceMs: Long = -1L
    private var lastNonSilentMs: Long = -1L

    fun onPcm(
        pcm: ByteArray,
        frames: Int,
        nowMs: Long,
    ): Boolean {
        val rms = computeRms(pcm, frames)
        if (rms >= rmsFloor) {
            silentSinceMs = -1L
            lastNonSilentMs = nowMs
            return false
        }
        if (silentSinceMs < 0L) {
            silentSinceMs = nowMs
        }
        return (nowMs - silentSinceMs) >= windowMs
    }

    private fun computeRms(
        pcm: ByteArray,
        frames: Int,
    ): Int {
        if (frames <= 0) return 0
        var sumSq = 0L
        var i = 0
        val end = frames * BYTES_PER_FRAME
        while (i < end) {
            val lo = pcm[i].toInt() and BYTE_MASK
            val hi = pcm[i + 1].toInt()
            val sample = (lo or (hi shl BITS_PER_BYTE)).toShort().toInt()
            sumSq += sample.toLong() * sample.toLong()
            i += 2
        }
        return kotlin.math.sqrt(sumSq.toDouble() / frames.toDouble()).toInt()
    }

    companion object {
        private const val BYTES_PER_FRAME = 2
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
    }
}
