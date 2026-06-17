package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceSessionTest {
    private val config =
        RealtimeVoiceConfig(
            frameBytes = FRAME_BYTES,
            frameTimeoutMs = 1,
            throughputWindowMs = 100,
            captureStallRestartMs = 2_000,
        )

    @Test
    fun rxSplitsBundledPacketsIntoFrames() {
        val transport = FakeTransport(incoming = listOf(frame(1) + frame(2)))
        val pushed = mutableListOf<ByteArray>()
        val accepted = mutableListOf<Long>()

        SessionHarness(transport)
            .apply {
                sink =
                    VoiceFrameSink { frame ->
                        pushed += frame
                        true
                    }
                onRxFrameAccepted = { accepted += it }
            }.build()
            .runRx()

        assertEquals(2, pushed.size)
        assertTrue(pushed[0].contentEquals(frame(1)))
        assertTrue(pushed[1].contentEquals(frame(2)))
        assertEquals(listOf(1L, 2L), accepted)
    }

    @Test
    fun rxDropsMalformedPackets() {
        val transport = FakeTransport(incoming = listOf(byteArrayOf(1, 2, 3)))
        val pushed = mutableListOf<ByteArray>()
        val warnings = mutableListOf<String>()

        SessionHarness(transport)
            .apply {
                sink =
                    VoiceFrameSink { frame ->
                        pushed += frame
                        true
                    }
                logWarn = { warnings += it }
            }.build()
            .runRx()

        assertTrue(pushed.isEmpty())
        assertTrue(warnings.any { it.contains("malformed packet") })
    }

    @Test
    fun txDoesNotRetryDroppedPackets() {
        val transport = FakeTransport(sendResults = listOf(VoiceSendResult(sent = false, busyMs = 0)))
        val lost = mutableListOf<String>()
        var txEnded = 0

        SessionHarness(transport)
            .apply {
                source = FakeSource(listOf(frame(7)))
                isCurrent = { transport.sentPackets.isEmpty() }
                onLinkLost = { lost += it }
                onTxEnded = { txEnded += 1 }
            }.build()
            .runTx("peer disconnected")

        assertEquals(1, transport.sentPackets.size)
        assertTrue(transport.sentPackets[0].contentEquals(frame(7)))
        assertTrue(lost.isEmpty())
        assertEquals(1, txEnded)
    }

    @Test
    fun txEscalatesCaptureStallWhenRestartFails() {
        val transport = FakeTransport()
        val lost = mutableListOf<String>()
        var nowCalls = 0

        SessionHarness(transport)
            .apply {
                source = FakeSource(listOf(null))
                isCurrent = { lost.isEmpty() }
                restartCapture = { false }
                onLinkLost = { lost += it }
                nowMs = {
                    if (nowCalls++ == 0) 0 else 2_001
                }
            }.build()
            .runTx("peer disconnected")

        assertEquals(listOf("Audio capture stalled"), lost)
    }

    private inner class SessionHarness(
        private val transport: FakeTransport,
    ) {
        var source: VoiceFrameSource = FakeSource(emptyList())
        var sink: VoiceFrameSink = VoiceFrameSink { true }
        var isCurrent: () -> Boolean = { true }
        var restartCapture: () -> Boolean = { true }
        var onLinkLost: (String) -> Unit = {}
        var onTxEnded: () -> Unit = {}
        var onRxFrameAccepted: (Long) -> Unit = {}
        var logWarn: (String) -> Unit = {}
        var nowMs: () -> Long = { 0 }

        fun build(): RealtimeVoiceSession =
            RealtimeVoiceSession(
                epoch = 42,
                io =
                    RealtimeVoiceIo(
                        transport = transport,
                        source = source,
                        sink = sink,
                    ),
                callbacks =
                    RealtimeVoiceCallbacks(
                        isCurrent = isCurrent,
                        bundleFrames = { 1 },
                        restartCapture = restartCapture,
                        onLinkLost = onLinkLost,
                        onTxEnded = onTxEnded,
                        onRxFrameAccepted = onRxFrameAccepted,
                        logInfo = {},
                        logWarn = logWarn,
                    ),
                config = config,
                nowMs = nowMs,
            )
    }

    private class FakeTransport(
        private val incoming: List<ByteArray> = emptyList(),
        private val sendResults: List<VoiceSendResult> = emptyList(),
    ) : RealtimeVoiceTransport {
        val sentPackets = mutableListOf<ByteArray>()
        private var receiveIndex = 0
        private var sendIndex = 0

        override fun send(packet: ByteArray): VoiceSendResult {
            sentPackets += packet
            val result = sendResults.getOrNull(sendIndex) ?: VoiceSendResult(sent = true, busyMs = 0)
            sendIndex += 1
            return result
        }

        override fun receive(): ByteArray? {
            val packet = incoming.getOrNull(receiveIndex)
            receiveIndex += 1
            return packet
        }

        override fun close() = Unit
    }

    private class FakeSource(
        private val packets: List<ByteArray?>,
    ) : VoiceFrameSource {
        private var index = 0

        override fun takePacket(
            maxFrames: Int,
            timeoutMs: Int,
        ): ByteArray? {
            val packet = packets.getOrNull(index)
            index += 1
            return packet
        }
    }

    private companion object {
        private const val FRAME_BYTES = 4

        private fun frame(seed: Int): ByteArray =
            byteArrayOf(
                seed.toByte(),
                (seed + 1).toByte(),
                (seed + 2).toByte(),
                (seed + 3).toByte(),
            )
    }
}
