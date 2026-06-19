package com.elodin.intercom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import com.elodin.intercom.media.MediaShareService
import com.elodin.intercom.proto.Proto
import com.elodin.intercom.radio.ConnectionStats
import com.elodin.intercom.radio.RadioController
import com.elodin.intercom.session.TransportMode

@Suppress("TooManyFunctions")
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

    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) {
                Log.w(TAG, "MEDIA projection consent denied")
                return@registerForActivityResult
            }
            MediaShareService.start(
                this,
                result.resultCode,
                result.data!!,
            )
            radio.startShareAudio()
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
                        linked = radio.linked,
                        stats = radio.stats,
                        transportMode = radio.transportMode,
                        mediaSharing = radio.mediaSharing,
                        mediaPartnerSharing = radio.mediaPartnerSharing,
                        mediaCaptureSupported = radio.mediaCaptureSupported,
                        mediaCaptureSilent = radio.mediaCaptureSilent,
                        onHost = { onHostButton() },
                        onGuest = { onGuestButton() },
                        onDiag = { shareDiagSnapshot() },
                        onTransportChange = { onTransportChange(it) },
                        onShareAudio = { launchShareAudio() },
                        onStopShareAudio = { onStopShareAudio() },
                    )
                }
            }
        }
    }

    private fun onTransportChange(mode: TransportMode) {
        if (radio.hosting || radio.guesting) return
        radio.switchTransport(mode)
    }

    private fun onHostButton() {
        if (radio.hosting) {
            radio.stopHost()
            return
        }
        ensurePermissions(
            hostPermissions(),
            requestHostPermissions,
        ) { radio.startHost() }
    }

    private fun onGuestButton() {
        if (radio.guesting) {
            radio.stopGuest()
            return
        }
        ensurePermissions(
            guestPermissions(),
            requestGuestPermissions,
        ) { radio.startGuest() }
    }

    private fun launchShareAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val mpManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestMediaProjection.launch(mpManager.createScreenCaptureIntent())
    }

    private fun onStopShareAudio() {
        // RadioController/MediaShareController stops the foreground service.
        radio.stopShareAudio()
    }

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(TAG, "MEDIA post-notifications denied")
                return@registerForActivityResult
            }
            launchShareAudio()
        }

    private fun hostPermissions(): Array<String> =
        when (radio.transportMode) {
            TransportMode.Bluetooth ->
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.RECORD_AUDIO,
                )
            TransportMode.WifiDirect ->
                wifiDirectPermissions() + Manifest.permission.RECORD_AUDIO
        }

    private fun guestPermissions(): Array<String> =
        when (radio.transportMode) {
            TransportMode.Bluetooth ->
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.RECORD_AUDIO,
                )
            TransportMode.WifiDirect ->
                wifiDirectPermissions() + Manifest.permission.RECORD_AUDIO
        }

    private fun wifiDirectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
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

@Suppress("LongParameterList", "UnusedParameter")
@Composable
private fun HomeScreen(
    status: String,
    hosting: Boolean,
    guesting: Boolean,
    linked: Boolean,
    stats: ConnectionStats?,
    transportMode: TransportMode,
    mediaSharing: Boolean,
    mediaPartnerSharing: Boolean,
    mediaCaptureSupported: Boolean,
    mediaCaptureSilent: Boolean,
    onHost: () -> Unit,
    onGuest: () -> Unit,
    onDiag: () -> Unit,
    onTransportChange: (TransportMode) -> Unit,
    onShareAudio: () -> Unit,
    onStopShareAudio: () -> Unit,
) {
    val sessionActive = hosting || guesting
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
            Spacer(Modifier.height(32.dp))
            TransportToggle(
                current = transportMode,
                enabled = !sessionActive,
                onChange = onTransportChange,
            )
            Spacer(Modifier.height(32.dp))
            Row {
                Button(onClick = onHost) { Text(if (hosting) "Stop" else "Host") }
                Spacer(Modifier.width(24.dp))
                OutlinedButton(onClick = onGuest) { Text(if (guesting) "Stop" else "Guest") }
            }
            Spacer(Modifier.height(24.dp))
            Text(text = status, style = MaterialTheme.typography.bodySmall)
            if (mediaCaptureSupported && linked) {
                MediaShareControls(
                    sharing = mediaSharing,
                    partnerSharing = mediaPartnerSharing,
                    captureSilent = mediaCaptureSilent,
                    onShareAudio = onShareAudio,
                    onStopShareAudio = onStopShareAudio,
                )
            }
            if (stats != null) {
                Spacer(Modifier.height(16.dp))
                StatsPanel(stats)
            }
        }
        if (sessionActive) {
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

@Composable
private fun TransportToggle(
    current: TransportMode,
    enabled: Boolean,
    onChange: (TransportMode) -> Unit,
) {
    Row {
        // Bluetooth transport is deprecated (LE/SCO airtime can't carry the
        // voice link reliably); grayed out, kept for later cleanup.
        OutlinedButton(onClick = {}, enabled = false) { Text("Bluetooth (deprecated)") }
        Spacer(Modifier.width(12.dp))
        if (current == TransportMode.WifiDirect) {
            Button(onClick = {}, enabled = enabled) { Text("Wi-Fi Direct") }
        } else {
            OutlinedButton(
                onClick = { onChange(TransportMode.WifiDirect) },
                enabled = enabled,
            ) { Text("Wi-Fi Direct") }
        }
    }
}

@Composable
private fun MediaShareControls(
    sharing: Boolean,
    partnerSharing: Boolean,
    captureSilent: Boolean,
    onShareAudio: () -> Unit,
    onStopShareAudio: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    if (sharing) {
        Button(onClick = onStopShareAudio) { Text("Stop Sharing") }
    } else {
        Button(onClick = onShareAudio, enabled = !partnerSharing) { Text("Share Audio") }
    }
    if (partnerSharing) {
        Spacer(Modifier.height(8.dp))
        Text(text = "Partner is sharing audio", style = MaterialTheme.typography.bodySmall)
    }
    if (sharing && captureSilent) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No audio detected — start playback (DRM apps can't be shared)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
