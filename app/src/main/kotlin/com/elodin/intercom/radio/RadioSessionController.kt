package com.elodin.intercom.radio

internal enum class DesiredRadioRole {
    Idle,
    Host,
    Guest,
}

internal data class RadioSessionState(
    val desiredRole: DesiredRadioRole = DesiredRadioRole.Idle,
    val status: String = IDLE_STATUS,
) {
    val hosting: Boolean get() = desiredRole == DesiredRadioRole.Host
    val guesting: Boolean get() = desiredRole == DesiredRadioRole.Guest

    companion object {
        const val IDLE_STATUS = "Idle — Host to advertise, Guest to scan"
    }
}

internal interface RadioEndpoint {
    fun start(): Boolean

    fun stop()
}

internal interface RadioEndpointFactory {
    fun host(
        onStatus: (String) -> Unit,
        onStopped: (RadioEndpoint) -> Unit,
    ): RadioEndpoint

    fun guest(
        onStatus: (String) -> Unit,
        onStopped: (RadioEndpoint) -> Unit,
    ): RadioEndpoint
}

@Suppress("TooManyFunctions")
internal class RadioSessionController(
    private val factory: RadioEndpointFactory,
    private val dispatch: (() -> Unit) -> Unit,
    private val onState: (RadioSessionState) -> Unit,
) {
    var state: RadioSessionState = RadioSessionState()
        private set

    private var host: RadioEndpoint? = null
    private var guest: RadioEndpoint? = null
    private var sessionToken = 0

    fun startHost() {
        stopGuest(reportStatus = false)
        val token = nextSessionToken()
        val radio =
            factory.host(
                onStatus = { message -> onStatus(token, message) },
                onStopped = { stopped -> onHostStopped(token, stopped) },
            )
        host = radio
        if (radio.start()) {
            setDesiredRole(DesiredRadioRole.Host)
        } else if (host === radio) {
            host = null
            setDesiredRole(DesiredRadioRole.Idle)
        }
    }

    fun startGuest() {
        stopHost(reportStatus = false)
        val token = nextSessionToken()
        val radio =
            factory.guest(
                onStatus = { message -> onStatus(token, message) },
                onStopped = { stopped -> onGuestStopped(token, stopped) },
            )
        guest = radio
        if (radio.start()) {
            setDesiredRole(DesiredRadioRole.Guest)
        } else if (guest === radio) {
            guest = null
            setDesiredRole(DesiredRadioRole.Idle)
        }
    }

    fun stopHost(reportStatus: Boolean = true) {
        val radio = host ?: return
        nextSessionToken()
        radio.stop()
        if (host === radio) {
            host = null
            setDesiredRole(DesiredRadioRole.Idle)
        }
        if (reportStatus) setStatus("Stopped")
    }

    fun stopGuest(reportStatus: Boolean = true) {
        val radio = guest ?: return
        nextSessionToken()
        radio.stop()
        if (guest === radio) {
            guest = null
            setDesiredRole(DesiredRadioRole.Idle)
        }
        if (reportStatus) setStatus("Stopped")
    }

    fun stopAll() {
        stopHost()
        stopGuest()
    }

    private fun nextSessionToken(): Int {
        sessionToken += 1
        return sessionToken
    }

    private fun onStatus(
        token: Int,
        message: String,
    ) {
        dispatchIfCurrent(token) { setStatus(message) }
    }

    private fun onHostStopped(
        token: Int,
        radio: RadioEndpoint,
    ) {
        dispatchIfCurrent(token) {
            if (host === radio) {
                host = null
                setDesiredRole(DesiredRadioRole.Idle)
            }
        }
    }

    private fun onGuestStopped(
        token: Int,
        radio: RadioEndpoint,
    ) {
        dispatchIfCurrent(token) {
            if (guest === radio) {
                guest = null
                setDesiredRole(DesiredRadioRole.Idle)
            }
        }
    }

    private fun dispatchIfCurrent(
        token: Int,
        block: () -> Unit,
    ) {
        dispatch {
            if (token == sessionToken) block()
        }
    }

    private fun setDesiredRole(role: DesiredRadioRole) {
        setState(state.copy(desiredRole = role))
    }

    private fun setStatus(message: String) {
        setState(state.copy(status = message))
    }

    private fun setState(nextState: RadioSessionState) {
        state = nextState
        onState(nextState)
    }
}
