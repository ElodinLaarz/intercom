package com.elodin.intercom

/**
 * JNI surface for the native core (M0 toolchain probe). [selfTest] runs the
 * host-tested ring buffer + seq/epoch filter on-device and returns `0xC0DE` on
 * success. No audio yet — Oboe/ADPCM land in M1.
 */
object NativeCore {
    init {
        System.loadLibrary("intercore")
    }

    external fun selfTest(): Int
}
