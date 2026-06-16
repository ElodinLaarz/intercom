package com.elodin.intercom.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioLinkHealthTest {
    private var clock = 0L

    @Test
    fun keepsHealthyWritesOnCurrentEpoch() {
        assertFalse(
            RadioLinkHealth.shouldReconnectAfterWrite(
                RadioLinkHealth.L2CAP_WRITE_STALL_RECONNECT_MS - 1,
            ),
        )
    }

    @Test
    fun reconnectsWhenReliableSocketStalls() {
        assertTrue(
            RadioLinkHealth.shouldReconnectAfterWrite(
                RadioLinkHealth.L2CAP_WRITE_STALL_RECONNECT_MS,
            ),
        )
    }

    @Test
    fun toleratesSingleRxStarvationWindow() {
        val health = RxDeliveryHealth { clock }

        repeat((RadioLinkHealth.RX_MIN_FRAMES_PER_WINDOW - 2).toInt()) {
            assertNull(health.onFrame())
        }
        clock = RadioLinkHealth.RX_DELIVERY_WINDOW_MS

        assertNull(health.onFrame())
    }

    @Test
    fun reconnectsAfterSustainedRxStarvation() {
        val health = RxDeliveryHealth { clock }
        var starvation: RxStarvation? = null

        repeat(RadioLinkHealth.RX_STARVED_WINDOWS_BEFORE_RECONNECT) {
            repeat((RadioLinkHealth.RX_MIN_FRAMES_PER_WINDOW - 2).toInt()) {
                starvation = health.onFrame() ?: starvation
            }
            clock += RadioLinkHealth.RX_DELIVERY_WINDOW_MS
            starvation = health.onFrame() ?: starvation
        }

        assertTrue(starvation?.starvedWindows == RadioLinkHealth.RX_STARVED_WINDOWS_BEFORE_RECONNECT)
    }
}
