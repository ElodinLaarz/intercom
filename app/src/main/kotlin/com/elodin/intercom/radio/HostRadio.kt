package com.elodin.intercom.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import com.elodin.intercom.NativeCore
import com.elodin.intercom.audio.VoiceAudioRoute
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.session.RadioEndpoint
import com.elodin.intercom.session.RadioEvent
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Host side of the M1 link (issue #18, M1_PLAN §3 step 3): BLE advertise with a
 * minimal manufacturer-data payload (landmine #13 — the moto overflows a full
 * adv packet), a GATT server that publishes the L2CAP PSM in a characteristic,
 * and the insecure L2CAP listener that *allocates* that PSM (§4.5: insecure for
 * M1). The guest scans (#19), reads the PSM, and opens the CoC (#20).
 *
 * Single owner of the host radio objects; constructed per host session and torn
 * down in [stop] — no reset() (rule 4). The session/epoch machinery (#21) will
 * own an instance; for now MainActivity drives it from the Host button. The
 * caller guarantees BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT are granted, so the
 * BLE calls are annotated [SuppressLint].
 *
 * [onEvent] receives lifecycle facts and short human-readable status lines; it
 * may be invoked from any thread (advertise/GATT/accept callbacks), so the
 * caller marshals to the session dispatcher.
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions")
internal class HostRadio(
    private val context: Context,
    private val onEvent: (RadioEvent) -> Unit,
) : RadioEndpoint {
    private val serviceUuid: UUID = UUID.fromString(Proto.SERVICE_UUID)
    private val psmCharUuid: UUID = UUID.fromString(Proto.PSM_CHAR_UUID)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var clientSocket: BluetoothSocket? = null
    private val l2capLock = Any()
    private val epochLock = Object()
    private var linkedEpoch: Long? = null
    private var psmBytes: ByteArray = ByteArray(0)
    private var psm: Int = 0
    private val connectedGuests = CopyOnWriteArraySet<BluetoothDevice>()

    @Volatile
    private var running = false

    @Volatile
    private var torn = false

    /** Open the L2CAP listener, publish its PSM over GATT, and start advertising. */
    override fun start(): Boolean {
        if (running) return true
        torn = false
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "RADIO ERROR bluetooth adapter null or disabled")
            emitFailed("Bluetooth is off — enable it and retry")
            return false
        }

        running = true
        val nextPsm = listenForL2cap(adapter) ?: return false
        psm = nextPsm
        val buf = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(psm)
        psmBytes = buf.array()
        Log.i(TAG, "RADIO l2cap listening psm=$psm")
        emitStatus("Starting host — PSM $psm")
        if (startGattServer(manager) && startAdvertising(adapter)) return true

        emitFailed("Host radio failed to start")
        stop(reportStopped = false)
        return false
    }

    /** Tear everything down. Idempotent. */
    override fun stop() {
        stop(reportStopped = true)
    }

    override fun beginEpoch(epochId: Long) {
        if (!running || torn) return
        val started = startPlayout(epochId)
        synchronized(epochLock) {
            if (started && running && !torn) linkedEpoch = epochId
            epochLock.notifyAll()
        }
        if (!started) emitFailed("Audio playout failed")
    }

    private fun stop(reportStopped: Boolean) {
        torn = true
        running = false
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop advertise: ${e.message}")
        }
        // A GATT server can't drop a *client-initiated* connection with
        // cancelConnection alone — "adopt" it via connect() first, then cancel,
        // so the guest sees the disconnect immediately instead of waiting out the
        // BLE supervision timeout (rule 4: clean teardown).
        connectedGuests.forEach { device ->
            try {
                gattServer?.connect(device, false)
                gattServer?.cancelConnection(device)
            } catch (e: SecurityException) {
                Log.w(TAG, "RADIO cancel gatt connection: ${e.message}")
            }
        }
        connectedGuests.clear()
        stopPlayout()
        try {
            gattServer?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop gatt: ${e.message}")
        }
        // Closing the accepted CoC is what the guest actually detects: its read
        // returns EOF (GuestRadio.watchForClose). The guest's own open CoC keeps
        // the shared ACL up, so GATT-cancel above never reaches it (rig, #20).
        closeClientSocket()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO stop l2cap: ${e.message}")
        }
        advertiser = null
        gattServer = null
        clientSocket = null
        serverSocket = null
        Log.i(TAG, "RADIO host stopped")
        if (reportStopped) emitStatus("Stopped")
    }

    private fun startPlayout(epochId: Long): Boolean {
        if (epochId !in 0..MAX_WIRE_EPOCH) return false
        VoiceAudioRoute.enterCommunication(context)
        if (NativeCore.startHostPlayout(epochId)) return true

        VoiceAudioRoute.leaveCommunication(context)
        return false
    }

    private fun stopPlayout() {
        NativeCore.stopHostPlayout()
        VoiceAudioRoute.leaveCommunication(context)
        synchronized(epochLock) {
            linkedEpoch = null
            epochLock.notifyAll()
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

    private fun openL2capListener(adapter: BluetoothAdapter): Int {
        val socket = adapter.listenUsingInsecureL2capChannel()
        serverSocket = socket
        thread(isDaemon = true, name = "l2cap-accept") { acceptLoop(socket) }
        return socket.psm
    }

    private fun listenForL2cap(adapter: BluetoothAdapter): Int? =
        try {
            openL2capListener(adapter)
        } catch (e: IOException) {
            Log.e(TAG, "RADIO l2cap listen failed: ${e.message}")
            emitFailed("L2CAP listen failed")
            stop(reportStopped = false)
            null
        }

    private fun acceptLoop(socket: BluetoothServerSocket) {
        try {
            while (running) {
                receiveFrom(socket.accept())
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "RADIO l2cap accept ended: ${e.message}")
        }
    }

    private fun receiveFrom(client: BluetoothSocket) {
        if (!publishClientSocket(client)) {
            closeSocket(client, "l2cap stale client")
            return
        }
        val address = client.remoteDevice?.address ?: UNKNOWN_PEER
        Log.i(TAG, "RADIO l2cap accepted $address")
        emitLinked(address)
        val epoch = awaitLinkedEpoch()
        if (epoch == null) {
            detachClientSocket(client)
            return
        }
        Log.i(TAG, "RADIO l2cap rx audio epoch=$epoch peer=$address")
        emitStatus("Voice link up — receiving from $address")
        readFrames(client)
        detachClientSocket(client)
        if (running && !torn) emitLinkLost("Guest disconnected")
    }

    // Drain frames until the guest drops or we tear down. Native owns decode,
    // epoch/SeqFilter gating, jitter buffering, and Oboe output (#23).
    private fun readFrames(client: BluetoothSocket) {
        val input = DataInputStream(client.inputStream)
        val buf = ByteArray(Proto.VOICE_FRAME_BYTES)
        var received = 0L
        var accepted = 0L
        try {
            while (running && !torn) {
                input.readFully(buf)
                if (!running || torn) return
                received += 1
                if (NativeCore.pushHostFrame(buf)) {
                    accepted += 1
                    reportAcceptedFrame(accepted)
                    continue
                }
                Log.w(TAG, "RADIO l2cap rx dropped frame #$received")
            }
        } catch (e: IOException) {
            if (running && !torn) Log.w(TAG, "RADIO l2cap rx ended: ${e.message}")
        }
    }

    private fun reportAcceptedFrame(accepted: Long) {
        if (!running || torn) return
        if (accepted > 1L) return
        Log.i(TAG, "RADIO l2cap rx accepted first voice frame")
        emitStatus("Voice link up — first audio frame")
    }

    private fun publishClientSocket(client: BluetoothSocket): Boolean =
        synchronized(l2capLock) {
            if (!running || torn) return@synchronized false
            clientSocket = client
            true
        }

    private fun closeClientSocket() {
        val socket =
            synchronized(l2capLock) {
                val closingSocket = clientSocket
                clientSocket = null
                closingSocket
            }
        closeSocket(socket, "l2cap client")
    }

    private fun detachClientSocket(client: BluetoothSocket) {
        synchronized(l2capLock) {
            if (clientSocket === client) clientSocket = null
        }
        closeSocket(client, "l2cap client")
    }

    private fun closeSocket(
        socket: BluetoothSocket?,
        label: String,
    ) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO close $label: ${e.message}")
        }
    }

    private fun startGattServer(manager: BluetoothManager): Boolean {
        // PERMISSION_READ is insecure — bonded/encrypted is an M3 gate item (§4.5).
        val characteristic =
            BluetoothGattCharacteristic(
                psmCharUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        val server = manager.openGattServer(context, gattCallback)
        if (server == null) {
            Log.e(TAG, "RADIO gatt openGattServer returned null")
            return false
        }

        gattServer = server
        if (server.addService(service)) {
            return true
        }
        Log.e(TAG, "RADIO gatt addService returned false")
        return false
    }

    private fun startAdvertising(adapter: BluetoothAdapter): Boolean {
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.e(TAG, "RADIO ERROR LE advertising unsupported")
            return false
        }
        advertiser = adv
        val settingsBuilder = AdvertiseSettings.Builder()
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        settingsBuilder.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        settingsBuilder.setConnectable(true)
        settingsBuilder.setTimeout(0)
        val settings = settingsBuilder.build()
        // Minimal payload (landmine #13): manufacturer data only — no 128-bit
        // service UUID, no device name. The guest scan filters on MSD (#2).
        val dataBuilder = AdvertiseData.Builder()
        dataBuilder.setIncludeDeviceName(false)
        dataBuilder.setIncludeTxPowerLevel(false)
        dataBuilder.addManufacturerData(
            Proto.MSD_COMPANY_ID,
            byteArrayOf(Proto.MSD_PATTERN0.toByte(), Proto.MSD_PATTERN1.toByte()),
        )
        val data = dataBuilder.build()
        return startAdvertising(adv, settings, data)
    }

    private fun startAdvertising(
        adv: BluetoothLeAdvertiser,
        settings: AdvertiseSettings,
        data: AdvertiseData,
    ): Boolean =
        try {
            adv.startAdvertising(settings, data, advertiseCallback)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "RADIO advertise permission failure: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "RADIO advertise start rejected: ${e.message}")
            false
        }

    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (torn || !running) return
                Log.i(TAG, "RADIO advertise onStartSuccess txPower=${settingsInEffect?.txPowerLevel}")
                emitAdvertising("Advertising — PSM $psm — waiting for a guest")
            }

            override fun onStartFailure(errorCode: Int) {
                if (torn) return
                Log.e(TAG, "RADIO advertise onStartFailure code=$errorCode")
                emitFailed("Advertise failed (code $errorCode)")
                stop(reportStopped = false)
            }
        }

    private val gattCallback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService?,
            ) {
                if (torn) return
                Log.i(TAG, "RADIO gatt serviceAdded status=$status uuid=${service?.uuid}")
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int,
            ) {
                if (torn) return
                Log.i(TAG, "RADIO gatt conn ${device?.address} status=$status newState=$newState")
                if (device == null) return
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedGuests.remove(device)
                    Log.i(TAG, "RADIO gatt guest disconnected ${device.address}")
                    emitAdvertising("Advertising — PSM $psm — waiting for a guest")
                    return
                }
                if (newState != BluetoothProfile.STATE_CONNECTED) return

                connectedGuests.add(device)
                emitStatus("Guest GATT connected — waiting for PSM read")
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?,
            ) {
                if (torn) return
                val ok =
                    device != null &&
                        characteristic?.uuid == psmCharUuid &&
                        offset in 0..psmBytes.size
                if (ok) {
                    Log.i(TAG, "RADIO gatt read psm by ${device?.address} offset=$offset")
                    emitStatus("Guest read PSM — waiting for L2CAP")
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        psmBytes.copyOfRange(offset, psmBytes.size),
                    )
                    return
                }
                if (device == null) {
                    Log.w(TAG, "RADIO gatt read psm with null device offset=$offset")
                    return
                }
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    offset.coerceAtLeast(0),
                    null,
                )
            }
        }

    private fun emitLinked(peer: String) {
        onEvent(RadioEvent.Linked(peer = peer, psm = psm))
    }

    private fun emitAdvertising(text: String) {
        onEvent(RadioEvent.Advertising(psm = psm, text = text))
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
        private const val UNKNOWN_PEER = "unknown"
        private const val MAX_WIRE_EPOCH = 0xFFFF_FFFFL
        private const val EPOCH_WAIT_MS = 100L
    }
}
