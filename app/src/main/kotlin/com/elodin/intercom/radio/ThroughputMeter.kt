package com.elodin.intercom.radio

internal data class MeterSnapshot(
    val windowMs: Long,
    val ops: Long,
    val frames: Long,
    val bytes: Long,
    val bps: Long,
    val fps: Long,
    val busyMs: Long,
    val busyPct: Long,
    val maxBusyMs: Long,
) {
    override fun toString(): String =
        "windowMs=$windowMs ops=$ops frames=$frames bytes=$bytes " +
            "Bps=$bps fps=$fps busyMs=$busyMs busyPct=$busyPct maxBusyMs=$maxBusyMs"
}

/**
 * Windowed socket-throughput meter for locating the voice-path bottleneck. It
 * accumulates frames / bytes / time-blocked-in-syscall per sample and emits one
 * summary per [windowMs].
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

    /** Record one socket op; returns a snapshot once per window, else null. */
    fun onSample(
        frameCount: Int,
        byteCount: Int,
        opMs: Long,
    ): MeterSnapshot? {
        val now = nowMs()
        if (windowStartMs == Long.MIN_VALUE) windowStartMs = now
        ops += 1
        frames += frameCount
        bytes += byteCount
        busyMs += opMs
        if (opMs > maxBusyMs) maxBusyMs = opMs

        val elapsed = now - windowStartMs
        if (elapsed < windowMs) return null

        val snapshot = summarize(elapsed)
        resetWindow(now)
        return snapshot
    }

    private fun summarize(elapsedMs: Long): MeterSnapshot {
        val seconds = elapsedMs / MILLIS_PER_SECOND.toDouble()
        return MeterSnapshot(
            windowMs = elapsedMs,
            ops = ops,
            frames = frames,
            bytes = bytes,
            bps = if (seconds > 0.0) (bytes / seconds).toLong() else 0L,
            fps = if (seconds > 0.0) (frames / seconds).toLong() else 0L,
            busyMs = busyMs,
            busyPct = if (elapsedMs > 0L) busyMs * PERCENT / elapsedMs else 0L,
            maxBusyMs = maxBusyMs,
        )
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
