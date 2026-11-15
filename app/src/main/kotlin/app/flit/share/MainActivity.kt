package app.flit.share

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.bluetooth.BluetoothAdapter
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Launches as the main screen AND as a system share target (SEND / SEND_MULTIPLE).
 * In share mode a banner appears on top ("tap a device"); finishing happens when
 * the dispatch clears the intake. Also renders the trusted-peer dialog (star).
 */
class MainActivity : ComponentActivity() {

    private var pendingPeer: FlitEngine.PeerEntry? = null
    private var shareMode = false

    /** Direct-share target from the sheet: dispatch the intake to this fp the moment both it and the device are ready. */
    private val autoPeerFp = mutableStateOf<String?>(null)

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

    /** Reactive mirror-on state — the Options row reads it, the ask reverts it. */
    private val mirrorOnState = mutableStateOf(FlitRoster.mirrorOn())

    /** Album mirror: the one system ask (photo/video read). Pure system dialog. */
    private val mirrorPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) {
            FlitRoster.setMirrorOn(true)
            FlitEngine.mirrorSeed(this)
            FlitEngine.mirrorRefresh()
            mirrorOnState.value = true
        } else {
            // Refused: the mirror stays off, honestly — nothing is watched.
            FlitRoster.setMirrorOn(false)
            mirrorOnState.value = false
            FlitEngine.toast("photo access is needed — mirroring stays off")
        }
    }

    private fun mediaReadGranted(): Boolean {
        val c = this
        return if (Build.VERSION.SDK_INT >= 33) {
            c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mirrorPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startSharing()
    }

    // The no-AP moment's two asks (see FlitEngine.directAsk). The system's
    // own sheet and dialog do all the talking; whichever way they conclude,
    // the engine closes that question's book — one ask, once. Each launcher
    // names its own kind on the result: a conclusion binds to the question
    // it concluded, not to whatever the engine holds while it lands late.
    private val directPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { FlitEngine.directAskAnswered(FlitBle.DirectAsk.PERMISSIONS) }

    private val radioPower = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { FlitEngine.directAskAnswered(FlitBle.DirectAsk.POWER) }

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
                // The fp rides in our own extra; on some OEM sheets (MIUI)
                // only the standard shortcut id survives — the id IS the
                // fingerprint by contract, so both roads end at the truth.
                val raw = intent.getStringExtra(FlitShortcuts.EXTRA_PEER_FP)
                    ?: intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                val fp = raw?.takeIf { FlitRoster.pinned(it) }
                if (fp != null) android.util.Log.i("FlitSend", "share-in: direct target ${FlitRoster.alias(fp) ?: fp.take(8)}")
                autoPeerFp.value = fp
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
        val rawPeers by FlitEngine.peers.collectAsState()
        val peers = FlitEngine.dedupedPeers(rawPeers)
        val received by FlitEngine.received.collectAsState()
        val log by FlitEngine.log.collectAsState()
        val share by FlitEngine.share.collectAsState()
        val queue by FlitEngine.queue.collectAsState()
        val lastPeer by FlitEngine.lastPeerFlow.collectAsState()
        val transfer by FlitEngine.transfer.collectAsState()
        var screen by remember { mutableStateOf(Screen.Home) }
        var showAddDialog by remember { mutableStateOf(false) }
        var showNoteDialog by remember { mutableStateOf(false) }
        var showQrDialog by remember { mutableStateOf(false) }
        var showScanDialog by remember { mutableStateOf(false) }
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

        // The no-AP moment: the engine holds a pending ask it can't present
        // alone. Launching the system's own sheet/dialog here is the whole
        // surface story — the sheet's vocabulary is the system's, nothing is
        // ours, and every conclusion (grant/deny/dismiss) shuts the book for
        // good. If this screen never opens while the ask lives, the ask waits.
        LaunchedEffect(Unit) {
            FlitEngine.directAsk.collect { ask ->
                when (ask) {
                    FlitBle.DirectAsk.PERMISSIONS -> directPerms.launch(FlitBleRadio.nearbyPerms)
                    FlitBle.DirectAsk.POWER ->
                        radioPower.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    null -> Unit
                }
            }
        }

        // Direct-share target: the sheet aimed at a trusted peer. Dispatch the
        // moment intake AND that device are ready — the second tap the sheet
        // already paid for. Offline targets never auto-send: content would fly
        // at a stale address; the user chooses manually instead.
        val autoFp = this@MainActivity.autoPeerFp.value
        if (autoFp != null) {
            LaunchedEffect(autoFp) {
                var waited = 0
                while (waited < 15_000) {
                    val s = FlitEngine.share.value
                    if (s == null) return@LaunchedEffect // intake discarded mid-flight
                    val peer = FlitEngine.peers.value.values.firstOrNull { it.info.fingerprint == autoFp }
                    if (peer != null && s.ready && FlitEngine.readyForSend()) {
                        android.util.Log.i("FlitSend", "direct-share dispatch to ${peer.displayName}")
                        FlitShortcuts.reportUsed(ctx, autoFp)
                        FlitEngine.sendShare(peer)
                        this@MainActivity.autoPeerFp.value = null
                        return@LaunchedEffect
                    }
                    delay(400)
                    waited += 400
                }
                this@MainActivity.autoPeerFp.value = null
                if (FlitEngine.share.value != null) {
                    FlitEngine.toast("${FlitRoster.all().firstOrNull { it.fingerprint == autoFp }?.alias ?: "that device"} is offline — tap it in the list to queue")
                }
            }
        }

        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

        // Direct-share targets live from the first second of the app. Trust
        // toggles and renames arriving via registration resync on their own;
        // the boot sweep covers everything left (offline pin without peers).
        LaunchedEffect(Unit) { FlitShortcuts.sync(this@MainActivity) }
        LaunchedEffect(state.running) { if (state.running) FlitShortcuts.sync(this@MainActivity) }

        // ---- in-app feedback -----------------------------------------------
        // Engine messages surface as an M3 snackbar while this UI is visible;
        // backgrounded flows (notification send, roster pinning) fall back to
        // system toasts — same message, same channel rules as before.
        val snackbar = remember { SnackbarHostState() }
        var pendingToast by remember { mutableStateOf<Job?>(null) }
        val toastScope = rememberCoroutineScope()
        val uiSink = remember {
            // One at a time: a fresh message cancels the in-flight one and
            // relaunches — showSnackbar replaces whatever is on screen.
            { msg: String ->
                pendingToast?.cancel()
                pendingToast = toastScope.launch {
                    snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
                }
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, uiSink) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> FlitEngine.uiSink = uiSink
                    Lifecycle.Event.ON_STOP -> if (FlitEngine.uiSink === uiSink) FlitEngine.uiSink = null
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            FlitEngine.uiSink = uiSink // composition exists only while the activity does
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                if (FlitEngine.uiSink === uiSink) FlitEngine.uiSink = null
                pendingToast?.cancel()
                pendingToast = null
            }
        }

        FlitTheme(themeOverride = theme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            // Expressive spatial springs — md.sys.motion values
                            // (tokens.md: default 0.8/380, fast 0.6/800).
                            (
                                slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 380f)) { w -> dir * w / 4 } + fadeIn(FlitSpring.effects())
                            ) togetherWith (
                                slideOutHorizontally(spring(dampingRatio = 0.6f, stiffness = 800f)) { w -> -dir * w / 5 } + fadeOut(FlitSpring.fastEffects())
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
                                                    // Engine pill: start/stop is hero moment #2 —
                                                    // the swap rides spatial springs; effects
                                                    // (opacity) never overshoot (tokens.md).
                                                    AnimatedContent(
                                                        targetState = state.running || state.starting,
                                                        transitionSpec = {
                                                            (
                                                                scaleIn(
                                                                    animationSpec = FlitSpring.spatial<Float>(),
                                                                    initialScale = 0.6f,
                                                                ) + fadeIn(FlitSpring.effects())
                                                                ) togetherWith (
                                                                scaleOut(
                                                                    animationSpec = FlitSpring.fastEffects(),
                                                                    targetScale = 0.95f,
                                                                ) + fadeOut(FlitSpring.fastEffects())
                                                                )
                                                        },
                                                        label = "enginePill",
                                                    ) { on ->
                                                        if (on) {
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
                                                                style = MaterialTheme.typography.labelLarge
                                                                    .copy(fontFeatureSettings = "tnum"),
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            )
                                                        }
                                                        LinearProgressIndicator(
                                                            progress = { frac },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(6.dp)
                                                                .clip(CircleShape),
                                                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                                .copy(alpha = 0.18f),
                                                        )
                                                        Text(
                                                            "${FlitEngine.fmtBytes(t.doneBytes)} / ${FlitEngine.fmtBytes(t.totalBytes)}",
                                                            style = MaterialTheme.typography.bodySmall
                                                                .copy(fontFeatureSettings = "tnum"),
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
                                            shape = RoundedCornerShape(FlitCorner.large),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                val bytes = s.files.sumOf { it.size }
                                                val parts = if (s.ready) {
                                                    buildList {
                                                        if (n > 0) {
                                                            add(SheetUi.countSummary(n))
                                                            if (bytes > 0) add(FlitEngine.fmtBytes(bytes))
                                                        }
                                                        if (!s.text.isNullOrBlank()) add("a text note")
                                                    }
                                                } else listOf("files (preparing…)")
                                                Row(verticalAlignment = Alignment.Top) {
                                                    Text(
                                                        "Ready to send ${parts.joinToString(" + ")}",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    IconButton(
                                                        onClick = { FlitEngine.discardIntake() },
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = "discard staged share",
                                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        )
                                                    }
                                                }
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
                                            shape = RoundedCornerShape(FlitCorner.large),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (share != null) {
                                                        FlitShortcuts.reportUsed(ctx, last.info.fingerprint)
                                                        FlitEngine.sendShare(last)
                                                    } else {
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
                                            shape = RoundedCornerShape(FlitCorner.large),
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
                                                        IconButton(onClick = { FlitEngine.retryQueued(q.fingerprint) }) {
                                                            Icon(
                                                                Icons.Filled.Refresh,
                                                                contentDescription = "retry now",
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.size(20.dp),
                                                            )
                                                        }
                                                        IconButton(onClick = { FlitEngine.cancelQueued(q.fingerprint) }) {
                                                            Icon(
                                                                Icons.Filled.Close,
                                                                contentDescription = "cancel send-later",
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.size(20.dp),
                                                            )
                                                        }
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
                                                        "devices speaking this protocol appear here on their own",
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
                                                if (share != null) {
                                                    FlitShortcuts.reportUsed(ctx, p.info.fingerprint)
                                                    FlitEngine.sendShare(p)
                                                } else {
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
                                                    Icons.AutoMirrored.Filled.List,
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

                    // Engine feedback hovers above the dock (dock = nav bars +
                    // 16 gap + 56 FAB; snackbar sits 8 above that). Visually
                    // the M3 snackbar contract: 48dp single line, extra-small
                    // radius, inverse-surface trio — constants match the m3
                    // defaults, spelled out so the contract is auditable in
                    // one place (an action, if ever added, takes inversePrimary;
                    // none today — engine toasts carry no actions).
                    SnackbarHost(
                        hostState = snackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 80.dp)
                            .padding(horizontal = 16.dp),
                    ) { data ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(FlitCorner.extraSmall),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shadowElevation = 3.dp,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .defaultMinSize(minHeight = 48.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    data.visuals.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            var addr by remember { mutableStateOf("") }
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add device") },
                text = {
                    Column {
                        Text(
                            "Zero typing: show your code to them, or scan theirs. No discoverable network? An address works too (port defaults to 53317).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showAddDialog = false
                                    if (!state.running && !state.starting) {
                                        FlitEngine.toast("starting Wi\u2011Fi sharing\u2026")
                                        askThenStart()
                                    }
                                    showQrDialog = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("My code") }
                            OutlinedButton(
                                onClick = {
                                    showAddDialog = false
                                    showScanDialog = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Scan code") }
                        }
                        OutlinedTextField(
                            value = addr,
                            onValueChange = { addr = it },
                            singleLine = true,
                            placeholder = { Text("192.168.1.42:53317 or flit:// code") },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAddDialog = false
                            if (addr.isNotBlank()) {
                                if (addr.trim().lowercase().startsWith("flit://")) {
                                    FlitEngine.addFromScan(addr)
                                } else {
                                    FlitEngine.addManualPeer(addr)
                                }
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

        if (showQrDialog) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { QrShowCard(onClose = { showQrDialog = false }) }
        }

        if (showScanDialog) {
            QrScanCard(
                onCode = { raw ->
                    FlitEngine.addFromScan(raw)
                },
                onClose = { showScanDialog = false },
            )
        }

        if (showNoteDialog) {
            var note by remember { mutableStateOf("") }
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
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
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
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
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
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
                            // M3 selection row: whole row selectable, trailing
                            // state handled by the RadioButton (onClick = null).
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = theme == key,
                                        role = Role.RadioButton,
                                        onClick = {
                                            prefs().edit().putString("theme", key).apply()
                                            theme = key
                                            showThemeDialog = false
                                        },
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = theme == key, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (theme == key) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
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
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
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
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
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
                shape = RoundedCornerShape(FlitCorner.largeIncreased),
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
                        subtitle = "offers from devices you haven't trusted are declined automatically",
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
                    SwitchOption(
                        title = "Parallel streams on Wi-Fi",
                        subtitle = "splits big files across several sockets for extra speed — only between Flit devices, every other receiver just gets the normal single-stream send",
                        initial = prefs().getBoolean("parallelStreams", true),
                    ) { on ->
                        prefs().edit().putBoolean("parallelStreams", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    if (Build.VERSION.SDK_INT >= 29) {
                        var showMirrorPicker by remember { mutableStateOf(false) }
                    var mirrorTarget by remember { mutableStateOf(FlitRoster.mirrorTarget()) }
                    val mirrorOn = mirrorOnState.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!mirrorOn && !mediaReadGranted()) {
                                    mirrorPerms.launch(mirrorPermissions())
                                } else {
                                    mirrorOnState.value = !mirrorOn
                                    FlitRoster.setMirrorOn(!mirrorOn)
                                    if (!mirrorOn) FlitEngine.mirrorSeed(this@MainActivity)
                                    FlitEngine.mirrorRefresh()
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mirror new camera shots", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    !mirrorOn -> "off — nothing is watched"
                                    mirrorTarget == null -> "on — choose the device to mirror to below"
                                    else -> "every shot the camera saves is sent to ${
                                        FlitRoster.aliasFor(mirrorTarget ?: "") ?: "the chosen device"
                                    } when it shows up"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = mirrorOn, onCheckedChange = { v ->
                            if (v && !mediaReadGranted()) {
                                mirrorPerms.launch(mirrorPermissions())
                            } else {
                                mirrorOnState.value = v
                                FlitRoster.setMirrorOn(v)
                                if (v) FlitEngine.mirrorSeed(this@MainActivity)
                                FlitEngine.mirrorRefresh()
                            }
                        })
                    }
                    OptionRow(
                        title = "Mirror to",
                        subtitle = when (val fp = mirrorTarget) {
                            null -> "choose a trusted device — new camera shots go to it"
                            else -> FlitRoster.aliasFor(fp) ?: fp.take(12)
                        },
                        onClick = { showMirrorPicker = true },
                    )
                    if (showMirrorPicker) {
                        val pinned = FlitRoster.all()
                        AlertDialog(
                            onDismissRequest = { showMirrorPicker = false },
                            title = { Text("Mirror camera shots to") },
                            text = {
                                Column {
                                    if (pinned.isEmpty()) {
                                        Text(
                                            "no trusted devices yet — add one on the Nearby screen first (send it something once, or scan its code)",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    } else {
                                        pinned.forEach { p ->
                                            Text(
                                                p.alias.ifBlank { p.fingerprint.take(12) },
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        FlitRoster.setMirrorTarget(p.fingerprint)
                                                        mirrorTarget = p.fingerprint
                                                        FlitEngine.mirrorRefresh()
                                                        showMirrorPicker = false
                                                    }
                                                    .padding(vertical = 12.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    FlitRoster.setMirrorTarget(null)
                                    mirrorTarget = null
                                    FlitEngine.mirrorRefresh()
                                    showMirrorPicker = false
                                }) { Text("Nobody") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showMirrorPicker = false }) { Text("Cancel") }
                            },
                        )
                    }
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
                    "Flit 0.2.0 — speaks the open LAN-share protocol natively",
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
                shape = RoundedCornerShape(FlitCorner.largeIncreased),
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
                        // Rotating chevron (spatial — position may bounce).
                        val chevron by animateFloatAsState(
                            targetValue = if (showLog) 0f else -90f,
                            animationSpec = FlitSpring.spatial<Float>(),
                            label = "logChevron",
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { rotationZ = chevron },
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
        // Same contract as PeerCard: restart the entrance when the card's
        // identity swaps in place (a new file at the same list slot).
        LaunchedEffect(r.name + r.whenMs) {
            delay(45L * (index % 6))
            enter.animateTo(1f, FlitSpring.spatial<Float>())
        }
        Surface(
            shape = RoundedCornerShape(FlitCorner.large),
            color = cs.surfaceContainerHighest,
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
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFeatureSettings = "tnum"),
                        color = cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
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
        val scanned = FlitRoster.scannedCode(peer.info.fingerprint)
        val enter = remember(peer.info.fingerprint) { Animatable(0f) }
        // Keyed identically to the Animatable above: when a card's identity
        // changes in place (stub → the device it stands for), the remember
        // yields a NEW Animatable at 0 and this effect must restart with it,
        // or the fresh instance strands invisible at alpha 0.
        LaunchedEffect(peer.info.fingerprint) {
            delay(45L * (index % 8))
            enter.animateTo(1f, FlitSpring.spatial<Float>())
        }
        // Identity avatar — stable hue per fingerprint (SheetUi palette),
        // tuned for the scheme in force; hue is content, not chrome.
        val hue = SheetUi.hueFor(peer.info.fingerprint).toFloat()
        val avatar = Color.hsl(hue, 0.55f, 0.82f)
        val onAvatar = Color.hsl(hue, 0.45f, 0.26f)
        Surface(
            color = Color.Transparent,
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
                    .padding(start = 4.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(avatar),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        SheetUi.initials(peer.displayName).ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onAvatar,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val trustSuffix =
                        (if (trusted) " · trusted" else "") + (if (scanned) " · code ✓" else "")
                    Text(
                        "${peer.address} · ${peer.info.deviceType ?: "device"}$trustSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMenu) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "device options",
                        tint = cs.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onSend,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .padding(start = 2.dp),
                ) {
                    Text("send", style = MaterialTheme.typography.labelLarge)
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
        // Hero entrance (tokens.md): the Send FAB lands first, the toolbar
        // follows 50 ms later. Runs once per activity instance, not per screen.
        var fabIn by remember { mutableStateOf(false) }
        var barIn by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            fabIn = true
            delay(50)
            barIn = true
        }
        val cs = MaterialTheme.colorScheme
        val fabScale by animateFloatAsState(
            targetValue = if (fabIn) 1f else 0.6f,
            animationSpec = FlitSpring.spatial<Float>(),
            label = "dockFabScale",
        )
        val fabAlpha by animateFloatAsState(
            targetValue = if (fabIn) 1f else 0f,
            animationSpec = FlitSpring.effects<Float>(),
            label = "dockFabAlpha",
        )
        val barInFraction by animateFloatAsState(
            targetValue = if (barIn) 1f else 0f,
            animationSpec = FlitSpring.spatial<Float>(),
            label = "dockBarIn",
        )
        val barAlpha by animateFloatAsState(
            targetValue = if (barIn) 1f else 0f,
            animationSpec = FlitSpring.effects<Float>(),
            label = "dockBarAlpha",
        )
        Row(
            modifier = modifier.navigationBarsPadding().padding(bottom = 16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // FAB = Send — "the move is the product" (brief principle 1).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                        alpha = fabAlpha
                    }
                    .size(56.dp),
            ) {
                SendFab(transfer, onSend)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = FlitCorner.full,
                color = cs.surfaceContainerHighest,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .graphicsLayer {
                        translationY = (1f - barInFraction) * 64f
                        alpha = barAlpha
                    },
            ) {
                Row(
                    modifier = Modifier
                        .height(64.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DockItem(Icons.Filled.Email, "Note", false, 0, onNote)
                    DockItem(Icons.Filled.Add, "Add", false, 0) {
                        onAdd()
                        onGoHome()
                    }
                    DockItem(Icons.AutoMirrored.Filled.List, "History", screen == Screen.History, fresh) {
                        // Tapping the selected tab again always returns home.
                        onScreen(if (screen == Screen.History) Screen.Home else Screen.History)
                    }
                    DockItem(Icons.Filled.Settings, "Options", screen == Screen.Options, queueCount) {
                        onScreen(if (screen == Screen.Options) Screen.Home else Screen.Options)
                    }
                }
            }
        }
    }

    /**
     * M3 floating-toolbar item: 40dp icon button, pressed shape-morphs to
     * 8dp (buttons.md pressed shape for this size); active screen marks
     * itself with the filled treatment (primary/onPrimary).
     */
    @Composable
    private fun DockItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean,
        badge: Int,
        onClick: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val corner by animateDpAsState(
            targetValue = if (pressed) 8.dp else 40.dp,
            animationSpec = FlitSpring.fastSpatial<Dp>(),
            label = "dockCorner",
        )
        val bg by animateColorAsState(
            targetValue = if (selected) cs.primary else Color.Transparent,
            animationSpec = FlitSpring.effects(),
            label = "dockTint",
        )
        IconButton(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier.size(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(corner))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) cs.onPrimary else cs.onSurfaceVariant,
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
        }
    }

    /** Medium FAB (56dp); pressed shape-morphs to 12dp (buttons.md, size M). */
    @Composable
    private fun SendFab(
        transfer: FlitEngine.TransferTelemetry?,
        onClick: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val corner by animateDpAsState(
            targetValue = if (pressed) 12.dp else 28.dp,
            animationSpec = FlitSpring.fastSpatial<Dp>(),
            label = "fabCorner",
        )
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
            Surface(
                onClick = onClick,
                interactionSource = interaction,
                shape = RoundedCornerShape(corner),
                color = cs.primaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send files",
                        tint = cs.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
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
                        .size(56.dp)
                        .padding(3.dp),
                    color = cs.primary,
                    trackColor = Color.Transparent,
                    strokeWidth = 4.dp,
                    strokeCap = StrokeCap.Round,
                )
            }
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
                        // Trust decisions are direct-share decisions: the sheet
                        // follows the roster in the same breath.
                        FlitShortcuts.sync(this@MainActivity)
                    }) { Text(if (trusted) "trusted — tap to forget" else "Trust this device") }
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
