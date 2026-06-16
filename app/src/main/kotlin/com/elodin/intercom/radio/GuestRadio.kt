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
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.proto.VoiceFrame
import java.io.IOException
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
 * so the BLE calls are annotated [SuppressLint]. [onStatus] may fire from any
 * thread (scan/GATT callbacks).
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions")
internal class GuestRadio(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onStopped: (GuestRadio) -> Unit = {},
) : RadioEndpoint {
    private val serviceUuid: UUID = UUID.fromString(Proto.SERVICE_UUID)
    private val psmCharUuid: UUID = UUID.fromString(Proto.PSM_CHAR_UUID)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanGeneration = AtomicInteger(0)

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var hostDevice: BluetoothDevice? = null

    @Volatile
    private var clientSocket: BluetoothSocket? = null
    private val l2capGeneration = AtomicInteger(0)
    private val l2capLock = Any()

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
        running = true
        val started = beginScan("Scanning for host…")
        if (!started) running = false
        return started
    }

    override fun stop() {
        stop(reportStopped = true)
    }

    private fun stop(reportStopped: Boolean) {
        torn = true
        running = false
        scanning = false
        scanGeneration.incrementAndGet()
        stopCurrentScan()
        closeL2cap()
        closeGatt(disconnect = true)
        scanner = null
        Log.i(TAG, "RADIO guest stopped")
        if (reportStopped) onStatus("Stopped")
        onStopped(this)
    }

    private fun connectToHost(device: BluetoothDevice) {
        hostDevice = device
        val nextGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (nextGatt == null) {
            fail("GATT connect failed to start")
            return
        }
        gatt = nextGatt
    }

    private fun parsePsm(value: ByteArray?): Int {
        if (value == null || value.size < Integer.BYTES) return -1
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun beginScan(
        statusMessage: String,
        keepTrying: Boolean = false,
    ): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val ble = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (ble == null) {
            Log.e(TAG, "RADIO ERROR bluetooth off or LE scan unsupported")
            onStatus("Can't scan — turn Bluetooth on")
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
        onStatus(statusMessage)
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
            onStatus("Scan failed to start")
            stop(reportStopped = false)
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "RADIO scan start rejected: ${e.message}")
            onStatus("Scan failed to start")
            false
        }

    private fun fail(message: String) {
        if (torn) return
        Log.e(TAG, "RADIO ERROR $message")
        onStatus(message)
        stop(reportStopped = false)
    }

    private fun retryScanAfterGatt(message: String) {
        if (torn) return
        Log.w(TAG, "RADIO $message")
        closeL2cap()
        closeGatt(disconnect = false)
        if (running) {
            scheduleScanRetry(RECONNECT_STATUS, GATT_RESCAN_DELAY_MS)
        }
    }

    private fun scheduleScanRetry(
        statusMessage: String,
        delayMs: Long,
    ) {
        val generation = prepareScanRetry()
        onStatus(statusMessage)
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
        onStatus(scanCooldownStatus(remainingMs))
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

    private fun openL2cap(psm: Int) {
        val device = hostDevice
        if (device == null) {
            fail("No host device for L2CAP")
            return
        }
        val generation =
            synchronized(l2capLock) {
                if (!running || torn) return
                l2capGeneration.incrementAndGet()
            }
        thread(isDaemon = true, name = "l2cap-connect") { connectLoop(generation, device, psm) }
    }

    // Open the CoC to the host's PSM and push one self-contained voice frame
    // (guest → host — the M1 direction). Retries on the bounded backoff ladder
    // (landmine #8); the real monotonic epoch arrives with the session (#21).
    private fun connectLoop(
        generation: Int,
        device: BluetoothDevice,
        psm: Int,
    ) {
        val ladder = BackoffLadder()
        var attempt = 0
        while (isL2capCurrent(generation)) {
            val socket = openAndSend(generation, device, psm)
            if (socket != null) {
                watchForClose(generation, socket)
                return
            }
            val delayMs = ladder.delayBeforeRetryMs(attempt) ?: break
            if (!sleepLadder(delayMs)) return
            attempt += 1
        }
        if (isL2capCurrent(generation)) fail("L2CAP connect failed")
    }

    private fun openAndSend(
        generation: Int,
        device: BluetoothDevice,
        psm: Int,
    ): BluetoothSocket? {
        var socket: BluetoothSocket? = null
        var connectedSocket: BluetoothSocket? = null
        try {
            socket = device.createInsecureL2capChannel(psm)
            if (publishSocketIfCurrent(generation, socket)) {
                socket.connect()
                if (sendFirstFrameIfCurrent(generation, socket)) {
                    connectedSocket = socket
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "RADIO l2cap connect/tx failed: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "RADIO l2cap permission failure: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "RADIO l2cap rejected PSM $psm: ${e.message}")
        }
        if (connectedSocket == null) {
            closeAndDetachSocket(socket)
        }
        return connectedSocket
    }

    private fun sendFirstFrameIfCurrent(
        generation: Int,
        socket: BluetoothSocket,
    ): Boolean {
        if (!isL2capCurrent(generation)) return false
        val frame =
            VoiceFrame(
                epoch = L2CAP_TEST_EPOCH,
                seq = 0,
                predSample = 0,
                stepIndex = 0,
                adpcm = ByteArray(Proto.VOICE_ADPCM_BYTES),
            )
        socket.outputStream.write(frame.encode())
        socket.outputStream.flush()
        if (!isL2capCurrent(generation)) return false
        Log.i(TAG, "RADIO l2cap tx frame epoch=${frame.epoch} seq=${frame.seq}")
        onStatus("Voice link up — sent first frame")
        return true
    }

    // One-way M1: the guest never receives audio, but it must still notice the
    // host vanishing. Symmetric to the host's readFrames — block on the CoC and
    // treat EOF/close as the host leaving. GATT-disconnect can't be relied on:
    // the guest's own open CoC keeps the shared ACL up, so a host-side GATT
    // cancel never reaches it (rig finding, #20). M2's duplex receive path
    // replaces this watch with the real decode pipeline.
    private fun watchForClose(
        generation: Int,
        socket: BluetoothSocket,
    ) {
        val input = socket.inputStream
        val sink = ByteArray(Proto.VOICE_FRAME_BYTES)
        try {
            while (isL2capCurrent(generation)) {
                if (input.read(sink) < 0) break
            }
        } catch (e: IOException) {
            Log.w(TAG, "RADIO l2cap watch ended: ${e.message}")
        }
        if (!isL2capCurrent(generation)) return
        Log.w(TAG, "RADIO l2cap host closed — link lost")
        mainHandler.post { onHostGone(generation) }
    }

    private fun onHostGone(generation: Int) {
        if (!isL2capCurrent(generation)) return
        retryScanAfterGatt("Host disconnected")
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

    private fun closeL2cap() {
        val socket =
            synchronized(l2capLock) {
                l2capGeneration.incrementAndGet()
                val closingSocket = clientSocket
                clientSocket = null
                hostDevice = null
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
                onStatus("Found host ${device.address} — connecting…")
                stopCurrentScan()
                connectToHost(device)
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
                    retryScanAfterGatt("Host disconnected (status $status)")
                    return
                }
                if (newState != BluetoothProfile.STATE_CONNECTED) return

                val connectedGatt = g ?: return fail("GATT connected without a handle")
                onStatus("Connected — negotiating link…")
                // Central drives connection params: HIGH priority (landmine
                // #1) + the 517 MTU, before service discovery.
                if (!connectedGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
                    Log.w(TAG, "RADIO gatt requestConnectionPriority returned false")
                }
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
                val connectedGatt = g ?: return fail("MTU changed without a GATT handle")
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
                    fail("Service discovery failed (status $status)")
                    return
                }
                val characteristic = g?.getService(serviceUuid)?.getCharacteristic(psmCharUuid)
                if (characteristic == null) {
                    Log.w(TAG, "RADIO gatt PSM characteristic not found status=$status")
                    fail("Host service/characteristic not found")
                    return
                }
                if (!g.readCharacteristic(characteristic)) {
                    fail("PSM read failed to start")
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
        onStatus("Discovering services…")
        if (!gatt.discoverServices()) {
            fail("Service discovery failed to start")
        }
    }

    private fun handlePsmRead(
        uuid: UUID?,
        value: ByteArray?,
        status: Int,
    ) {
        if (torn) return
        val psm = parsePsm(value)
        if (uuid != psmCharUuid) {
            fail("Unexpected GATT characteristic")
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("PSM read failed (status $status)")
            return
        }
        if (psm <= 0) {
            fail("Invalid host PSM")
            return
        }
        Log.i(TAG, "RADIO gatt read PSM=$psm status=$status")
        onStatus("Host PSM $psm — opening voice link…")
        openL2cap(psm)
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val MTU = 517

        // Placeholder epoch until the session machine (#21) owns the monotonic
        // connection epoch; the host logs it but does not gate on it yet (#23).
        private const val L2CAP_TEST_EPOCH = 1
        private const val SCAN_STATUS = "Scanning for host…"
        private const val RECONNECT_STATUS = "Host disconnected — rescanning…"
        private const val GATT_RESCAN_DELAY_MS = 350L
        private const val SCAN_RETRY_MS = 750L
        private const val SCAN_REFRESH_MS = 10_000L
        private const val SCAN_REFRESH_RESTART_MS = 150L
        private const val MIN_SCAN_START_INTERVAL_MS = 1_000L
        private const val COOLDOWN_STATUS_TICK_MS = 100L
        private const val SCAN_START_WINDOW_MS = 30_000L
        private const val MAX_SCAN_STARTS_PER_WINDOW = 4
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
