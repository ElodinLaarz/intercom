package com.elodin.intercom

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * M0 stub. No radio, no audio — exists so the smoke harness has something to
 * install, launch, and grep for. See V2_PLAN.md §5 (M0) and STATUS.md.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "STARTED version=${BuildConfig.VERSION_NAME}")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onHost = { Log.i(TAG, "TAP host (stub — radio lands in M1)") },
                        onJoin = { Log.i(TAG, "TAP join (stub — radio lands in M1)") },
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "INTERCOM"
    }
}

@Composable
private fun HomeScreen(onHost: () -> Unit, onJoin: () -> Unit) {
    var lastTap by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Intercom", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "v2 skeleton — radio not built yet (M0)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(48.dp))
        Row {
            Button(onClick = { lastTap = "Host"; onHost() }) { Text("Host") }
            Spacer(Modifier.width(24.dp))
            OutlinedButton(onClick = { lastTap = "Join"; onJoin() }) { Text("Join") }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = lastTap?.let { "$it tapped — stub only" } ?: "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
