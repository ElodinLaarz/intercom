package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSessionEndToEndTest {
    @Test
    fun guestKeepsSearchingAcrossHostRestartUntilStop() {
        val hostRig = RigPhone()
        val guestRig = RigPhone()

        hostRig.controller.startHost()
        guestRig.controller.startGuest()
        hostRig.host.status("Advertising — PSM 177 — waiting for a guest")
        guestRig.guest.status("Scanning for host…")

        hostRig.host.status("Guest GATT connected — waiting for PSM read")
        guestRig.guest.status("Linked — host PSM 177 (L2CAP CoC is #20)")

        hostRig.controller.stopHost()
        guestRig.guest.status("Host disconnected (status 19) — scanning for host…")

        assertEquals(DesiredRadioRole.Idle, hostRig.controller.state.desiredRole)
        assertTrue(guestRig.controller.state.guesting)
        assertEquals(
            "Host disconnected (status 19) — scanning for host…",
            guestRig.controller.state.status,
        )

        hostRig.controller.startHost()
        hostRig.host.status("Advertising — PSM 199 — waiting for a guest")
        guestRig.guest.status("Found host AA:BB:CC:DD:EE:FF — connecting…")
        guestRig.guest.status("Linked — host PSM 199 (L2CAP CoC is #20)")

        assertTrue(hostRig.controller.state.hosting)
        assertTrue(guestRig.controller.state.guesting)

        guestRig.controller.stopGuest()

        assertEquals(DesiredRadioRole.Idle, guestRig.controller.state.desiredRole)
        assertEquals("Stopped", guestRig.controller.state.status)
    }

    private class RigPhone {
        val factory = FakeRadioEndpointFactory()
        val controller =
            RadioSessionController(
                factory = factory,
                dispatch = { block -> block() },
                onState = {},
            )

        val host: FakeRadioEndpoint get() = factory.hosts.last()
        val guest: FakeRadioEndpoint get() = factory.guests.last()
    }
}
