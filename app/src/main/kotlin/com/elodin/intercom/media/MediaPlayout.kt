package com.elodin.intercom.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the peer's shared media through an [AudioTrack] with `USAGE_MEDIA` /
 * `CONTENT_TYPE_MUSIC`, separate from the voice path's communication stream.
 * Only the LISTENER runs a playout (the sharer already hears the source app
 * locally), so this never double-plays the sharer's own audio.
 *
 * [setDucked] lowers the track volume so the intercom voice stays intelligible
 * over the media — the "voice ducks media" behaviour. Blocking [write] from the
 * media-link RX thread paces delivery to realtime (natural backpressure).
 */
internal class MediaPlayout(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private var track: AudioTrack? = null

    @Volatile
    private var ducked = false

    fun start(): Boolean {
        val channelMask =
            if (channels >= STEREO_CHANNELS) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "MEDIA playout getMinBufferSize failed")
            return false
        }

        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

        val t =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * BUFFER_FACTOR)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "MEDIA playout AudioTrack not initialized")
            t.release()
            return false
        }

        track = t
        applyVolume()
        t.play()
        Log.i(TAG, "MEDIA playout started rate=$sampleRate ch=$channels")
        return true
    }

    fun write(
        pcm: ByteArray,
        size: Int,
    ) {
        val t = track ?: return
        if (size <= 0) return
        t.write(pcm, 0, size, AudioTrack.WRITE_BLOCKING)
    }

    /** Duck (or restore) the media so the intercom voice stays on top. */
    fun setDucked(duck: Boolean) {
        if (ducked == duck) return
        ducked = duck
        applyVolume()
        Log.i(TAG, "MEDIA playout ducked=$duck")
    }

    fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MEDIA playout stop: ${e.message}")
        }
        t.release()
        Log.i(TAG, "MEDIA playout stopped")
    }

    private fun applyVolume() {
        val gain = if (ducked) DUCKED_GAIN else FULL_GAIN
        track?.setVolume(gain * AudioTrack.getMaxVolume())
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val BUFFER_FACTOR = 2
        private const val STEREO_CHANNELS = 2
        private const val FULL_GAIN = 1.0f
        private const val DUCKED_GAIN = 0.25f
    }
}
