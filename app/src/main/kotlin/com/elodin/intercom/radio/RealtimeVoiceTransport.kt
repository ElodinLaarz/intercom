package com.elodin.intercom.radio

import android.bluetooth.BluetoothSocket
import java.io.DataInputStream
import java.io.IOException

/**
 * Voice transport boundary for the realtime rewrite.
 *
 * Callers send complete voice packets and never rely on retransmission. A
 * transport may drop an outgoing packet, and the voice session must continue.
 * Each packet contains one or more self-contained fixed-size voice frames; the
 * native SeqFilter/jitter path turns missing seqs into realtime gaps.
 */
internal interface RealtimeVoiceTransport : AutoCloseable {
    @Throws(IOException::class)
    fun send(packet: ByteArray): VoiceSendResult

    @Throws(IOException::class)
    fun receive(): ByteArray?

    override fun close()
}

internal data class VoiceSendResult(
    val sent: Boolean,
    val busyMs: Long,
)

/**
 * Adapter for the current BLE L2CAP CoC stream.
 *
 * L2CAP is still reliable and ordered, so this adapter is a stepping stone, not
 * the final transport. It exposes fixed frames as packets so HostRadio and
 * GuestRadio can already be written against realtime datagram semantics.
 */
internal class L2capVoiceTransport(
    private val socket: BluetoothSocket,
    private val frameBytes: Int,
    private val nowMs: () -> Long,
) : RealtimeVoiceTransport {
    private val input = DataInputStream(socket.inputStream)
    private val output = socket.outputStream

    override fun send(packet: ByteArray): VoiceSendResult {
        val startMs = nowMs()
        output.write(packet)
        output.flush()
        return VoiceSendResult(sent = true, busyMs = nowMs() - startMs)
    }

    override fun receive(): ByteArray {
        val packet = ByteArray(frameBytes)
        input.readFully(packet)
        return packet
    }

    override fun close() {
        socket.close()
    }
}
