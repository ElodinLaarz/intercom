package com.elodin.intercom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.RadioController

/**
 * M1 tracer-bullet debug screen: two buttons start each role (one role per
 * phone). Host (#18) advertises + GATT + L2CAP; Guest (#19) scans, connects, and
 * reads the host's L2CAP PSM. Each is a start/stop toggle and they're mutually
 * exclusive; [RadioController] holds the live state the status line renders. See
 * M1_PLAN.md §3.
 */
class MainActivity : ComponentActivity() {
    private val radio by lazy { RadioController(applicationContext) }

    private val requestHostPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (!grants.values.all { it }) {
                Log.w(TAG, "host perms denied")
                return@registerForActivityResult
            }
            radio.startHost()
        }

    private val requestGuestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (!grants.values.all { it }) {
                Log.w(TAG, "scan perms denied")
                return@registerForActivityResult
            }
            radio.startGuest()
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
                        status = radio.status,
                        hosting = radio.hosting,
                        guesting = radio.guesting,
                        onHost = { onHostButton() },
                        onGuest = { onGuestButton() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        radio.close()
        super.onDestroy()
    }

    // Host and Guest toggle and are mutually exclusive (one role per phone).
    private fun onHostButton() {
        if (radio.hosting) {
            radio.stopHost()
            return
        }
        ensurePermissions(
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
            requestHostPermissions,
        ) { radio.startHost() }
    }

    private fun onGuestButton() {
        if (radio.guesting) {
            radio.stopGuest()
            return
        }
        ensurePermissions(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            requestGuestPermissions,
        ) { radio.startGuest() }
    }

    private fun ensurePermissions(
        perms: Array<String>,
        launcher: ActivityResultLauncher<Array<String>>,
        onGranted: () -> Unit,
    ) {
        val granted =
            perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        if (granted) {
            onGranted()
            return
        }
        launcher.launch(perms)
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
    guesting: Boolean,
    onHost: () -> Unit,
    onGuest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Intercom", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "M1 tracer — one role per phone",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(48.dp))
        Row {
            Button(onClick = onHost) { Text(if (hosting) "Stop" else "Host") }
            Spacer(Modifier.width(24.dp))
            OutlinedButton(onClick = onGuest) { Text(if (guesting) "Stop" else "Guest") }
        }
        Spacer(Modifier.height(24.dp))
        Text(text = status, style = MaterialTheme.typography.bodySmall)
    }
}
