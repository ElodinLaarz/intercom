package com.elodin.intercom.session

import com.elodin.intercom.radio.ScanStartBudget
import com.elodin.intercom.radio.scanCooldownStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndToEndTest {
    @Test
    fun linkedDisconnectReturnsBothPhonesToIdleUntilUserRestarts() {
        val hostRig = RigPhone()
        val guestRig = RigPhone()

        hostRig.controller.startHost()
        guestRig.controller.startGuest()
        hostRig.host.event(RadioEvent.Advertising(psm = 177, text = "Advertising — PSM 177 — waiting for a guest"))
        guestRig.guest.event(RadioEvent.Status("Scanning for host"))

        hostRig.host.event(RadioEvent.Status("Guest read PSM - waiting for L2CAP"))
        guestRig.guest.event(RadioEvent.Status("Host PSM 177 - opening voice link"))
        guestRig.guest.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 177))
        hostRig.host.event(RadioEvent.Linked(peer = "11:22:33:44:55:66", psm = 177))

        assertEquals(1L, (hostRig.controller.state as LinkState.Linked).epoch.id)
        assertEquals(1L, (guestRig.controller.state as LinkState.Linked).epoch.id)

        hostRig.controller.stopHost()
        guestRig.guest.event(RadioEvent.LinkLost("Host disconnected"))

        assertTrue(hostRig.controller.state is LinkState.Idle)
        assertTrue(guestRig.controller.state is LinkState.Idle)

        hostRig.controller.startHost()
        guestRig.controller.startGuest()
        hostRig.host.event(RadioEvent.Advertising(psm = 199, text = "Advertising — PSM 199 — waiting for a guest"))
        guestRig.guest.event(
            RadioEvent.Found(
                peer = "AA:BB:CC:DD:EE:FF",
                text = "Found host AA:BB:CC:DD:EE:FF — connecting…",
            ),
        )
        guestRig.guest.event(RadioEvent.Linked(peer = "AA:BB:CC:DD:EE:FF", psm = 199))
        hostRig.host.event(RadioEvent.Linked(peer = "11:22:33:44:55:66", psm = 199))

        assertEquals(2L, (hostRig.controller.state as LinkState.Linked).epoch.id)
        assertEquals(2L, (guestRig.controller.state as LinkState.Linked).epoch.id)
    }

    @Test
    fun rapidHostStopGuestClickStormDebouncesGuestScanRequests() {
        val scheduler = FakeScheduler()
        val scanRequests = mutableListOf<Long>()
        val rig =
            ClickStormRig(
                scheduler = scheduler,
                scanRequests = scanRequests,
            )
        val clicks = rapidHostStopGuestClicks()

        clicks.forEach { button ->
            rig.click(button)
            scheduler.advanceBy(CLICK_INTERVAL_MS)
        }

        assertTrue(rig.controller.state.guesting)
        assertEquals(listOf(FIRST_GUEST_CLICK_MS), scanRequests)

        scheduler.advanceBy(SCAN_DEBOUNCE_MS - (CLICK_STORM_MS - FIRST_GUEST_CLICK_MS) - 1)

        assertEquals(listOf(FIRST_GUEST_CLICK_MS), scanRequests)

        scheduler.advanceBy(1)

        assertEquals(listOf(FIRST_GUEST_CLICK_MS, FIRST_GUEST_CLICK_MS + SCAN_DEBOUNCE_MS), scanRequests)
    }

    private fun rapidHostStopGuestClicks(): List<Button> =
        buildList {
            repeat(COMPLETE_CLICK_CYCLES) {
                add(Button.Host)
                add(Button.Host)
                add(Button.Guest)
            }
            add(Button.Host)
            add(Button.Host)
            add(Button.Host)
            add(Button.Guest)
        }

    private class RigPhone {
        val factory = FakeRadioEndpointFactory()
        val controller =
            SessionController(
                factory = factory,
                dispatcher = InlineSessionDispatcher(),
            )

        val host: FakeRadioEndpoint get() = factory.hosts.last()
        val guest: FakeRadioEndpoint get() = factory.guests.last()
    }

    private class ClickStormRig(
        private val scheduler: FakeScheduler,
        scanRequests: MutableList<Long>,
    ) {
        private val factory =
            ClickStormEndpointFactory(
                scheduler = scheduler,
                scanRequests = scanRequests,
            )
        val controller =
            SessionController(
                factory = factory,
                dispatcher = InlineSessionDispatcher(),
            )

        fun click(button: Button) {
            when (button) {
                Button.Host -> clickHost()
                Button.Guest -> clickGuest()
            }
        }

        private fun clickHost() {
            if (controller.state.hosting) {
                controller.stopHost()
                return
            }
            controller.startHost()
        }

        private fun clickGuest() {
            if (controller.state.guesting) {
                controller.stopGuest()
                return
            }
            controller.startGuest()
        }
    }

    private enum class Button {
        Host,
        Guest,
    }

    private class ClickStormEndpointFactory(
        private val scheduler: FakeScheduler,
        private val scanRequests: MutableList<Long>,
    ) : RadioEndpointFactory {
        private val scanBudget =
            ScanStartBudget(
                windowMs = SCAN_WINDOW_MS,
                maxStarts = MAX_SCAN_STARTS,
                minIntervalMs = SCAN_DEBOUNCE_MS,
                nowMs = { scheduler.nowMs },
            )

        override fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint = PassiveRadioEndpoint()

        override fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint =
            DebouncedGuestEndpoint(
                scheduler = scheduler,
                scanBudget = scanBudget,
                scanRequests = scanRequests,
                onEvent = onEvent,
            )
    }

    private class PassiveRadioEndpoint : RadioEndpoint {
        override fun start(): Boolean = true

        override fun stop() = Unit
    }

    private class DebouncedGuestEndpoint(
        private val scheduler: FakeScheduler,
        private val scanBudget: ScanStartBudget,
        private val scanRequests: MutableList<Long>,
        private val onEvent: (RadioEvent) -> Unit,
    ) : RadioEndpoint {
        private var running = false
        private var generation = 0

        override fun start(): Boolean {
            running = true
            attemptScanStart()
            return true
        }

        override fun stop() {
            running = false
            generation += 1
        }

        private fun attemptScanStart() {
            val delayMs = scanBudget.delayUntilAvailableMs()
            if (delayMs > 0) {
                scheduleScanStart(delayMs)
                return
            }
            scanBudget.recordStart()
            scanRequests += scheduler.nowMs
            onEvent(RadioEvent.Status("Scanning for host"))
        }

        private fun scheduleScanStart(delayMs: Long) {
            val scheduledGeneration = generation
            onEvent(RadioEvent.Status(scanCooldownStatus(delayMs)))
            scheduler.postDelayed(delayMs) {
                if (running && scheduledGeneration == generation) attemptScanStart()
            }
        }
    }

    private class FakeScheduler {
        var nowMs = 0L
            private set
        private val tasks = mutableListOf<ScheduledTask>()

        fun postDelayed(
            delayMs: Long,
            block: () -> Unit,
        ) {
            tasks += ScheduledTask(nowMs + delayMs, block)
        }

        fun advanceBy(deltaMs: Long) {
            val targetMs = nowMs + deltaMs
            runDueTasksUntil(targetMs)
            nowMs = targetMs
        }

        private fun runDueTasksUntil(targetMs: Long) {
            while (true) {
                val next = tasks.filter { it.runAtMs <= targetMs }.minByOrNull { it.runAtMs } ?: return
                tasks.remove(next)
                nowMs = next.runAtMs
                next.block()
            }
        }
    }

    private data class ScheduledTask(
        val runAtMs: Long,
        val block: () -> Unit,
    )

    companion object {
        private const val COMPLETE_CLICK_CYCLES = 32
        private const val CLICK_INTERVAL_MS = 1L
        private const val CLICK_STORM_MS = 100L
        private const val FIRST_GUEST_CLICK_MS = 2L
        private const val SCAN_DEBOUNCE_MS = 250L
        private const val SCAN_WINDOW_MS = 10_000L
        private const val MAX_SCAN_STARTS = 4
    }
}
