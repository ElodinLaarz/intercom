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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import com.elodin.intercom.proto.Proto
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
 * [onStatus] receives short human-readable status lines for the debug UI; it may
 * be invoked from any thread (advertise/GATT/accept callbacks), so the caller
 * marshals to the main thread.
 */
@SuppressLint("MissingPermission")
class HostRadio(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val serviceUuid: UUID = UUID.fromString(Proto.SERVICE_UUID)
    private val psmCharUuid: UUID = UUID.fromString(Proto.PSM_CHAR_UUID)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var psmBytes: ByteArray = ByteArray(0)
    private var psm: Int = 0
    private val connectedGuests = CopyOnWriteArraySet<BluetoothDevice>()

    @Volatile
    private var running = false

    /** Open the L2CAP listener, publish its PSM over GATT, and start advertising. */
    fun start() {
        if (running) return
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "RADIO ERROR bluetooth adapter null or disabled")
            onStatus("Bluetooth is off — enable it and retry")
            return
        }
        running = true
        psm = openL2capListener(adapter)
        val buf = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(psm)
        psmBytes = buf.array()
        Log.i(TAG, "RADIO l2cap listening psm=$psm")
        onStatus("Starting host — PSM $psm")
        startGattServer(manager)
        startAdvertising(adapter)
    }

    /** Tear everything down. Idempotent. */
    fun stop() {
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
            gattServer?.connect(device, false)
            gattServer?.cancelConnection(device)
        }
        connectedGuests.clear()
        try {
            gattServer?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop gatt: ${e.message}")
        }
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RADIO stop l2cap: ${e.message}")
        }
        advertiser = null
        gattServer = null
        serverSocket = null
        Log.i(TAG, "RADIO host stopped")
        onStatus("Stopped")
    }

    private fun openL2capListener(adapter: BluetoothAdapter): Int {
        val socket = adapter.listenUsingInsecureL2capChannel()
        serverSocket = socket
        thread(isDaemon = true, name = "l2cap-accept") { acceptLoop(socket) }
        return socket.psm
    }

    private fun acceptLoop(socket: BluetoothServerSocket) {
        try {
            while (running) {
                val client = socket.accept()
                Log.i(TAG, "RADIO l2cap accepted ${client.remoteDevice?.address}")
                onStatus("Guest connected (L2CAP) — ${client.remoteDevice?.address}")
                // Frame I/O is #20; this PR only proves the listener accepts.
                client.close()
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "RADIO l2cap accept ended: ${e.message}")
        }
    }

    private fun startGattServer(manager: BluetoothManager) {
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
        gattServer = server
        server.addService(service)
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.e(TAG, "RADIO ERROR LE advertising unsupported")
            onStatus("LE advertising unsupported on this device")
            return
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
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "RADIO advertise onStartSuccess txPower=${settingsInEffect?.txPowerLevel}")
                onStatus("Advertising — PSM $psm — waiting for a guest")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "RADIO advertise onStartFailure code=$errorCode")
                onStatus("Advertise failed (code $errorCode)")
            }
        }

    private val gattCallback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService?,
            ) {
                Log.i(TAG, "RADIO gatt serviceAdded status=$status uuid=${service?.uuid}")
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int,
            ) {
                Log.i(TAG, "RADIO gatt conn ${device?.address} status=$status newState=$newState")
                if (device == null) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedGuests.add(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedGuests.remove(device)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?,
            ) {
                val ok = characteristic?.uuid == psmCharUuid && offset <= psmBytes.size
                if (ok) {
                    Log.i(TAG, "RADIO gatt read psm by ${device?.address} offset=$offset")
                    onStatus("Guest read PSM — ${device?.address}")
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        psmBytes.copyOfRange(offset, psmBytes.size),
                    )
                } else {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

    companion object {
        private const val TAG = "INTERCOM"
    }
}
