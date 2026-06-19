package com.elodin.intercom

/**
 * JNI surface for the native core (M0 toolchain probe). [selfTest] runs the
 * host-tested ring buffer + seq/epoch filter on-device and returns `0xC0DE` on
 * success. The M1 voice path keeps socket I/O in Kotlin; native owns Oboe,
 * ADPCM, seq filtering, and jitter buffering, exchanging only frame bytes.
 */
object NativeCore {
    init {
        System.loadLibrary("intercore")
    }

    external fun selfTest(): Int

    external fun startGuestCapture(epoch: Long): Boolean

    external fun takeGuestBundle(
        maxFrames: Int,
        timeoutMs: Int,
    ): ByteArray?

    external fun stopGuestCapture()

    external fun startHostPlayout(epoch: Long): Boolean

    external fun pushHostFrame(frame: ByteArray): Boolean

    external fun stopHostPlayout()

    /**
     * Peak |amplitude| (0..32767) of the partner's most recently decoded voice
     * frame, or 0 when no voice playout is live. Polled by the shared-media
     * ducking loop to detect partner speech.
     */
    external fun voiceRxPeak(): Int
}
