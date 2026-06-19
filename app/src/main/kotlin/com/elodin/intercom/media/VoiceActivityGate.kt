package com.elodin.intercom.media

/**
 * Pure hysteresis gate that turns a stream of voice peak-amplitude samples into
 * a stable "duck the media" decision. Used by [MediaShareController] to dip the
 * shared media while the partner is talking and restore it when they stop.
 *
 * Hysteresis avoids flapping at word/syllable gaps: ducking ENGAGES the moment a
 * peak crosses [onThreshold], and only RELEASES after the peak has stayed below
 * [offThreshold] continuously for [releaseMs]. [nowMs] is injected so the
 * release timing is unit-testable without a real clock.
 */
internal class VoiceActivityGate(
    private val onThreshold: Int,
    private val offThreshold: Int,
    private val releaseMs: Long,
) {
    private var ducking = false
    private var belowSinceMs = NEVER

    /** Feed one peak sample; returns true when media should be ducked. */
    fun update(
        peak: Int,
        nowMs: Long,
    ): Boolean {
        if (peak >= onThreshold) {
            ducking = true
            belowSinceMs = NEVER
            return true
        }
        if (!ducking) return false
        if (peak >= offThreshold) {
            belowSinceMs = NEVER // still in the sustain band — hold the duck
            return true
        }

        if (belowSinceMs == NEVER) belowSinceMs = nowMs
        if (nowMs - belowSinceMs >= releaseMs) {
            ducking = false
            belowSinceMs = NEVER
            return false
        }
        return true
    }

    companion object {
        private const val NEVER = -1L
    }
}
