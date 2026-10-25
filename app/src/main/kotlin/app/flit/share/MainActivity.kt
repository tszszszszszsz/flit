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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Launches as the main screen AND as a system share target (SEND / SEND_MULTIPLE).
 * In share mode a banner appears on top ("tap a device"); finishing happens when
 * the dispatch clears the intake. Also renders the trusted-peer dialog (star).
 */
class MainActivity : ComponentActivity() {

    private var pendingPeer: FlitEngine.PeerEntry? = null
    private var shareMode = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val peer = pendingPeer
        if (peer != null) {
            pendingPeer = null
            FlitEngine.send(peer, uris)
        } else {
            // Dock "Send": files first, device chosen afterwards.
            FlitEngine.stagePicked(uris)
            if (uris.isNotEmpty()) FlitEngine.toast("now tap the receiving device")
        }
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

    // ==== UI ====================================================================

    @Composable
    private fun FlitUi() {
        val state by FlitEngine.state.collectAsState()
        val peers by FlitEngine.peers.collectAsState()
        val received by FlitEngine.received.collectAsState()
        val log by FlitEngine.log.collectAsState()
        val share by FlitEngine.share.collectAsState()
        val queue by FlitEngine.queue.collectAsState()
        val lastPeer by FlitEngine.lastPeerFlow.collectAsState()
        val transfer by FlitEngine.transfer.collectAsState()
        var screen by remember { mutableStateOf(Screen.Home) }
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

        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

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
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (
                                slideInHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ) { w -> dir * w / 4 } + fadeIn(tween(150))
                            ) togetherWith (
                                slideOutHorizontally { w -> -dir * w / 5 } + fadeOut(tween(110))
                            )
                        },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Home -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .statusBarsPadding()
                                        .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                                        .padding(bottom = 148.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    // ---- hero -------------------------------------------------
                                    val onHero = if (state.running) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(28.dp),
                                        color = when {
                                            state.error != null -> MaterialTheme.colorScheme.errorContainer
                                            state.running -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                        tonalElevation = 2.dp,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                PresenceDot(state)
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Flit",
                                                        style = MaterialTheme.typography.headlineSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (state.error != null) {
                                                            MaterialTheme.colorScheme.onErrorContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        },
                                                    )
                                                    Text(
                                                        when {
                                                            state.error != null -> "start failed: ${state.error}"
                                                            state.running ->
                                                                "visible on Wi‑Fi as “${state.alias}” · port ${state.port}"
                                                            state.starting -> "starting…"
                                                            else -> "not sharing — one tap from the dock"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = onHero,
                                                    )
                                                }
                                                if (state.running || state.starting) {
                                                    // Disabled while starting: a tap here used to
                                                    // cancel a start that hadn't announced itself yet.
                                                    OutlinedButton(
                                                        onClick = { stopSharing() },
                                                        enabled = !state.starting,
                                                        shape = CircleShape,
                                                    ) {
                                                        Text(if (state.starting) "starting…" else "Stop")
                                                    }
                                                } else {
                                                    Button(
                                                        onClick = { askThenStart() },
                                                        shape = CircleShape,
                                                    ) {
                                                        Text("Share on Wi‑Fi")
                                                    }
                                                }
                                            }
                                            if (state.running) {
                                                Text(
                                                    "fp ${state.fingerprintShort}…",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                                )
                                            }
                                            // Live transfer strip: slides in only while bytes flow.
                                            AnimatedVisibility(
                                                visible = transfer != null,
                                                enter = fadeIn(tween(180)) +
                                                    expandVertically(expandFrom = Alignment.Top),
                                                exit = shrinkVertically(shrinkTowards = Alignment.Top) +
                                                    fadeOut(tween(140)),
                                            ) {
                                                transfer?.let { t ->
                                                    val frac = if (t.totalBytes > 0) {
                                                        (t.doneBytes.toDouble() / t.totalBytes).toFloat().coerceIn(0f, 1f)
                                                    } else 0.12f
                                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Text(
                                                                if (t.isSend) "↑ Sending" else "↓ Receiving",
                                                                style = MaterialTheme.typography.titleSmall,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            )
                                                            if (t.speedBytesPerSec > 0) Text(
                                                                "${FlitEngine.fmtBytes(t.speedBytesPerSec)}/s",
                                                                style = MaterialTheme.typography.labelLarge,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            )
                                                        }
                                                        LinearProgressIndicator(
                                                            progress = { frac },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(12.dp)
                                                                .clip(CircleShape),
                                                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                                .copy(alpha = 0.18f),
                                                        )
                                                        Text(
                                                            "${FlitEngine.fmtBytes(t.doneBytes)} / ${FlitEngine.fmtBytes(t.totalBytes)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // ---- staged share banner -----------------------------------
                                    share?.let { s ->
                                        val n = s.files.size + s.uris.size
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                val parts = if (s.ready) {
                                                    buildList {
                                                        if (n > 0) add("$n file${if (n > 1) "s" else ""}")
                                                        if (!s.text.isNullOrBlank()) add("a text note")
                                                    }
                                                } else listOf("files (preparing…)")
                                                Text(
                                                    "Ready to send ${parts.joinToString(" + ")}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                                if (!s.text.isNullOrBlank() && n == 0) {
                                                    Text(
                                                        s.text.take(160),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                                    )
                                                }
                                                Text(
                                                    if (s.ready) {
                                                        "tap a device below to send" +
                                                            (s.sourceApp?.let { " (from $it)" } ?: "")
                                                    } else "copying shared files to a safe place…",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                                )
                                            }
                                        }
                                    }

                                    // ---- send-again hero chip ----------------------------------
                                    if (state.running && lastPeer != null) {
                                        val last = lastPeer!!
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (share != null) FlitEngine.sendShare(last) else {
                                                        pendingPeer = last
                                                        pickFiles.launch(arrayOf("*/*"))
                                                    }
                                                },
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                                                            CircleShape,
                                                        ),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Refresh,
                                                        contentDescription = "Send again",
                                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Send again to ${last.displayName}",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                    Text(
                                                        "picks files right away — works offline, queues until it answers",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // ---- send-later queue --------------------------------------
                                    AnimatedVisibility(
                                        visible = queue.isNotEmpty(),
                                        enter = fadeIn(tween(180)) + expandVertically(),
                                        exit = shrinkVertically() + fadeOut(tween(140)),
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    "Send later",
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                for (q in queue) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                q.alias,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                            )
                                                            Text(
                                                                "${q.uris.size} file(s) waiting for it to reconnect",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                        TextButton(onClick = { FlitEngine.retryQueued(q.fingerprint) }) { Text("↻") }
                                                        TextButton(onClick = { FlitEngine.cancelQueued(q.fingerprint) }) { Text("✕") }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // ---- nearby devices ----------------------------------------
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Nearby devices",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (peers.isNotEmpty()) {
                                            Text(
                                                "${peers.size} online",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (peers.isEmpty()) {
                                        Surface(
                                            shape = RoundedCornerShape(28.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(22.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                if (state.running) {
                                                    ScanningDots()
                                                    Text("scanning…", style = MaterialTheme.typography.titleSmall)
                                                    Text(
                                                        "Flit and LocalSend devices on this Wi‑Fi appear here on their own",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                } else {
                                                    Text("Nobody around yet", style = MaterialTheme.typography.titleMedium)
                                                    Text(
                                                        "start sharing, or add a device from the dock",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    for ((idx, p) in peers.values.sortedBy { it.displayName }.withIndex()) {
                                        PeerCard(
                                            p,
                                            index = idx,
                                            onSend = {
                                                if (share != null) FlitEngine.sendShare(p) else {
                                                    pendingPeer = p
                                                    pickFiles.launch(arrayOf("*/*"))
                                                }
                                            },
                                            onMenu = { peerMenuFor = p },
                                        )
                                    }
                                }
                            }

                            Screen.History -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .statusBarsPadding()
                                        .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                                        .padding(bottom = 148.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Received",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (received.isNotEmpty()) {
                                            TextButton(onClick = { FlitEngine.clearReceived() }) {
                                                Text("Clear all")
                                            }
                                        }
                                    }
                                    if (received.isEmpty()) {
                                        Spacer(Modifier.height(28.dp))
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(92.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Filled.List,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(38.dp),
                                                )
                                            }
                                            Text("Files land here", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "everything you receive becomes a card — tap to open, ↦ to re-share",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    } else {
                                        for ((idx, r) in received.asReversed().withIndex()) {
                                            ReceivedCard(
                                                r,
                                                index = idx,
                                                onOpen = { openReceived(r) },
                                                onShare = { shareReceived(r) },
                                            )
                                        }
                                    }
                                }
                            }

                            Screen.Options -> OptionScreen(
                                state = state,
                                theme = theme,
                                onTheme = { theme = it },
                                showRename = { showRenameDialog = true },
                                showTheme = { showThemeDialog = true },
                                showBattery = { showBatteryDialog = true },
                                keepOn = keepOn,
                                setKeepOn = { v ->
                                    keepOn = v
                                    prefs().edit().putBoolean("keepScreenOn", v).apply()
                                },
                                globalTreeSet = globalTree.value != null,
                                onPickDest = {
                                    pendingDestPeer = GLOBAL_DEST
                                    pickDest.launch(null)
                                },
                                onResetDest = {
                                    prefs().edit().remove("destTreeGlobal").apply()
                                    globalTree.value = null
                                    FlitEngine.toast("back to Downloads/Flit")
                                },
                                log = log,
                            )
                        }
                    }

                    Dock(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        screen = screen,
                        onScreen = { screen = it },
                        onGoHome = { screen = Screen.Home },
                        state = state,
                        transfer = transfer,
                        queueCount = queue.size,
                        fresh = received.count { System.currentTimeMillis() - it.whenMs < 30 * 60_000 },
                        onSend = {
                            if (!state.running && !state.starting) {
                                FlitEngine.toast("starting Wi‑Fi sharing…")
                                askThenStart()
                            }
                            pendingPeer = null
                            pickFiles.launch(arrayOf("*/*"))
                        },
                        onNote = { showNoteDialog = true },
                        onAdd = { showAddDialog = true },
                    )
                }
            }
        }

        if (showAddDialog) {
            var addr by remember { mutableStateOf("") }
            MaterialTheme(colorScheme = colors) { // MIUI dialog windows resolve a light scheme — pin ours
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
                            if (addr.isNotBlank()) {
                                FlitEngine.addManualPeer(addr)
                                screen = Screen.Home
                            }
                        },
                    ) { Text("Add") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                },
            )
            }
        }

        if (showNoteDialog) {
            var note by remember { mutableStateOf("") }
            MaterialTheme(colorScheme = colors) { // MIUI dialog windows resolve a light scheme — pin ours
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
                            FlitEngine.toast("note ready — tap a device")
                        }
                    }) { Text("Prepare") }
                },
                dismissButton = {
                    TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") }
                },
            )
            }
        }

        if (showRenameDialog) {
            var name by remember { mutableStateOf(FlitEngine.currentAlias()) }
            MaterialTheme(colorScheme = colors) { // MIUI dialog windows resolve a light scheme — pin ours
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
        }

        if (showThemeDialog) {
            MaterialTheme(colorScheme = colors) { // MIUI dialog windows resolve a light scheme — pin ours
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
        }

        if (showBatteryDialog) {
            MaterialTheme(colorScheme = colors) { // MIUI dialog windows resolve a light scheme — pin ours
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                title = { Text("Background battery") },
                text = {
                    Text(
                        "The system rations background work, which can pause sharing while Flit is closed. " +
                            "Excluding Flit from battery optimization keeps it available on your Wi‑Fi. " +
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
        }

        peerMenuFor?.let { peer ->
            MaterialTheme(colorScheme = colors) { // MIUI dialog windows resolve a light scheme — pin ours
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
    }

    @Composable
    private fun OptionScreen(
        state: FlitEngine.EngineState,
        theme: String,
        onTheme: (String) -> Unit,
        showRename: () -> Unit,
        showTheme: () -> Unit,
        showBattery: () -> Unit,
        keepOn: Boolean,
        setKeepOn: (Boolean) -> Unit,
        globalTreeSet: Boolean,
        onPickDest: () -> Unit,
        onResetDest: () -> Unit,
        log: List<FlitEngine.LogLine>,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                .padding(bottom = 148.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Options",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    OptionRow(
                        title = "Device name",
                        subtitle = "“${FlitEngine.currentAlias()}” — how other devices see you",
                        onClick = showRename,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    OptionRow(
                        title = "Theme",
                        subtitle = when (theme) {
                            "dark" -> "dark"
                            "light" -> "light"
                            else -> "follow the system"
                        },
                        onClick = showTheme,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SwitchOption(
                        title = "Only trusted devices can send me files",
                        subtitle = "offers from devices you haven't starred (★) are declined automatically",
                        initial = prefs().getBoolean("onlyTrusted", false),
                    ) { on ->
                        prefs().edit().putBoolean("onlyTrusted", on).apply()
                        if (on && state.running) FlitEngine.toast("takes effect for new offers now")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SwitchOption(
                        title = "Hidden on the network",
                        subtitle = "stop announcing — nobody lists you until you send first, or they add you by address",
                        initial = prefs().getBoolean("hiddenMode", false),
                    ) { on ->
                        prefs().edit().putBoolean("hiddenMode", on).apply()
                        if (state.running) {
                            stopSharing()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startSharing()
                                FlitEngine.toast(if (on) "hidden from now on" else "visible again")
                            }, 700)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPickDest)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default receive folder", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (!globalTreeSet) "all incoming files go to Downloads/Flit — tap to choose your own"
                                else "custom folder (trusted devices can still have their own)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (globalTreeSet) {
                            TextButton(onClick = onResetDest) { Text("Reset") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SwitchOption(
                        title = "Start on device boot",
                        subtitle = "be reachable again right after a reboot (best effort — tight systems allow it only after the first unlock)",
                        initial = prefs().getBoolean("startOnBoot", false),
                    ) { on ->
                        prefs().edit().putBoolean("startOnBoot", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SwitchOption(
                        title = "Sound when transfers finish",
                        subtitle = "a short chime plays after a completed transfer",
                        initial = prefs().getBoolean("transferSound", false),
                    ) { on ->
                        prefs().edit().putBoolean("transferSound", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SwitchOption(
                        title = "Keep the screen on while transferring",
                        subtitle = "stops the system from dozing a transfer you're watching",
                        initial = keepOn,
                    ) { on -> setKeepOn(on) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SwitchOption(
                        title = "Crush files when sending",
                        subtitle = "text, notes, logs, JSON and code are gzip'd on the way — Flit receivers unpack them automatically",
                        initial = prefs().getBoolean("crushSend", false),
                    ) { on ->
                        prefs().edit().putBoolean("crushSend", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    OptionRow(
                        title = "Background battery…",
                        subtitle = "let background sharing run without the system rationing it",
                        onClick = showBattery,
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Flit 0.1.0 — speaks the LocalSend v2 protocol natively",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "no accounts · no cloud · no telemetry",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            var showLog by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .animateContentSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLog = !showLog },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Activity log",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (showLog) {
                            TextButton(onClick = { FlitEngine.clearLog() }) { Text("Clear") }
                        }
                        Text(
                            if (showLog) "▾" else "▸",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(visible = showLog) {
                        Text(
                            log.joinToString("\n") { "${it.ts}  ${it.text}" }.ifEmpty { "—" },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                        )
                    }
                }
            }
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

    // ==== UI pieces =============================================================

    @Composable
    private fun OptionRow(title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = { v -> on = v; onChange(v) })
        }
    }

    @Composable
    private fun ReceivedCard(
        r: FlitEngine.ReceivedFile,
        index: Int,
        onOpen: () -> Unit,
        onShare: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val enter = remember(r.name + r.whenMs) { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(45L * (index % 6))
            enter.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cs.surfaceContainerLow,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 40f
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        r.name.substringAfterLast('.', "file").take(3).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${ts(r.whenMs)} · ${FlitEngine.fmtBytes(r.bytes)} · tap to open",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Re-share ${r.name}",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun PeerCard(peer: FlitEngine.PeerEntry, index: Int, onSend: () -> Unit, onMenu: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        val trusted = FlitRoster.pinned(peer.info.fingerprint)
        val enter = remember(peer.info.fingerprint) { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(45L * (index % 8))
            enter.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = cs.surfaceContainerLow,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 44f
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSend)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (trusted) cs.primaryContainer else cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        peer.displayName.trim().take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (trusted) cs.onPrimaryContainer else cs.onSurface,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${peer.address} · ${peer.info.deviceType ?: "device"}" + if (trusted) " · trusted" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onMenu) {
                    Text(
                        if (trusted) "★" else "☆",
                        fontSize = 20.sp,
                        color = if (trusted) cs.primary else cs.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = cs.primaryContainer,
                    modifier = Modifier
                        .clickable(onClick = onSend)
                        .padding(start = 2.dp),
                ) {
                    Text(
                        "send",
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onPrimaryContainer,
                    )
                }
            }
        }
    }

    @Composable
    private fun PresenceDot(state: FlitEngine.EngineState) {
        val color = when {
            state.error != null -> MaterialTheme.colorScheme.error
            state.running -> MaterialTheme.colorScheme.primary
            state.starting -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.outline
        }
        when {
            state.running -> {
                val inf = rememberInfiniteTransition(label = "presence")
                val p by inf.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(750, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse,
                    ),
                    label = "pulse",
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .scale(p)
                        .background(color, CircleShape),
                )
            }
            state.starting -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.5.dp,
            )
            else ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color.copy(alpha = 0.55f), CircleShape),
                )
        }
    }

    @Composable
    private fun ScanningDots() {
        val cs = MaterialTheme.colorScheme
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(3) { k ->
                val inf = rememberInfiniteTransition(label = "dot$k")
                val a by inf.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(650, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse,
                        StartOffset(k * 230),
                    ),
                    label = "a$k",
                )
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .alpha(a)
                        .background(cs.primary, CircleShape),
                )
            }
        }
    }

    @Composable
    private fun Dock(
        modifier: Modifier = Modifier,
        screen: Screen,
        onScreen: (Screen) -> Unit,
        onGoHome: () -> Unit,
        state: FlitEngine.EngineState,
        transfer: FlitEngine.TransferTelemetry?,
        queueCount: Int,
        fresh: Int,
        onSend: () -> Unit,
        onNote: () -> Unit,
        onAdd: () -> Unit,
    ) {
        Surface(
            modifier = modifier
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            shape = RoundedCornerShape(34.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            shadowElevation = 14.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SendDockButton(transfer, onSend)
                DockItem(
                    Icons.Filled.Email,
                    "Note",
                    selected = false,
                    badge = 0,
                    onClick = onNote,
                )
                DockItem(
                    Icons.Filled.Add,
                    "Add",
                    selected = false,
                    badge = 0,
                    onClick = {
                        onAdd()
                        onGoHome()
                    },
                )
                DockItem(
                    Icons.Filled.List,
                    "History",
                    selected = screen == Screen.History,
                    badge = fresh,
                    onClick = {
                        // Tapping the selected tab again always returns home.
                        onScreen(if (screen == Screen.History) Screen.Home else Screen.History)
                    },
                )
                DockItem(
                    Icons.Filled.Settings,
                    "Options",
                    selected = screen == Screen.Options,
                    badge = queueCount,
                    onClick = {
                        onScreen(if (screen == Screen.Options) Screen.Home else Screen.Options)
                    },
                )
            }
        }
    }

    @Composable
    private fun DockItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean,
        badge: Int,
        onClick: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val bg by animateColorAsState(
            targetValue = if (selected) cs.secondaryContainer else Color.Transparent,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "dockbg",
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                if (badge > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .background(cs.error, CircleShape),
                    )
                }
            }
            Text(
                label,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun SendDockButton(transfer: FlitEngine.TransferTelemetry?, onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.86f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "sendScale",
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp).scale(scale)) {
                Surface(
                    onClick = onClick,
                    interactionSource = interaction,
                    shape = CircleShape,
                    color = cs.primary,
                    shadowElevation = 7.dp,
                    modifier = Modifier.size(54.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Send files",
                            tint = cs.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                // Progress ring orbits the send button while bytes flow.
                if (transfer != null) {
                    CircularProgressIndicator(
                        progress = {
                            if (transfer.totalBytes > 0) {
                                (transfer.doneBytes.toDouble() / transfer.totalBytes).toFloat().coerceIn(0f, 1f)
                            } else 0.1f
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .padding(3.dp),
                        color = cs.tertiary,
                        trackColor = Color.Transparent,
                        strokeWidth = 4.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
            Text("Send", fontSize = 10.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
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
                    }) { Text(if (trusted) "★ trusted (tap to forget)" else "☆ trust this device") }
                    if (trusted) {
                        Text(
                            "folder: " + when {
                                dest == null -> "Downloads/Flit (default)"
                                else -> "custom"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onChooseDest(peer.info.fingerprint) }) {
                            Text(if (dest == null) "Choose folder…" else "Change folder…")
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

private enum class Screen { Home, History, Options }
