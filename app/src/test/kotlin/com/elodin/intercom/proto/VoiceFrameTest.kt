package com.elodin.intercom.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bounds + wire-layout tests for the Kotlin [VoiceFrame] codec (V2_PLAN §6:
 * "bounds tests on every wire field"). The golden vector matches the C++ host
 * test (native/tests/test_voice_frame.cpp) byte-for-byte so the two codecs
 * cannot drift on the wire.
 */
class VoiceFrameTest {
    private fun golden() =
        VoiceFrame(
            epoch = 0x01020304,
            seq = 0x05060708,
            predSample = (-2).toShort(),
            stepIndex = Proto.VOICE_STEP_INDEX_MAX,
            adpcm = ByteArray(Proto.VOICE_ADPCM_BYTES) { it.toByte() },
        )

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    @Test
    fun serializeMatchesGoldenBytes() {
        val out = golden().encode()
        assertEquals(Proto.VOICE_FRAME_BYTES, out.size)
        // epoch 0x01020304 LE
        assertEquals(0x04, u(out[0]))
        assertEquals(0x03, u(out[1]))
        assertEquals(0x02, u(out[2]))
        assertEquals(0x01, u(out[3]))
        // seq 0x05060708 LE
        assertEquals(0x08, u(out[4]))
        assertEquals(0x07, u(out[5]))
        assertEquals(0x06, u(out[6]))
        assertEquals(0x05, u(out[7]))
        // predSample -2 = 0xFFFE LE
        assertEquals(0xFE, u(out[8]))
        assertEquals(0xFF, u(out[9]))
        assertEquals(0x58, u(out[10])) // stepIndex 88
        assertEquals(0x00, u(out[11])) // reserved
        assertEquals(0x00, u(out[12])) // adpcm[0]
        assertEquals(0x01, u(out[13])) // adpcm[1]
        assertEquals(0x9F, u(out[Proto.VOICE_FRAME_BYTES - 1])) // adpcm[159] == 159
    }

    @Test
    fun roundTripsThroughDecode() {
        val f = golden()
        val got = VoiceFrame.decode(f.encode())
        assertNotNull(got)
        assertEquals(f.epoch, got!!.epoch)
        assertEquals(f.seq, got.seq)
        assertEquals(f.predSample, got.predSample)
        assertEquals(f.stepIndex, got.stepIndex)
        assertArrayEquals(f.adpcm, got.adpcm)
    }

    @Test
    fun decodeRejectsWrongLength() {
        assertNull(VoiceFrame.decode(ByteArray(Proto.VOICE_FRAME_BYTES - 1)))
        assertNull(VoiceFrame.decode(ByteArray(Proto.VOICE_FRAME_BYTES + 1)))
        assertNull(VoiceFrame.decode(ByteArray(0)))
    }

    @Test
    fun decodeBoundsChecksStepIndex() {
        // 88 is the max valid value.
        val ok = golden().encode()
        ok[Proto.VOICE_OFF_STEP_INDEX] = Proto.VOICE_STEP_INDEX_MAX.toByte()
        assertNotNull(VoiceFrame.decode(ok))
        // 89 and 255 are off the IMA step table — drop the frame (landmine #12).
        val tooBig = golden().encode()
        tooBig[Proto.VOICE_OFF_STEP_INDEX] = (Proto.VOICE_STEP_INDEX_MAX + 1).toByte()
        assertNull(VoiceFrame.decode(tooBig))
        val maxByte = golden().encode()
        maxByte[Proto.VOICE_OFF_STEP_INDEX] = 0xFF.toByte()
        assertNull(VoiceFrame.decode(maxByte))
    }

    @Test
    fun decodeRejectsNonZeroReserved() {
        val bad = golden().encode()
        bad[Proto.VOICE_OFF_RESERVED] = 1
        assertNull(VoiceFrame.decode(bad))
    }

    @Test
    fun headerFieldsSurviveFullRange() {
        // epoch 0xFFFFFFFF, seq 0x80000000, predSample sign edges all round-trip.
        for (p in listOf(Short.MIN_VALUE, Short.MAX_VALUE, (-1).toShort(), 0.toShort())) {
            val f = golden().copy(epoch = -1, seq = Int.MIN_VALUE, predSample = p)
            val got = VoiceFrame.decode(f.encode())
            assertNotNull(got)
            assertEquals(-1, got!!.epoch)
            assertEquals(Int.MIN_VALUE, got.seq)
            assertEquals(p, got.predSample)
        }
    }
}
