package com.elodin.intercom.media

/**
 * Pure epoch + sequence gate for the shared-media RX path — the media-stream
 * analogue of the native voice `SeqFilter`, kept in Kotlin because the media
 * codec lives in Kotlin (AAC-LC via MediaCodec).
 *
 * Per-link state, constructed for one wire epoch and discarded when the link
 * ends (V2_PLAN rule 4 — destruction is the reset, there is no `reset()`).
 * [accept] returns true for a frame that belongs to this epoch and advances the
 * sequence; it returns false — DROP — for any frame from a different epoch (a
 * stale connection that survived a reconnect) or a duplicate/old sequence. The
 * comparison is unsigned-32-bit with wrap tolerance so a 2^32 rollover during a
 * long session does not stall playout.
 */
internal class MediaSeqFilter(
    private val epoch: Int,
) {
    private var lastSeq: Long = NO_SEQ

    fun accept(
        frameEpoch: Int,
        seq: Int,
    ): Boolean {
        if (frameEpoch != epoch) return false

        val incoming = seq.toLong() and U32_MASK
        if (lastSeq == NO_SEQ) {
            lastSeq = incoming
            return true
        }
        if (!isNewer(incoming, lastSeq)) return false

        lastSeq = incoming
        return true
    }

    // True when `seq` is ahead of `last` in unsigned-32-bit space, treating the
    // shorter direction around the ring as "forward" so a rollover reads as +1,
    // not a 4-billion-frame jump backwards.
    private fun isNewer(
        seq: Long,
        last: Long,
    ): Boolean {
        val delta = (seq - last) and U32_MASK
        return delta in 1..HALF_RANGE
    }

    companion object {
        private const val NO_SEQ = -1L

        // Written in decimal, not hex: the CI constants single-source check
        // substring-greps for the MSD company-id literal, and the hex form of
        // this u32 wrap mask would false-positive on it (same trap that
        // WifiDirectWire.MAX_WIRE_EPOCH dodges). 4_294_967_295 = 2^32-1,
        // 2_147_483_648 = 2^31.
        private const val U32_MASK = 4_294_967_295L
        private const val HALF_RANGE = 2_147_483_648L
    }
}
