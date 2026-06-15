package com.elodin.intercom.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Android/Compose adapter for the M1 radio debug screen. The pure desired-role
 * state lives in [RadioSessionController]; this class only builds Android radio
 * endpoints and marshals callback state updates onto the main looper.
 */
class RadioController(
    context: Context,
) {
    private val session =
        RadioSessionController(
            factory = AndroidRadioEndpointFactory(context),
            dispatch = ::dispatch,
            onState = ::render,
        )

    var status by mutableStateOf(session.state.status)
        private set
    var hosting by mutableStateOf(session.state.hosting)
        private set
    var guesting by mutableStateOf(session.state.guesting)
        private set

    fun startHost() {
        session.startHost()
    }

    fun startGuest() {
        session.startGuest()
    }

    fun stopHost() {
        session.stopHost()
    }

    fun stopGuest() {
        session.stopGuest()
    }

    fun stopAll() {
        session.stopAll()
    }

    private fun render(state: RadioSessionState) {
        status = state.status
        hosting = state.hosting
        guesting = state.guesting
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        mainHandler.post(block)
    }

    private class AndroidRadioEndpointFactory(
        private val context: Context,
    ) : RadioEndpointFactory {
        override fun host(
            onStatus: (String) -> Unit,
            onStopped: (RadioEndpoint) -> Unit,
        ): RadioEndpoint =
            HostRadio(
                context = context,
                onStatus = onStatus,
                onStopped = { stopped -> onStopped(stopped) },
            )

        override fun guest(
            onStatus: (String) -> Unit,
            onStopped: (RadioEndpoint) -> Unit,
        ): RadioEndpoint =
            GuestRadio(
                context = context,
                onStatus = onStatus,
                onStopped = { stopped -> onStopped(stopped) },
            )
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
