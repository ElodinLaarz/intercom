package com.elodin.intercom.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bounds + wire-layout tests for the Kotlin [MediaFrame] codec. The golden
 * vector matches the C++ host test (native/tests/test_media_frame.cpp)
 * byte-for-byte so the two codecs cannot drift on the wire.
 */
class MediaFrameTest {
    private fun golden() =
        MediaFrame(
            epoch = 0x01020304,
            seq = 0x05060708,
            flags = 0x02,
            payload = ByteArray(5) { it.toByte() },
        )

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    @Test
    fun serializeMatchesGoldenBytes() {
        val out = golden().encode()
        assertEquals(Proto.MEDIA_HEADER_BYTES + 5, out.size)
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
        assertEquals(0x02, u(out[8])) // flags
        assertEquals(0x00, u(out[9])) // reserved
        assertEquals(0x05, u(out[10])) // len=5 LE low byte
        assertEquals(0x00, u(out[11])) // len=5 LE high byte
        assertEquals(0x00, u(out[12])) // payload[0]
        assertEquals(0x01, u(out[13])) // payload[1]
        assertEquals(0x04, u(out[16])) // payload[4] == 4
    }

    @Test
    fun roundTripsThroughDecode() {
        val f = golden()
        val got = MediaFrame.decode(f.encode())
        assertNotNull(got)
        assertEquals(f.epoch, got!!.epoch)
        assertEquals(f.seq, got.seq)
        assertEquals(f.flags, got.flags)
        assertArrayEquals(f.payload, got.payload)
    }

    @Test
    fun decodeRejectsShortBuffer() {
        assertNull(MediaFrame.decode(ByteArray(Proto.MEDIA_HEADER_BYTES - 1)))
        assertNull(MediaFrame.decode(ByteArray(0)))
    }

    @Test
    fun decodeRejectsNonZeroReserved() {
        val bad = golden().encode()
        bad[Proto.MEDIA_OFF_RESERVED] = 1
        assertNull(MediaFrame.decode(bad))
    }

    @Test
    fun decodeRejectsLenTooLarge() {
        // Write the full over-cap len across BOTH bytes and size the buffer to
        // header+len, so decode reaches the len>MAX guard (not the size-mismatch
        // branch). A truncated single byte would collapse to 1 and pass trivially.
        val tooBig = Proto.MEDIA_PAYLOAD_MAX_BYTES + 1
        val bad = ByteArray(Proto.MEDIA_HEADER_BYTES + tooBig)
        bad[Proto.MEDIA_OFF_LEN] = (tooBig and 0xFF).toByte()
        bad[Proto.MEDIA_OFF_LEN + 1] = ((tooBig shr 8) and 0xFF).toByte()
        assertNull(MediaFrame.decode(bad))
    }

    @Test
    fun decodeRejectsTotalSizeMismatch() {
        val ok = golden().encode()
        assertNull(MediaFrame.decode(ok.copyOf(ok.size - 1)))
        assertNull(MediaFrame.decode(ok.copyOf(ok.size + 1)))
    }

    @Test
    fun unknownFlagsBitPassesThrough() {
        val f = golden().copy(flags = 0x80)
        val got = MediaFrame.decode(f.encode())
        assertNotNull(got)
        assertEquals(0x80, got!!.flags)
    }

    @Test
    fun zeroLenFrameRoundTrips() {
        val f =
            MediaFrame(
                epoch = 0xAAAAAAAA.toInt(),
                seq = 0xBBBBBBBB.toInt(),
                flags = 0x04,
                payload = ByteArray(0),
            )
        val out = f.encode()
        assertEquals(Proto.MEDIA_HEADER_BYTES, out.size)
        val got = MediaFrame.decode(out)
        assertNotNull(got)
        assertEquals(f.epoch, got!!.epoch)
        assertEquals(f.seq, got.seq)
        assertEquals(f.flags, got.flags)
        assertEquals(0, got.payload.size)
    }
}
