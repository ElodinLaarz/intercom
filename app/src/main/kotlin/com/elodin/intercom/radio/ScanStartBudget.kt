package com.elodin.intercom.radio

internal class ScanStartBudget(
    private val windowMs: Long,
    private val maxStarts: Int,
    private val nowMs: () -> Long,
) {
    private val starts = ArrayDeque<Long>()

    @Synchronized
    fun delayUntilAvailableMs(): Long {
        val now = nowMs()
        prune(now)
        if (starts.size < maxStarts) return 0
        return (windowMs - (now - starts.first())).coerceAtLeast(0)
    }

    @Synchronized
    fun recordStart() {
        val now = nowMs()
        prune(now)
        starts.addLast(now)
    }

    private fun prune(now: Long) {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) {
            starts.removeFirst()
        }
    }
}

internal fun scanCooldownStatus(remainingMs: Long): String {
    val tenths = (remainingMs.coerceAtLeast(0) + MILLIS_PER_TENTH - 1) / MILLIS_PER_TENTH
    return "Rapid role changes — Bluetooth scan cooldown " +
        "${tenths / TENTHS_PER_SECOND}.${tenths % TENTHS_PER_SECOND}s"
}

private const val MILLIS_PER_TENTH = 100L
private const val TENTHS_PER_SECOND = 10L
