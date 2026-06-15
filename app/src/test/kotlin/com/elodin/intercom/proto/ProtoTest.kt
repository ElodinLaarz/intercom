package com.elodin.intercom.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the generated [Proto] constants. Also the first Kotlin unit test, so it
 * proves the `testDebugUnitTest` job runs on CI.
 */
class ProtoTest {
    @Test
    fun protocolVersionIsPositive() {
        assertTrue(Proto.PROTOCOL_VERSION >= 1)
    }

    @Test
    fun msdMatchesLandmine2() {
        assertEquals(0xFFFF, Proto.MSD_COMPANY_ID)
        assertEquals(0x01, Proto.MSD_PATTERN0)
        assertEquals(0x01, Proto.MSD_PATTERN1)
    }

    @Test
    fun uuidsWellFormedAndDistinct() {
        val re = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        assertTrue(Proto.SERVICE_UUID.matches(re))
        assertTrue(Proto.PSM_CHAR_UUID.matches(re))
        assertNotEquals(Proto.SERVICE_UUID, Proto.PSM_CHAR_UUID)
    }
}
