package com.elodin.intercom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.ConnectionStats
import com.elodin.intercom.radio.RadioController

/**
 * M1 tracer-bullet debug screen: two buttons start each role (one role per
 * phone). Host (#18) advertises + GATT + L2CAP; Guest (#19) scans, connects, and
 * reads the host's L2CAP PSM. Each is a start/stop toggle and they're mutually
 * exclusive; [RadioController] holds the live state the status line renders. See
 * M1_PLAN.md §3.
 */
class MainActivity : ComponentActivity() {
    private val model by viewModels<IntercomViewModel> {
        IntercomViewModel.Factory(applicationContext)
    }
    private val radio: RadioController get() = model.radio

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
                Log.w(TAG, "guest perms denied")
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
                        stats = radio.stats,
                        onHost = { onHostButton() },
                        onGuest = { onGuestButton() },
                        onDiag = { shareDiagSnapshot() },
                    )
                }
            }
        }
    }

    // Host and Guest toggle and are mutually exclusive (one role per phone).
    private fun onHostButton() {
        if (radio.hosting) {
            radio.stopHost()
            return
        }
        ensurePermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO,
            ),
            requestHostPermissions,
        ) { radio.startHost() }
    }

    private fun onGuestButton() {
        if (radio.guesting) {
            radio.stopGuest()
            return
        }
        ensurePermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO,
            ),
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

    private fun shareDiagSnapshot() {
        val text = radio.snapshotText(applicationContext)
        Log.i(TAG, "DIAG snapshot:\n$text")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "Intercom Diagnostics")
            }
        startActivity(Intent.createChooser(intent, "Share Diagnostic Snapshot"))
    }

    companion object {
        const val TAG = "INTERCOM"
    }
}

private class IntercomViewModel(
    context: Context,
) : ViewModel() {
    val radio = RadioController(context.applicationContext)

    override fun onCleared() {
        radio.close()
    }

    class Factory(
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(IntercomViewModel::class.java)) {
                return IntercomViewModel(context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun HomeScreen(
    status: String,
    hosting: Boolean,
    guesting: Boolean,
    stats: ConnectionStats?,
    onHost: () -> Unit,
    onGuest: () -> Unit,
    onDiag: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
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
            if (stats != null) {
                Spacer(Modifier.height(16.dp))
                StatsPanel(stats)
            }
        }
        if (hosting || guesting) {
            TextButton(
                onClick = onDiag,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Text("Diag")
            }
        }
    }
}

@Composable
private fun StatsPanel(stats: ConnectionStats) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "TX: ${stats.txBps} Bps  ${stats.txFps} fps  ${stats.txBusyPct}%",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        val rxLine =
            "RX: ${stats.rxBps} Bps  ${stats.rxFps} fps  " +
                "${stats.rxBusyPct}%  gap ${stats.rxMaxBusyMs}ms"
        Text(
            text = rxLine,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
