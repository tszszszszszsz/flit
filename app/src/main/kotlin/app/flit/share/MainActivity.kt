package app.flit.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var pendingPeer: FlitEngine.PeerEntry? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val peer = pendingPeer ?: return@registerForActivityResult
        FlitEngine.send(peer, uris)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startSharing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlitEngine.channels(this)
        setContent { FlitUi() }
    }

    private fun startSharing() {
        ContextCompat.startForegroundService(this, Intent(this, FlitService::class.java))
    }

    private fun stopSharing() {
        stopService(Intent(this, FlitService::class.java))
    }

    private fun askThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSharing()
        }
    }

    @Composable
    private fun FlitUi() {
        val state by FlitEngine.state.collectAsState()
        val peers by FlitEngine.peers.collectAsState()
        val received by FlitEngine.received.collectAsState()
        val log by FlitEngine.log.collectAsState()

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Flit", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        state.error != null -> "start failed: ${state.error}"
                        state.running -> "visible on Wi\u2011Fi as \u201c${state.alias}\u201d \u00b7 port ${state.port}"
                        state.starting -> "starting\u2026"
                        else -> "not sharing"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.running || state.starting) {
                        OutlinedButton(onClick = { stopSharing() }) { Text("Stop sharing") }
                    } else {
                        Button(onClick = { askThenStart() }) { Text("Share on Wi\u2011Fi") }
                    }
                    if (state.running) {
                        Text("fp ${state.fingerprintShort}\u2026", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                HorizontalDivider()

                Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                if (peers.isEmpty()) {
                    Text(
                        "no devices yet \u2014 another Flit or LocalSend device on the same Wi\u2011Fi will appear here",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (p in peers.values.sortedBy { it.displayName }) {
                    PeerRow(p) {
                        pendingPeer = p
                        pickFiles.launch(arrayOf("*/*"))
                    }
                }

                HorizontalDivider()

                Text("Received", style = MaterialTheme.typography.titleMedium)
                if (received.isEmpty()) {
                    Text("nothing yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        received.joinToString("\n") {
                            "${ts(it.whenMs)}  ${it.name}  ${FlitEngine.fmtBytes(it.bytes)}"
                        },
                        fontSize = 13.sp,
                    )
                }

                HorizontalDivider()

                Text("Activity", style = MaterialTheme.typography.titleMedium)
                Text(
                    log.joinToString("\n") { "${it.ts}  ${it.text}" }.ifEmpty { "\u2014" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    @Composable
    private fun PeerRow(peer: FlitEngine.PeerEntry, onSend: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSend)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${peer.address} \u00b7 ${peer.info.deviceType ?: "device"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("send \u2192", color = MaterialTheme.colorScheme.primary)
        }
    }

    private fun ts(whenMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(whenMs))
}
