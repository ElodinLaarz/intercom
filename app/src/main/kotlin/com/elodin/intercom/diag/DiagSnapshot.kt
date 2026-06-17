package com.elodin.intercom.diag

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.elodin.intercom.BuildConfig
import com.elodin.intercom.audio.VoiceAudioRoute
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.ConnectionStats
import com.elodin.intercom.session.LinkState
import java.time.Instant

internal data class DiagSnapshot(
    val timestampIso: String,
    val uptimeMs: Long,
    val role: String,
    val epochId: Long?,
    val wireEpoch: Long?,
    val peer: String?,
    val psm: Int?,
    val linkDetail: String,
    val stats: ConnectionStats?,
    val deviceModel: String,
    val androidVersion: String,
    val apiLevel: Int,
    val appVersion: String,
    val btEnabled: Boolean,
    val audioRouteDegraded: Boolean,
) {
    fun format(): String =
        buildString {
            appendLine("=== Intercom Diagnostics Snapshot ===")
            appendLine("Timestamp: $timestampIso")
            appendLine("Uptime: $uptimeMs ms")
            appendLine()
            appendLine("--- Link State ---")
            appendLine("Role: $role")
            if (epochId != null) appendLine("Epoch: $epochId")
            if (wireEpoch != null) appendLine("Wire Epoch: 0x${wireEpoch.toString(HEX_RADIX).uppercase()}")
            if (peer != null) appendLine("Peer: $peer")
            if (psm != null) appendLine("PSM: $psm")
            appendLine("Status: $linkDetail")
            appendLine()
            appendLine("--- Connection Stats ---")
            if (stats != null) {
                appendLine("TX: ${stats.txBps} Bps  ${stats.txFps} fps  ${stats.txBusyPct}% busy")
                appendLine(
                    "RX: ${stats.rxBps} Bps  ${stats.rxFps} fps  " +
                        "${stats.rxBusyPct}% busy  max gap ${stats.rxMaxBusyMs}ms",
                )
            } else {
                appendLine("(no stats)")
            }
            appendLine()
            appendLine("--- Environment ---")
            appendLine("Device: $deviceModel")
            appendLine("Android: $androidVersion (API $apiLevel)")
            appendLine("App: $appVersion")
            appendLine("Bluetooth: ${if (btEnabled) "ON" else "OFF"}")
            appendLine("Audio Route Degraded: $audioRouteDegraded")
            appendLine()
            appendLine("--- Voice Config ---")
            appendLine("Protocol: v${Proto.PROTOCOL_VERSION}")
            appendLine("Sample Rate: ${Proto.VOICE_SAMPLE_RATE_HZ} Hz")
            appendLine(
                "Frame: ${Proto.VOICE_FRAME_MS} ms / ${Proto.VOICE_FRAME_SAMPLES} samples / " +
                    "${Proto.VOICE_FRAME_BYTES} bytes wire",
            )
            appendLine("Codec: IMA ADPCM 4-bit (${Proto.VOICE_ADPCM_BYTES} bytes payload)")
            appendLine()
            append("=== End Snapshot ===")
        }

    companion object {
        private const val HEX_RADIX = 16

        fun capture(
            context: Context,
            state: LinkState,
            stats: ConnectionStats?,
        ): DiagSnapshot {
            val linked = state as? LinkState.Linked
            val manager = context.getSystemService(BluetoothManager::class.java)
            return DiagSnapshot(
                timestampIso = Instant.now().toString(),
                uptimeMs = SystemClock.elapsedRealtime(),
                role =
                    when {
                        state.hosting -> "Host"
                        state.guesting -> "Guest"
                        else -> "Idle"
                    },
                epochId = linked?.epoch?.id,
                wireEpoch = linked?.wireEpoch,
                peer = linked?.peer,
                psm = linked?.psm,
                linkDetail = state.detail,
                stats = stats,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                appVersion = BuildConfig.VERSION_NAME,
                btEnabled = manager?.adapter?.isEnabled == true,
                audioRouteDegraded = VoiceAudioRoute.isCommunicationRouteDegraded(),
            )
        }
    }
}
