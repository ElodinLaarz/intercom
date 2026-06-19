package com.elodin.intercom.media

import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import com.elodin.intercom.NativeCore
import com.elodin.intercom.proto.MediaFrame
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.wifidirect.WifiDirectMediaLink
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Coordinates the shared-audio feature: capture+encode one side's media and
 * stream it over the [WifiDirectMediaLink] (TX), and decode+play the partner's
 * stream (RX), with the intercom voice ducking the media.
 *
 * Owned by [com.elodin.intercom.radio.RadioController], driven off link state:
 * [onLinked] builds the media channel for an epoch, [onUnlinked] tears it down
 * (V2_PLAN rule 4). The voice radios are untouched — this rides the same Wi-Fi
 * Direct group on a separate port.
 *
 * Threading: the TX pipeline (capture/encoder) lives on the main + capture
 * threads; the RX pipeline (decoder/playout) is built, fed, and torn down only
 * on the media-link worker thread (MediaCodec is not safe to release elsewhere
 * mid-decode), via [onFrame]/[onLinkClosed]; ducking runs on its own poll thread.
 */
@Suppress("TooManyFunctions")
internal class MediaShareController(
    private val onSharingChanged: (Boolean) -> Unit,
    private val onPartnerSharingChanged: (Boolean) -> Unit,
    private val onCaptureSilentChanged: (Boolean) -> Unit,
    private val onStopProjectionService: () -> Unit,
) {
    // Link context (mutated on the main thread).
    private var link: WifiDirectMediaLink? = null
    private var isHost = false

    @Volatile
    private var linkEpoch = 0

    @Volatile
    private var linked = false

    private val txSeq = AtomicInteger(0)

    // TX / sharer pipeline (main + capture threads).
    private var capture: MediaCapture? = null
    private var encoder: MediaEncoder? = null
    private var projection: MediaProjection? = null

    @Volatile
    private var sharing = false

    @Volatile
    private var wantShare = false

    // RX / listener pipeline. Built/fed/torn down on the media-link worker thread;
    // WifiDirectMediaLink.close() joins that worker before the next epoch's link
    // is built, so the codec stays single-threaded. @Volatile is belt-and-suspenders
    // for the (practically unreachable) bounded-join-timeout edge.
    @Volatile
    private var decoder: MediaDecoder? = null

    @Volatile
    private var playout: MediaPlayout? = null

    @Volatile
    private var seqFilter: MediaSeqFilter? = null

    @Volatile
    private var partnerSharing = false

    // Ducking poll (own thread).
    @Volatile
    private var duckRunning = false
    private var duckThread: Thread? = null

    init {
        MediaProjectionRelay.setConsumer(::onProjection)
        MediaProjectionRelay.setRevokeConsumer(::onProjectionRevoked)
    }

    // --- Link lifecycle (main thread) ---

    fun onLinked(
        isHost: Boolean,
        peer: String,
        wireEpoch: Long,
    ) {
        if (linked) return
        this.isHost = isHost
        linkEpoch = wireEpoch.toInt()
        txSeq.set(0)
        seqFilter = MediaSeqFilter(linkEpoch)
        linked = true

        val mediaLink = WifiDirectMediaLink(isHost, peer, ::onFrame, ::onLinkClosed)
        link = mediaLink
        mediaLink.start()
        Log.i(TAG, "MEDIA share link up host=$isHost peer=$peer wireEpoch=$linkEpoch")

        if (wantShare) projection?.let { startTx(it) } // resume sharing after a reconnect
    }

    fun onUnlinked() {
        if (!linked) return
        linked = false
        stopTx()
        // close() joins the worker, so its onLinkClosed → stopRx (codec/playout
        // release) completes BEFORE the next onLinked builds a new link — no
        // cross-thread MediaCodec use across the epoch boundary.
        link?.close()
        link = null
        seqFilter = null
    }

    fun close() {
        MediaProjectionRelay.setConsumer(null)
        MediaProjectionRelay.setRevokeConsumer(null)
        onUnlinked()
        stopProjectionService()
        projection = null
        wantShare = false
    }

    // --- Share intent (main thread) ---

    fun requestShare() {
        wantShare = true
        val ready = projection
        if (ready != null && linked) startTx(ready)
    }

    fun stopShare() {
        wantShare = false
        stopTx()
        stopProjectionService() // stop the FGS so it releases the MediaProjection
        projection = null
    }

    private fun onProjection(granted: MediaProjection) {
        projection = granted
        if (wantShare && linked) startTx(granted)
    }

    // The user/OS revoked screen-capture consent (service onStop). Drop the now
    // dead projection and reset intent so the UI and capability don't diverge.
    private fun onProjectionRevoked() {
        wantShare = false
        stopTx()
        projection = null
    }

    // Stop the foreground service so it releases the MediaProjection. Safe to
    // call unconditionally — stopService is a no-op when nothing is running, so
    // this also covers a session stopped between consent and projection delivery.
    private fun stopProjectionService() {
        onStopProjectionService()
    }

    // --- TX: capture + encode + send (main + capture threads) ---

    private fun startTx(source: MediaProjection) {
        if (sharing) return
        if (partnerSharing) {
            // One sender at a time — don't capture the peer's playout back (feedback).
            Log.i(TAG, "MEDIA share suppressed: partner already sharing")
            return
        }
        val mediaLink = link ?: return

        val enc =
            MediaEncoder(Proto.MEDIA_SAMPLE_RATE_HZ, Proto.MEDIA_CHANNELS_MAX) { unit ->
                sendAudio(mediaLink, unit)
            }
        if (!enc.start()) {
            Log.w(TAG, "MEDIA share encoder start failed")
            return
        }

        val cap =
            MediaCapture(
                projection = source,
                onPcm = { pcm, length -> enc.encode(pcm, length) },
                onSilenceChanged = onCaptureSilentChanged,
            )
        if (!cap.start()) {
            enc.stop()
            Log.w(TAG, "MEDIA share capture start failed")
            return
        }

        encoder = enc
        capture = cap
        sharing = true
        sendSentinel(mediaLink, SHARE_STATE_START)
        onSharingChanged(true)
        Log.i(TAG, "MEDIA sharing started")
    }

    private fun stopTx() {
        val wasSharing = sharing
        sharing = false
        if (wasSharing) link?.let { sendSentinel(it, SHARE_STATE_STOP) }
        capture?.stop() // joins the capture thread, so no encode() races the codec stop
        capture = null
        encoder?.stop()
        encoder = null
        if (wasSharing) {
            onSharingChanged(false)
            Log.i(TAG, "MEDIA sharing stopped")
        }
    }

    private fun sendAudio(
        mediaLink: WifiDirectMediaLink,
        unit: ByteArray,
    ) {
        if (unit.size > MediaEncoder.MAX_UNIT_BYTES) {
            Log.w(TAG, "MEDIA tx drop oversize unit=${unit.size}")
            return
        }
        mediaLink.send(MediaFrame(linkEpoch, txSeq.getAndIncrement(), FLAG_STEREO, unit))
    }

    private fun sendSentinel(
        mediaLink: WifiDirectMediaLink,
        state: Int,
    ) {
        mediaLink.send(
            MediaFrame(linkEpoch, txSeq.getAndIncrement(), FLAG_SENTINEL, byteArrayOf(state.toByte())),
        )
    }

    // --- RX: receive + decode + play (media-link worker thread) ---

    private fun onFrame(frame: MediaFrame) {
        val filter = seqFilter ?: return
        if (!filter.accept(frame.epoch, frame.seq)) return
        if (isSentinel(frame)) {
            handleSentinel(frame)
            return
        }
        ensureRx()
        decoder?.decode(frame.payload)
    }

    private fun handleSentinel(frame: MediaFrame) {
        val starting = frame.payload.isNotEmpty() && frame.payload[0].toInt() == SHARE_STATE_START
        if (!starting) {
            stopRx()
            return
        }
        if (sharing) {
            // Simultaneous-share race: exactly one survives. The host keeps sending
            // and ignores the peer START (never play our own captured stream back);
            // the guest yields and becomes the listener.
            if (isHost) return
            stopTx()
        }
        ensureRx()
    }

    private fun onLinkClosed() {
        stopRx()
    }

    private fun ensureRx() {
        if (playout != null) return
        val sink = MediaPlayout(Proto.MEDIA_SAMPLE_RATE_HZ, Proto.MEDIA_CHANNELS_MAX)
        if (!sink.start()) {
            Log.w(TAG, "MEDIA listener playout start failed")
            return
        }
        val dec =
            MediaDecoder(Proto.MEDIA_SAMPLE_RATE_HZ, Proto.MEDIA_CHANNELS_MAX) { pcm, size ->
                sink.write(pcm, size)
            }
        if (!dec.start()) {
            sink.stop()
            Log.w(TAG, "MEDIA listener decoder start failed")
            return
        }
        decoder = dec
        playout = sink
        setPartnerSharing(true)
        startDuckLoop(sink)
        Log.i(TAG, "MEDIA listener pipeline up")
    }

    private fun stopRx() {
        stopDuckLoop()
        decoder?.stop()
        decoder = null
        playout?.stop()
        playout = null
        setPartnerSharing(false)
    }

    // --- Ducking (own thread): partner voice peak → media duck ---

    private fun startDuckLoop(target: MediaPlayout) {
        if (duckThread != null) return
        duckRunning = true
        duckThread =
            thread(isDaemon = true, name = "media-duck") {
                val gate = VoiceActivityGate(DUCK_ON_PEAK, DUCK_OFF_PEAK, DUCK_RELEASE_MS)
                while (duckRunning) {
                    target.setDucked(gate.update(NativeCore.voiceRxPeak(), SystemClock.elapsedRealtime()))
                    sleep(DUCK_POLL_MS)
                }
            }
    }

    // Both start/stop run on the single media-link worker thread, so joining the
    // prior duck thread here guarantees it has exited before a new one can start —
    // no orphaned, never-terminating poll thread accumulates across stop/start.
    private fun stopDuckLoop() {
        duckRunning = false
        val previous = duckThread
        duckThread = null
        if (previous != null && previous !== Thread.currentThread()) {
            previous.interrupt()
            previous.join(DUCK_JOIN_MS)
        }
    }

    private fun setPartnerSharing(value: Boolean) {
        if (partnerSharing == value) return
        partnerSharing = value
        onPartnerSharingChanged(value)
    }

    private fun isSentinel(frame: MediaFrame): Boolean = frame.flags and FLAG_SENTINEL != 0

    private fun sleep(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val FLAG_STEREO = 0x01
        private const val FLAG_SENTINEL = 0x04
        private const val SHARE_STATE_START = 1
        private const val SHARE_STATE_STOP = 0

        // Voice ducking: peak thresholds over the partner's decoded voice
        // (0..32767) with hysteresis; rig-tunable.
        private const val DUCK_ON_PEAK = 2_500
        private const val DUCK_OFF_PEAK = 1_200
        private const val DUCK_RELEASE_MS = 350L
        private const val DUCK_POLL_MS = 60L
        private const val DUCK_JOIN_MS = 200L
    }
}
