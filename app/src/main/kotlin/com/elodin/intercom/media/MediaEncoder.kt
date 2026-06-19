package com.elodin.intercom.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.elodin.intercom.proto.Proto

/**
 * AAC-LC encoder for the shared-media TX path, built on Android's guaranteed
 * hardware [MediaCodec] AAC encoder (no native codec, no vendored library). It
 * consumes interleaved PCM16 from [MediaCapture] and emits raw AAC access units
 * (no ADTS/codec-config on the wire — the peer's [MediaDecoder] is configured
 * from a deterministic AudioSpecificConfig derived from the same fixed
 * sample-rate/channels, so each access unit is self-contained given that config).
 *
 * Best-effort by design: if no input buffer is free, the PCM chunk is dropped
 * (a brief media glitch) rather than blocking the capture thread — media yields
 * to realtime, exactly like the voice path drops rather than backlogs.
 *
 * Single-threaded: [encode] is only ever called from the `media-capture` thread.
 */
internal class MediaEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onAccessUnit: (ByteArray) -> Unit,
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    @Suppress("TooGenericExceptionCaught")
    fun start(): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        return try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            presentationUs = 0L
            Log.i(TAG, "MEDIA encoder started rate=$sampleRate ch=$channels bitrate=$BITRATE_BPS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA encoder start failed: ${e.message}")
            releaseCodec()
            false
        }
    }

    fun encode(
        pcm: ByteArray,
        length: Int,
    ) {
        val c = codec ?: return
        if (length <= 0) return
        try {
            // The capture buffer is larger than one codec input buffer, so feed
            // the whole chunk across as many input buffers as it takes — never
            // drop the tail of a read.
            var offset = 0
            while (offset < length) {
                val queued = feedInput(c, pcm, offset, length)
                if (queued <= 0) break // no free buffer — drop the remainder (best-effort)
                offset += queued
                drainOutput(c)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MEDIA encoder encode failed: ${e.message}")
        }
    }

    fun stop() {
        releaseCodec()
        Log.i(TAG, "MEDIA encoder stopped")
    }

    // Queue one input buffer's worth of PCM starting at [offset]; returns the
    // byte count queued (0 if no input buffer was free).
    private fun feedInput(
        c: MediaCodec,
        pcm: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val index = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return 0

        val input = c.getInputBuffer(index) ?: return 0
        input.clear()
        val room = minOf(length - offset, input.remaining())
        input.put(pcm, offset, room)
        c.queueInputBuffer(index, 0, room, presentationUs, 0)
        presentationUs += framesFor(room) * MICROS_PER_SEC / sampleRate
        return room
    }

    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, 0)
            if (index < 0) return

            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && bufferInfo.size > 0) {
                val output = c.getOutputBuffer(index)
                if (output != null) {
                    val unit = ByteArray(bufferInfo.size)
                    output.position(bufferInfo.offset)
                    output.get(unit, 0, bufferInfo.size)
                    onAccessUnit(unit)
                }
            }
            c.releaseOutputBuffer(index, false)
        }
    }

    private fun framesFor(byteCount: Int): Long {
        val bytesPerFrame = channels * BYTES_PER_SAMPLE
        return (byteCount / bytesPerFrame).toLong()
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MEDIA encoder stop: ${e.message}")
        }
        c.release()
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val BITRATE_BPS = 128_000
        private const val MAX_INPUT_BYTES = 8_192
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val MICROS_PER_SEC = 1_000_000L
        private const val BYTES_PER_SAMPLE = 2

        /** Max AAC access-unit size we'll put on the wire (bounds vs the frame cap). */
        const val MAX_UNIT_BYTES = Proto.MEDIA_PAYLOAD_MAX_BYTES
    }
}
