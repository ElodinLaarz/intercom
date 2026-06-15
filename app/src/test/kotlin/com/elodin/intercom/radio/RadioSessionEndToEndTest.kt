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
        guestRig.guest.status("Scanning for host…")

        assertEquals(DesiredRadioRole.Idle, hostRig.controller.state.desiredRole)
        assertTrue(guestRig.controller.state.guesting)
        assertEquals("Scanning for host…", guestRig.controller.state.status)

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
            RadioSessionController(
                factory = factory,
                dispatch = { block -> block() },
                onState = {},
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
            RadioSessionController(
                factory = factory,
                dispatch = { block -> block() },
                onState = {},
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

        override fun host(
            onStatus: (String) -> Unit,
            onStopped: (RadioEndpoint) -> Unit,
        ): RadioEndpoint = PassiveRadioEndpoint()

        override fun guest(
            onStatus: (String) -> Unit,
            onStopped: (RadioEndpoint) -> Unit,
        ): RadioEndpoint =
            DebouncedGuestEndpoint(
                scheduler = scheduler,
                scanBudget = scanBudget,
                scanRequests = scanRequests,
                onStatus = onStatus,
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
        private val onStatus: (String) -> Unit,
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
            onStatus("Scanning for host…")
        }

        private fun scheduleScanStart(delayMs: Long) {
            val scheduledGeneration = generation
            onStatus(scanCooldownStatus(delayMs))
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
