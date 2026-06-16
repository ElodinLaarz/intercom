package com.elodin.intercom.session

import android.os.Handler
import android.os.HandlerThread

@Suppress("TooManyFunctions")
internal class SessionController(
    private val factory: RadioEndpointFactory,
    private val dispatcher: SessionDispatcher = HandlerThreadSessionDispatcher(),
    private val onState: (LinkState) -> Unit = {},
    private val logger: (String) -> Unit = {},
) {
    var state: LinkState = LinkState.Idle()
        private set

    private var activeEndpoint: ActiveEndpoint? = null
    private var activeEpoch: Epoch? = null
    private var eventToken = 0L
    private var epochCounter = 0L

    fun startHost() {
        dispatcher.dispatch { start(LinkRole.Host) }
    }

    fun startGuest() {
        dispatcher.dispatch { start(LinkRole.Guest) }
    }

    fun stopHost() {
        dispatcher.dispatch {
            if (activeEndpoint?.role == LinkRole.Host) stopActive(reportStopped = true)
        }
    }

    fun stopGuest() {
        dispatcher.dispatch {
            if (activeEndpoint?.role == LinkRole.Guest) stopActive(reportStopped = true)
        }
    }

    fun stop() {
        dispatcher.dispatch { stopActive(reportStopped = true) }
    }

    fun stopAll() {
        stop()
    }

    fun close() {
        dispatcher.dispatch {
            stopActive(reportStopped = false)
            dispatcher.close()
        }
    }

    private fun start(role: LinkRole) {
        if (activeEndpoint?.role == role) return

        stopActive(reportStopped = false)
        val token = nextEventToken()
        val endpoint =
            when (role) {
                LinkRole.Host -> factory.host { event -> dispatchEvent(token, event) }
                LinkRole.Guest -> factory.guest { event -> dispatchEvent(token, event) }
            }
        activeEndpoint = ActiveEndpoint(token, role, endpoint)
        setState(initialState(role))
        if (endpoint.start()) return

        if (activeEndpoint?.token != token) return
        activeEndpoint = null
        activeEpoch = null
        invalidateEvents()
        setState(LinkState.Idle("Failed to start ${role.label()}"))
    }

    private fun dispatchEvent(
        token: Long,
        event: RadioEvent,
    ) {
        dispatcher.dispatch {
            if (activeEndpoint?.token != token) return@dispatch
            handleEvent(event)
        }
    }

    private fun handleEvent(event: RadioEvent) {
        when (event) {
            is RadioEvent.Advertising -> onAdvertising(event)
            is RadioEvent.Found -> onFound(event)
            is RadioEvent.Linked -> onLinked(event)
            is RadioEvent.LinkLost -> tearDownToIdle("Link lost - ${event.reason}")
            is RadioEvent.Failed -> tearDownToIdle(event.reason)
            is RadioEvent.Status -> onStatus(event.text)
        }
    }

    private fun onAdvertising(event: RadioEvent.Advertising) {
        if (activeEndpoint?.role != LinkRole.Host) return
        if (activeEpoch != null) return

        setState(LinkState.Hosting(psm = event.psm, detail = event.text))
    }

    private fun onFound(event: RadioEvent.Found) {
        if (activeEndpoint?.role != LinkRole.Guest) return

        setState(LinkState.Connecting(peer = event.peer, detail = event.text))
    }

    private fun onLinked(event: RadioEvent.Linked) {
        val endpoint = activeEndpoint ?: return
        if (activeEpoch != null) return

        epochCounter += 1
        val epoch = Epoch(epochCounter, endpoint.endpoint)
        val wireEpoch = event.wireEpoch.takeIf { it > 0 } ?: epoch.id
        activeEpoch = epoch
        val detail = "Linked epoch=${epoch.id} wireEpoch=$wireEpoch peer=${event.peer} psm=${event.psm}"
        logger(
            "SESSION linked epoch=${epoch.id} wireEpoch=$wireEpoch role=${endpoint.role.label()} " +
                "peer=${event.peer} psm=${event.psm}",
        )
        setState(
            LinkState.Linked(
                epoch = epoch,
                role = endpoint.role,
                peer = event.peer,
                psm = event.psm,
                detail = detail,
            ),
        )
        endpoint.endpoint.beginEpoch(wireEpoch)
    }

    private fun onStatus(text: String) {
        val endpoint = activeEndpoint ?: return
        val next =
            when (val current = state) {
                is LinkState.Idle -> current.copy(detail = text)
                is LinkState.Hosting -> current.copy(detail = text)
                is LinkState.Scanning -> current.copy(detail = text)
                is LinkState.Connecting -> current.copy(detail = text)
                is LinkState.Linked -> current.copy(detail = text)
            }
        if (next.role == endpoint.role || next is LinkState.Idle) setState(next)
    }

    private fun stopActive(reportStopped: Boolean) {
        val endpoint = activeEndpoint
        if (endpoint == null) {
            if (reportStopped) setState(LinkState.Idle("Stopped"))
            return
        }

        activeEndpoint = null
        val epoch = activeEpoch
        activeEpoch = null
        invalidateEvents()
        if (epoch != null) {
            epoch.close()
        } else {
            endpoint.endpoint.stop()
        }
        if (reportStopped) setState(LinkState.Idle("Stopped"))
    }

    private fun tearDownToIdle(detail: String) {
        val endpoint = activeEndpoint ?: return
        activeEndpoint = null
        val epoch = activeEpoch
        activeEpoch = null
        invalidateEvents()
        if (epoch != null) {
            epoch.close()
        } else {
            endpoint.endpoint.stop()
        }
        setState(LinkState.Idle(detail))
    }

    private fun nextEventToken(): Long {
        eventToken += 1
        return eventToken
    }

    private fun invalidateEvents() {
        eventToken += 1
    }

    private fun setState(nextState: LinkState) {
        state = nextState
        onState(nextState)
    }

    private fun initialState(role: LinkRole): LinkState =
        when (role) {
            LinkRole.Host -> LinkState.Hosting()
            LinkRole.Guest -> LinkState.Scanning()
        }

    private data class ActiveEndpoint(
        val token: Long,
        val role: LinkRole,
        val endpoint: RadioEndpoint,
    )
}

internal interface SessionDispatcher {
    fun dispatch(block: () -> Unit)

    fun close() = Unit
}

internal class HandlerThreadSessionDispatcher(
    name: String = "session",
) : SessionDispatcher {
    private val thread = HandlerThread(name).also { it.start() }
    private val handler = Handler(thread.looper)

    override fun dispatch(block: () -> Unit) {
        handler.post(block)
    }

    override fun close() {
        thread.quitSafely()
    }
}

internal class InlineSessionDispatcher : SessionDispatcher {
    override fun dispatch(block: () -> Unit) {
        block()
    }
}

private fun LinkRole.label(): String =
    when (this) {
        LinkRole.Host -> "host"
        LinkRole.Guest -> "guest"
    }
