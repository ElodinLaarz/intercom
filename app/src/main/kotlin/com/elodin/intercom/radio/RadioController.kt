package com.elodin.intercom.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elodin.intercom.IntercomForegroundService
import com.elodin.intercom.session.LinkState
import com.elodin.intercom.session.RadioEndpoint
import com.elodin.intercom.session.RadioEndpointFactory
import com.elodin.intercom.session.RadioEvent
import com.elodin.intercom.session.SessionController

/**
 * Android/Compose adapter for the M1 radio debug screen. The link state lives in
 * [SessionController]; this class only builds Android radio endpoints and
 * marshals rendered state updates onto the main looper.
 */
class RadioController(
    context: Context,
) {
    private val context = context.applicationContext
    private val session =
        SessionController(
            factory = AndroidRadioEndpointFactory(this.context),
            onState = ::render,
            logger = { line -> Log.i(TAG, line) },
        )

    var status by mutableStateOf(session.state.detail)
        private set
    var hosting by mutableStateOf(session.state.hosting)
        private set
    var guesting by mutableStateOf(session.state.guesting)
        private set

    fun startHost() {
        IntercomForegroundService.start(context)
        session.startHost()
    }

    fun startGuest() {
        IntercomForegroundService.start(context)
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

    fun close() {
        session.close()
        IntercomForegroundService.stop(context)
    }

    private fun render(state: LinkState) {
        dispatch {
            status = state.detail
            hosting = state.hosting
            guesting = state.guesting
            if (state is LinkState.Idle) IntercomForegroundService.stop(context)
        }
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
        override fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            HostRadio(
                context = context,
                onEvent = onEvent,
            )

        override fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            GuestRadio(
                context = context,
                onEvent = onEvent,
            )
    }

    companion object {
        private const val TAG = "INTERCOM"
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
