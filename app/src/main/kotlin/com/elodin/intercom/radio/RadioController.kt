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
    private var sessionToken = 0

    fun startHost() {
        stopGuest(reportStatus = false)
        val token = nextSessionToken()
        val radio =
            HostRadio(
                context = context,
                onStatus = { message -> onStatus(token, message) },
                onStopped = { stopped -> onHostStopped(token, stopped) },
            )
        host = radio
        if (radio.start()) {
            hosting = true
        } else if (host === radio) {
            host = null
            hosting = false
        }
    }

    fun startGuest() {
        stopHost(reportStatus = false)
        val token = nextSessionToken()
        val radio =
            GuestRadio(
                context = context,
                onStatus = { message -> onStatus(token, message) },
                onStopped = { stopped -> onGuestStopped(token, stopped) },
            )
        guest = radio
        if (radio.start()) {
            scanning = true
        } else if (guest === radio) {
            guest = null
            scanning = false
        }
    }

    fun stopHost(reportStatus: Boolean = true) {
        val radio = host ?: return
        nextSessionToken()
        radio.stop()
        if (host === radio) {
            host = null
            hosting = false
        }
        if (reportStatus) showStatus("Stopped")
    }

    fun stopGuest(reportStatus: Boolean = true) {
        val radio = guest ?: return
        nextSessionToken()
        radio.stop()
        if (guest === radio) {
            guest = null
            scanning = false
        }
        if (reportStatus) showStatus("Stopped")
    }

    fun stopAll() {
        stopHost()
        stopGuest()
    }

    private fun nextSessionToken(): Int {
        sessionToken += 1
        return sessionToken
    }

    private fun showStatus(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            status = message
        } else {
            mainHandler.post { status = message }
        }
    }

    private fun onStatus(
        token: Int,
        message: String,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (token == sessionToken) status = message
        } else {
            mainHandler.post {
                if (token == sessionToken) status = message
            }
        }
    }

    private fun onHostStopped(
        token: Int,
        radio: HostRadio,
    ) {
        mainHandler.post {
            if (token == sessionToken && host === radio) {
                host = null
                hosting = false
            }
        }
    }

    private fun onGuestStopped(
        token: Int,
        radio: GuestRadio,
    ) {
        mainHandler.post {
            if (token == sessionToken && guest === radio) {
                guest = null
                scanning = false
            }
        }
    }
}
