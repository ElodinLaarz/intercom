package com.elodin.intercom.wifidirect

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WifiDirectWire {
    const val MAX_WIRE_EPOCH = 4_294_967_295L

    private const val PREFACE_BYTES = 8
    private const val PREFACE_MAGIC = 0x3144_4657 // "WFD1" little-endian on the wire.

    fun isValidWireEpoch(epoch: Long): Boolean = epoch in 1..MAX_WIRE_EPOCH

    @Throws(IOException::class)
    fun writePreface(
        output: OutputStream,
        wireEpoch: Long,
    ) {
        if (!isValidWireEpoch(wireEpoch)) throw IOException("invalid wire epoch $wireEpoch")

        val preface =
            ByteBuffer
                .allocate(PREFACE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(PREFACE_MAGIC)
                .putInt(wireEpoch.toUInt().toInt())
                .array()
        output.write(preface)
        output.flush()
    }

    @Throws(IOException::class)
    fun readPreface(input: InputStream): Long {
        val preface = ByteArray(PREFACE_BYTES)
        DataInputStream(input).readFully(preface)
        val buffer = ByteBuffer.wrap(preface).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        if (magic != PREFACE_MAGIC) throw IOException("bad Wi-Fi Direct preface")

        val wireEpoch = buffer.int.toUInt().toLong()
        if (!isValidWireEpoch(wireEpoch)) throw IOException("invalid wire epoch $wireEpoch")
        return wireEpoch
    }
}
