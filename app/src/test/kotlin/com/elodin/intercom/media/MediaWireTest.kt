package com.elodin.intercom.media

import com.elodin.intercom.proto.MediaFrame
import com.elodin.intercom.proto.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class MediaWireTest {
    private fun frame(
        seq: Int,
        size: Int,
    ): MediaFrame =
        MediaFrame(
            epoch = 0x11223344,
            seq = seq,
            flags = 0x01,
            payload = ByteArray(size) { (it and 0xFF).toByte() },
        )

    @Test
    fun roundTripsOneFrame() {
        val out = ByteArrayOutputStream()
        val f = frame(seq = 9, size = 40)
        MediaWire.writeFrame(out, f)

        val got = MediaWire.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(f.epoch, got!!.epoch)
        assertEquals(f.seq, got.seq)
        assertEquals(f.flags, got.flags)
        assertArrayEquals(f.payload, got.payload)
    }

    @Test
    fun readsBackToBackFramesInOrder() {
        val out = ByteArrayOutputStream()
        MediaWire.writeFrame(out, frame(seq = 1, size = 0))
        MediaWire.writeFrame(out, frame(seq = 2, size = 10))
        MediaWire.writeFrame(out, frame(seq = 3, size = 300))

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(1, MediaWire.readFrame(input)!!.seq)
        assertEquals(2, MediaWire.readFrame(input)!!.seq)
        val third = MediaWire.readFrame(input)!!
        assertEquals(3, third.seq)
        assertEquals(300, third.payload.size)
    }

    @Test
    fun malformedFrameReturnsNullButKeepsStreamAligned() {
        // A correctly-framed but decode-rejected frame (reserved != 0) must not
        // desync the stream: readFrame returns null and the next frame is intact.
        val bad = frame(seq = 1, size = 8).encode()
        bad[Proto.MEDIA_OFF_RESERVED] = 1
        val good = frame(seq = 2, size = 8)

        val out = ByteArrayOutputStream()
        out.write(bad)
        MediaWire.writeFrame(out, good)

        val input = ByteArrayInputStream(out.toByteArray())
        assertNull(MediaWire.readFrame(input)) // dropped, bytes consumed
        assertEquals(2, MediaWire.readFrame(input)!!.seq) // still aligned
    }

    @Test
    fun truncatedStreamThrows() {
        val out = ByteArrayOutputStream()
        MediaWire.writeFrame(out, frame(seq = 1, size = 20))
        val truncated = out.toByteArray().copyOf(Proto.MEDIA_HEADER_BYTES + 5)
        assertThrows(IOException::class.java) {
            MediaWire.readFrame(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun oversizedLengthThrows() {
        val header = ByteArray(Proto.MEDIA_HEADER_BYTES)
        val tooBig = Proto.MEDIA_PAYLOAD_MAX_BYTES + 1
        header[Proto.MEDIA_OFF_LEN] = (tooBig and 0xFF).toByte()
        header[Proto.MEDIA_OFF_LEN + 1] = ((tooBig shr 8) and 0xFF).toByte()
        assertThrows(IOException::class.java) {
            MediaWire.readFrame(ByteArrayInputStream(header))
        }
    }
}
