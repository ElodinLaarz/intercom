package com.elodin.intercom.radio

/**
 * Windowed socket-throughput meter for locating the voice-path bottleneck. It
 * accumulates frames / bytes / time-blocked-in-syscall per sample and emits one
 * summary line per [windowMs].
 *
 * On the TX side the blocked time is `write()`+`flush()` — a high `busyPct`
 * means the BT stack/link can't absorb the send (local backpressure), and once
 * the send buffer is full the offered `Bps` equals the true drain rate. On the
 * RX side it is the `read()` wait, and `maxBusyMs` is the longest delivery gap
 * (a stall). Comparing TX `Bps` (what we push) against the peer's RX `Bps`
 * (what arrives) tells us whether frames die in local send-backpressure or
 * over the air.
 *
 * Pure and clock-injected (like [BackoffLadder]); one instance per direction
 * per link, discarded on disconnect (rule 4).
 */
internal class ThroughputMeter(
    private val windowMs: Long,
    private val nowMs: () -> Long,
) {
    private var windowStartMs = Long.MIN_VALUE
    private var ops = 0L
    private var frames = 0L
    private var bytes = 0L
    private var busyMs = 0L
    private var maxBusyMs = 0L

    /** Record one socket op; returns a summary once per window, else null. */
    fun onSample(
        frameCount: Int,
        byteCount: Int,
        opMs: Long,
    ): String? {
        val now = nowMs()
        if (windowStartMs == Long.MIN_VALUE) windowStartMs = now
        ops += 1
        frames += frameCount
        bytes += byteCount
        busyMs += opMs
        if (opMs > maxBusyMs) maxBusyMs = opMs

        val elapsed = now - windowStartMs
        if (elapsed < windowMs) return null

        val summary = summarize(elapsed)
        resetWindow(now)
        return summary
    }

    private fun summarize(elapsedMs: Long): String {
        val seconds = elapsedMs / MILLIS_PER_SECOND.toDouble()
        val bps = if (seconds > 0.0) (bytes / seconds).toLong() else 0L
        val fps = if (seconds > 0.0) (frames / seconds).toLong() else 0L
        val busyPct = if (elapsedMs > 0L) busyMs * PERCENT / elapsedMs else 0L
        return "windowMs=$elapsedMs ops=$ops frames=$frames bytes=$bytes " +
            "Bps=$bps fps=$fps busyMs=$busyMs busyPct=$busyPct maxBusyMs=$maxBusyMs"
    }

    private fun resetWindow(now: Long) {
        windowStartMs = now
        ops = 0L
        frames = 0L
        bytes = 0L
        busyMs = 0L
        maxBusyMs = 0L
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private const val PERCENT = 100L
    }
}
