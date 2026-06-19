package com.elodin.intercom.wifidirect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WifiDirectWireTest {
    @Test
    fun prefaceRoundTripsUnsignedWireEpoch() {
        val out = ByteArrayOutputStream()

        WifiDirectWire.writePreface(out, WifiDirectWire.MAX_WIRE_EPOCH)
        val got = WifiDirectWire.readPreface(ByteArrayInputStream(out.toByteArray()))

        assertEquals(WifiDirectWire.MAX_WIRE_EPOCH, got)
    }

    @Test(expected = IOException::class)
    fun prefaceRejectsZeroEpoch() {
        val out = ByteArrayOutputStream()

        WifiDirectWire.writePreface(out, 0)
    }

    @Test(expected = IOException::class)
    fun prefaceRejectsWrongMagic() {
        val bad = ByteArray(8)

        WifiDirectWire.readPreface(ByteArrayInputStream(bad))
    }
}
