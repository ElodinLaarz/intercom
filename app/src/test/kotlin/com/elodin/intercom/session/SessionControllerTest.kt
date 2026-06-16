package com.elodin.intercom.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {
    @Test
    fun epochIncrementsPerLinked() {
        val fixture = Fixture()

        fixture.controller.startGuest()
        fixture.guest.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 177))
        val first = fixture.controller.state as LinkState.Linked
        fixture.guest.event(RadioEvent.LinkLost("Host disconnected"))

        fixture.controller.startGuest()
        fixture.guest.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 199))
        val second = fixture.controller.state as LinkState.Linked

        assertEquals(1L, first.epoch.id)
        assertEquals(2L, second.epoch.id)
    }

    @Test
    fun epochClosesExactlyOnceOnDisconnect() {
        val fixture = Fixture()
        fixture.controller.startHost()
        val host = fixture.host

        host.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 177))
        host.event(RadioEvent.LinkLost("Guest disconnected"))
        host.event(RadioEvent.LinkLost("Guest disconnected again"))

        assertEquals(1, host.stopCount)
        assertTrue(fixture.controller.state is LinkState.Idle)
        assertEquals("Link lost - Guest disconnected", fixture.controller.state.detail)
    }

    @Test
    fun staleGuestEventDoesNotSurviveRoleSwap() {
        val fixture = Fixture()
        fixture.controller.startGuest()
        val oldGuest = fixture.guest

        fixture.controller.startHost()
        oldGuest.event(RadioEvent.Linked(peer = "stale", psm = 177))
        oldGuest.event(RadioEvent.Status("Voice link up - stale"))

        assertTrue(fixture.controller.state.hosting)
        assertFalse(fixture.controller.state.guesting)
        assertFalse(fixture.controller.state is LinkState.Linked)
        assertEquals(1, oldGuest.stopCount)
        assertEquals(1, fixture.host.startCount)
    }

    @Test
    fun intentsDriveHostLifecycle() {
        val fixture = Fixture()

        fixture.controller.startHost()
        assertTrue(fixture.controller.state is LinkState.Hosting)

        fixture.host.event(RadioEvent.Advertising(psm = 177, text = "Advertising — PSM 177 — waiting"))
        val hosting = fixture.controller.state as LinkState.Hosting
        assertEquals(177, hosting.psm)
        assertEquals("Advertising — PSM 177 — waiting", hosting.detail)

        fixture.host.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 177))
        val linked = fixture.controller.state as LinkState.Linked
        assertEquals(LinkRole.Host, linked.role)
        assertEquals(177, linked.psm)
        assertEquals(listOf(1L), fixture.host.begunEpochs)

        fixture.controller.stopHost()
        assertTrue(fixture.controller.state is LinkState.Idle)
        assertEquals("Stopped", fixture.controller.state.detail)
    }

    @Test
    fun hostAdvertisingDoesNotOverrideLinkedEpoch() {
        val fixture = Fixture()
        fixture.controller.startHost()
        fixture.host.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 177))
        val linked = fixture.controller.state as LinkState.Linked

        fixture.host.event(RadioEvent.Advertising(psm = 177, text = "Advertising — PSM 177 — waiting"))

        assertTrue(fixture.controller.state is LinkState.Linked)
        assertEquals(linked.epoch.id, (fixture.controller.state as LinkState.Linked).epoch.id)
    }

    @Test
    fun intentsDriveGuestLifecycle() {
        val fixture = Fixture()

        fixture.controller.startGuest()
        assertTrue(fixture.controller.state is LinkState.Scanning)

        fixture.guest.event(
            RadioEvent.Found(
                peer = "AA:BB:CC:DD:EE:FF",
                text = "Found host AA:BB:CC:DD:EE:FF — connecting…",
            ),
        )
        val connecting = fixture.controller.state as LinkState.Connecting
        assertEquals("AA:BB:CC:DD:EE:FF", connecting.peer)

        fixture.guest.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 199))
        val linked = fixture.controller.state as LinkState.Linked
        assertEquals(LinkRole.Guest, linked.role)
        assertEquals(199, linked.psm)
        assertEquals(listOf(1L), fixture.guest.begunEpochs)

        fixture.guest.event(RadioEvent.LinkLost("Host disconnected"))
        assertTrue(fixture.controller.state is LinkState.Idle)
    }

    private class Fixture {
        val factory = FakeRadioEndpointFactory()
        val states = mutableListOf<LinkState>()
        val controller =
            SessionController(
                factory = factory,
                dispatcher = InlineSessionDispatcher(),
                onState = { state -> states += state },
            )

        val host: FakeRadioEndpoint get() = factory.hosts.last()
        val guest: FakeRadioEndpoint get() = factory.guests.last()
    }
}

internal class FakeRadioEndpointFactory : RadioEndpointFactory {
    val hosts = mutableListOf<FakeRadioEndpoint>()
    val guests = mutableListOf<FakeRadioEndpoint>()

    override fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint {
        val endpoint = FakeRadioEndpoint(onEvent)
        hosts += endpoint
        return endpoint
    }

    override fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint {
        val endpoint = FakeRadioEndpoint(onEvent)
        guests += endpoint
        return endpoint
    }
}

internal class FakeRadioEndpoint(
    private val onEvent: (RadioEvent) -> Unit,
) : RadioEndpoint {
    var startCount = 0
        private set
    var stopCount = 0
        private set
    var startResult = true
    val begunEpochs = mutableListOf<Long>()

    override fun start(): Boolean {
        startCount += 1
        return startResult
    }

    override fun beginEpoch(epochId: Long) {
        begunEpochs += epochId
    }

    override fun stop() {
        stopCount += 1
    }

    fun event(event: RadioEvent) {
        onEvent(event)
    }
}
