package com.elodin.intercom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.HostRadio

/**
 * M1 tracer-bullet debug screen: two buttons start each role. Host (#18) is
 * wired — advertise + GATT + L2CAP; Join (guest scan) lands in #19. The
 * [HomeScreen] status line reflects the live host state. See M1_PLAN.md §3.
 */
class MainActivity : ComponentActivity() {
    private var hostRadio: HostRadio? = null
    private var hostStatus by mutableStateOf("Idle — tap Host to advertise")
    private var hostActive by mutableStateOf(false)

    private val requestBlePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startHost()
            } else {
                Log.w(TAG, "BLE permissions denied — cannot host")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "STARTED version=${BuildConfig.VERSION_NAME}")
        Log.i(TAG, "NATIVE selfTest=${nativeSelfTest()}")
        Log.i(TAG, "PROTO v=${Proto.PROTOCOL_VERSION} svc=${Proto.SERVICE_UUID}")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        status = hostStatus,
                        hosting = hostActive,
                        onHost = { onHostButton() },
                        onJoin = {
                            Log.i(TAG, "TAP join (stub — guest scan is M1.4/#19)")
                            hostStatus = "Join isn't wired yet — guest scan is #19"
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        hostRadio?.stop()
        super.onDestroy()
    }

    private fun ensureHostPermissions() {
        val perms =
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        val granted =
            perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        if (granted) startHost() else requestBlePermissions.launch(perms)
    }

    // The Host button toggles: idle -> advertise, active -> stop. Every tap
    // changes state and the status line, so the button always responds.
    private fun onHostButton() {
        if (hostActive) stopHost() else ensureHostPermissions()
    }

    private fun startHost() {
        Log.i(TAG, "TAP host — starting advertise + GATT + L2CAP (M1.3/#18)")
        val radio = HostRadio(applicationContext, ::onHostStatus)
        hostRadio = radio
        hostActive = true
        radio.start()
    }

    private fun stopHost() {
        Log.i(TAG, "TAP host — stopping")
        hostRadio?.stop()
        hostRadio = null
        hostActive = false
    }

    private fun onHostStatus(message: String) {
        runOnUiThread { hostStatus = message }
    }

    // selfTest can throw UnsatisfiedLinkError if the .so failed to load — log it
    // rather than crash, so the smoke harness still sees the NATIVE line.
    @Suppress("TooGenericExceptionCaught")
    private fun nativeSelfTest(): String =
        try {
            "0x%X".format(NativeCore.selfTest())
        } catch (t: Throwable) {
            "ERR ${t.message}"
        }

    companion object {
        const val TAG = "INTERCOM"
    }
}

@Composable
private fun HomeScreen(
    status: String,
    hosting: Boolean,
    onHost: () -> Unit,
    onJoin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Intercom", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "M1 tracer — Host advertises; Join scans (#19)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(48.dp))
        Row {
            Button(onClick = onHost) { Text(if (hosting) "Stop" else "Host") }
            Spacer(Modifier.width(24.dp))
            OutlinedButton(onClick = onJoin) { Text("Join") }
        }
        Spacer(Modifier.height(24.dp))
        Text(text = status, style = MaterialTheme.typography.bodySmall)
    }
}
