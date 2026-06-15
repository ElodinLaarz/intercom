package com.elodin.intercom.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.elodin.intercom.proto.Proto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

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
class GuestRadio(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val serviceUuid: UUID = UUID.fromString(Proto.SERVICE_UUID)
    private val psmCharUuid: UUID = UUID.fromString(Proto.PSM_CHAR_UUID)

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var running = false

    // Set once in stop(); a torn-down instance ignores its own late scan/GATT
    // callbacks so a buffered DISCONNECT can't clobber the next session's status
    // (rule 4 — destruction is the reset, no stale state across sessions).
    @Volatile
    private var torn = false

    fun start() {
        if (running) return
        val manager = context.getSystemService(BluetoothManager::class.java)
        val ble = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (ble == null) {
            Log.e(TAG, "RADIO ERROR bluetooth off or LE scan unsupported")
            onStatus("Can't scan — turn Bluetooth on")
            return
        }
        running = true
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
        Log.i(TAG, "RADIO scan start (MSD filter)")
        onStatus("Scanning for host…")
        ble.startScan(listOf(filter.build()), settings.build(), scanCallback)
    }

    fun stop() {
        torn = true
        running = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop scan: ${e.message}")
        }
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "RADIO stop gatt: ${e.message}")
        }
        scanner = null
        gatt = null
        Log.i(TAG, "RADIO guest stopped")
        onStatus("Stopped")
    }

    private fun connectToHost(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun parsePsm(value: ByteArray?): Int {
        if (value == null || value.size < Integer.BYTES) return -1
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val device = result?.device ?: return
                if (torn || !running) return
                running = false // got our host — stop scanning and connect
                Log.i(TAG, "RADIO scan match ${device.address}")
                onStatus("Found host ${device.address} — connecting…")
                try {
                    scanner?.stopScan(this)
                } catch (e: SecurityException) {
                    Log.w(TAG, "RADIO stopScan: ${e.message}")
                }
                connectToHost(device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (torn) return
                Log.e(TAG, "RADIO scan onScanFailed code=$errorCode")
                onStatus("Scan failed (code $errorCode)")
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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onStatus("Connected — negotiating link…")
                    // Central drives connection params: HIGH priority (landmine
                    // #1) + the 517 MTU, before service discovery.
                    g?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g?.requestMtu(MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onStatus("Disconnected (status $status)")
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt?,
                mtu: Int,
                status: Int,
            ) {
                if (torn) return
                Log.i(TAG, "RADIO gatt mtu=$mtu status=$status")
                onStatus("MTU $mtu — discovering services…")
                g?.discoverServices()
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt?,
                status: Int,
            ) {
                if (torn) return
                val characteristic = g?.getService(serviceUuid)?.getCharacteristic(psmCharUuid)
                if (characteristic == null) {
                    Log.w(TAG, "RADIO gatt PSM characteristic not found status=$status")
                    onStatus("Host service/characteristic not found")
                    return
                }
                g.readCharacteristic(characteristic)
            }

            // Deprecated 3-arg overload: the API 33+ 4-arg version defaults to
            // calling this, so it fires on every supported device (minSdk 31).
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int,
            ) {
                if (torn) return
                val psm = parsePsm(characteristic?.value)
                Log.i(TAG, "RADIO gatt read PSM=$psm status=$status")
                onStatus("Linked — host PSM $psm (L2CAP CoC is #20)")
            }
        }

    companion object {
        private const val TAG = "INTERCOM"
        private const val MTU = 517
    }
}
