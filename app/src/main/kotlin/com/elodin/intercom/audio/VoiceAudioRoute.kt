package com.elodin.intercom.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Android audio-mode setup that must happen before Oboe opens the stream
 * (landmine #4). Native owns the actual streams; this helper only applies the
 * platform communication mode/route requested by the voice path.
 */
internal object VoiceAudioRoute {
    private var activeUsers = 0

    @Synchronized
    fun enterCommunication(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        activeUsers += 1
        if (activeUsers > 1) return

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val device = preferredCommunicationDevice(audio) ?: return
        if (!audio.setCommunicationDevice(device)) {
            Log.w(TAG, "AUDIO setCommunicationDevice failed type=${device.type}")
        }
    }

    @Synchronized
    fun leaveCommunication(context: Context) {
        if (activeUsers == 0) return

        activeUsers -= 1
        if (activeUsers > 0) return

        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun preferredCommunicationDevice(audio: AudioManager): AudioDeviceInfo? {
        val devices = audio.availableCommunicationDevices
        return DEVICE_ORDER.firstNotNullOfOrNull { type ->
            devices.firstOrNull { it.type == type }
        }
    }

    private const val TAG = "INTERCOM"

    private val DEVICE_ORDER =
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
}
