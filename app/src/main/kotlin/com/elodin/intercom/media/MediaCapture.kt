package com.elodin.intercom.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.elodin.intercom.proto.Proto

/**
 * Owns the [AudioRecord] for playback capture (AudioPlaybackCapture via a granted
 * [MediaProjection]). Captures only USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN —
 * NEVER USAGE_VOICE_COMMUNICATION, so the intercom's own voice is never looped
 * back into the shared-media stream.
 *
 * A daemon thread named `media-capture` reads interleaved PCM16 (48 kHz stereo)
 * and hands each buffer to [onPcm] (the AAC encoder) and to a [SilenceDetector]
 * whose transitions are reported through [onSilenceChanged].
 *
 * Note: capturable apps only — AntennaPod and other open media apps allow it;
 * apps that set `ALLOW_CAPTURE_BY_NONE` (most DRM music/video) emit silence here.
 */
internal class MediaCapture(
    private val projection: MediaProjection,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onSilenceChanged: (Boolean) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    fun start(): Boolean {
        val config =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val sampleRate = Proto.MEDIA_SAMPLE_RATE_HZ
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            Log.w(TAG, "MEDIA capture getMinBufferSize failed")
            return false
        }

        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()

        val rec =
            AudioRecord
                .Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * BUFFER_FACTOR)
                .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "MEDIA capture AudioRecord not initialized")
            rec.release()
            return false
        }

        audioRecord = rec
        running = true
        thread = Thread({ readLoop(rec) }, "media-capture").also { it.isDaemon = true }
        thread?.start()
        Log.i(TAG, "MEDIA capture started rate=$sampleRate stereo bufSize=${minBuf * BUFFER_FACTOR}")
        return true
    }

    fun stop() {
        running = false
        thread?.join(THREAD_JOIN_TIMEOUT_MS)
        thread = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        Log.i(TAG, "MEDIA capture stopped")
    }

    private fun readLoop(rec: AudioRecord) {
        rec.startRecording()
        val bufSize = rec.bufferSizeInFrames * Proto.MEDIA_CHANNELS_MAX * BYTES_PER_SAMPLE
        val buf = ByteArray(bufSize)
        val detector = SilenceDetector(windowMs = SILENCE_WINDOW_MS, rmsFloor = RMS_FLOOR)
        var lastSilent: Boolean? = null

        while (running) {
            val bytesRead = rec.read(buf, 0, bufSize)
            if (bytesRead <= 0) {
                if (running) Log.w(TAG, "MEDIA capture read returned $bytesRead")
                continue
            }
            onPcm(buf, bytesRead)

            val samples = bytesRead / BYTES_PER_SAMPLE
            val nowMs = System.currentTimeMillis()
            val silent = detector.onPcm(buf, samples, nowMs)
            if (silent != lastSilent) {
                lastSilent = silent
                onSilenceChanged(silent)
            }
        }
        rec.stop()
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val THREAD_JOIN_TIMEOUT_MS = 1_000L
        private const val SILENCE_WINDOW_MS = 2_000L
        private const val RMS_FLOOR = 10
        private const val BUFFER_FACTOR = 2
        private const val BYTES_PER_SAMPLE = 2
    }
}
