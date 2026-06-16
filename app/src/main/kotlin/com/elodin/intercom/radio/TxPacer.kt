package com.elodin.intercom.radio

/**
 * Paces L2CAP voice writes against a wall clock so the kernel/BlueDroid socket
 * TX buffer below the app can't accumulate seconds of audio (landmine #1). The
 * native producer queue is capped and drops-to-latest, but that cap sits
 * *upstream* of the socket: once frames leave `takeFrame` they pile into a deep
 * FIFO send buffer the app can neither read nor flush. Blindly writing 50 fps
 * into a link draining slower (classic SCO headset coexistence starves the BLE
 * ACL) fills that FIFO, and a full FIFO is permanent latency — every later frame
 * plays out behind the backlog.
 *
 * The send path offers the freshest frame each tick; this decides whether to
 * write it or drop it to stay real-time, and never bursts to "catch up" after a
 * stall — for live voice the past is worthless, only the present matters.
 *
 * Pure and clock-injected (like [BackoffLadder] / [ScanStartBudget]) so the
 * policy is unit-tested off-device. One instance per link, discarded on
 * disconnect — no reset() (rule 4).
 *
 * Rate is AIMD on the one congestion signal observable locally: how long
 * `write()` blocks. A write past the stall threshold means the socket FIFO is
 * full and back-pressuring at the drain rate, so the offered interval is doubled
 * (rate cut); healthy writes step it back toward the route's base. The base
 * itself widens on a Bluetooth audio route, where the radio can't carry 50 fps
 * while it also services the headset.
 */
internal class TxPacer(
    private val nowMs: () -> Long,
) {
    private var baseIntervalMs = NORMAL_INTERVAL_MS
    private var intervalMs = NORMAL_INTERVAL_MS
    private var dueMs = Long.MIN_VALUE

    private var offered = 0L
    private var sent = 0L
    private var dropped = 0L
    private var stalls = 0L

    /** Widen the floor cadence when audio is on a radio-sharing (BT) route. */
    fun setRouteDegraded(degraded: Boolean) {
        val nextBase = if (degraded) HEADSET_INTERVAL_MS else NORMAL_INTERVAL_MS
        if (nextBase == baseIntervalMs) return
        baseIntervalMs = nextBase
        intervalMs = intervalMs.coerceAtLeast(nextBase)
    }

    /**
     * Decide whether to write the freshest frame now. Drops (false) when we are
     * ahead of the paced cadence; after a long gap it resyncs to the present
     * instead of replaying a backlog.
     */
    fun shouldSend(): Boolean {
        offered += 1
        val now = nowMs()
        if (dueMs == Long.MIN_VALUE) dueMs = now
        if (now < dueMs) {
            dropped += 1
            return false
        }
        val base = if (now - dueMs > intervalMs) now else dueMs
        dueMs = base + intervalMs
        sent += 1
        return true
    }

    /** Feed back how long the last `write()` blocked, to adapt the offered rate. */
    fun onWriteComplete(writeMs: Long) {
        if (writeMs > baseIntervalMs * STALL_FACTOR) {
            stalls += 1
            intervalMs = (intervalMs * BACKOFF_FACTOR).coerceAtMost(MAX_INTERVAL_MS)
            return
        }
        if (intervalMs <= baseIntervalMs) return
        intervalMs = (intervalMs - RECOVER_STEP_MS).coerceAtLeast(baseIntervalMs)
    }

    fun snapshot(): Snapshot = Snapshot(offered, sent, dropped, stalls, intervalMs)

    data class Snapshot(
        val offered: Long,
        val sent: Long,
        val dropped: Long,
        val stalls: Long,
        val intervalMs: Long,
    )

    companion object {
        const val NORMAL_INTERVAL_MS = 20L // 50 fps — matches the 20 ms encoder
        const val HEADSET_INTERVAL_MS = 40L // 25 fps — classic SCO can't carry 50
        const val MAX_INTERVAL_MS = 200L // 5 fps floor under sustained stall
        private const val STALL_FACTOR = 2L // write blocking > 2 frame-times = congested
        private const val BACKOFF_FACTOR = 2L
        private const val RECOVER_STEP_MS = 4L
    }
}
