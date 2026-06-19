package com.elodin.intercom.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.elodin.intercom.NativeCore
import com.elodin.intercom.audio.VoiceAudioRoute
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.ConnectionStats
import com.elodin.intercom.radio.MeterSnapshot
import com.elodin.intercom.radio.ThroughputMeter
import com.elodin.intercom.session.RadioEndpoint
import com.elodin.intercom.session.RadioEvent
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions")
internal class WifiDirectHostRadio(
    private val context: Context,
    private val onEvent: (RadioEvent) -> Unit,
    private val onStats: (ConnectionStats) -> Unit = {},
) : RadioEndpoint {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null
    private val socketLock = Any()
    private val audioLock = Any()
    private val epochLock = Object()
    private var linkedEpoch: Long? = null
    private var captureStarted = false
    private var playoutStarted = false
    private var wireEpoch: Long = 0

    private var wifiLock: WifiManager.WifiLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var createGroupAttempts = 0

    @Volatile
    private var lastTxSnapshot: MeterSnapshot? = null

    @Volatile
    private var lastRxSnapshot: MeterSnapshot? = null

    @Volatile
    private var running = false

    @Volatile
    private var torn = false

    override fun start(): Boolean {
        if (running) return true
        torn = false

        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            emitFailed("Wi-Fi Direct not supported")
            return false
        }

        val ch = mgr.initialize(context, Looper.getMainLooper(), null)
        if (ch == null) {
            emitFailed("Wi-Fi Direct init failed")
            return false
        }

        manager = mgr
        channel = ch
        running = true
        wireEpoch = nextWireEpoch()
        acquireWifiLock()

        createGroupAttempts = 0
        createGroupWithRetry()

        return true
    }

    // Create the GO pinned to a 2.4 GHz social channel (ch 6) with fixed
    // credentials. The default autonomous GO lands on an arbitrary (often 5 GHz)
    // operating channel; the guest's service discovery only scans the social
    // channels (1/6/11), so the GAS/DNS-SD exchange never completes and the guest
    // sees the peer but never resolves the service. Pinning to 2437 MHz puts the
    // GO where the guest is already listening. BUSY clears with a remove + retry.
    private fun createGroupWithRetry() {
        if (!running || torn) return
        val ch = channel ?: return
        val mgr = manager ?: return
        emitStatus("Creating Wi-Fi Direct group…")
        mgr.createGroup(
            ch,
            buildGroupConfig(),
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!running || torn) return
                    Log.i(TAG, "RADIO wifi-direct group created freq=$GROUP_FREQ_MHZ")
                    registerService()
                }

                override fun onFailure(reason: Int) {
                    if (torn) return
                    Log.e(TAG, "RADIO wifi-direct createGroup failed reason=$reason")
                    if (reason == WifiP2pManager.BUSY && createGroupAttempts < MAX_CREATE_GROUP_ATTEMPTS) {
                        createGroupAttempts += 1
                        Log.w(TAG, "RADIO wifi-direct createGroup BUSY — clear + retry $createGroupAttempts")
                        removeGroup()
                        mainHandler.postDelayed({ createGroupWithRetry() }, CREATE_GROUP_RETRY_MS)
                        return
                    }
                    emitFailed("Group creation failed (reason $reason)")
                    stop(reportStopped = false)
                }
            },
        )
    }

    private fun buildGroupConfig(): WifiP2pConfig =
        WifiP2pConfig
            .Builder()
            .setNetworkName(GROUP_NETWORK_NAME)
            .setPassphrase(GROUP_PASSPHRASE)
            .setGroupOperatingFrequency(GROUP_FREQ_MHZ)
            .build()

    override fun stop() {
        stop(reportStopped = true)
    }

    override fun beginEpoch(epochId: Long) {
        if (!running || torn) return
        val started = startAudio(epochId)
        synchronized(epochLock) {
            if (started && running && !torn) linkedEpoch = epochId
            epochLock.notifyAll()
        }
        if (!started) emitFailed("Audio start failed")
    }

    private fun stop(reportStopped: Boolean) {
        torn = true
        running = false
        stopAudio()
        closeClientSocket()
        closeTcpServer()
        clearServices()
        removeGroup()
        releaseWifiLock()
        channel = null
        manager = null
        wireEpoch = 0
        Log.i(TAG, "RADIO wifi-direct host stopped")
        if (reportStopped) emitStatus("Stopped")
    }

    private fun removeGroup() {
        val ch = channel ?: return
        val mgr = manager ?: return
        mgr.removeGroup(ch, null)
    }

    @SuppressLint("WifiManagerLeak")
    private fun acquireWifiLock() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "intercom:wfd-host")
        lock.acquire()
        wifiLock = lock
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun nextWireEpoch(): Long {
        val epoch = SystemClock.elapsedRealtimeNanos() and WifiDirectWire.MAX_WIRE_EPOCH
        if (WifiDirectWire.isValidWireEpoch(epoch)) return epoch
        return TCP_PORT.toLong()
    }

    // --- Service registration ---

    private fun registerService() {
        val ch = channel ?: return
        val mgr = manager ?: return
        val info =
            WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_INSTANCE,
                SERVICE_TYPE,
                mapOf("port" to TCP_PORT.toString()),
            )
        mgr.addLocalService(
            ch,
            info,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!running || torn) return
                    Log.i(TAG, "RADIO wifi-direct service registered type=$SERVICE_TYPE")
                    startTcpServer()
                }

                override fun onFailure(reason: Int) {
                    if (torn) return
                    Log.e(TAG, "RADIO wifi-direct addLocalService failed reason=$reason")
                    emitFailed("Service registration failed (reason $reason)")
                    stop(reportStopped = false)
                }
            },
        )
    }

    private fun clearServices() {
        val ch = channel ?: return
        val mgr = manager ?: return
        mgr.clearLocalServices(ch, null)
    }

    // --- TCP server ---

    private fun startTcpServer() {
        thread(isDaemon = true, name = "wfd-tcp-accept") {
            try {
                val server = ServerSocket(TCP_PORT)
                serverSocket = server
                Log.i(TAG, "RADIO wifi-direct TCP listening port=$TCP_PORT")
                emitAdvertising("Wi-Fi Direct host — port $TCP_PORT — waiting for guest")
                acceptLoop(server)
            } catch (e: IOException) {
                if (running && !torn) {
                    Log.e(TAG, "RADIO wifi-direct TCP listen failed: ${e.message}")
                    emitFailed("TCP server failed")
                    stop(reportStopped = false)
                }
            }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (running && !torn) {
                receiveFrom(server.accept())
                prepareForNextGuest()
            }
        } catch (e: IOException) {
            if (running && !torn) Log.w(TAG, "RADIO wifi-direct accept ended: ${e.message}")
        }
    }

    // A guest dropped but the group, service, and TCP server stay up — loop back
    // to accept() for the next guest. Reset the dead epoch's audio (so the next
    // beginEpoch starts fresh) and mint a new wire epoch so a stale guest's
    // frames can't be replayed against the next session.
    private fun prepareForNextGuest() {
        if (!running || torn) return
        stopAudio()
        wireEpoch = nextWireEpoch()
        Log.i(TAG, "RADIO wifi-direct host ready for next guest wireEpoch=$wireEpoch")
        emitAdvertising("Wi-Fi Direct host — port $TCP_PORT — waiting for guest")
    }

    private fun receiveFrom(client: Socket) {
        client.tcpNoDelay = true
        if (!publishClientSocket(client)) {
            closeSocket(client, "wifi-direct stale client")
            return
        }
        val address = client.inetAddress?.hostAddress ?: UNKNOWN_PEER
        if (!sendPreface(client)) {
            detachClientSocket(client)
            return
        }
        Log.i(TAG, "RADIO wifi-direct accepted $address wireEpoch=$wireEpoch")
        emitLinked(address)
        val epoch = awaitLinkedEpoch()
        if (epoch == null) {
            detachClientSocket(client)
            return
        }
        Log.i(TAG, "RADIO wifi-direct duplex audio epoch=$epoch peer=$address")
        emitStatus("Voice link up — duplex audio with $address")
        thread(isDaemon = true, name = "wfd-tcp-tx") { writeCaptureFrames(client, epoch) }
        readRemoteFrames(client)
        detachClientSocket(client)
        if (running && !torn) emitReconnecting("Guest disconnected — waiting for rejoin")
    }

    private fun sendPreface(client: Socket): Boolean =
        try {
            WifiDirectWire.writePreface(client.outputStream, wireEpoch)
            true
        } catch (e: IOException) {
            Log.w(TAG, "RADIO wifi-direct handshake write failed: ${e.message}")
            false
        }

    // --- Voice frame I/O ---

    private fun readRemoteFrames(client: Socket) {
        val input = DataInputStream(client.inputStream)
        val buf = ByteArray(Proto.VOICE_FRAME_BYTES)
        val rxMeter = ThroughputMeter(NET_WINDOW_MS) { SystemClock.elapsedRealtime() }
        var received = 0L
        var accepted = 0L
        try {
            while (isClientCurrent(client)) {
                val readStartMs = SystemClock.elapsedRealtime()
                input.readFully(buf)
                val readMs = SystemClock.elapsedRealtime() - readStartMs
                if (!isClientCurrent(client)) return
                received += 1
                rxMeter.onSample(1, buf.size, readMs)?.let {
                    Log.i(TAG, "RXNET $it")
                    lastRxSnapshot = it
                    pushStats()
                }
                if (NativeCore.pushHostFrame(buf)) {
                    accepted += 1
                    reportAcceptedFrame(accepted)
                    continue
                }
                Log.w(TAG, "RADIO wifi-direct rx dropped frame #$received")
            }
        } catch (e: IOException) {
            if (isClientCurrent(client)) Log.w(TAG, "RADIO wifi-direct rx ended: ${e.message}")
        }
    }

    private fun writeCaptureFrames(
        client: Socket,
        epoch: Long,
    ) {
        val output = client.outputStream
        val txMeter = ThroughputMeter(NET_WINDOW_MS) { SystemClock.elapsedRealtime() }
        var lastFrameAtMs = SystemClock.elapsedRealtime()
        try {
            while (isClientCurrent(client)) {
                val bundle = NativeCore.takeGuestBundle(1, FRAME_TIMEOUT_MS)
                if (bundle == null) {
                    lastFrameAtMs = recoverCaptureIfStalled(epoch, lastFrameAtMs, client) ?: return
                    continue
                }
                lastFrameAtMs = SystemClock.elapsedRealtime()
                val writeMs = writeBundle(output, bundle)
                txMeter.onSample(1, bundle.size, writeMs)?.let {
                    Log.i(TAG, "TXNET epoch=$epoch $it")
                    lastTxSnapshot = it
                    pushStats()
                }
            }
        } catch (e: IOException) {
            if (isClientCurrent(client)) {
                Log.w(TAG, "RADIO wifi-direct tx ended: ${e.message}")
                emitReconnecting("Guest disconnected — waiting for rejoin")
            }
        } finally {
            stopCapture()
            detachClientSocket(client)
        }
    }

    private fun recoverCaptureIfStalled(
        epoch: Long,
        lastFrameAtMs: Long,
        client: Socket,
    ): Long? {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastFrameAtMs <= CAPTURE_STALL_MS) return lastFrameAtMs

        Log.w(TAG, "AUDIO capture starved — restarting epoch=$epoch")
        if (restartCapture(epoch)) return SystemClock.elapsedRealtime()

        if (isClientCurrent(client)) emitReconnecting("Audio capture stalled — waiting for rejoin")
        return null
    }

    private fun writeBundle(
        output: OutputStream,
        bundle: ByteArray,
    ): Long {
        val startMs = SystemClock.elapsedRealtime()
        output.write(bundle)
        output.flush()
        return SystemClock.elapsedRealtime() - startMs
    }

    private fun reportAcceptedFrame(accepted: Long) {
        if (!running || torn) return
        if (accepted > 1L) return
        Log.i(TAG, "RADIO wifi-direct rx accepted first voice frame")
        emitStatus("Voice link up — first remote audio frame")
    }

    // --- Audio lifecycle ---

    private fun startAudio(epochId: Long): Boolean {
        if (!startPlayout(epochId)) return false
        if (startCapture(epochId)) return true
        stopPlayout()
        return false
    }

    private fun stopAudio() {
        stopCapture()
        stopPlayout()
        synchronized(epochLock) {
            linkedEpoch = null
            epochLock.notifyAll()
        }
    }

    private fun startPlayout(epochId: Long): Boolean =
        synchronized(audioLock) {
            if (playoutStarted) return@synchronized true
            if (!WifiDirectWire.isValidWireEpoch(epochId)) return@synchronized false

            VoiceAudioRoute.enterCommunication(context)
            if (NativeCore.startHostPlayout(epochId)) {
                playoutStarted = true
                return@synchronized true
            }

            VoiceAudioRoute.leaveCommunication(context)
            false
        }

    private fun stopPlayout() {
        synchronized(audioLock) {
            if (!playoutStarted) return

            playoutStarted = false
            NativeCore.stopHostPlayout()
            VoiceAudioRoute.leaveCommunication(context)
        }
    }

    private fun startCapture(epochId: Long): Boolean =
        synchronized(audioLock) {
            if (captureStarted) return@synchronized true
            if (!WifiDirectWire.isValidWireEpoch(epochId)) return@synchronized false

            VoiceAudioRoute.enterCommunication(context)
            if (NativeCore.startGuestCapture(epochId)) {
                captureStarted = true
                return@synchronized true
            }

            VoiceAudioRoute.leaveCommunication(context)
            false
        }

    private fun stopCapture() {
        synchronized(audioLock) {
            if (!captureStarted) return

            captureStarted = false
            NativeCore.stopGuestCapture()
            VoiceAudioRoute.leaveCommunication(context)
        }
    }

    private fun restartCapture(epochId: Long): Boolean {
        if (!WifiDirectWire.isValidWireEpoch(epochId)) return false
        synchronized(audioLock) {
            if (!captureStarted) return false

            NativeCore.stopGuestCapture()
            if (NativeCore.startGuestCapture(epochId)) return true

            captureStarted = false
            VoiceAudioRoute.leaveCommunication(context)
            return false
        }
    }

    private fun awaitLinkedEpoch(): Long? =
        synchronized(epochLock) {
            while (running && !torn && linkedEpoch == null) {
                try {
                    epochLock.wait(EPOCH_WAIT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@synchronized null
                }
            }
            if (!running || torn) return@synchronized null
            linkedEpoch
        }

    // --- Socket management ---

    private fun isClientCurrent(client: Socket): Boolean = running && !torn && clientSocket === client

    private fun publishClientSocket(client: Socket): Boolean =
        synchronized(socketLock) {
            if (!running || torn) return@synchronized false
            clientSocket = client
            true
        }

    private fun closeClientSocket() {
        val socket =
            synchronized(socketLock) {
                val s = clientSocket
                clientSocket = null
                s
            }
        closeSocket(socket, "wifi-direct client")
    }

    private fun detachClientSocket(client: Socket) {
        synchronized(socketLock) {
            if (clientSocket === client) clientSocket = null
        }
        closeSocket(client, "wifi-direct client")
    }

    private fun closeTcpServer() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO wifi-direct close server: ${e.message}")
        }
        serverSocket = null
    }

    private fun closeSocket(
        socket: Socket?,
        label: String,
    ) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO close $label: ${e.message}")
        }
    }

    private fun pushStats() {
        val tx = lastTxSnapshot
        val rx = lastRxSnapshot
        onStats(
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

    // --- Event emission ---

    private fun emitLinked(peer: String) {
        onEvent(RadioEvent.Linked(peer = peer, psm = TCP_PORT, wireEpoch = wireEpoch))
    }

    private fun emitAdvertising(text: String) {
        onEvent(RadioEvent.Advertising(psm = TCP_PORT, text = text))
    }

    private fun emitReconnecting(reason: String) {
        onEvent(RadioEvent.Reconnecting(reason))
    }

    private fun emitFailed(reason: String) {
        onEvent(RadioEvent.Failed(reason))
    }

    private fun emitStatus(text: String) {
        onEvent(RadioEvent.Status(text))
    }

    companion object {
        private const val TAG = "INTERCOM"
        internal const val TCP_PORT = 9753
        internal const val SERVICE_TYPE = "_intercom._tcp"
        private const val SERVICE_INSTANCE = "Intercom"
        private const val UNKNOWN_PEER = "unknown"
        private const val EPOCH_WAIT_MS = 100L
        private const val FRAME_TIMEOUT_MS = 100
        private const val NET_WINDOW_MS = 2_000L
        private const val CAPTURE_STALL_MS = 2_000L

        // Fixed GO identity pinned to 2.4 GHz social channel 6. The guest joins by
        // these credentials directly (no service discovery), so they are the single
        // source of truth for both ends. networkName must match
        // ^DIRECT-[a-zA-Z0-9]{2}.*; passphrase is ASCII 8..63.
        internal const val GROUP_NETWORK_NAME = "DIRECT-ic-Intercom"
        internal const val GROUP_PASSPHRASE = "intercom-wfd-link"
        private const val GROUP_FREQ_MHZ = 2437
        private const val CREATE_GROUP_RETRY_MS = 1_500L
        private const val MAX_CREATE_GROUP_ATTEMPTS = 3
    }
}
