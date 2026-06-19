package com.elodin.intercom.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One on-wire media frame (shared-audio path, coded audio over Wi-Fi Direct only).
 * Little-endian, variable length (header [Proto.MEDIA_HEADER_BYTES] + coded payload):
 *
 *   `epoch u32 | seq u32 | flags u8 | reserved u8 | len u16 | payload[len]`
 *
 * The payload is opaque coded audio (the app encodes AAC-LC via Android
 * MediaCodec); the wire layout is codec-neutral.
 *
 * flags bit0 = stereo, bit1 = FEC-bearing, bit2 = share-state sentinel;
 * other bits reserved (sender sends 0). The parser is lenient on flags
 * (unknown bits pass through) but strict on reserved (drop if nonzero).
 *
 * All sizes/offsets come from [Proto]; the C++ header `proto/constants.h` is
 * the single source (landmine #10). The C++ mirror is `proto/media_frame.h`.
 *
 * Note: [payload] is a [ByteArray] — compare frames field-by-field (e.g.
 * `contentEquals`), not with the generated `==`.
 */
data class MediaFrame(
    val epoch: Int,
    val seq: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    /** Serialize to [Proto.MEDIA_HEADER_BYTES] + [payload].size little-endian bytes. */
    fun encode(): ByteArray {
        require(payload.size in 0..Proto.MEDIA_PAYLOAD_MAX_BYTES) {
            "payload size ${payload.size} exceeds max ${Proto.MEDIA_PAYLOAD_MAX_BYTES}"
        }
        val total = Proto.MEDIA_HEADER_BYTES + payload.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(epoch)
        bb.putInt(seq)
        bb.put(flags.toByte())
        bb.put(RESERVED)
        bb.putShort(payload.size.toShort())
        bb.put(payload)
        return bb.array()
    }

    companion object {
        private const val RESERVED: Byte = 0

        /**
         * Parse + bounds-check. Returns null — the frame is DROPPED — if
         * the length is too short, `reserved` is non-zero, the encoded len
         * exceeds [Proto.MEDIA_PAYLOAD_MAX_BYTES], or the total size does not
         * match header + len. Unknown `flags` bits pass through (lenient
         * parser policy). `epoch`/`seq` are returned as-is; the media
         * SeqFilter gates them later.
         */
        fun decode(bytes: ByteArray): MediaFrame? {
            if (bytes.size < Proto.MEDIA_HEADER_BYTES) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val epoch = bb.getInt()
            val seq = bb.getInt()
            val flags = bb.get().toUByte().toInt()
            val reserved = bb.get().toUByte().toInt()
            val len = bb.getShort().toUShort().toInt()
            if (reserved != 0) return null
            if (len > Proto.MEDIA_PAYLOAD_MAX_BYTES) return null
            if (bytes.size != Proto.MEDIA_HEADER_BYTES + len) return null
            val payload = ByteArray(len)
            bb.get(payload)
            return MediaFrame(epoch, seq, flags, payload)
        }
    }
}
