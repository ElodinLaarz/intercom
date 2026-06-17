package com.elodin.intercom.radio

import com.elodin.intercom.NativeCore
import com.elodin.intercom.proto.Proto
import java.io.IOException

internal fun interface VoiceFrameSource {
    fun takePacket(
        maxFrames: Int,
        timeoutMs: Int,
    ): ByteArray?
}

internal fun interface VoiceFrameSink {
    fun pushFrame(frame: ByteArray): Boolean
}

internal data class RealtimeVoiceConfig(
    val frameBytes: Int = Proto.VOICE_FRAME_BYTES,
    val frameTimeoutMs: Int = 100,
    val throughputWindowMs: Long = 2_000L,
    val captureStallRestartMs: Long = 2_000L,
)

internal data class RealtimeVoiceCallbacks(
    val isCurrent: () -> Boolean,
    val bundleFrames: () -> Int,
    val restartCapture: () -> Boolean,
    val onLinkLost: (String) -> Unit,
    val onTxEnded: () -> Unit,
    val onRxFrameAccepted: (Long) -> Unit,
    val onStats: (ConnectionStats) -> Unit = {},
    val logInfo: (String) -> Unit,
    val logWarn: (String) -> Unit,
)

internal data class RealtimeVoiceIo(
    val transport: RealtimeVoiceTransport,
    val source: VoiceFrameSource,
    val sink: VoiceFrameSink,
)

/**
 * Runs realtime voice over a packet-shaped transport.
 *
 * The contract is intentionally UDP-like: every native frame is self-contained,
 * the sender never retries dropped packets, and the receiver accepts gaps as a
 * normal realtime condition. L2CAP currently sits behind this boundary as a
 * stream adapter; the full rewrite can swap that adapter without changing the
 * session or native audio ownership.
 */
internal class RealtimeVoiceSession(
    private val epoch: Long,
    private val io: RealtimeVoiceIo,
    private val callbacks: RealtimeVoiceCallbacks,
    private val config: RealtimeVoiceConfig = RealtimeVoiceConfig(),
    private val nowMs: () -> Long,
) {
    private val txMeter = ThroughputMeter(config.throughputWindowMs, nowMs)
    private val rxMeter = ThroughputMeter(config.throughputWindowMs, nowMs)

    @Volatile
    private var lastTxSnapshot: MeterSnapshot? = null

    @Volatile
    private var lastRxSnapshot: MeterSnapshot? = null

    fun runTx(peerDisconnectedReason: String) {
        var lastFrameAtMs = nowMs()
        try {
            while (callbacks.isCurrent()) {
                val packet = io.source.takePacket(callbacks.bundleFrames(), config.frameTimeoutMs)
                if (packet == null) {
                    lastFrameAtMs = recoverCaptureIfStalled(lastFrameAtMs) ?: return
                    continue
                }

                lastFrameAtMs = nowMs()
                sendPacket(packet)
            }
        } catch (e: IOException) {
            if (callbacks.isCurrent()) {
                callbacks.logWarn("RADIO voice tx ended: ${e.message}")
                callbacks.onLinkLost(peerDisconnectedReason)
            }
        } finally {
            callbacks.onTxEnded()
        }
    }

    fun runRx() {
        var accepted = 0L
        var received = 0L
        try {
            while (callbacks.isCurrent()) {
                val readStartMs = nowMs()
                val packet = io.transport.receive() ?: return
                val readMs = nowMs() - readStartMs
                if (!callbacks.isCurrent()) return

                val frameCount = frameCount(packet)
                if (frameCount == null) {
                    callbacks.logWarn("RADIO voice rx dropped malformed packet bytes=${packet.size}")
                    continue
                }
                rxMeter.onSample(frameCount, packet.size, readMs)?.let { snap ->
                    callbacks.logInfo("RXNET $snap")
                    lastRxSnapshot = snap
                    pushStats()
                }

                var offset = 0
                repeat(frameCount) {
                    received += 1
                    val frame = packet.copyOfRange(offset, offset + config.frameBytes)
                    offset += config.frameBytes
                    if (io.sink.pushFrame(frame)) {
                        accepted += 1
                        callbacks.onRxFrameAccepted(accepted)
                        return@repeat
                    }
                    callbacks.logWarn("RADIO voice rx dropped frame #$received")
                }
            }
        } catch (e: IOException) {
            if (callbacks.isCurrent()) callbacks.logWarn("RADIO voice rx ended: ${e.message}")
        }
    }

    private fun sendPacket(packet: ByteArray) {
        val frameCount = frameCount(packet)
        if (frameCount == null) {
            callbacks.logWarn("RADIO voice tx dropped malformed packet bytes=${packet.size}")
            return
        }

        val result = io.transport.send(packet)
        if (!result.sent) {
            callbacks.logWarn("RADIO voice tx dropped packet frames=$frameCount bytes=${packet.size}")
            return
        }

        txMeter.onSample(frameCount, packet.size, result.busyMs)?.let { snap ->
            callbacks.logInfo("TXNET epoch=$epoch $snap")
            lastTxSnapshot = snap
            pushStats()
        }
    }

    private fun frameCount(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        if (packet.size % config.frameBytes != 0) return null
        return packet.size / config.frameBytes
    }

    private fun pushStats() {
        val tx = lastTxSnapshot
        val rx = lastRxSnapshot
        callbacks.onStats(
            ConnectionStats(
                txBps = tx?.bps ?: 0,
                txFps = tx?.fps ?: 0,
                txBusyPct = tx?.busyPct ?: 0,
                rxBps = rx?.bps ?: 0,
                rxFps = rx?.fps ?: 0,
                rxBusyPct = rx?.busyPct ?: 0,
                rxMaxBusyMs = rx?.maxBusyMs ?: 0,
            ),
        )
    }

    private fun recoverCaptureIfStalled(lastFrameAtMs: Long): Long? {
        val now = nowMs()
        if (now - lastFrameAtMs <= config.captureStallRestartMs) return lastFrameAtMs

        callbacks.logWarn("AUDIO capture starved — restarting epoch=$epoch")
        if (callbacks.restartCapture()) return nowMs()

        callbacks.onLinkLost("Audio capture stalled")
        return null
    }
}

internal object NativeVoiceFrameSource : VoiceFrameSource {
    override fun takePacket(
        maxFrames: Int,
        timeoutMs: Int,
    ): ByteArray? = NativeCore.takeGuestBundle(maxFrames, timeoutMs)
}

internal object NativeVoiceFrameSink : VoiceFrameSink {
    override fun pushFrame(frame: ByteArray): Boolean = NativeCore.pushHostFrame(frame)
}
