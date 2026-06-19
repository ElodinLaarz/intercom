package com.elodin.intercom.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * AAC-LC decoder for the shared-media RX path ([MediaCodec], hardware). Each
 * incoming frame is a raw AAC access unit (no ADTS/codec-config on the wire), so
 * the decoder is configured with a deterministic `csd-0` AudioSpecificConfig
 * built from the fixed sample-rate/channels — the same parameters the sender's
 * [MediaEncoder] used — making every access unit self-decodable.
 *
 * Single-threaded: [decode] is only called from the media-link RX thread.
 * Decoded PCM16 is delivered to [onPcm] (the [MediaPlayout] sink).
 */
internal class MediaDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onPcm: (ByteArray, Int) -> Unit,
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    @Suppress("TooGenericExceptionCaught")
    fun start(): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setByteBuffer(CSD_0, ByteBuffer.wrap(audioSpecificConfig(sampleRate, channels)))
        return try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            presentationUs = 0L
            Log.i(TAG, "MEDIA decoder started rate=$sampleRate ch=$channels")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA decoder start failed: ${e.message}")
            releaseCodec()
            false
        }
    }

    fun decode(accessUnit: ByteArray) {
        val c = codec ?: return
        if (accessUnit.isEmpty()) return
        try {
            feedInput(c, accessUnit)
            drainOutput(c)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MEDIA decoder decode failed: ${e.message}")
        }
    }

    fun stop() {
        releaseCodec()
        Log.i(TAG, "MEDIA decoder stopped")
    }

    private fun feedInput(
        c: MediaCodec,
        accessUnit: ByteArray,
    ) {
        val index = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return // no free buffer — drop (best-effort)

        val input = c.getInputBuffer(index) ?: return
        input.clear()
        input.put(accessUnit)
        c.queueInputBuffer(index, 0, accessUnit.size, presentationUs, 0)
        presentationUs += AAC_FRAME_SAMPLES * MICROS_PER_SEC / sampleRate
    }

    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, 0)
            if (index < 0) return

            if (bufferInfo.size > 0) {
                val output = c.getOutputBuffer(index)
                if (output != null) {
                    val pcm = ByteArray(bufferInfo.size)
                    output.position(bufferInfo.offset)
                    output.get(pcm, 0, bufferInfo.size)
                    onPcm(pcm, bufferInfo.size)
                }
            }
            c.releaseOutputBuffer(index, false)
        }
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MEDIA decoder stop: ${e.message}")
        }
        c.release()
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val CSD_0 = "csd-0"
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val MICROS_PER_SEC = 1_000_000L
        private const val AAC_FRAME_SAMPLES = 1_024L
        private const val AAC_LC_OBJECT_TYPE = 2
        private const val BITS_3 = 3
        private const val BITS_7 = 7
        private const val LOW_BIT_MASK = 1

        // AAC sample-rate index table (ISO/IEC 14496-3). Index into this is the
        // 4-bit samplingFrequencyIndex packed into the AudioSpecificConfig.
        private val SAMPLE_RATE_TABLE =
            intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000)

        /**
         * Build the 2-byte AAC-LC AudioSpecificConfig (`csd-0`): 5-bit object
         * type, 4-bit sample-rate index, 4-bit channel config, 3-bit GASpecific
         * tail. Deterministic from the fixed rate/channels, so it need not be
         * sent on the wire.
         */
        fun audioSpecificConfig(
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val freqIndex = SAMPLE_RATE_TABLE.indexOf(sampleRate).let { if (it < 0) DEFAULT_FREQ_INDEX else it }
            val b0 = (AAC_LC_OBJECT_TYPE shl BITS_3) or (freqIndex shr LOW_BIT_MASK)
            val b1 = ((freqIndex and LOW_BIT_MASK) shl BITS_7) or (channels shl BITS_3)
            return byteArrayOf(b0.toByte(), b1.toByte())
        }

        private const val DEFAULT_FREQ_INDEX = 3 // 48 kHz
    }
}
