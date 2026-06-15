package com.elodin.intercom.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One on-wire voice frame (M1_PLAN.md §2). Little-endian, [Proto.VOICE_FRAME_BYTES]:
 *
 *   `epoch u32 | seq u32 | predSample i16 | stepIndex u8 | reserved u8 | adpcm[160]`
 *
 * [predSample]/[stepIndex] are the pre-roll IMA predictor snapshot the decoder
 * seeds from — frames are self-contained, so a single lost frame costs exactly
 * one 20 ms gap. All sizes/offsets come from [Proto]; the C++ header
 * `proto/constants.h` is the single source (landmine #10). The C++ mirror is
 * `proto/voice_frame.h`.
 *
 * Note: [adpcm] is a [ByteArray] — compare frames field-by-field (e.g.
 * `contentEquals`), not with the generated `==`.
 */
data class VoiceFrame(
    val epoch: Int,
    val seq: Int,
    val predSample: Short,
    val stepIndex: Int,
    val adpcm: ByteArray,
) {
    /** Serialize to exactly [Proto.VOICE_FRAME_BYTES] little-endian bytes. */
    fun encode(): ByteArray {
        val bb = ByteBuffer.allocate(Proto.VOICE_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(epoch)
        bb.putInt(seq)
        bb.putShort(predSample)
        bb.put(stepIndex.toByte())
        bb.put(RESERVED)
        bb.put(adpcm)
        return bb.array()
    }

    companion object {
        private const val RESERVED: Byte = 0

        /**
         * Parse + bounds-check every field (landmine #12). Returns null — the
         * frame is DROPPED — if the length is wrong, `stepIndex` exceeds
         * [Proto.VOICE_STEP_INDEX_MAX], or `reserved` is non-zero. `epoch`/`seq`
         * are returned as-is; the SeqFilter gates them against the live epoch.
         */
        fun decode(bytes: ByteArray): VoiceFrame? {
            if (bytes.size != Proto.VOICE_FRAME_BYTES) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val epoch = bb.getInt()
            val seq = bb.getInt()
            val predSample = bb.getShort()
            val stepIndex = bb.get().toUByte().toInt()
            val reserved = bb.get().toUByte().toInt()
            val adpcm = ByteArray(Proto.VOICE_ADPCM_BYTES)
            bb.get(adpcm)
            if (stepIndex > Proto.VOICE_STEP_INDEX_MAX || reserved != 0) return null
            return VoiceFrame(epoch, seq, predSample, stepIndex, adpcm)
        }
    }
}
