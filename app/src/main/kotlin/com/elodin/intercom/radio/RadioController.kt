package com.elodin.intercom.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the host/guest radios and the observable debug-screen state. One role per
 * phone — starting one stops the other (mutual exclusion). Each start builds a
 * fresh radio (rule 4: per-session construction). MainActivity handles runtime
 * permissions and calls [startHost]/[startGuest] once granted; this drives the
 * lifecycle. The session/epoch machinery (#21) will absorb this.
 *
 * [status]/[hosting]/[scanning] are Compose state the screen renders. Radio
 * callbacks fire off the main thread, so status updates are posted to the main
 * looper.
 */
class RadioController(
    private val context: Context,
) {
    var status by mutableStateOf("Idle — Host to advertise, Join to scan")
        private set
    var hosting by mutableStateOf(false)
        private set
    var scanning by mutableStateOf(false)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var host: HostRadio? = null
    private var guest: GuestRadio? = null

    fun startHost() {
        stopGuest()
        val radio = HostRadio(context, ::onStatus)
        host = radio
        hosting = true
        radio.start()
    }

    fun startGuest() {
        stopHost()
        val radio = GuestRadio(context, ::onStatus)
        guest = radio
        scanning = true
        radio.start()
    }

    fun stopHost() {
        host?.stop()
        host = null
        hosting = false
    }

    fun stopGuest() {
        guest?.stop()
        guest = null
        scanning = false
    }

    fun stopAll() {
        stopHost()
        stopGuest()
    }

    private fun onStatus(message: String) {
        mainHandler.post { status = message }
    }
}
