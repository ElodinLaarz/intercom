package com.elodin.intercom.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.elodin.intercom.NativeCore
import com.elodin.intercom.audio.VoiceAudioRoute
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.ConnectionStats
import com.elodin.intercom.radio.MeterSnapshot
import com.elodin.intercom.radio.ScanStartBudget
import com.elodin.intercom.radio.ThroughputMeter
import com.elodin.intercom.session.RadioEndpoint
import com.elodin.intercom.session.RadioEvent
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions")
internal class WifiDirectGuestRadio(
    private val context: Context,
    private val onEvent: (RadioEvent) -> Unit,
    private val onStats: (ConnectionStats) -> Unit = {},
) : RadioEndpoint {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var clientSocket: Socket? = null
    private val socketLock = Any()
    private val audioLock = Any()
    private val epochLock = Object()
    private val genLock = Any()
    private var linkedEpoch: Long? = null
    private var captureStarted = false
    private var playoutStarted = false
    private var connectionGeneration = 0

    @Volatile
    private var rejoining = false

    // Re-issued each join attempt; a stale watchdog fire is ignored. Touched only
    // on the P2P channel looper (main) — see callbacks.
    private var joinWatchdogToken = 0

    @Volatile
    private var lastTxSnapshot: MeterSnapshot? = null

    @Volatile
    private var lastRxSnapshot: MeterSnapshot? = null

    @Volatile
    private var running = false

    @Volatile
    private var torn = false

    @Volatile
    private var connecting = false

    @Volatile
    private var linked = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var receiver: BroadcastReceiver? = null

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
        acquireWifiLock()

        registerReceiver()
        emitStatus("Connecting to Intercom host…")
        joinHostGroup()

        return true
    }

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
        connecting = false
        linked = false
        synchronized(genLock) { connectionGeneration += 1 }
        stopAudio()
        closeSocket()
        removeGroup()
        unregisterReceiver()
        releaseWifiLock()
        channel = null
        manager = null
        Log.i(TAG, "RADIO wifi-direct guest stopped")
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
        val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "intercom:wfd-guest")
        lock.acquire()
        wifiLock = lock
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // --- Broadcast receiver ---

    private fun registerReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
        val rcv =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (torn || !running) return
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> handleStateChanged(intent)
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> handleConnectionChanged()
                    }
                }
            }
        receiver = rcv
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(rcv, filter)
        }
    }

    private fun unregisterReceiver() {
        val rcv = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(rcv)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "RADIO wifi-direct unregister receiver: ${e.message}")
        }
    }

    private fun handleStateChanged(intent: Intent) {
        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) return

        emitFailed("Wi-Fi Direct disabled")
        stop(reportStopped = false)
    }

    private fun handleConnectionChanged() {
        val ch = channel ?: return
        val mgr = manager ?: return
        mgr.requestConnectionInfo(ch) { info ->
            if (torn || !running) return@requestConnectionInfo
            if (info?.groupFormed != true) return@requestConnectionInfo
            if (info.isGroupOwner) {
                handleSelfGroupOwner()
                return@requestConnectionInfo
            }
            val ownerAddress = info.groupOwnerAddress ?: return@requestConnectionInfo
            val hostIp = ownerAddress.hostAddress ?: return@requestConnectionInfo
            onConnectionEstablished(hostIp)
        }
    }

    // --- Join host group (no service discovery) ---

    // The host runs an autonomous GO with a fixed networkName+passphrase; we join
    // it directly by those credentials. This sidesteps Wi-Fi Direct service
    // discovery (DNS-SD), which is unreliable on some chipsets — the guest sees
    // the GO as a peer but the GAS/DNS-SD service response never arrives, so the
    // app never learns which peer to dial. The unique SSID identifies our host and
    // a present GO makes the guest join as client.
    private fun joinHostGroup() {
        if (torn || !running) return
        val ch = channel ?: return
        val mgr = manager ?: return
        connecting = true
        emitStatus("Connecting to Intercom host…")
        mgr.connect(
            ch,
            joinConfig(),
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!running || torn) return
                    Log.i(TAG, "RADIO wifi-direct join initiated name=${WifiDirectHostRadio.GROUP_NETWORK_NAME}")
                    scheduleJoinWatchdog()
                }

                override fun onFailure(reason: Int) {
                    if (torn) return
                    connecting = false
                    Log.e(TAG, "RADIO wifi-direct join failed reason=$reason")
                    if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                        emitFailed("Wi-Fi Direct not supported")
                        stop(reportStopped = false)
                        return
                    }
                    scheduleJoin("join failed (reason $reason)")
                }
            },
        )
    }

    private fun joinConfig(): WifiP2pConfig =
        WifiP2pConfig
            .Builder()
            .setNetworkName(WifiDirectHostRadio.GROUP_NETWORK_NAME)
            .setPassphrase(WifiDirectHostRadio.GROUP_PASSPHRASE)
            .build()

    private fun onConnectionEstablished(hostAddress: String) {
        if (linked) return
        linked = true
        connecting = false
        val generation =
            synchronized(genLock) {
                connectionGeneration += 1
                connectionGeneration
            }
        thread(isDaemon = true, name = "wfd-tcp-connect") {
            connectAndStream(generation, hostAddress)
        }
    }

    // We became GO ourselves — the host's group was not up when we joined, so our
    // credential-join formed a fresh group instead of joining. Leave it and retry;
    // the host owns the GO role.
    private fun handleSelfGroupOwner() {
        if (torn || !running) return
        Log.w(TAG, "RADIO wifi-direct joined as GO (host absent) — leaving + retry")
        linked = false
        connecting = false
        removeGroup()
        scheduleJoin("waiting for host")
    }

    // A recoverable drop. Keep the endpoint alive (do NOT stop()): invalidate the
    // dead socket/threads, drop the dead epoch's audio, tell the session we are
    // reconnecting (not LinkLost), leave the stale group, and re-join the host on
    // a throttled cadence. Mirrors the BT GuestRadio retry loop.
    private fun rejoin(reason: String) {
        if (torn || !running) return
        synchronized(genLock) {
            if (rejoining) return
            rejoining = true
        }
        linked = false
        connecting = false
        closeSocket()
        stopAudio()
        Log.w(TAG, "RADIO wifi-direct rejoin: $reason")
        emitReconnecting(reason)
        removeGroup()
        scheduleJoin(reason)
    }

    private fun scheduleJoin(reason: String) {
        val delayMs = joinBudget.delayUntilAvailableMs().coerceAtLeast(REJOIN_MIN_DELAY_MS)
        Log.i(TAG, "RADIO wifi-direct re-join in ${delayMs}ms ($reason)")
        mainHandler.postDelayed({ runJoin() }, delayMs)
    }

    private fun runJoin() {
        if (torn || !running || linked) return
        rejoining = false
        joinBudget.recordStart()
        joinHostGroup()
    }

    // connect() can silently fail to form the group (no onFailure, no broadcast).
    // If still unlinked after a grace period, re-issue the join. Re-armed each
    // attempt; a stale fire is ignored via the token. On the channel looper (main).
    private fun scheduleJoinWatchdog() {
        joinWatchdogToken += 1
        val token = joinWatchdogToken
        mainHandler.postDelayed({
            if (torn || !running || linked) return@postDelayed
            if (token != joinWatchdogToken) return@postDelayed
            Log.w(TAG, "RADIO wifi-direct join watchdog — retry")
            scheduleJoin("join watchdog")
        }, JOIN_WATCHDOG_MS)
    }

    // --- TCP client + voice stream ---

    private fun connectAndStream(
        generation: Int,
        hostAddress: String,
    ) {
        var attempt = 0
        while (isConnectionCurrent(generation) && attempt < MAX_TCP_ATTEMPTS) {
            val socket = tryTcpConnect(hostAddress)
            if (socket != null) {
                streamAudio(generation, socket, hostAddress)
                linked = false
                return
            }
            attempt += 1
            if (attempt < MAX_TCP_ATTEMPTS && isConnectionCurrent(generation)) {
                sleepRetry(TCP_RETRY_MS)
            }
        }
        if (isConnectionCurrent(generation)) {
            rejoin("TCP connect to $hostAddress failed")
        }
    }

    private fun tryTcpConnect(hostAddress: String): Socket? =
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(hostAddress, WifiDirectHostRadio.TCP_PORT), TCP_CONNECT_TIMEOUT_MS)
            socket
        } catch (e: IOException) {
            Log.w(TAG, "RADIO wifi-direct TCP connect attempt failed: ${e.message}")
            null
        }

    private fun streamAudio(
        generation: Int,
        socket: Socket,
        hostAddress: String,
    ) {
        if (!publishSocketIfCurrent(generation, socket)) {
            closeSocket(socket)
            return
        }
        val hostWireEpoch = readHostPreface(generation, socket) ?: return
        Log.i(TAG, "RADIO wifi-direct TCP connected to $hostAddress wireEpoch=$hostWireEpoch")
        emitLinked(hostAddress, hostWireEpoch)
        val epoch = awaitLinkedEpoch(generation)
        if (epoch == null) {
            closeAndDetachSocket(socket)
            return
        }

        Log.i(TAG, "RADIO wifi-direct duplex audio epoch=$epoch")
        emitStatus("Voice link up — duplex audio")
        thread(isDaemon = true, name = "wfd-tcp-rx") { readRemoteFrames(generation, socket) }
        writeCaptureFrames(generation, socket, epoch)
    }

    private fun readHostPreface(
        generation: Int,
        socket: Socket,
    ): Long? =
        try {
            WifiDirectWire.readPreface(socket.inputStream)
        } catch (e: IOException) {
            closeAndDetachSocket(socket)
            if (isConnectionCurrent(generation)) {
                Log.w(TAG, "RADIO wifi-direct handshake read failed: ${e.message}")
                rejoin("Handshake failed")
            }
            null
        }

    // --- Voice frame I/O ---

    private fun readRemoteFrames(
        generation: Int,
        socket: Socket,
    ) {
        val input = DataInputStream(socket.inputStream)
        val buf = ByteArray(Proto.VOICE_FRAME_BYTES)
        val rxMeter = ThroughputMeter(NET_WINDOW_MS) { SystemClock.elapsedRealtime() }
        var received = 0L
        var accepted = 0L
        try {
            while (isConnectionCurrent(generation)) {
                val readStartMs = SystemClock.elapsedRealtime()
                input.readFully(buf)
                val readMs = SystemClock.elapsedRealtime() - readStartMs
                if (!isConnectionCurrent(generation)) return
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
            if (isConnectionCurrent(generation)) Log.w(TAG, "RADIO wifi-direct rx ended: ${e.message}")
        } finally {
            closeAndDetachSocket(socket)
        }
        if (!isConnectionCurrent(generation)) return
        rejoin("Host disconnected")
    }

    private fun writeCaptureFrames(
        generation: Int,
        socket: Socket,
        epoch: Long,
    ) {
        val output = socket.outputStream
        val txMeter = ThroughputMeter(NET_WINDOW_MS) { SystemClock.elapsedRealtime() }
        var lastFrameAtMs = SystemClock.elapsedRealtime()
        try {
            while (isConnectionCurrent(generation)) {
                val bundle = NativeCore.takeGuestBundle(1, FRAME_TIMEOUT_MS)
                if (bundle == null) {
                    lastFrameAtMs = recoverCaptureIfStalled(epoch, lastFrameAtMs) ?: return
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
            if (isConnectionCurrent(generation)) {
                Log.w(TAG, "RADIO wifi-direct tx ended: ${e.message}")
                rejoin("Host disconnected")
            }
        } finally {
            stopCapture()
            closeAndDetachSocket(socket)
        }
    }

    private fun recoverCaptureIfStalled(
        epoch: Long,
        lastFrameAtMs: Long,
    ): Long? {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastFrameAtMs <= CAPTURE_STALL_MS) return lastFrameAtMs

        Log.w(TAG, "AUDIO capture starved — restarting epoch=$epoch")
        if (restartCapture(epoch)) return SystemClock.elapsedRealtime()

        rejoin("Audio capture stalled")
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

    private fun sleepRetry(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // --- Audio lifecycle ---

    private fun startAudio(epochId: Long): Boolean {
        if (!startCapture(epochId)) return false
        if (startPlayout(epochId)) return true
        stopCapture()
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

    private fun awaitLinkedEpoch(generation: Int): Long? =
        synchronized(epochLock) {
            while (isConnectionCurrent(generation) && linkedEpoch == null) {
                try {
                    epochLock.wait(EPOCH_WAIT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@synchronized null
                }
            }
            if (!isConnectionCurrent(generation)) return@synchronized null
            linkedEpoch
        }

    // --- Socket management ---

    private fun isConnectionCurrent(generation: Int): Boolean =
        running && !torn && synchronized(genLock) { generation == connectionGeneration }

    private fun publishSocketIfCurrent(
        generation: Int,
        socket: Socket,
    ): Boolean =
        synchronized(socketLock) {
            if (!isConnectionCurrent(generation)) return@synchronized false
            clientSocket = socket
            true
        }

    private fun closeSocket() {
        val socket =
            synchronized(socketLock) {
                synchronized(genLock) { connectionGeneration += 1 }
                val s = clientSocket
                clientSocket = null
                s
            }
        closeSocket(socket)
    }

    private fun closeAndDetachSocket(socket: Socket?) {
        if (socket == null) return
        synchronized(socketLock) {
            if (clientSocket === socket) clientSocket = null
        }
        closeSocket(socket)
    }

    private fun closeSocket(socket: Socket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO wifi-direct close: ${e.message}")
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

    private fun emitLinked(
        peer: String,
        wireEpoch: Long,
    ) {
        onEvent(RadioEvent.Linked(peer = peer, psm = WifiDirectHostRadio.TCP_PORT, wireEpoch = wireEpoch))
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
        private const val EPOCH_WAIT_MS = 100L
        private const val FRAME_TIMEOUT_MS = 100
        private const val NET_WINDOW_MS = 2_000L
        private const val CAPTURE_STALL_MS = 2_000L
        private const val TCP_CONNECT_TIMEOUT_MS = 10_000
        private const val TCP_RETRY_MS = 500L
        private const val MAX_TCP_ATTEMPTS = 5

        // Auto-rejoin connect cadence. Throttled so repeated drops never spam the
        // P2P framework; retries indefinitely (no user nearby to re-tap on a bike).
        // The join watchdog re-issues connect() if it silently fails to form.
        private const val JOIN_WINDOW_MS = 30_000L
        private const val MAX_JOIN_STARTS_PER_WINDOW = 4
        private const val MIN_JOIN_INTERVAL_MS = 1_000L
        private const val REJOIN_MIN_DELAY_MS = 800L
        private const val JOIN_WATCHDOG_MS = 12_000L
        private val joinBudget =
            ScanStartBudget(
                windowMs = JOIN_WINDOW_MS,
                maxStarts = MAX_JOIN_STARTS_PER_WINDOW,
                minIntervalMs = MIN_JOIN_INTERVAL_MS,
                nowMs = { SystemClock.elapsedRealtime() },
            )
    }
}
