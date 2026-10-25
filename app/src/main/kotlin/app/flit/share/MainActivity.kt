package app.flit.share

import android.Manifest
import android.content.ClipData
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
        if (fp == GLOBAL_DEST) {
            if (tree == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            globalTree.value = tree.toString()
            prefs().edit().putString("destTreeGlobal", tree.toString()).apply()
            FlitEngine.toast("received files now land in the chosen folder")
        } else if (tree == null) {
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

    private val GLOBAL_DEST = "global"

    /** Reactive mirror of the global receive folder pref (null = default). */
    private val globalTree = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startSharing()
    }

    /** App-wide settings — same store the engine/roster read ("flit"). */
    private fun prefs() = getSharedPreferences("flit", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlitEngine.channels(this)
        globalTree.value = prefs().getString("destTreeGlobal", null)
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

    /**
     * Applies the new device identity immediately: sharing restarts behind the
     * scenes (a short gap keeps the service stop/start from racing). A Flit
     * peer mid-transfer will reconnect and resume from its saved prefix.
     */
    private fun applyAlias(name: String) {
        FlitEngine.setAlias(name)
        if (FlitEngine.state.value.running || FlitEngine.state.value.starting) {
            stopSharing()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startSharing()
                FlitEngine.toast("renamed — sharing restarted")
            }, 700)
        } else {
            FlitEngine.toast("renamed — applies when sharing starts")
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
        var showNoteDialog by remember { mutableStateOf(false) }
        var peerMenuFor by remember { mutableStateOf<FlitEngine.PeerEntry?>(null) }
        var showRenameDialog by remember { mutableStateOf(false) }
        var showThemeDialog by remember { mutableStateOf(false) }
        var showBatteryDialog by remember { mutableStateOf(false) }
        var theme by remember { mutableStateOf(prefs().getString("theme", "auto") ?: "auto") }
        var keepOn by remember { mutableStateOf(prefs().getBoolean("keepScreenOn", true)) }
        val transferActive by FlitEngine.transferActive.collectAsState()
        val ctx = LocalContext.current

        // Options → keep-screen-on: held only while a transfer is in flight.
        DisposableEffect(transferActive, keepOn) {
            val win = (ctx as android.app.Activity).window
            if (transferActive && keepOn) {
                win.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
            } else {
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Share-sheet mode: close ourselves once the dispatch clears the intake.
        if (shareMode) {
            LaunchedEffect(share) { if (share == null && FlitEngine.state.value.running) finish() }
        }

        val dark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
        val colors = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }
        MaterialTheme(colorScheme = colors) {
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
                            // Disabled while starting: a tap here used to cancel a
                            // start that hadn't announced itself yet, leaving the
                            // engine silently dead while logs said "egress pinned".
                            OutlinedButton(
                                onClick = { stopSharing() },
                                enabled = !state.starting,
                            ) { Text(if (state.starting) "starting\u2026" else "Stop sharing") }
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
                    val lastPeer by FlitEngine.lastPeerFlow.collectAsState()
                    if (state.running && lastPeer != null) {
                        val last = lastPeer!!
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (share != null) FlitEngine.sendShare(last) else {
                                            pendingPeer = last
                                            pickFiles.launch(arrayOf("*/*"))
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "↻ Send again to ${last.displayName}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        "picks files right away — works even if it's offline (queues for later)",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                    }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showNoteDialog = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("\u2709 Note\u2026") }
                            OutlinedButton(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("+ Add device\u2026") }
                        }
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
                                TextButton(onClick = { FlitEngine.retryQueued(q.fingerprint) }) { Text("↻") }
                                TextButton(onClick = { FlitEngine.cancelQueued(q.fingerprint) }) { Text("✕") }
                            }
                        }
                    }

                    HorizontalDivider()

                    SectionHeader("Options")
                    OptionRow(
                        title = "Device name",
                        subtitle = "\u201c${FlitEngine.currentAlias()}\u201d — how other devices see you",
                        onClick = { showRenameDialog = true },
                    )
                    OptionRow(
                        title = "Theme",
                        subtitle = when (theme) {
                            "dark" -> "dark"
                            "light" -> "light"
                            else -> "follow the system"
                        },
                        onClick = { showThemeDialog = true },
                    )
                    SwitchOption(
                        title = "Only trusted devices can send me files",
                        subtitle = "offers from devices you haven't starred (\u2605) are declined automatically",
                        initial = prefs().getBoolean("onlyTrusted", false),
                    ) { on ->
                        prefs().edit().putBoolean("onlyTrusted", on).apply()
                        if (on && state.running) FlitEngine.toast("takes effect for new offers now")
                    }
                    SwitchOption(
                        title = "Hidden on the network",
                        subtitle = "stop announcing — nobody lists you until you send first, or they add you by address",
                        initial = prefs().getBoolean("hiddenMode", false),
                    ) { on ->
                        prefs().edit().putBoolean("hiddenMode", on).apply()
                        if (on && state.running) {
                            stopSharing()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startSharing()
                                FlitEngine.toast("hidden from now on")
                            }, 700)
                        } else if (state.running) {
                            stopSharing()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startSharing()
                                FlitEngine.toast("visible again")
                            }, 700)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pendingDestPeer = GLOBAL_DEST
                                pickDest.launch(null)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default receive folder", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (globalTree.value == null) "all incoming files go to Downloads/Flit \u2014 tap to choose your own"
                                else "custom folder (trusted devices can still have their own)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (globalTree.value != null) {
                            TextButton(onClick = {
                                prefs().edit().remove("destTreeGlobal").apply()
                                globalTree.value = null
                                FlitEngine.toast("back to Downloads/Flit")
                            }) { Text("Reset") }
                        }
                    }
                    SwitchOption(
                        title = "Start on device boot",
                        subtitle = "be reachable again right after a reboot (best effort — tight systems allow it only after the first unlock)",
                        initial = prefs().getBoolean("startOnBoot", false),
                    ) { on ->
                        prefs().edit().putBoolean("startOnBoot", on).apply()
                    }
                    SwitchOption(
                        title = "Sound when transfers finish",
                        subtitle = "a short chime plays after a completed transfer",
                        initial = prefs().getBoolean("transferSound", false),
                    ) { on ->
                        prefs().edit().putBoolean("transferSound", on).apply()
                    }
                    SwitchOption(
                        title = "Keep the screen on while transferring",
                        subtitle = "stops the system from dozing a transfer you're watching",
                        initial = keepOn,
                    ) { on ->
                        keepOn = on
                        prefs().edit().putBoolean("keepScreenOn", on).apply()
                    }
                    SwitchOption(
                        title = "Crush files when sending",
                        subtitle = "text, notes, logs, JSON and code are gzip'd on the way — Flit receivers unpack them automatically",
                        initial = prefs().getBoolean("crushSend", false),
                    ) { on ->
                        prefs().edit().putBoolean("crushSend", on).apply()
                    }
                    OptionRow(
                        title = "Background battery\u2026",
                        subtitle = "let background sharing run without the system rationing it",
                        onClick = { showBatteryDialog = true },
                    )

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Received", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (received.isNotEmpty()) {
                            TextButton(onClick = { FlitEngine.clearReceived() }) { Text("Clear") }
                        }
                    }
                    if (received.isEmpty()) {
                        Text("nothing yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column {
                            for (r in received.asReversed()) {
                                ReceivedRow(
                                    r,
                                    onOpen = { openReceived(r) },
                                    onShare = { shareReceived(r) },
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { FlitEngine.clearLog() }) { Text("Clear") }
                    }
                    Text(
                        log.joinToString("\n") { "${it.ts}  ${it.text}" }.ifEmpty { "—" },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
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

        if (showNoteDialog) {
            var note by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNoteDialog = false },
                title = { Text("Send a note") },
                text = {
                    Column {
                        Text(
                            "Plain text, sent as a text note. Prepare it here, then tap a device.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(2000) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showNoteDialog = false
                        if (note.isNotBlank()) {
                            FlitEngine.stageNote(note)
                            FlitEngine.toast("note ready — tap a device below")
                        }
                    }) { Text("Prepare") }
                },
                dismissButton = {
                    TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showRenameDialog) {
            var name by remember { mutableStateOf(FlitEngine.currentAlias()) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Device name") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showRenameDialog = false
                        if (name.isNotBlank()) applyAlias(name)
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text("Theme") },
                text = {
                    Column {
                        listOf(
                            "auto" to "Follow the system",
                            "light" to "Light",
                            "dark" to "Dark",
                        ).forEach { (key, label) ->
                            TextButton(onClick = {
                                prefs().edit().putString("theme", key).apply()
                                theme = key
                                showThemeDialog = false
                            }) { Text(if (theme == key) "● $label" else "○ $label") }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showBatteryDialog) {
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                title = { Text("Background battery") },
                text = {
                    Text(
                        "The system rations background work, which can pause sharing while Flit is closed. " +
                            "Excluding Flit from battery optimization keeps it available on your Wi\u2011Fi. " +
                            "This opens the system settings — the choice stays yours.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showBatteryDialog = false
                        runCatching {
                            startActivity(
                                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { FlitEngine.toast("no battery settings on this device") }
                    }) { Text("Open settings") }
                },
                dismissButton = {
                    TextButton(onClick = { showBatteryDialog = false }) { Text("Not now") }
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
    }

    private fun openReceived(r: FlitEngine.ReceivedFile) {
        val uri = when {
            r.uri != null -> Uri.parse(r.uri)
            r.path != null -> FileProvider.getUriForFile(this, "$packageName.fileprovider", r.path)
            else -> null
        } ?: run { FlitEngine.toast("no location stored for ${r.name}"); return }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FlitEngine.guessMime(r.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, r.name).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(r.name, uri)
        }
        runCatching { startActivity(chooser) }
            .onFailure { FlitEngine.toast("nothing can open ${r.name}") }
    }

    private fun shareReceived(r: FlitEngine.ReceivedFile) {
        val uri = when {
            r.uri != null -> Uri.parse(r.uri)
            r.path != null -> FileProvider.getUriForFile(this, "$packageName.fileprovider", r.path)
            else -> null
        } ?: run { FlitEngine.toast("no location stored for ${r.name}"); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = FlitEngine.guessMime(r.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share ${r.name}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(r.name, uri)
        }
        runCatching { startActivity(chooser) }
            .onFailure { FlitEngine.toast("share failed") }
    }

    @Composable
    private fun SectionHeader(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun OptionRow(title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun SwitchOption(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var on by remember { mutableStateOf(initial) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val v = !on
                    on = v
                    onChange(v)
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, onCheckedChange = { v -> on = v; onChange(v) })
        }
    }

    @Composable
    private fun ReceivedRow(
        r: FlitEngine.ReceivedFile,
        onOpen: () -> Unit,
        onShare: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${ts(r.whenMs)} \u00b7 ${FlitEngine.fmtBytes(r.bytes)} \u00b7 tap to open",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onShare) { Text("\u21A6") }
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
                        "Trusted devices are kept in the roster; received files stream straight into the folder you pick for them (otherwise Downloads/Flit).",
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
