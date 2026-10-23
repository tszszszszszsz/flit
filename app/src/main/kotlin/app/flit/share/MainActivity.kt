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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Launches as the main screen AND as a system share target (SEND / SEND_MULTIPLE).
 * In share mode a banner appears on top ("tap a device"); finishing happens when
 * the dispatch clears the intake. Also renders the trusted-peer dialog (star).
 */
class MainActivity : ComponentActivity() {

    private var pendingPeer: FlitEngine.PeerEntry? = null
    private var shareMode = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val peer = pendingPeer ?: return@registerForActivityResult
        pendingPeer = null
        FlitEngine.send(peer, uris)
    }

    private val pickDest = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val fp = pendingDestPeer ?: return@registerForActivityResult
        pendingDestPeer = null
        if (tree == null) {
            FlitRoster.setDestTree(fp, null)
        } else {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            FlitRoster.setDestTree(fp, tree.toString())
            FlitEngine.toast("received files for this device go to the chosen folder")
        }
    }

    private var pendingDestPeer: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startSharing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlitEngine.channels(this)
        ingestIntent(intent)
        setContent { FlitUi() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ingestIntent(intent)
    }

    private fun ingestIntent(intent: Intent?) {
        android.util.Log.i("FlitSend", "ingest action=${intent?.action} onNew=${intent?.let { it.flags and 0x10000000 != 0 }}")
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                shareMode = true
                FlitEngine.handleShareIntent(intent, referrerHost = referrer?.host)
                // The engine may not be running yet; share flows auto-start silently.
                if (!FlitEngine.state.value.running && !FlitEngine.state.value.starting) startSharing()
            }
        }
    }

    private fun startSharing() {
        if (!FlitService.launch(this)) {
            // System rationed background FGS (dataSync budget): engine still
            // runs while the app is in the foreground.
            FlitEngine.start(applicationContext)
            FlitEngine.toast("background sharing limited by system; works while open")
        }
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
        val share by FlitEngine.share.collectAsState()
        val queue by FlitEngine.queue.collectAsState()
        var showAddDialog by remember { mutableStateOf(false) }
        var peerMenuFor by remember { mutableStateOf<FlitEngine.PeerEntry?>(null) }

        // Share-sheet mode: close ourselves once the dispatch clears the intake.
        if (shareMode) {
            LaunchedEffect(share) { if (share == null && FlitEngine.state.value.running) finish() }
        }

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

                if (share != null) {
                    Card {
                        Column(modifier = Modifier.padding(14.dp)) {
                            val s = share!!
                            val parts = if (s.ready) {
                                buildList {
                                    if (s.files.isNotEmpty()) add("${s.files.size} file${if (s.files.size > 1) "s" else ""}")
                                    if (!s.text.isNullOrBlank()) add("a text note")
                                }
                            } else listOf("files (preparing…)")
                            Text(
                                "Ready to send ${parts.joinToString(" + ")}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (!s.text.isNullOrBlank() && s.files.isEmpty()) {
                                Text(
                                    s.text.take(160),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                )
                            }
                            Text(
                                if (s.ready) {
                                    "tap a device below to send" + (s.sourceApp?.let { " (from $it)" } ?: "")
                                } else "copying shared files to a safe place…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                if (peers.isEmpty()) {
                    Text(
                        if (state.running) "scanning\u2026 another Flit or LocalSend device on the same Wi\u2011Fi will appear here"
                        else "start sharing, or add a device by address",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (p in peers.values.sortedBy { it.displayName }) {
                    PeerRow(
                        p,
                        onSend = {
                            if (share != null) FlitEngine.sendShare(p) else {
                                pendingPeer = p
                                pickFiles.launch(arrayOf("*/*"))
                            }
                        },
                        onMenu = { peerMenuFor = p },
                    )
                }
                if (state.running) {
                    OutlinedButton(onClick = { showAddDialog = true }) { Text("+ Add device by address\u2026") }
                }

                if (queue.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Send later", style = MaterialTheme.typography.titleMedium)
                    for (q in queue) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(q.alias, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${q.uris.size} file(s) waiting for it to reappear",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { FlitEngine.cancelQueued(q.fingerprint) }) { Text("✕") }
                        }
                    }
                }

        if (showAddDialog) {
            var addr by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add device by address") },
                text = {
                    Column {
                        Text(
                            "A peer that is never discovered automatically? Enter an IP or address (port defaults to 53317).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = addr,
                            onValueChange = { addr = it },
                            singleLine = true,
                            placeholder = { Text("192.168.1.42:53317") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAddDialog = false
                            if (addr.isNotBlank()) FlitEngine.addManualPeer(addr)
                        },
                    ) { Text("Add") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                },
            )
        }

                peerMenuFor?.let { peer ->
                    PeerMenuDialog(
                        peer = peer,
                        onDismiss = { peerMenuFor = null },
                        onChooseDest = { fp ->
                            peerMenuFor = null
                            pendingDestPeer = fp
                            pickDest.launch(null)
                        },
                    )
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
                    log.joinToString("\n") { "${it.ts}  ${it.text}" }.ifEmpty { "—" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    @Composable
    private fun PeerRow(peer: FlitEngine.PeerEntry, onSend: () -> Unit, onMenu: () -> Unit) {
        val trusted = FlitRoster.pinned(peer.info.fingerprint)
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
                    "${peer.address} \u00b7 ${peer.info.deviceType ?: "device"}" + if (trusted) " \u00b7 trusted" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onMenu) { Text(if (trusted) "\u2605" else "\u2606") }
            Text("\u2192 send", color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun PeerMenuDialog(
        peer: FlitEngine.PeerEntry,
        onDismiss: () -> Unit,
        onChooseDest: (String) -> Unit,
    ) {
        var trusted by remember { mutableStateOf(FlitRoster.pinned(peer.info.fingerprint)) }
        var dest by remember { mutableStateOf(FlitRoster.destTree(peer.info.fingerprint)) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(peer.displayName) },
            text = {
                Column {
                    Text(
                        "Trusted devices are kept in the roster; received files land in the folder you pick for them (otherwise Downloads/Flit).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        if (trusted) {
                            FlitRoster.unpin(peer.info.fingerprint)
                            FlitRoster.setDestTree(peer.info.fingerprint, null)
                            trusted = false
                            dest = null
                        } else {
                            FlitRoster.pin(peer.info.fingerprint, peer.displayName)
                            trusted = true
                        }
                    }) { Text(if (trusted) "\u2605 trusted (tap to forget)" else "\u2606 trust this device") }
                    if (trusted) {
                        Text(
                            "folder: " + when {
                                dest == null -> "Downloads/Flit (default)"
                                else -> "custom"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onChooseDest(peer.info.fingerprint) }) {
                            Text(if (dest == null) "Choose folder\u2026" else "Change folder\u2026")
                        }
                        if (dest != null) {
                            TextButton(onClick = {
                                FlitRoster.setDestTree(peer.info.fingerprint, null)
                                dest = null
                            }) { Text("Back to default folder") }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        )
    }

    private fun ts(whenMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(whenMs))
}
