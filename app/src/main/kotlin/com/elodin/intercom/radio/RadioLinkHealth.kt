package com.elodin.intercom.radio

/**
 * Radio link health policy for reliable L2CAP sockets.
 *
 * A single multi-second write means the peer will hear stale audio even though
 * GATT still reports connected. Treat that as link loss so M2 reconnect can
 * replace the wedged CoC with a fresh epoch.
 */
internal object RadioLinkHealth {
    const val L2CAP_WRITE_STALL_RECONNECT_MS = 1_500L
    const val RX_DELIVERY_WINDOW_MS = 2_000L
    const val RX_MIN_FRAMES_PER_WINDOW = 75L
    const val RX_STARVED_WINDOWS_BEFORE_RECONNECT = 3

    fun shouldReconnectAfterWrite(writeMs: Long): Boolean = writeMs >= L2CAP_WRITE_STALL_RECONNECT_MS
}

internal class RxDeliveryHealth(
    private val nowMs: () -> Long,
) {
    private var windowStartMs = Long.MIN_VALUE
    private var frames = 0L
    private var starvedWindows = 0

    fun onFrame(): RxStarvation? {
        val now = nowMs()
        if (windowStartMs == Long.MIN_VALUE) windowStartMs = now
        frames += 1

        val elapsedMs = now - windowStartMs
        if (elapsedMs < RadioLinkHealth.RX_DELIVERY_WINDOW_MS) return null

        val result = evaluateWindow(elapsedMs)
        windowStartMs = now
        frames = 0L
        return result
    }

    private fun evaluateWindow(elapsedMs: Long): RxStarvation? {
        if (frames >= RadioLinkHealth.RX_MIN_FRAMES_PER_WINDOW) {
            starvedWindows = 0
            return null
        }

        starvedWindows += 1
        if (starvedWindows < RadioLinkHealth.RX_STARVED_WINDOWS_BEFORE_RECONNECT) return null

        return RxStarvation(
            frames = frames,
            elapsedMs = elapsedMs,
            starvedWindows = starvedWindows,
        )
    }
}

internal data class RxStarvation(
    val frames: Long,
    val elapsedMs: Long,
    val starvedWindows: Int,
)
