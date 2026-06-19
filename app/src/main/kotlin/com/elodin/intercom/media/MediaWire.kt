package com.elodin.intercom.media

import com.elodin.intercom.proto.MediaFrame
import com.elodin.intercom.proto.Proto
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-delimited framing for [MediaFrame] over a TCP stream (the shared-media
 * channel on [Proto.MEDIA_STREAM_PORT]). Unlike the fixed-stride voice frames,
 * media frames are variable length, so each is self-delimiting: the 12-byte
 * header is read first, its `len` field gives the payload size, then exactly
 * that many payload bytes are read. Keeping the framing here (pure, stream-only)
 * makes it unit-testable without a socket.
 */
internal object MediaWire {
    /**
     * Read one framed [MediaFrame] from [input], blocking until a full frame is
     * available. Throws [IOException] on stream EOF/close or a desynchronised
     * length (which can't be resynced without a delimiter — the caller drops the
     * connection). Returns null when the bytes framed correctly but failed
     * [MediaFrame.decode] bounds (e.g. non-zero reserved): the stream stays
     * aligned, so the caller drops just this frame and keeps reading.
     */
    @Throws(IOException::class)
    fun readFrame(input: InputStream): MediaFrame? {
        val data = DataInputStream(input)
        val header = ByteArray(Proto.MEDIA_HEADER_BYTES)
        data.readFully(header)

        val lenLow = header[Proto.MEDIA_OFF_LEN].toInt() and BYTE_MASK
        val lenHigh = header[Proto.MEDIA_OFF_LEN + 1].toInt() and BYTE_MASK
        val len = lenLow or (lenHigh shl BITS_PER_BYTE)
        if (len > Proto.MEDIA_PAYLOAD_MAX_BYTES) {
            throw IOException("media frame len $len exceeds ${Proto.MEDIA_PAYLOAD_MAX_BYTES}")
        }

        val full = ByteArray(Proto.MEDIA_HEADER_BYTES + len)
        header.copyInto(full)
        data.readFully(full, Proto.MEDIA_HEADER_BYTES, len)
        return MediaFrame.decode(full)
    }

    /** Write one [MediaFrame] (header + payload) to [output] and flush. */
    @Throws(IOException::class)
    fun writeFrame(
        output: OutputStream,
        frame: MediaFrame,
    ) {
        output.write(frame.encode())
        output.flush()
    }

    /** True when [t] signals the peer closed the stream (vs. a transient error). */
    fun isStreamClosed(t: IOException): Boolean = t is EOFException

    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
}
