package com.elodin.intercom.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android audio-mode setup that must happen before Oboe opens the stream
 * (landmine #4). Native owns the actual streams; this helper only applies the
 * platform communication mode/route requested by the voice path.
 */
internal object VoiceAudioRoute {
    private var activeUsers = 0
    private var activeAudio: AudioManager? = null
    private var callbackRegistered = false

    private val callbackHandler = Handler(Looper.getMainLooper())
    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshCommunicationRoute("device added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refreshCommunicationRoute("device removed")
            }
        }

    @Synchronized
    fun enterCommunication(context: Context) {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        activeUsers += 1
        if (activeUsers == 1) {
            activeAudio = audio
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            registerDeviceCallback(audio)
        }

        applyCommunicationRoute(audio, "enter")
    }

    @Synchronized
    fun leaveCommunication(context: Context) {
        if (activeUsers == 0) return

        activeUsers -= 1
        if (activeUsers > 0) return

        val audio = activeAudio ?: context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        unregisterDeviceCallback(audio)
        activeAudio = null
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    @Synchronized
    private fun refreshCommunicationRoute(reason: String) {
        val audio = activeAudio ?: return
        if (activeUsers == 0) return

        applyCommunicationRoute(audio, reason)
    }

    private fun applyCommunicationRoute(
        audio: AudioManager,
        reason: String,
    ) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val candidates = preferredCommunicationDevices(audio)
        if (candidates.isEmpty()) {
            Log.w(TAG, "AUDIO no communication route candidates")
            return
        }

        val current = audio.communicationDevice
        candidates.forEach { device ->
            if (sameRoute(current, device)) return

            if (audio.setCommunicationDevice(device)) {
                Log.i(TAG, "AUDIO route $reason ${device.describe()}")
                return
            }
            Log.w(TAG, "AUDIO setCommunicationDevice failed ${device.describe()}")
        }
    }

    private fun preferredCommunicationDevices(audio: AudioManager): List<AudioDeviceInfo> {
        val devices = audio.availableCommunicationDevices
        return DEVICE_ORDER.mapNotNull { type ->
            devices.firstOrNull { it.type == type }
        }
    }

    private fun registerDeviceCallback(audio: AudioManager) {
        if (callbackRegistered) return

        audio.registerAudioDeviceCallback(deviceCallback, callbackHandler)
        callbackRegistered = true
    }

    private fun unregisterDeviceCallback(audio: AudioManager) {
        if (!callbackRegistered) return

        audio.unregisterAudioDeviceCallback(deviceCallback)
        callbackRegistered = false
    }

    private fun sameRoute(
        current: AudioDeviceInfo?,
        candidate: AudioDeviceInfo,
    ): Boolean = current != null && current.id == candidate.id && current.type == candidate.type

    private fun AudioDeviceInfo.describe(): String = "type=${typeName(type)} id=$id name=$productName"

    private fun typeName(type: Int): String =
        when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
            else -> "UNKNOWN_$type"
        }

    private const val TAG = "INTERCOM"

    private val DEVICE_ORDER =
        listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
}
