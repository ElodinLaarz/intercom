package com.elodin.intercom.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.elodin.intercom.NativeCore
import com.elodin.intercom.audio.VoiceAudioRoute
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.session.RadioEndpoint
import com.elodin.intercom.session.RadioEvent
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Guest side of the M1 link (issue #19, M1_PLAN §3 step 4): scan with a
 * manufacturer-data filter + continuous updates (landmine #2 — an unfiltered or
 * first-match scan gets demoted to opportunistic ~90 s in), GATT-connect to the
 * host, raise connection priority (landmine #1) and MTU, discover services, and
 * read the L2CAP PSM the host published (#18). Opening the CoC to that PSM is #20.
 *
 * Single owner of the guest radio objects; torn down in [stop] — no reset()
 * (rule 4). The caller guarantees BLUETOOTH_SCAN + BLUETOOTH_CONNECT are granted,
 * so the BLE calls are annotated [SuppressLint]. [onEvent] may fire from any
 * thread (scan/GATT/L2CAP callbacks).
 */
@SuppressLint("MissingPermission")
@Suppress("LargeClass", "TooManyFunctions")
internal class GuestRadio(
    private val context: Context,
    private val onEvent: (RadioEvent) -> Unit,
) : RadioEndpoint {
    private val serviceUuid: UUID = UUID.fromString(Proto.SERVICE_UUID)
    private val psmCharUuid: UUID = UUID.fromString(Proto.PSM_CHAR_UUID)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanGeneration = AtomicInteger(0)

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var hostDevice: BluetoothDevice? = null
    private val reconnectLock = Any()
    private val reconnectBackoff = BackoffLadder()
    private var reconnecting = false
    private var reconnectCycle = 0
    private var reconnectTimer = 0
    private var reconnectAttempt = 0
    private var cachedHostFailures = 0

    @Volatile
    private var clientSocket: BluetoothSocket? = null
    private val l2capGeneration = AtomicInteger(0)
    private val l2capLock = Any()
    private val audioLock = Any()
    private val epochLock = Object()
    private var linkedEpoch: Long? = null
    private var captureStarted = false
    private var playoutStarted = false

    @Volatile
    private var running = false

    @Volatile
    private var scanning = false

    // Set once in stop(); a torn-down instance ignores its own late scan/GATT
    // callbacks so a buffered DISCONNECT can't clobber the next session's status
    // (rule 4 — destruction is the reset, no stale state across sessions).
    @Volatile
    private var torn = false

    override fun start(): Boolean {
        if (running) return true
        torn = false
        resetReconnectState()
        running = true
        val started = beginScan("Scanning for host…")
        if (!started) running = false
        return started
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
        if (!started) {
            startOrContinueReconnect(
                reason = "Audio start failed",
                notifySession = true,
                forceScan = false,
            )
        }
    }

    override fun endEpoch() {
        if (!running || torn) return
        stopAudio()
        closeL2cap(clearHostDevice = false)
        if (!isReconnecting()) closeGatt(disconnect = true)
    }

    private fun stop(reportStopped: Boolean) {
        torn = true
        running = false
        scanning = false
        resetReconnectState()
        scanGeneration.incrementAndGet()
        stopAudio()
        stopCurrentScan()
        closeL2cap(clearHostDevice = true)
        closeGatt(disconnect = true)
        scanner = null
        Log.i(TAG, "RADIO guest stopped")
        if (reportStopped) emitStatus("Stopped")
    }

    private fun connectToHost(device: BluetoothDevice): Boolean {
        if (!running || torn) return false
        hostDevice = device
        val nextGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (nextGatt == null) {
            Log.w(TAG, "RADIO gatt connect failed to start ${device.address}")
            return false
        }
        gatt = nextGatt
        return true
    }

    private data class HostLinkParams(
        val psm: Int,
        val wireEpoch: Long,
    )

    private fun parseHostLinkParams(value: ByteArray?): HostLinkParams? {
        if (value == null || value.size < Integer.BYTES) return null

        val bb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val psm = bb.int
        val wireEpoch =
            if (value.size >= HOST_LINK_PARAMS_BYTES) {
                bb.int.toLong() and MAX_WIRE_EPOCH
            } else {
                psm.toLong() and MAX_WIRE_EPOCH
            }
        if (psm <= 0 || wireEpoch == 0L) return null
        return HostLinkParams(psm = psm, wireEpoch = wireEpoch)
    }

    private fun beginScan(
        statusMessage: String,
        keepTrying: Boolean = false,
    ): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val ble = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (ble == null) {
            Log.e(TAG, "RADIO ERROR bluetooth off or LE scan unsupported")
            emitFailed("Can't scan — turn Bluetooth on")
            stop(reportStopped = false)
            return false
        }
        val cooldownMs = scanStartBudget.delayUntilAvailableMs()
        if (cooldownMs > 0) {
            Log.w(TAG, "RADIO scan cooldown ${cooldownMs}ms before retry")
            scheduleScanCooldown(cooldownMs)
            return true
        }

        scanner = ble
        // Filter on the host's minimal MSD adv ([0x01,0x01]) — not the 128-bit
        // UUID, which the host omits from the adv PDU. Continuous + aggressive
        // matching (landmine #2): first-match/unfiltered scans get demoted.
        val filter = ScanFilter.Builder()
        filter.setManufacturerData(
            Proto.MSD_COMPANY_ID,
            byteArrayOf(Proto.MSD_PATTERN0.toByte(), Proto.MSD_PATTERN1.toByte()),
        )
        val settings = ScanSettings.Builder()
        settings.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        settings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        settings.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        settings.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        settings.setReportDelay(0)
        scanGeneration.incrementAndGet()
        Log.i(TAG, "RADIO scan start (MSD filter)")
        emitStatus(statusMessage)
        val started = startScan(ble, filter, settings)
        scanning = started
        if (started) {
            scheduleScanRefresh()
            return true
        }
        if (keepTrying && running && !torn) scheduleScanRetry(SCAN_STATUS, SCAN_RETRY_MS)
        return false
    }

    private fun startScan(
        ble: BluetoothLeScanner,
        filter: ScanFilter.Builder,
        settings: ScanSettings.Builder,
    ): Boolean =
        try {
            ble.startScan(listOf(filter.build()), settings.build(), scanCallback)
            scanStartBudget.recordStart()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "RADIO scan start permission failure: ${e.message}")
            emitFailed("Scan failed to start")
            stop(reportStopped = false)
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "RADIO scan start rejected: ${e.message}")
            emitFailed("Scan failed to start")
            false
        }

    private fun fail(message: String) {
        if (torn) return
        Log.e(TAG, "RADIO ERROR $message")
        emitFailed(message)
        stop(reportStopped = false)
    }

    private fun recoverSetupFailure(
        message: String,
        forceScan: Boolean = false,
    ) {
        if (!running || torn) return
        Log.w(TAG, "RADIO setup recovery: $message")
        startOrContinueReconnect(
            reason = message,
            notifySession = hasLinkedEpoch(),
            forceScan = forceScan || cachedHostFailuresReached,
        )
    }

    private fun startOrContinueReconnect(
        reason: String,
        notifySession: Boolean,
        forceScan: Boolean,
    ) {
        if (!running || torn) return
        val first: Boolean
        val cycle: Int
        synchronized(reconnectLock) {
            first = !reconnecting
            if (first) {
                reconnecting = true
                reconnectAttempt = 0
                cachedHostFailures = 0
                reconnectCycle += 1
            }
            cycle = reconnectCycle
        }

        closeLinkForReconnect()
        if (first && notifySession) emitLinkLost(reason)
        if (first) scheduleReconnectDeadline(cycle)
        scheduleReconnectAttempt(cycle, reason, forceScan)
    }

    private fun closeLinkForReconnect() {
        scanning = false
        scanGeneration.incrementAndGet()
        stopCurrentScan()
        stopAudio()
        closeL2cap(clearHostDevice = false)
        closeGatt(disconnect = true)
    }

    private fun scheduleReconnectDeadline(cycle: Int) {
        mainHandler.postDelayed(
            {
                if (isReconnectCycleCurrent(cycle)) {
                    giveUpReconnect("Reconnect timed out")
                }
            },
            RECONNECT_TIMEOUT_MS,
        )
    }

    private fun scheduleReconnectAttempt(
        cycle: Int,
        reason: String,
        forceScan: Boolean,
    ) {
        val timer: Int
        val attempt: Int
        if (!running || torn) return
        synchronized(reconnectLock) {
            if (!isReconnectCycleCurrentLocked(cycle)) return
            attempt = reconnectAttempt
            reconnectAttempt += 1
            reconnectTimer += 1
            timer = reconnectTimer
        }
        val delayMs = reconnectBackoff.cappedDelayBeforeRetryMs(attempt)
        emitStatus("Reconnecting in ${delayMs}ms — $reason")
        mainHandler.postDelayed(
            { runReconnectAttempt(cycle, timer, forceScan) },
            delayMs,
        )
    }

    private fun runReconnectAttempt(
        cycle: Int,
        timer: Int,
        forceScan: Boolean,
    ) {
        if (!isReconnectTimerCurrent(cycle, timer)) return
        val cachedDevice = hostDevice
        val useCached = !forceScan && cachedDevice != null && !cachedHostFailuresReached
        if (useCached) {
            val failureCount = recordCachedHostAttempt()
            emitFound(
                cachedDevice.address,
                "Reconnecting to host ${cachedDevice.address} ($failureCount/$CACHED_RETRIES_BEFORE_SCAN)…",
            )
            closeGatt(disconnect = false)
            if (connectToHost(cachedDevice)) return
            scheduleReconnectAttempt(
                cycle = cycle,
                reason = "GATT connect failed to start",
                forceScan = cachedHostFailuresReached,
            )
            return
        }

        beginReconnectScan(cycle)
    }

    private fun beginReconnectScan(cycle: Int) {
        if (!isReconnectCycleCurrent(cycle)) return
        beginScan("Reconnecting — scanning for host…", keepTrying = true)
    }

    private fun giveUpReconnect(reason: String) {
        synchronized(reconnectLock) {
            if (!reconnecting) return
            reconnecting = false
            reconnectCycle += 1
            reconnectTimer += 1
        }
        fail("Rejoin required — $reason")
    }

    private fun resetReconnectState() {
        synchronized(reconnectLock) {
            reconnecting = false
            reconnectCycle += 1
            reconnectTimer += 1
            reconnectAttempt = 0
            cachedHostFailures = 0
        }
    }

    private fun isReconnectCycleCurrent(cycle: Int): Boolean =
        running &&
            !torn &&
            synchronized(reconnectLock) {
                reconnecting && reconnectCycle == cycle
            }

    private fun isReconnectTimerCurrent(
        cycle: Int,
        timer: Int,
    ): Boolean =
        running &&
            !torn &&
            synchronized(reconnectLock) {
                reconnecting && reconnectCycle == cycle && reconnectTimer == timer
            }

    private fun isReconnecting(): Boolean = synchronized(reconnectLock) { reconnecting }

    private val cachedHostFailuresReached: Boolean
        get() =
            synchronized(reconnectLock) {
                cachedHostFailures >= CACHED_RETRIES_BEFORE_SCAN
            }

    private fun isReconnectCycleCurrentLocked(cycle: Int): Boolean = reconnecting && reconnectCycle == cycle

    private fun recordCachedHostAttempt(): Int =
        synchronized(reconnectLock) {
            cachedHostFailures += 1
            cachedHostFailures
        }

    private fun hasLinkedEpoch(): Boolean =
        synchronized(epochLock) {
            linkedEpoch != null
        }

    private fun scheduleScanRetry(
        statusMessage: String,
        delayMs: Long,
    ) {
        val generation = prepareScanRetry()
        emitStatus(statusMessage)
        postScanRetry(generation, delayMs, statusMessage)
    }

    private fun scheduleScanCooldown(delayMs: Long) {
        val generation = prepareScanRetry()
        val retryAtMs = SystemClock.elapsedRealtime() + delayMs
        updateCooldownStatus(generation, retryAtMs)
        postScanRetry(generation, delayMs, SCAN_STATUS)
    }

    private fun prepareScanRetry(): Int {
        val generation = scanGeneration.incrementAndGet()
        scanning = false
        stopCurrentScan()
        return generation
    }

    private fun postScanRetry(
        generation: Int,
        delayMs: Long,
        statusMessage: String,
    ) {
        mainHandler.postDelayed(
            {
                if (running && !torn && generation == scanGeneration.get()) {
                    beginScan(statusMessage, keepTrying = true)
                }
            },
            delayMs,
        )
    }

    private fun updateCooldownStatus(
        generation: Int,
        retryAtMs: Long,
    ) {
        if (!running || torn || generation != scanGeneration.get()) return
        val remainingMs = (retryAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        emitStatus(scanCooldownStatus(remainingMs))
        if (remainingMs == 0L) return
        mainHandler.postDelayed(
            { updateCooldownStatus(generation, retryAtMs) },
            minOf(COOLDOWN_STATUS_TICK_MS, remainingMs),
        )
    }

    private fun scanCooldownAfterFailure(): Long {
        val budgetDelayMs = scanStartBudget.delayUntilAvailableMs()
        if (budgetDelayMs > 0) return budgetDelayMs
        return SCAN_START_WINDOW_MS
    }

    private fun scheduleScanRefresh() {
        val generation = scanGeneration.get()
        mainHandler.postDelayed(
            {
                if (shouldRefreshScan(generation)) {
                    Log.w(TAG, "RADIO scan watchdog refresh")
                    scheduleScanRetry(SCAN_STATUS, SCAN_REFRESH_RESTART_MS)
                }
            },
            SCAN_REFRESH_MS,
        )
    }

    private fun shouldRefreshScan(generation: Int): Boolean {
        val activeScan = running && scanning && !torn
        val currentScan = generation == scanGeneration.get()
        return activeScan && currentScan
    }

    private fun stopCurrentScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop scan: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "RADIO stop scan rejected: ${e.message}")
        }
    }

    private fun closeGatt(disconnect: Boolean) {
        val closingGatt = gatt
        gatt = null
        try {
            if (disconnect) closingGatt?.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO disconnect gatt: ${e.message}")
        }
        try {
            closingGatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop gatt: ${e.message}")
        }
    }

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
            if (epochId !in 0..MAX_WIRE_EPOCH) return@synchronized false

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
            if (epochId !in 0..MAX_WIRE_EPOCH) return@synchronized false

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
        if (epochId !in 0..MAX_WIRE_EPOCH) return false
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
            while (isL2capCurrent(generation) && linkedEpoch == null) {
                try {
                    epochLock.wait(EPOCH_WAIT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@synchronized null
                }
            }
            if (!isL2capCurrent(generation)) return@synchronized null
            linkedEpoch
        }

    private fun openL2cap(params: HostLinkParams) {
        val device = hostDevice
        if (device == null) {
            recoverSetupFailure("No host device for L2CAP", forceScan = true)
            return
        }
        val generation =
            synchronized(l2capLock) {
                if (!running || torn) return
                l2capGeneration.incrementAndGet()
            }
        thread(isDaemon = true, name = "l2cap-connect") { connectLoop(generation, device, params) }
    }

    // Open the CoC to the host's PSM and run duplex voice over one fixed-frame
    // byte stream. Pre-link failures walk the bounded ladder, then feed the M2
    // reconnect cycle instead of stopping the selected guest role.
    private fun connectLoop(
        generation: Int,
        device: BluetoothDevice,
        params: HostLinkParams,
    ) {
        val ladder = BackoffLadder()
        var attempt = 0
        while (isL2capCurrent(generation)) {
            if (openAndStream(generation, device, params)) return
            val delayMs = ladder.delayBeforeRetryMs(attempt) ?: break
            if (!sleepLadder(delayMs)) return
            attempt += 1
        }
        if (isL2capCurrent(generation)) recoverSetupFailure("L2CAP connect failed")
    }

    private fun openAndStream(
        generation: Int,
        device: BluetoothDevice,
        params: HostLinkParams,
    ): Boolean {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createInsecureL2capChannel(params.psm)
            if (publishSocketIfCurrent(generation, socket)) {
                socket.connect()
                streamAudioIfCurrent(generation, socket, device, params)
                closeAndDetachSocket(socket)
                return true
            }
        } catch (e: IOException) {
            Log.w(TAG, "RADIO l2cap connect/tx failed: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "RADIO l2cap permission failure: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "RADIO l2cap rejected PSM ${params.psm}: ${e.message}")
        }
        closeAndDetachSocket(socket)
        return false
    }

    private fun streamAudioIfCurrent(
        generation: Int,
        socket: BluetoothSocket,
        device: BluetoothDevice,
        params: HostLinkParams,
    ) {
        if (!isL2capCurrent(generation)) return
        Log.i(TAG, "RADIO l2cap tx connected ${device.address} wireEpoch=${params.wireEpoch}")
        emitLinked(device.address, params)
        val epoch = awaitLinkedEpoch(generation) ?: return
        if (!isL2capCurrent(generation)) return
        resetReconnectState()

        Log.i(TAG, "RADIO l2cap duplex audio epoch=$epoch")
        emitStatus("Voice link up — duplex audio")
        thread(isDaemon = true, name = "l2cap-rx") { readRemoteFrames(generation, socket, epoch) }
        writeCaptureFrames(generation, socket, epoch)
    }

    private fun writeCaptureFrames(
        generation: Int,
        socket: BluetoothSocket,
        epoch: Long,
    ) {
        val output = socket.outputStream
        val txMeter = ThroughputMeter(TX_NET_WINDOW_MS) { SystemClock.elapsedRealtime() }
        var lastFrameAtMs = SystemClock.elapsedRealtime()
        try {
            while (isL2capCurrent(generation)) {
                val bundleFrames = bundleFramesForRoute()
                val bundle = NativeCore.takeGuestBundle(bundleFrames, TX_FRAME_TIMEOUT_MS)
                if (bundle == null) {
                    lastFrameAtMs = recoverCaptureIfStalled(epoch, lastFrameAtMs) ?: return
                    continue
                }
                lastFrameAtMs = SystemClock.elapsedRealtime()
                val writeMs = writeBundle(output, bundle)
                txMeter.onSample(bundleFrames, bundle.size, writeMs)?.let { Log.i(TAG, "TXNET epoch=$epoch $it") }
                if (RadioLinkHealth.shouldReconnectAfterWrite(writeMs)) {
                    Log.w(TAG, "RADIO l2cap tx stalled ${writeMs}ms epoch=$epoch")
                    startOrContinueReconnect(
                        reason = "L2CAP write stalled ${writeMs}ms",
                        notifySession = true,
                        forceScan = false,
                    )
                    return
                }
            }
        } catch (e: IOException) {
            if (isL2capCurrent(generation)) {
                Log.w(TAG, "RADIO l2cap tx ended: ${e.message}")
                startOrContinueReconnect(
                    reason = "Host disconnected",
                    notifySession = true,
                    forceScan = false,
                )
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
        if (nowMs - lastFrameAtMs <= CAPTURE_STALL_RESTART_MS) return lastFrameAtMs

        Log.w(TAG, "AUDIO capture starved — restarting epoch=$epoch")
        if (restartCapture(epoch)) return SystemClock.elapsedRealtime()

        startOrContinueReconnect(
            reason = "Audio capture stalled",
            notifySession = true,
            forceScan = false,
        )
        return null
    }

    // Smaller packet rate on a radio-sharing BT route (landmine #1): bundle
    // consecutive frames into one write rather than dropping them — dropping
    // decimates speech into silence. Native returns the freshest contiguous run,
    // so the peer plays the bundle gap-free.
    private fun bundleFramesForRoute(): Int {
        if (VoiceAudioRoute.isCommunicationRouteDegraded()) return DEGRADED_BUNDLE_FRAMES
        return 1
    }

    // One socket write carries a whole bundle of consecutive frames; returns how
    // long write()+flush() blocked so the meter can spot send backpressure.
    // IOException propagates to the writer loop and ends the link.
    private fun writeBundle(
        output: OutputStream,
        bundle: ByteArray,
    ): Long {
        val startMs = SystemClock.elapsedRealtime()
        output.write(bundle)
        output.flush()
        return SystemClock.elapsedRealtime() - startMs
    }

    // Drain frames until the host drops or we tear down. Native owns decode,
    // epoch/SeqFilter gating, jitter buffering, and Oboe output (#23).
    private fun readRemoteFrames(
        generation: Int,
        socket: BluetoothSocket,
        epoch: Long,
    ) {
        val input = DataInputStream(socket.inputStream)
        val buf = ByteArray(Proto.VOICE_FRAME_BYTES)
        val rxMeter = ThroughputMeter(TX_NET_WINDOW_MS) { SystemClock.elapsedRealtime() }
        val rxHealth = RxDeliveryHealth { SystemClock.elapsedRealtime() }
        var received = 0L
        var accepted = 0L
        try {
            while (isL2capCurrent(generation)) {
                val readStartMs = SystemClock.elapsedRealtime()
                input.readFully(buf)
                val readMs = SystemClock.elapsedRealtime() - readStartMs
                if (!isL2capCurrent(generation)) return
                received += 1
                rxMeter.onSample(1, buf.size, readMs)?.let { Log.i(TAG, "RXNET $it") }
                rxHealth.onFrame()?.let {
                    Log.w(
                        TAG,
                        "RADIO l2cap rx starved epoch=$epoch frames=${it.frames} " +
                            "windowMs=${it.elapsedMs} windows=${it.starvedWindows}",
                    )
                    startOrContinueReconnect(
                        reason = "L2CAP rx starved",
                        notifySession = true,
                        forceScan = false,
                    )
                    return
                }
                if (NativeCore.pushHostFrame(buf)) {
                    accepted += 1
                    reportAcceptedFrame(accepted)
                    continue
                }
                Log.w(TAG, "RADIO l2cap rx dropped frame #$received")
            }
        } catch (e: IOException) {
            if (isL2capCurrent(generation)) Log.w(TAG, "RADIO l2cap rx ended: ${e.message}")
        } finally {
            closeAndDetachSocket(socket)
        }
        if (!isL2capCurrent(generation)) return
        Log.w(TAG, "RADIO l2cap host closed — link lost")
        startOrContinueReconnect(
            reason = "Host disconnected",
            notifySession = true,
            forceScan = false,
        )
    }

    private fun reportAcceptedFrame(accepted: Long) {
        if (!running || torn) return
        if (accepted > 1L) return
        Log.i(TAG, "RADIO l2cap rx accepted first voice frame")
        emitStatus("Voice link up — first remote audio frame")
    }

    private fun sleepLadder(delayMs: Long): Boolean {
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return running && !torn
    }

    private fun closeL2cap(clearHostDevice: Boolean) {
        val socket =
            synchronized(l2capLock) {
                l2capGeneration.incrementAndGet()
                val closingSocket = clientSocket
                clientSocket = null
                if (clearHostDevice) hostDevice = null
                closingSocket
            }
        closeSocket(socket)
    }

    private fun publishSocketIfCurrent(
        generation: Int,
        socket: BluetoothSocket,
    ): Boolean =
        synchronized(l2capLock) {
            if (!isL2capCurrent(generation)) return@synchronized false
            clientSocket = socket
            true
        }

    private fun closeAndDetachSocket(socket: BluetoothSocket?) {
        if (socket == null) return
        synchronized(l2capLock) {
            if (clientSocket === socket) clientSocket = null
        }
        closeSocket(socket)
    }

    private fun isL2capCurrent(generation: Int): Boolean = running && !torn && generation == l2capGeneration.get()

    private fun closeSocket(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO l2cap close: ${e.message}")
        }
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val device = result?.device ?: return
                if (torn || !running || !scanning) return
                scanning = false // got our host — stop scanning and connect
                scanGeneration.incrementAndGet()
                Log.i(TAG, "RADIO scan match ${device.address}")
                emitFound(device.address, "Found host ${device.address} — connecting…")
                stopCurrentScan()
                if (connectToHost(device)) return
                recoverSetupFailure("GATT connect failed to start", forceScan = true)
            }

            override fun onScanFailed(errorCode: Int) {
                if (torn || !running) return
                Log.e(TAG, "RADIO scan onScanFailed code=$errorCode")
                if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                    scheduleScanCooldown(scanCooldownAfterFailure())
                    return
                }
                scheduleScanRetry(SCAN_STATUS, SCAN_RETRY_MS)
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt?,
                status: Int,
                newState: Int,
            ) {
                if (torn) return
                Log.i(TAG, "RADIO gatt conn status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val currentGatt = gatt
                    if (g != null && currentGatt != null && currentGatt !== g) return
                    if (g != null && currentGatt == null && isReconnecting()) return
                    startOrContinueReconnect(
                        reason = "Host disconnected (status $status)",
                        notifySession = hasLinkedEpoch(),
                        forceScan = cachedHostFailuresReached,
                    )
                    return
                }
                if (newState != BluetoothProfile.STATE_CONNECTED) return

                val connectedGatt = g ?: return recoverSetupFailure("GATT connected without a handle")
                if (gatt !== connectedGatt) return
                emitStatus("Connected — negotiating link…")
                // Central drives connection params: HIGH priority (landmine
                // #1) + the 517 MTU, before service discovery. HIGH is not
                // sticky — Android reverts the interval to BALANCED after the
                // initial burst — so re-assert it on a timer for the link's life.
                if (!connectedGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
                    Log.w(TAG, "RADIO gatt requestConnectionPriority returned false")
                }
                scheduleConnPriorityRefresh(connectedGatt)
                if (!connectedGatt.requestMtu(MTU)) {
                    Log.w(TAG, "RADIO gatt requestMtu returned false; discovering services")
                    discoverServices(connectedGatt)
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt?,
                mtu: Int,
                status: Int,
            ) {
                if (torn) return
                Log.i(TAG, "RADIO gatt mtu=$mtu status=$status")
                val connectedGatt = g ?: return recoverSetupFailure("MTU changed without a GATT handle")
                if (gatt !== connectedGatt) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "RADIO gatt mtu request failed status=$status; discovering services")
                }
                discoverServices(connectedGatt)
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt?,
                status: Int,
            ) {
                if (torn) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recoverSetupFailure("Service discovery failed (status $status)")
                    return
                }
                val characteristic = g?.getService(serviceUuid)?.getCharacteristic(psmCharUuid)
                if (characteristic == null) {
                    Log.w(TAG, "RADIO gatt PSM characteristic not found status=$status")
                    recoverSetupFailure("Host service/characteristic not found", forceScan = true)
                    return
                }
                if (!g.readCharacteristic(characteristic)) {
                    recoverSetupFailure("PSM read failed to start")
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handlePsmRead(characteristic.uuid, value, status)
            }

            // Deprecated 3-arg overload for API 31/32 devices.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int,
            ) {
                handlePsmRead(characteristic?.uuid, characteristic?.value, status)
            }
        }

    private fun discoverServices(gatt: BluetoothGatt) {
        emitStatus("Discovering services…")
        if (!gatt.discoverServices()) {
            recoverSetupFailure("Service discovery failed to start")
        }
    }

    // Re-assert HIGH connection priority on a timer (landmine #1): the request is
    // not sticky — Android relaxes the interval back toward BALANCED after the
    // initial transfer, which on the rig coincides with voice goodput collapsing
    // a few seconds in. Self-stops when torn down or superseded by a new gatt.
    private fun scheduleConnPriorityRefresh(g: BluetoothGatt) {
        mainHandler.postDelayed(
            {
                if (torn || !running || gatt !== g) return@postDelayed
                val ok = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                Log.i(TAG, "RADIO reassert CONNECTION_PRIORITY_HIGH ok=$ok")
                scheduleConnPriorityRefresh(g)
            },
            CONN_PRIORITY_REFRESH_MS,
        )
    }

    private fun handlePsmRead(
        uuid: UUID?,
        value: ByteArray?,
        status: Int,
    ) {
        if (torn) return
        val params = parseHostLinkParams(value)
        if (uuid != psmCharUuid) {
            recoverSetupFailure("Unexpected GATT characteristic", forceScan = true)
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recoverSetupFailure("PSM read failed (status $status)")
            return
        }
        if (params == null) {
            recoverSetupFailure("Invalid host PSM", forceScan = true)
            return
        }
        Log.i(TAG, "RADIO gatt read PSM=${params.psm} wireEpoch=${params.wireEpoch} status=$status")
        emitStatus("Host PSM ${params.psm} — opening voice link…")
        openL2cap(params)
    }

    private fun emitLinked(
        peer: String,
        params: HostLinkParams,
    ) {
        onEvent(RadioEvent.Linked(peer = peer, psm = params.psm, wireEpoch = params.wireEpoch))
    }

    private fun emitFound(
        peer: String,
        text: String,
    ) {
        onEvent(RadioEvent.Found(peer = peer, text = text))
    }

    private fun emitLinkLost(reason: String) {
        onEvent(RadioEvent.LinkLost(reason))
    }

    private fun emitFailed(reason: String) {
        onEvent(RadioEvent.Failed(reason))
    }

    private fun emitStatus(text: String) {
        onEvent(RadioEvent.Status(text))
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val MTU = 517
        private const val CONN_PRIORITY_REFRESH_MS = 4_000L

        private const val MAX_WIRE_EPOCH = 4_294_967_295L // 2^32 - 1: max u32 wire epoch
        private const val HOST_LINK_PARAMS_BYTES = 2 * Integer.BYTES
        private const val EPOCH_WAIT_MS = 100L
        private const val TX_FRAME_TIMEOUT_MS = 100
        private const val DEGRADED_BUNDLE_FRAMES = 2
        private const val TX_NET_WINDOW_MS = 2_000L
        private const val CAPTURE_STALL_RESTART_MS = 2_000L
        private const val SCAN_STATUS = "Scanning for host…"
        private const val SCAN_RETRY_MS = 750L
        private const val SCAN_REFRESH_MS = 10_000L
        private const val SCAN_REFRESH_RESTART_MS = 150L
        private const val MIN_SCAN_START_INTERVAL_MS = 1_000L
        private const val COOLDOWN_STATUS_TICK_MS = 100L
        private const val SCAN_START_WINDOW_MS = 30_000L
        private const val MAX_SCAN_STARTS_PER_WINDOW = 4
        private const val CACHED_RETRIES_BEFORE_SCAN = 2
        private const val RECONNECT_TIMEOUT_MS = 30_000L
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
        private val scanStartBudget =
            ScanStartBudget(
                windowMs = SCAN_START_WINDOW_MS,
                maxStarts = MAX_SCAN_STARTS_PER_WINDOW,
                minIntervalMs = MIN_SCAN_START_INTERVAL_MS,
                nowMs = { SystemClock.elapsedRealtime() },
            )
    }
}
