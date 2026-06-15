package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSessionControllerTest {
    @Test
    fun guestDisconnectStatusKeepsGuestIntent() {
        val fixture = Fixture()
        fixture.controller.startGuest()
        val guest = fixture.factory.guests.single()

        guest.status("Linked — host PSM 177 (L2CAP CoC is #20)")
        guest.status("Host disconnected (status 19) — scanning for host…")

        assertTrue(fixture.controller.state.guesting)
        assertFalse(fixture.controller.state.hosting)
        assertEquals(
            "Host disconnected (status 19) — scanning for host…",
            fixture.controller.state.status,
        )
    }

    @Test
    fun terminalGuestStopReturnsToIdle() {
        val fixture = Fixture()
        fixture.controller.startGuest()
        val guest = fixture.factory.guests.single()

        guest.stoppedCallback()

        assertEquals(DesiredRadioRole.Idle, fixture.controller.state.desiredRole)
    }

    @Test
    fun manualStopIgnoresLateGuestCallbacks() {
        val fixture = Fixture()
        fixture.controller.startGuest()
        val guest = fixture.factory.guests.single()

        fixture.controller.stopGuest()
        guest.status("Linked — stale")
        guest.stoppedCallback()

        assertEquals(DesiredRadioRole.Idle, fixture.controller.state.desiredRole)
        assertEquals("Stopped", fixture.controller.state.status)
    }

    @Test
    fun startingHostStopsGuestAndIgnoresOldGuestCallbacks() {
        val fixture = Fixture()
        fixture.controller.startGuest()
        val oldGuest = fixture.factory.guests.single()

        fixture.controller.startHost()
        oldGuest.status("Linked — stale")

        assertTrue(fixture.controller.state.hosting)
        assertFalse(fixture.controller.state.guesting)
        assertEquals(1, oldGuest.stopCount)
        val host = fixture.factory.hosts.single()
        assertEquals(1, host.startCount)
    }

    private class Fixture {
        val factory = FakeRadioEndpointFactory()
        val states = mutableListOf<RadioSessionState>()
        val controller =
            RadioSessionController(
                factory = factory,
                dispatch = { block -> block() },
                onState = { state -> states += state },
            )
    }
}

internal class FakeRadioEndpointFactory : RadioEndpointFactory {
    val hosts = mutableListOf<FakeRadioEndpoint>()
    val guests = mutableListOf<FakeRadioEndpoint>()

    override fun host(
        onStatus: (String) -> Unit,
        onStopped: (RadioEndpoint) -> Unit,
    ): RadioEndpoint {
        val endpoint = FakeRadioEndpoint(onStatus, onStopped)
        hosts += endpoint
        return endpoint
    }

    override fun guest(
        onStatus: (String) -> Unit,
        onStopped: (RadioEndpoint) -> Unit,
    ): RadioEndpoint {
        val endpoint = FakeRadioEndpoint(onStatus, onStopped)
        guests += endpoint
        return endpoint
    }
}

internal class FakeRadioEndpoint(
    private val onStatus: (String) -> Unit,
    private val onStopped: (RadioEndpoint) -> Unit,
) : RadioEndpoint {
    var startCount = 0
        private set
    var stopCount = 0
        private set
    var startResult = true

    override fun start(): Boolean {
        startCount += 1
        return startResult
    }

    override fun stop() {
        stopCount += 1
    }

    fun status(message: String) {
        onStatus(message)
    }

    fun stoppedCallback() {
        onStopped(this)
    }
}
