package com.elodin.intercom.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elodin.intercom.diag.DiagSnapshot
import com.elodin.intercom.session.LinkState
import com.elodin.intercom.session.RadioEndpoint
import com.elodin.intercom.session.RadioEndpointFactory
import com.elodin.intercom.session.RadioEvent
import com.elodin.intercom.session.SessionController
import com.elodin.intercom.session.TransportMode
import com.elodin.intercom.wifidirect.WifiDirectGuestRadio
import com.elodin.intercom.wifidirect.WifiDirectHostRadio

@Suppress("TooManyFunctions")
class RadioController(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    var transportMode by mutableStateOf(TransportMode.Bluetooth)
        private set

    private var session = createSession(TransportMode.Bluetooth)

    var status by mutableStateOf(session.state.detail)
        private set
    var hosting by mutableStateOf(session.state.hosting)
        private set
    var guesting by mutableStateOf(session.state.guesting)
        private set
    var stats by mutableStateOf<ConnectionStats?>(null)
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

    fun switchTransport(mode: TransportMode) {
        if (mode == transportMode) return
        session.close()
        transportMode = mode
        session = createSession(mode)
        render(session.state)
    }

    fun close() {
        session.close()
    }

    fun snapshotText(context: Context): String = DiagSnapshot.capture(context, session.state, stats).format()

    private fun createSession(mode: TransportMode): SessionController =
        SessionController(
            factory = factoryFor(mode),
            onState = ::render,
            logger = { line -> Log.i(TAG, line) },
        )

    private fun factoryFor(mode: TransportMode): RadioEndpointFactory =
        when (mode) {
            TransportMode.Bluetooth ->
                AndroidRadioEndpointFactory(appContext, ::onStats)
            TransportMode.WifiDirect ->
                WifiDirectEndpointFactory(appContext, ::onStats)
        }

    private fun render(state: LinkState) {
        dispatch {
            status = state.detail
            hosting = state.hosting
            guesting = state.guesting
            if (state !is LinkState.Linked) stats = null
        }
    }

    private fun onStats(newStats: ConnectionStats) {
        dispatch { stats = newStats }
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
        private val onStats: (ConnectionStats) -> Unit,
    ) : RadioEndpointFactory {
        override fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            HostRadio(
                context = context,
                onEvent = onEvent,
                onStats = onStats,
            )

        override fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            GuestRadio(
                context = context,
                onEvent = onEvent,
                onStats = onStats,
            )
    }

    private class WifiDirectEndpointFactory(
        private val context: Context,
        private val onStats: (ConnectionStats) -> Unit,
    ) : RadioEndpointFactory {
        override fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            WifiDirectHostRadio(
                context = context,
                onEvent = onEvent,
                onStats = onStats,
            )

        override fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            WifiDirectGuestRadio(
                context = context,
                onEvent = onEvent,
                onStats = onStats,
            )
    }

    companion object {
        private const val TAG = "INTERCOM"
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
