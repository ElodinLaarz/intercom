package com.elodin.intercom.radio

/**
 * The bounded restart backoff ladder (landmine #8 — one-shot immediate reopens
 * race route settling and then give up). V2_PLAN §4.1 fixes the rungs at
 * 150/300/600/1000/1500 ms; this is their single owner.
 *
 * Stateless on purpose: the caller drives a 0-based retry index, so re-walking
 * the ladder after a fresh disconnect (M2 reconnect) is just restarting from 0 —
 * there is no mutable cursor to reset (rule 4).
 *
 * The first attempt is immediate; [delayBeforeRetryMs] is the wait *before* each
 * subsequent retry, and null once the ladder is exhausted.
 */
internal class BackoffLadder(
    rungsMs: LongArray = DEFAULT_RUNGS_MS,
) {
    private val rungsMs = rungsMs.copyOf()

    /** Wait before 0-based [retryIndex]; null when no rungs remain (give up). */
    fun delayBeforeRetryMs(retryIndex: Int): Long? = rungsMs.getOrNull(retryIndex)

    /** Wait before 0-based [retryIndex], holding at the last rung after exhaustion. */
    fun cappedDelayBeforeRetryMs(retryIndex: Int): Long {
        if (rungsMs.isEmpty()) return 0L
        if (retryIndex < 0) return rungsMs.first()
        return rungsMs.getOrElse(retryIndex) { rungsMs.last() }
    }

    companion object {
        private val DEFAULT_RUNGS_MS = longArrayOf(150L, 300L, 600L, 1_000L, 1_500L)
    }
}
