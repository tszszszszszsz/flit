package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.ideas.peeralias.PeerAlias
import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Must stay identical to android:authorities on the provider in AndroidManifest.xml. */
private const val FILE_PROVIDER_AUTHORITY = "app.flit.share.fileprovider"

/** Saved-instance key: the share-sheet SEND was already consumed by this task. */
private const val KEY_SHARE_CONSUMED = "shareIntentConsumed"

/**
 * Caller-declared extras on an external SEND, mirrored into a plain value so
 * the target decision can be pinned on the JVM without an Android Intent. Own
 * no Android/Compose references of its own.
 */
data class ShareTargetExtras(
    val peerFp: String?,
    val shortcutId: String?,
) {
    /**
     * Only EXTRA_PEER_FP may preselect a peer. EXTRA_SHORTCUT_ID is ignored on
     * purpose: FlitEngine ignores it too, so using it here would leave the two
     * paths disagreeing about who the target is. A shortcut-id-only SEND
     * preselects nobody.
     */
    fun preselection(): String? = peerFp
}

/** Newest signed file announcements one courier parcel carries. Each event
 *  carries a cert and signature, so the envelope's plaintext cap fits only a
 *  few; the engine re-verifies and refuses on any oversized seal. */
private const val COURIER_DROP_MAX_FILE_EVENTS = 4

/** QrAdd is not Parcelable; its own URI is the canonical, round-trippable form.
 *  Compose's MutableState overload wants Saver<T, …> where T is the VALUE type. */
private val QrAddStateSaver = Saver<QrAdd?, String>(
    save = { it?.uri().orEmpty() },
    restore = { raw -> if (raw.isBlank()) null else parseAddCode(raw) },
)

/**
 * Launches as the main screen AND as a system share target (SEND / SEND_MULTIPLE).
 * In share mode a banner appears on top ("tap a device"); finishing happens when
 * the dispatch clears the intake. Also renders the trusted-peer dialog (star).
 */
class MainActivity : ComponentActivity() {

    private var pendingPeer: FlitEngine.PeerEntry? = null
    private var shareMode = false

    /** The share-sheet SEND is dispatched once; a re-create must not re-ingest it. */
    private var shareIntentConsumed = false

    /** finish() is the one-shot conclusion of share-sheet mode. */
    private var shareFinishDone = false

    /**
     * External viewer/chooser launches leave and re-enter through the activity's
     * result registry. Registered at construction so the call is lifecycle-bound;
     * the result is deliberately ignored — a viewer can never finish Flit or
     * change the sharing/Circle state on the way back.
     */
    private val openExternal = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* returning from a viewer is a no-op: no finish, no state teardown */ }

    /** A failed open must never escape to the default handler and kill the process. */
    private val openGuard = CoroutineExceptionHandler { _, t ->
        android.util.Log.w("FlitOpen", "open-file path failed", t)
        FlitEngine.toast("could not open that file")
    }

    /** The latest ACTION_VIEW invite; a new link supersedes an in-flight join. */
    private var pendingCircleJoin: Job? = null

    /** A flit://add link waits here until the user confirms host, fp and circle. */
    private val deepLinkToConfirm = mutableStateOf<QrAdd?>(null)

    /** Caller-declared share target: preselected only, never an automatic send. */
    private val preselectedPeerFp = mutableStateOf<String?>(null)

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

    /** Circle attach: pick one file, spool it grant-proof, post its manifest. */
    private val pickCircleFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) circleAttach(uri)
    }

    // ==== QR parcel show inputs (all local; no name or cert travels in a frame) ====
    private val parcelRecipientFp = mutableStateOf("")
    private val parcelCertText = mutableStateOf("")
    private val parcelPayloadUri = mutableStateOf<Uri?>(null)
    private val parcelFrames = mutableStateOf<List<String>>(emptyList())
    private var parcelPickKind = ""

    /** One picker for the two local show inputs: the file bytes and the recipient cert. */
    private val pickParcelInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        when (parcelPickKind) {
            "cert" -> parcelCertText.value = runCatching {
                contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
            }.getOrDefault("")
            else -> parcelPayloadUri.value = uri
        }
        parcelPickKind = ""
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
        if (savedInstanceState?.getBoolean(KEY_SHARE_CONSUMED) == true) {
            // A config-change recreate must not re-ingest the SEND that the
            // previous instance already dispatched — that stale intake was
            // the "app closes fully" path.
            shareIntentConsumed = true
            setIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        } else {
            ingestIntent(intent)
        }
        // Cold start after a killed process/engine: the user never stopped
        // sharing, so bring it back SILENTLY — never re-prompt on a cold start.
        restoreSharingIfDesired()
        setContent { FlitUi() }
    }

    override fun onResume() {
        super.onResume()
        // A detour through an external chooser can outlive the engine; on the
        // way back, honour the intent the user last expressed.
        restoreSharingIfDesired()
    }

    /** Idempotent, silent restore — only when nothing is already up. */
    private fun restoreSharingIfDesired() {
        val s = FlitEngine.state.value
        if (s.running || s.starting) return
        if (FlitRoster.shouldRestoreSharing(s.running, s.starting)) startSharing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop re-entry: keep getIntent() in sync so a recreation cannot
        // re-ingest the original (stale) link or share request.
        setIntent(intent)
        ingestIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARE_CONSUMED, shareIntentConsumed)
    }

    private fun ingestIntent(intent: Intent?) {
        android.util.Log.i("FlitSend", "ingest action=${intent?.action} onNew=${intent?.let { it.flags and 0x10000000 != 0 }}")
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                shareMode = true
                // Consume the intake now: neither a config-change recreate nor
                // a later re-entry may dispatch this same SEND a second time.
                shareIntentConsumed = true
                setIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
                // The fp rides in our own extra and is caller-declared: it only
                // preselects a target in the banner, never dispatches bytes.
                // EXTRA_SHORTCUT_ID is deliberately NOT a fallback — FlitEngine
                // ignores it, so a shortcut-id-only SEND preselects nobody.
                val extras = ShareTargetExtras(
                    peerFp = intent.getStringExtra(FlitShortcuts.EXTRA_PEER_FP),
                    shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID),
                )
                preselectedPeerFp.value = preselectPeerFp(extras.preselection()) { FlitRoster.pinned(it) }
                // The referrer is caller-declared too: drop it rather than
                // attribute the staged share to an unverified host.
                FlitEngine.handleShareIntent(intent, referrerHost = null)
                // Funnel every Activity-originated start through the one
                // POST_NOTIFICATIONS-aware gate before the FGS is launched.
                if (!FlitEngine.state.value.running && !FlitEngine.state.value.starting) askThenStart()
            }
            Intent.ACTION_VIEW -> {
                // An invite replaces any share-sheet context: no stale
                // auto-dispatch to a peer, no auto-finish mid-invite.
                shareMode = false
                preselectedPeerFp.value = null
                val uri = intent.data ?: return
                // An explicit intent can reach us with any authority. Check the
                // scheme and the exact authority before the tolerant code parser
                // sees a lookalike like flit://add.evil; flit://pair is retired.
                if (!uri.scheme.equals("flit", ignoreCase = true)) return
                if (!isFlitAddAuthority(uri.authority)) return
                val code = runCatching { parseAddCode(uri.toString()) }.getOrNull()
                if (code == null) {
                    FlitEngine.toast("that is not a Flit invite")
                    return
                }
                // Nothing is trusted on arrival: stage the link for an explicit
                // confirmation naming host, fp prefix and circle id. No pin, no
                // join and no sharing start happen before that confirmation.
                pendingCircleJoin?.cancel()
                pendingCircleJoin = null
                deepLinkToConfirm.value = code
            }
        }
    }

    /** Drops a staged deep link without trusting it or starting anything. */
    private fun dismissDeepLink() {
        deepLinkToConfirm.value = null
        pendingCircleJoin?.cancel()
        pendingCircleJoin = null
        // Consume the link so a recreate cannot re-stage it behind the user.
        setIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
    }

    /** Runs only from the explicit confirmation dialog; the link was inert until now. */
    private fun confirmDeepLink(code: QrAdd) {
        deepLinkToConfirm.value = null
        setIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        if (code.circle.isNullOrBlank() || code.invite.isNullOrBlank()) {
            FlitEngine.addFromScan(code)
            return
        }
        // A Circle invite needs a live dialer. The user has just confirmed, so
        // start through the same permission-aware gate, then join when ready.
        if (!FlitEngine.state.value.running && !FlitEngine.state.value.starting) askThenStart()
        pendingCircleJoin = lifecycleScope.launch(openGuard) {
            var waited = 0
            while (isActive && !FlitEngine.state.value.running &&
                FlitEngine.state.value.error == null && waited < 100
            ) {
                delay(200)
                waited++
            }
            if (!isActive || lifecycle.currentState == Lifecycle.State.DESTROYED) {
                pendingCircleJoin = null
                return@launch
            }
            if (!FlitEngine.state.value.running) {
                FlitEngine.toast("sharing did not start — open Flit and try the invite again")
                pendingCircleJoin = null
                return@launch
            }
            FlitEngine.joinCircle(code)
            pendingCircleJoin = null
        }
        pendingCircleJoin?.let { job ->
            job.invokeOnCompletion { if (pendingCircleJoin === job) pendingCircleJoin = null }
        }
    }

    /** The banner's explicit send: a preselected fp is never an auto-send. */
    private fun sendStagedTo(fingerprint: String) {
        val peer = FlitEngine.peers.value.values.firstOrNull { it.info.fingerprint == fingerprint }
        val staged = FlitEngine.share.value
        if (peer != null && staged?.ready == true && FlitEngine.readyForSend()) {
            FlitShortcuts.reportUsed(this, fingerprint)
            FlitEngine.sendShare(peer)
        } else {
            FlitEngine.toast(
                "${FlitRoster.all().firstOrNull { it.fingerprint == fingerprint }?.alias ?: "that device"} is offline — tap it in the list to queue",
            )
        }
        preselectedPeerFp.value = null
    }

    private fun startSharing() {
        // Every deliberate start records the user's intent — share-sheet,
        // invite and silent restore alike — so a killed FGS can be revived.
        FlitRoster.setSharingDesired(true)
        // An FGS start outlives this activity: no activity context is held.
        if (!FlitService.launch(applicationContext)) {
            // System rationed background FGS (dataSync budget): engine still
            // runs while the app is in the foreground.
            FlitEngine.start(applicationContext)
            FlitEngine.toast("background sharing limited by system; works while open")
        }
    }

    /**
     * Explicit user Stop only. A live FGS clears its own persisted intent in
     * onDestroy; we cover the engine-only / refused-FGS case where no service
     * teardown will do it. Rebinds use [stopForRebind] and never clear it.
     */
    private fun stopSharing() {
        val serviceWasRunning = stopService(Intent(applicationContext, FlitService::class.java))
        if (!serviceWasRunning || !FlitEngine.state.value.running) {
            FlitEngine.stop()
            FlitRoster.setSharingDesired(false) // idempotent; no write when already false
        }
    }

    /** Internal rebind (alias, hidden, carrier, relay): never clear user intent. */
    private fun stopForRebind() {
        stopService(Intent(applicationContext, FlitService::class.java))
    }

    /**
     * Single POST_NOTIFICATIONS-aware gate for every user/Activity-originated
     * sharing start (hero, dock, share sheet, invite). Cold-start restore and
     * internal rebinds call [startSharing] directly and stay silent.
     */
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
            stopForRebind() // internal rebind — desired stays true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isDestroyed) return@postDelayed // never start from a dead activity
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
        val knownDevices by FlitEngine.knownDevices.collectAsState()
        val panicEvents by FlitEngine.panicEvents.collectAsState()
        val circleState by FlitEngine.circleState.collectAsState()
        val circleEvents by FlitEngine.circleEvents.collectAsState()
        // Shared pad: a derived view rebuilt by the engine on every store fold,
        // never a second persisted copy. The UI only renders and sends callbacks.
        val padView by FlitEngine.padView.collectAsState()
        // Only shas with a copy actually openable on THIS device get an Open action.
        val landedShas by FlitEngine.circleLandedShas.collectAsState()
        // Process-local courier outcomes keyed by SHA-256; the file card reads its carry line from this.
        val courierHandoffs by FlitEngine.courierHandoffs.collectAsState()
        // Full fingerprint (not the short hero form) is what event authors are
        // compared against in the feed; identity loads async, so track it.
        val meFp by remember(circleState) {
            mutableStateOf(FlitEngine.selfQrAdd()?.fingerprint.orEmpty())
        }
        // Transient UI state is saved: a config change (or returning from an
        // external viewer that recreated us) must not silently drop the modal
        // that was up, or leave a half-resolved Circle invite behind.
        var screen by rememberSaveable { mutableStateOf(Screen.Home) }
        var showAddDialog by rememberSaveable { mutableStateOf(false) }
        var showNoteDialog by rememberSaveable { mutableStateOf(false) }
        var showQrDialog by rememberSaveable { mutableStateOf(false) }
        var showScanDialog by rememberSaveable { mutableStateOf(false) }
        var showKnownDialog by rememberSaveable { mutableStateOf(false) }
        var showPanicDialog by rememberSaveable { mutableStateOf(false) }
        // PeerEntry has no saveable contract; this menu simply closes on a re-create.
        var peerMenuFor by remember { mutableStateOf<FlitEngine.PeerEntry?>(null) }
        var showRenameDialog by rememberSaveable { mutableStateOf(false) }
        var showThemeDialog by rememberSaveable { mutableStateOf(false) }
        var showBatteryDialog by rememberSaveable { mutableStateOf(false) }
        var showRelayDialog by rememberSaveable { mutableStateOf(false) }
        // QR parcel is a Home action + a full-screen modal, never a dock tab.
        var showParcel by rememberSaveable { mutableStateOf(false) }
        var parcelTab by rememberSaveable { mutableStateOf(0) } // 0 = scan, 1 = show
        // Circle is a Home action + a full-screen feed, never a dock tab.
        var showCircle by rememberSaveable { mutableStateOf(false) }
        var showCircleStart by rememberSaveable { mutableStateOf(false) }
        var showCircleJoin by rememberSaveable { mutableStateOf(false) }
        var showCircleScan by rememberSaveable { mutableStateOf(false) }
        var showCircleInvite by rememberSaveable { mutableStateOf(false) }
        var circleInvite by rememberSaveable(stateSaver = QrAddStateSaver) {
            mutableStateOf<QrAdd?>(null)
        }
        var pendingCircleOpen by rememberSaveable { mutableStateOf(false) }
        // Resolving an Open action: disables the file card while we look it up
        // and launch. Plain remember — a recreate cancels the coroutine and
        // must not leave the card stuck in "opening".
        var openingSha256 by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(circleState) {
            if (circleState != null && pendingCircleOpen) {
                showCircle = true
                pendingCircleOpen = false
            }
        }
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

        // QR codes, circle invites, the circle feed, panic alerts and deep-link
        // confirmations carry secrets. Keep them out of screenshots, recents
        // and non-secure overlays while (and only while) they are on screen.
        val secureSurface = showQrDialog || showScanDialog || showCircleInvite ||
            showCircle || showCircleStart || showCircleJoin || showCircleScan ||
            showPanicDialog || showParcel || deepLinkToConfirm.value != null
        DisposableEffect(secureSurface) {
            val win = (ctx as android.app.Activity).window
            if (secureSurface) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        // Share-sheet mode: finish ONCE, and only from a live, unobstructed
        // screen. A backgrounded activity (external chooser) or a modal Circle
        // feed must never be finished by a stale intake clearing.
        if (shareMode && !shareFinishDone) {
            LaunchedEffect(share) {
                if (share != null || shareFinishDone) return@LaunchedEffect
                if (showCircle) return@LaunchedEffect
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
                if (!FlitEngine.state.value.running) return@LaunchedEffect
                shareFinishDone = true
                finish()
            }
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

        // A caller-declared target only preselects the staged-share banner.
        // No SEND intent may dispatch bytes on its own: the banner's Send button
        // is the only road from an external share to FlitEngine.sendShare.

        // One owner per back press. Material dialogs register a handler too;
        // covering the gap where a sheet is up but nothing claims the event
        // stops the default Activity.finish() — the "app closed" report.
        val overlayOpen = pendingCircleOpen || showAddDialog || showNoteDialog ||
            showQrDialog || showScanDialog || showKnownDialog || showPanicDialog ||
            showRenameDialog || showThemeDialog || showBatteryDialog || showRelayDialog ||
            showParcel || showCircleStart || showCircleJoin || showCircleScan || showCircleInvite ||
            deepLinkToConfirm.value != null || peerMenuFor != null
        BackHandler(enabled = showCircle) { showCircle = false } // Circle owns back while up
        BackHandler(enabled = !showCircle && overlayOpen) {
            // Dismiss only — never finish the activity out of a modal state.
            when {
                deepLinkToConfirm.value != null -> dismissDeepLink()
                showParcel -> showParcel = false
                pendingCircleOpen -> pendingCircleOpen = false
                peerMenuFor != null -> peerMenuFor = null
                showBatteryDialog -> showBatteryDialog = false
                showThemeDialog -> showThemeDialog = false
                showRelayDialog -> showRelayDialog = false
                showRenameDialog -> showRenameDialog = false
                showNoteDialog -> showNoteDialog = false
                showCircleInvite -> showCircleInvite = false
                showCircleScan -> showCircleScan = false
                showCircleJoin -> showCircleJoin = false
                showCircleStart -> showCircleStart = false
                showScanDialog -> showScanDialog = false
                showQrDialog -> showQrDialog = false
                showPanicDialog -> showPanicDialog = false
                showKnownDialog -> showKnownDialog = false
                showAddDialog -> showAddDialog = false
            }
        }
        BackHandler(enabled = !showCircle && !overlayOpen && screen != Screen.Home) {
            screen = Screen.Home
        }

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
        // One host at a time: the Circle feed is a separate modal window, so
        // it carries the snackbar while the one behind that window is omitted.
        val snackbarHost: @Composable (Modifier) -> Unit = { hostModifier ->
            SnackbarHost(hostState = snackbar, modifier = hostModifier) { data ->
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
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // ---- panic alerts -----------------------------------------
                                    // Received alerts only show here. Nothing is acted on
                                    // automatically: every action below is a deliberate tap.
                                    AnimatedVisibility(visible = panicEvents.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            for (event in panicEvents) PanicBanner(event)
                                        }
                                    }

                                    // ---- staged share banner -----------------------------------
                                    share?.let { s ->
                                        val n = s.files.size + s.uris.size
                                        // A caller-declared target is preselected here; it never
                                        // sends on its own. The explicit Send button is the user tap.
                                        val stagedTarget = preselectedPeerFp.value
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
                                                // Caller-controlled note text and referrer are
                                                // deliberately not rendered here: only counts.
                                                Text(
                                                    if (s.ready) "tap a device below to send"
                                                    else "copying shared files to a safe place…",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                                if (stagedTarget != null) {
                                                    val label = FlitRoster.alias(stagedTarget) ?: "selected device"
                                                    OutlinedButton(
                                                        enabled = s.ready,
                                                        onClick = { sendStagedTo(stagedTarget) },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(top = 8.dp),
                                                    ) { Text("Send to $label") }
                                                }
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
                                                        contentDescription = null, // adjacent Text already says "Send again to …"
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
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
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

                                    // ---- circle entry -------------------------------------------
                                    // A small Home action, not a dock tab: the feed opens as its
                                    // own surface, and a circle that does not exist yet starts
                                    // from the founding sheet.
                                    Surface(
                                        shape = RoundedCornerShape(FlitCorner.large),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (circleState != null) showCircle = true
                                                else showCircleStart = true
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                                        CircleShape,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    "C",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "Circle",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    if (circleState == null) {
                                                        "a temporary group for messages, files and polls"
                                                    } else {
                                                        "${circleState?.members?.size ?: 0} members · ${circleEvents.size} events"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                if (circleState == null) "Start" else "Open",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }

                                    // ---- QR parcel entry ---------------------------------------
                                    // Additive Home action. The parcel screen owns both its
                                    // show and scan modes; Circle and transfer stay untouched.
                                    Surface(
                                        shape = RoundedCornerShape(FlitCorner.large),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // Both modes need the local identity; no screen
                                                // opens and nothing is staged when it is absent.
                                                if (FlitEngine.parcelIdentity() == null) {
                                                    FlitEngine.toast("identity not ready — start sharing first")
                                                } else {
                                                    showParcel = true
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
                                                    .size(36.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                                        CircleShape,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    "Q",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "QR parcel",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    "show frames or scan them — no radio link",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                "Open",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
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
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(
                                            onClick = { showPanicDialog = true },
                                            shape = CircleShape,
                                            contentPadding = PaddingValues(horizontal = 14.dp),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("Panic", style = MaterialTheme.typography.labelLarge) }
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
                                onShowKnown = { showKnownDialog = true },
                                knownCount = knownDevices.size,
                                showTheme = { showThemeDialog = true },
                                showBattery = { showBatteryDialog = true },
                                showRelay = { showRelayDialog = true },
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

                    // Circle feed: a modal full-surface window, not a sibling
                    // overlay — the dock/home below must not stay hit-testable
                    // or reachable in semantics while the feed is up.
                    if (showCircle) {
                        Dialog(
                            onDismissRequest = { showCircle = false },
                            properties = DialogProperties(
                                usePlatformDefaultWidth = false,
                                // MainActivity's BackHandler is the single owner of
                                // back here; the dialog window must not also claim
                                // it and double-dismiss across the two windows.
                                dismissOnBackPress = false,
                            ),
                        ) {
                            SecureWindow { } // the Circle feed's own window, not the Activity's
                            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background),
                                ) {
                                    CircleScreen(
                                        state = circleState,
                                        events = circleEvents,
                                        members = circleState?.members ?: emptyList(),
                                        meFp = meFp,
                                        onPost = { text -> FlitEngine.circlePost(text) },
                                        onFile = { pickCircleFile.launch(arrayOf("*/*")) },
                                        onCourierDrop = { fp ->
                                            // The member sheet hands us the absent member's
                                            // fingerprint. Carry the newest signed file
                                            // announcements they may have missed; the engine
                                            // re-verifies and refuses without their cert.
                                            val files = circleEvents
                                                .filter {
                                                    it.type.equals(CircleEvent.FILE, ignoreCase = true) &&
                                                        CircleCore.verify(it)
                                                }
                                                .takeLast(COURIER_DROP_MAX_FILE_EVENTS)
                                            FlitEngine.circleCourierDrop(files, fp)
                                        },
                                        onOpenFile = { sha ->
                                            val key = sha.trim().lowercase(Locale.ROOT)
                                            openingSha256 = key
                                            lifecycleScope.launch(openGuard) {
                                                try {
                                                    val r = runCatching {
                                                        FlitEngine.circleReceivedFor(sha)
                                                    }.getOrNull()
                                                    // A config change or the chooser itself can
                                                    // destroy us mid-resolve; never launch from a dead UI.
                                                    if (!isActive ||
                                                        lifecycle.currentState == Lifecycle.State.DESTROYED
                                                    ) return@launch
                                                    if (r != null) openReceived(r)
                                                    // Missing locally: say so, then free the card.
                                                    else FlitEngine.toast("that file hasn't landed here yet")
                                                } finally {
                                                    // Success, failure or Missing — never leave a
                                                    // card disabled after the attempt is over.
                                                    if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                                                        openingSha256 = null
                                                    }
                                                }
                                            }
                                        },
                                        onPoll = { question, options ->
                                            FlitEngine.circlePostPoll(question, options)
                                        },
                                        onVote = { pollId, option -> FlitEngine.circleVote(pollId, option) },
                                        onStartKick = { fp -> FlitEngine.circleStartKick(fp) },
                                        onVoteKick = { kickId, approve ->
                                            FlitEngine.circleVoteKick(kickId, approve)
                                        },
                                        onKick = { fp -> FlitEngine.circleKick(fp) },
                                        onRole = { fp, role -> FlitEngine.circleSetRole(fp, role) },
                                        onLeave = {
                                            FlitEngine.circleLeave()
                                            showCircle = false
                                        },
                                        onQr = { showCircleInvite = true },
                                        onBack = { showCircle = false },
                                        landedSha256 = landedShas,
                                        openingSha256 = openingSha256,
                                        courierHandoffs = courierHandoffs,
                                        // ---- pad surface: view from the engine, actions back to it ----
                                        padView = padView,
                                        onPadPut = { key, text -> FlitEngine.circlePadPut(key, text) },
                                        onPadDel = { key -> FlitEngine.circlePadDel(key) },
                                    )
                                    snackbarHost(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .navigationBarsPadding()
                                            .padding(bottom = 16.dp)
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }

                    // QR parcel: a modal full-surface window like the Circle feed.
                    // Both modes need the local identity; scan gets the injected
                    // writer and show gets frames sealed from local inputs.
                    if (showParcel) {
                        Dialog(
                            onDismissRequest = { showParcel = false },
                            properties = DialogProperties(
                                usePlatformDefaultWidth = false,
                                dismissOnBackPress = false, // MainActivity's BackHandler owns back
                            ),
                        ) {
                            SecureWindow { } // QR parcel frames ride this dialog's window
                            MaterialTheme(colorScheme = FlitThemeColors(theme)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background),
                                ) {
                                    val idf = FlitEngine.parcelIdentity()
                                    if (idf == null) {
                                        // Defensive: the gate at entry already refused to open.
                                        LaunchedEffect(Unit) {
                                            FlitEngine.toast("identity not ready — start sharing first")
                                            showParcel = false
                                        }
                                    } else {
                                        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                OutlinedButton(
                                                    onClick = { parcelTab = 0 },
                                                    enabled = parcelTab != 0,
                                                ) { Text("Scan") }
                                                OutlinedButton(
                                                    onClick = { parcelTab = 1 },
                                                    enabled = parcelTab != 1,
                                                ) { Text("Show") }
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    showParcel = false
                                                    parcelFrames.value = emptyList()
                                                }) { Text("Close") }
                                            }
                                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                if (parcelTab == 0) {
                                                    ParcelScanCard(
                                                        identity = idf,
                                                        onBytes = ::writeParcelReceived,
                                                        onClose = { showParcel = false },
                                                    )
                                                } else {
                                                    val frames by parcelFrames
                                                    if (frames.isEmpty()) {
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .verticalScroll(rememberScrollState())
                                                                .padding(20.dp),
                                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                                        ) {
                                                            Text(
                                                                "Show a parcel",
                                                                style = MaterialTheme.typography.titleMedium,
                                                            )
                                                            val fp by parcelRecipientFp
                                                            OutlinedTextField(
                                                                value = fp,
                                                                onValueChange = { parcelRecipientFp.value = it },
                                                                label = { Text("recipient fingerprint") },
                                                                singleLine = true,
                                                                modifier = Modifier.fillMaxWidth(),
                                                            )
                                                            val certText by parcelCertText
                                                            OutlinedTextField(
                                                                value = certText,
                                                                onValueChange = { parcelCertText.value = it },
                                                                label = { Text("recipient certificate (Base64 DER)") },
                                                                minLines = 3,
                                                                modifier = Modifier.fillMaxWidth(),
                                                            )
                                                            Row(
                                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                            ) {
                                                                OutlinedButton(onClick = {
                                                                    parcelPickKind = "cert"
                                                                    pickParcelInput.launch(arrayOf("*/*"))
                                                                }) { Text("Pick certificate") }
                                                                OutlinedButton(onClick = {
                                                                    parcelPickKind = "payload"
                                                                    pickParcelInput.launch(arrayOf("*/*"))
                                                                }) { Text("Pick file") }
                                                            }
                                                            val payloadUri by parcelPayloadUri
                                                            Text(
                                                                if (payloadUri == null) {
                                                                    "no file picked yet"
                                                                } else {
                                                                    "file picked — ready to seal"
                                                                },
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                            Button(
                                                                onClick = {
                                                                    val uri = parcelPayloadUri.value
                                                                    if (uri == null) {
                                                                        FlitEngine.toast("pick a file first")
                                                                    } else {
                                                                        val payload = runCatching {
                                                                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                                                        }.getOrNull()
                                                                        if (payload == null) {
                                                                            FlitEngine.toast("could not read that file")
                                                                        } else {
                                                                            val made = makeParcelFrames(
                                                                                idf,
                                                                                parcelRecipientFp.value,
                                                                                parcelCertText.value,
                                                                                payload,
                                                                            )
                                                                            if (made != null) parcelFrames.value = made
                                                                        }
                                                                    }
                                                                },
                                                                modifier = Modifier.fillMaxWidth(),
                                                            ) { Text("Make frames") }
                                                        }
                                                    } else {
                                                        ParcelShowCard(
                                                            frames = frames,
                                                            onClose = {
                                                                showParcel = false
                                                                parcelFrames.value = emptyList()
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    snackbarHost(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .navigationBarsPadding()
                                            .padding(bottom = 16.dp)
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Engine feedback hovers above the dock (dock = nav bars +
                    // 16 gap + 56 FAB; snackbar sits 8 above that). Each modal
                    // window carries its own host while this one is hidden.
                    if (!showCircle && !showParcel) {
                        snackbarHost(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 80.dp)
                                .padding(horizontal = 16.dp),
                        )
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

        // A flit://add link is inert until this dialog is confirmed. It names
        // exactly what the user is being asked to trust — nothing is pinned,
        // joined, or started before the confirm button is tapped.
        deepLinkToConfirm.value?.let { code ->
            val prompt = describeDeepLink(code)
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                    onDismissRequest = { dismissDeepLink() },
                    title = { Text(if (prompt.isInvite) "Join this Circle?" else "Add this device?") },
                    text = {
                        SecureWindow {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "A flit:// link is untrusted. Check the details before Flit contacts this device.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("Host: ${prompt.host}")
                                Text("Fingerprint: ${prompt.fingerprintPrefix}…")
                                prompt.circleId?.let { Text("Circle: $it") }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { confirmDeepLink(code) }) {
                            Text(if (prompt.isInvite) "Join" else "Add")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { dismissDeepLink() }) { Text("Cancel") }
                    },
                )
            }
        }

        if (showKnownDialog) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                KnownDevicesSheet(devices = knownDevices, onClose = { showKnownDialog = false })
            }
        }

        if (showPanicDialog) {
            var note by remember { mutableStateOf("") }
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                    onDismissRequest = { showPanicDialog = false },
                    title = { Text("Panic alert") },
                    text = {
                        SecureWindow {
                            Column {
                                Text(
                                    "Sends a short alert to your known devices. Nothing happens to this device automatically — recipients decide what to do; no files open and no settings change.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedTextField(
                                    value = note,
                                    onValueChange = { note = it.take(280) },
                                    label = { Text("Note (optional)") },
                                    minLines = 2,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showPanicDialog = false
                            FlitEngine.sendPanic(note)
                        }) { Text("Send alert") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPanicDialog = false }) { Text("Cancel") }
                    },
                )
            }
        }

        if (showQrDialog) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { QrShowCard(onClose = { showQrDialog = false }) }
        }

        if (showScanDialog) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                QrScanCard(
                    onCode = { raw ->
                        FlitEngine.addFromScan(raw)
                    },
                    onClose = { showScanDialog = false },
                )
            }
        }

        // ---- Circle sheets -------------------------------------------------
        // Founding: start a fresh circle (then share its QR), or join one.
        if (showCircleStart) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                    onDismissRequest = { showCircleStart = false },
                    title = { Text("Circle") },
                    text = {
                        Text(
                            "A temporary group on your devices only — no accounts, no server. " +
                                "Messages, file references and polls stay with the devices that joined. " +
                                "Anyone holding the invite can join until you leave the circle.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val code = FlitEngine.startCircle()
                            showCircleStart = false
                            if (code != null) {
                                circleInvite = code
                                showCircleInvite = true
                            } else {
                                FlitEngine.toast("sharing is not ready yet — start sharing first")
                            }
                        }) { Text("Start a Circle") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            showCircleStart = false
                            showCircleJoin = true
                        }) { Text("Join by QR") }
                    },
                )
            }
        }

        if (showCircleJoin) {
            var pasted by remember { mutableStateOf("") }
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                    onDismissRequest = { showCircleJoin = false },
                    title = { Text("Join a Circle") },
                    text = {
                        Column {
                            Text(
                                "Scan the founder's invite, or paste the flit:// code text. The group key arrives only over the pinned connection to the host.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { pasted = it },
                                singleLine = true,
                                placeholder = { Text("flit://add?v=2&…") },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            )
                            TextButton(onClick = {
                                showCircleJoin = false
                                showCircleScan = true
                            }) { Text("Scan code") }
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = pasted.isNotBlank(),
                            onClick = {
                                val code = parseAddCode(pasted)
                                showCircleJoin = false
                                if (code == null) {
                                    FlitEngine.toast("that does not look like a Flit code")
                                } else {
                                    pendingCircleOpen = true
                                    FlitEngine.joinCircle(code)
                                }
                            },
                        ) { Text("Join") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCircleJoin = false }) { Text("Cancel") }
                    },
                )
            }
        }

        if (showCircleScan) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                QrScanCard(
                    onCode = { raw ->
                        showCircleScan = false
                        val code = parseAddCode(raw)
                        if (code == null) {
                            FlitEngine.toast("that does not look like a Flit code")
                        } else {
                            pendingCircleOpen = true
                            FlitEngine.joinCircle(code)
                        }
                    },
                    onClose = { showCircleScan = false },
                )
            }
        }

        if (showCircleInvite) {
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                    onDismissRequest = { showCircleInvite = false },
                    title = { Text("Circle invite") },
                    text = {
                        SecureWindow {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val invite = circleInvite
                                if (invite != null) {
                                    QrCanvas(text = invite.uri(), side = 200.dp)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Anyone who scans this can join until the circle is left. Treat it like a key to the door — a new circle retires it.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Text(
                                        "Only the founder can show this circle's invite. To share a fresh one, start a new circle.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showCircleInvite = false
                            showCircle = true
                        }) { Text("Done") }
                    },
                )
            }
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

        if (showRelayDialog) {
            var relayAddressText by remember { mutableStateOf(prefs().getString("relayAddress", "") ?: "") }
            var relayTokenText by remember { mutableStateOf(prefs().getString("relayToken", "") ?: "") }
            MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
                AlertDialog(
                    onDismissRequest = { showRelayDialog = false },
                    title = { Text("Relay (optional)") },
                    text = {
                        Column {
                            Text(
                                "A relay you run is a last resort for peers direct routes cannot reach. It pairs devices and shuttles bytes only — files, names and identities stay end-to-end. Leave the address empty to turn it off.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = relayAddressText,
                                onValueChange = { relayAddressText = it.take(256) },
                                label = { Text("Address (host:port)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            OutlinedTextField(
                                value = relayTokenText,
                                onValueChange = { relayTokenText = it.take(128) },
                                label = { Text("Shared token (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showRelayDialog = false
                            prefs().edit()
                                .putString("relayAddress", relayAddressText.trim())
                                .putString("relayToken", relayTokenText.trim())
                                .apply()
                            FlitEngine.relayChanged()
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRelayDialog = false }) { Text("Cancel") }
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
        onShowKnown: () -> Unit,
        knownCount: Int,
        showTheme: () -> Unit,
        showBattery: () -> Unit,
        showRelay: () -> Unit,
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OptionRow(
                        title = "Known devices",
                        subtitle = if (knownCount == 0) {
                            "no paired devices yet — add one by QR code"
                        } else {
                            "$knownCount paired — choose how each one is reached"
                        },
                        onClick = onShowKnown,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OptionRow(
                        title = "Theme",
                        subtitle = when (theme) {
                            "dark" -> "dark"
                            "light" -> "light"
                            else -> "follow the system"
                        },
                        onClick = showTheme,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Only trusted devices can send me files",
                        subtitle = "offers from devices you haven't trusted are declined automatically",
                        initial = prefs().getBoolean("onlyTrusted", false),
                    ) { on ->
                        prefs().edit().putBoolean("onlyTrusted", on).apply()
                        if (on && state.running) FlitEngine.toast("takes effect for new offers now")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Hidden on the network",
                        subtitle = "stop announcing — nobody lists you until you send first, or they add you by address",
                        initial = prefs().getBoolean("hiddenMode", false),
                    ) { on ->
                        prefs().edit().putBoolean("hiddenMode", on).apply()
                        if (state.running) {
                            stopForRebind() // rebind, not an explicit Stop
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (isDestroyed) return@postDelayed
                                startSharing()
                                FlitEngine.toast(if (on) "hidden from now on" else "visible again")
                            }, 700)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "QR-only mode",
                        subtitle = "only QR-paired devices can connect — no announcing or scanning",
                        initial = FlitEngine.qrOnly(),
                    ) { on -> FlitEngine.setQrOnly(on) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Start on device boot",
                        subtitle = "be reachable again right after a reboot (best effort — tight systems allow it only after the first unlock)",
                        initial = prefs().getBoolean("startOnBoot", false),
                    ) { on ->
                        prefs().edit().putBoolean("startOnBoot", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Sound when transfers finish",
                        subtitle = "a short chime plays after a completed transfer",
                        initial = prefs().getBoolean("transferSound", false),
                    ) { on ->
                        prefs().edit().putBoolean("transferSound", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Keep the screen on while transferring",
                        subtitle = "stops the system from dozing a transfer you're watching",
                        initial = keepOn,
                    ) { on -> setKeepOn(on) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Crush files when sending",
                        subtitle = "text, notes, logs, JSON and code are gzip'd on the way — Flit receivers unpack them automatically",
                        initial = prefs().getBoolean("crushSend", false),
                    ) { on ->
                        prefs().edit().putBoolean("crushSend", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Parallel streams on Wi-Fi",
                        subtitle = "splits big files across several sockets for extra speed — only between Flit devices, every other receiver just gets the normal single-stream send",
                        initial = prefs().getBoolean("parallelStreams", true),
                    ) { on ->
                        prefs().edit().putBoolean("parallelStreams", on).apply()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchOption(
                        title = "Transfer over Bluetooth",
                        subtitle = "direct link when devices are close — slower than Wi-Fi; both devices need sharing on, this option enabled, and a one-time pairing consent",
                        initial = prefs().getBoolean("bluetoothTransfer", false),
                    ) { on ->
                        prefs().edit().putBoolean("bluetoothTransfer", on).apply()
                        FlitEngine.bluetoothChanged()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val relayAddress = prefs().getString("relayAddress", "") ?: ""
                    OptionRow(
                        title = "Relay (optional)",
                        subtitle = if (relayAddress.isBlank()) {
                            "off — leave empty to never use one; set a host:port you trust to reach peers that cannot answer directly"
                        } else {
                            "$relayAddress — tried last, only for files waiting on a peer direct routes cannot find"
                        },
                        onClick = showRelay,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (Build.VERSION.SDK_INT >= 29) {
                        var showMirrorPicker by remember { mutableStateOf(false) }
                    var mirrorTarget by remember { mutableStateOf(FlitRoster.mirrorTarget()) }
                    val mirrorOn = mirrorOnState.value
                    val setMirror: (Boolean) -> Unit = { v ->
                        if (v && !mediaReadGranted()) {
                            mirrorPerms.launch(mirrorPermissions())
                        } else {
                            mirrorOnState.value = v
                            FlitRoster.setMirrorOn(v)
                            if (v) FlitEngine.mirrorSeed(this@MainActivity)
                            FlitEngine.mirrorRefresh()
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = mirrorOn, role = Role.Switch, onValueChange = setMirror)
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
                        Switch(
                            checked = mirrorOn,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    OptionRow(
                        title = "Mirror to",
                        subtitle = when (val fp = mirrorTarget) {
                            null -> "choose a trusted device — new camera shots go to it"
                            else -> FlitRoster.aliasFor(fp) ?: PeerAlias.of(fp)?.text ?: fp
                        },
                        onClick = { showMirrorPicker = true },
                    )
                    if (showMirrorPicker) {
                        val pinned = FlitRoster.all()
                        MaterialTheme(colorScheme = FlitThemeColors(theme)) { // MIUI dialog windows resolve a light scheme — pin ours
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
                                                    p.alias.ifBlank { PeerAlias.of(p.fingerprint)?.text ?: p.fingerprint },
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
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

    /**
     * Circle file drop: copy out of SAF into app cache (grant-proof), hash it,
     * then post a file event carrying only name/size/SHA-256. The spool path
     * stays local; bytes reach members through Flit's existing sendFiles path.
     */
    private fun circleAttach(uri: Uri) {
        val resolver = contentResolver
        val name = resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            } else null
        }?.ifBlank { "file" } ?: "file"
        val safe = name.replace(Regex("[\\/:*?\"<>|]"), "_").takeLast(64)
        val dir = File(cacheDir, "circle").apply { mkdirs() }
        val dest = File(dir, "${System.currentTimeMillis()}-$safe")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            val input = resolver.openInputStream(uri) ?: run {
                FlitEngine.toast("could not read that file")
                return
            }
            input.use { stream ->
                java.io.FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        size += n
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            FlitEngine.circlePostFile(name, size, sha, dest.absolutePath)
        } catch (_: Exception) {
            FlitEngine.toast("could not attach that file")
        }
    }

    /**
     * Show input: seal a locally picked file for a locally supplied recipient
     * certificate/fingerprint. The signer is the app's own identity; no frame
     * carries a file name or a certificate.
     */
    private fun makeParcelFrames(
        identity: TlsIdentity,
        recipientFp: String,
        certText: String,
        payload: ByteArray,
    ): List<String>? {
        val fp = recipientFp.trim().uppercase(Locale.ROOT)
        if (fp.isBlank()) {
            FlitEngine.toast("enter the recipient fingerprint")
            return null
        }
        if (payload.isEmpty() || payload.size > ParcelCodec.MAX_AIR_GAP_PARCEL_BYTES) {
            FlitEngine.toast("pick a file within the parcel size limit")
            return null
        }
        val cert = runCatching {
            val der = Base64.getDecoder().decode(certText.filterNot { it.isWhitespace() })
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        }.getOrNull()
        if (cert == null) {
            FlitEngine.toast("could not read the recipient certificate")
            return null
        }
        val certFp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull()
        if (certFp == null || !certFp.equals(fp, ignoreCase = true)) {
            FlitEngine.toast("certificate does not match that fingerprint")
            return null
        }
        val frames = ParcelCodec.encodeParcel(
            payload,
            UUID.randomUUID().toString().replace("-", ""),
            fp,
            cert,
            identity.fingerprint,
            identity,
        )
        if (frames.isEmpty()) {
            FlitEngine.toast("could not seal a parcel — nothing to show")
            return null
        }
        return frames
    }

    /**
     * Writer injected into the QR parcel screen. Verified bytes land in the
     * same received bucket the engine's receive path uses: MediaStore
     * Downloads/Flit. No file name travels in a frame, so the name is local.
     */
    private fun writeParcelReceived(bytes: ByteArray) {
        val name = "parcel-${System.currentTimeMillis()}.bin"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Flit")
        }
        val uri = runCatching {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        if (uri == null) {
            FlitEngine.logLine("parcel: could not create Downloads/Flit/$name")
            FlitEngine.toast("could not save the parcel")
            return
        }
        val written = runCatching {
            val out = contentResolver.openOutputStream(uri) ?: return@runCatching false
            out.use { it.write(bytes) }
            true
        }.getOrDefault(false)
        if (written) {
            FlitEngine.recordParcelReceived(name, bytes.size, uri.toString())
            FlitEngine.logLine("parcel → Downloads/Flit/$name (${FlitEngine.fmtBytes(bytes.size.toLong())})")
            FlitEngine.toast("parcel saved to Downloads/Flit")
        } else {
            runCatching { contentResolver.delete(uri, null, null) }
            FlitEngine.logLine("parcel: could not write $name")
            FlitEngine.toast("could not save the parcel")
        }
    }

    /**
     * Resolve a ledger entry to a readable content URI + MIME. The WHOLE
     * construction — including FileProvider.getUriForFile and the resolver's
     * type lookup — is guarded. A stale path, an authority mismatch or a
     * revoked grant ends in a toast, never an exception on the main thread.
     */
    private fun resolveReceived(r: FlitEngine.ReceivedFile): Pair<Uri, String>? = runCatching {
        val u = when {
            r.uri != null -> Uri.parse(r.uri)
            r.path != null -> {
                // A file moved to Downloads/Flit after receipt has no local
                // copy left; handing out a FileProvider URI to it is a lie.
                if (!r.path.isFile) throw IllegalStateException("file gone")
                FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, r.path)
            }
            else -> null
        } ?: throw IllegalStateException("no location stored")
        // Direct-to-storage entries carry a content URI: its own declared type
        // beats a guess from the file name.
        val m = if (r.uri != null) {
            contentResolver.getType(u) ?: FlitEngine.guessMime(r.name)
        } else {
            FlitEngine.guessMime(r.name)
        }
        u to m
    }.getOrNull()

    private fun openReceived(r: FlitEngine.ReceivedFile) {
        val (uri, mime) = resolveReceived(r) ?: run {
            FlitEngine.toast("no location stored for ${r.name}")
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The grant rides on the base intent AND the chooser: OEM resolvers
            // may rebuild EXTRA_INTENT, and ClipData keeps the read permission.
            clipData = ClipData.newRawUri(r.name, uri)
        }
        val chooser = Intent.createChooser(view, r.name).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(r.name, uri)
        }
        // Explicit, lifecycle-bound launch: a viewer result can never finish us.
        runCatching { openExternal.launch(chooser) }
            .onFailure { FlitEngine.toast("nothing can open ${r.name}") }
    }

    private fun shareReceived(r: FlitEngine.ReceivedFile) {
        val (uri, mime) = resolveReceived(r) ?: run {
            FlitEngine.toast("no location stored for ${r.name}")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(r.name, uri)
        }
        val chooser = Intent.createChooser(send, "Share ${r.name}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(r.name, uri)
        }
        runCatching { openExternal.launch(chooser) }
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
                .toggleable(
                    value = on,
                    role = Role.Switch,
                    onValueChange = { v ->
                        on = v
                        onChange(v)
                    },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            // The row owns the switch semantics and the whole-row tap target;
            // the thumb is decoration only (no duplicate toggle node).
            Switch(
                checked = on,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }

    /** One incoming alert. Every action is an explicit tap — no auto-response. */
    @Composable
    private fun PanicBanner(event: FlitEngine.PanicEvent) {
        val cs = MaterialTheme.colorScheme
        val from = event.alias?.takeIf { it.isNotBlank() } ?: PeerAlias.of(event.senderFp)?.text ?: event.senderFp
        Surface(
            shape = RoundedCornerShape(FlitCorner.large),
            color = cs.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Panic alert from $from · ${ts(event.atMs)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onErrorContainer,
                )
                if (event.text.isNotBlank()) {
                    Text(
                        event.text.take(280),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onErrorContainer,
                    )
                }
                Text(
                    "Nothing happens to this device automatically. Forward sends the alert to your known devices only when you tap it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onErrorContainer,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { FlitEngine.acknowledgePanic(event.id) }) {
                        Text("Acknowledge", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { FlitEngine.dismissPanic(event.id) }) {
                        Text("Dismiss", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { FlitEngine.forwardPanic(event.id) }) {
                        Text("Forward", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    /** QR-paired devices and the route chosen for each one. */
    @Composable
    private fun KnownDevicesSheet(
        devices: List<FlitKnown.Device>,
        onClose: () -> Unit,
    ) {
        var policyFor by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Known devices") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (devices.isEmpty()) {
                        Text(
                            "No known devices yet — add one by QR code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (device in devices) {
                        val fp = device.fingerprint
                        Surface(
                            shape = RoundedCornerShape(FlitCorner.medium),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            device.alias.ifBlank { PeerAlias.of(fp)?.text ?: fp },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "fp ${fp.take(12)}…",
                                            style = MaterialTheme.typography.bodySmall
                                                .copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { FlitEngine.forgetKnown(fp) }) {
                                        Text("Forget", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                OptionRow(
                                    title = "Route",
                                    subtitle = routeLabel(device.routePolicy),
                                    onClick = { policyFor = fp },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Relay for this device", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "use a relay as a last resort for this device",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Switch(
                                        checked = device.relayFor,
                                        onCheckedChange = { on -> FlitEngine.setRelayFor(fp, on) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = onClose) { Text("Done") } },
        )
        policyFor?.let { fp ->
            val current = devices.firstOrNull { it.fingerprint == fp }?.routePolicy
                ?: FlitKnown.RoutePolicy.AUTO
            AlertDialog(
                onDismissRequest = { policyFor = null },
                title = { Text("Route to device") },
                text = {
                    Column {
                        for (policy in FlitKnown.RoutePolicy.values()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = policy == current,
                                        role = Role.RadioButton,
                                        onClick = {
                                            FlitEngine.setRoutePolicy(fp, policy)
                                            policyFor = null
                                        },
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = policy == current, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    routeLabel(policy),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (policy == current) {
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
                    TextButton(onClick = { policyFor = null }) { Text("Cancel") }
                },
            )
        }
    }

    private fun routeLabel(policy: FlitKnown.RoutePolicy): String = when (policy) {
        FlitKnown.RoutePolicy.AUTO -> "automatic"
        FlitKnown.RoutePolicy.WIFI_ONLY -> "Wi‑Fi only"
        FlitKnown.RoutePolicy.BLUETOOTH_ONLY -> "Bluetooth only"
        FlitKnown.RoutePolicy.RELAY_ONLY -> "relay only"
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
                        .background(color, CircleShape),
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
            modifier = Modifier.size(48.dp), // 48dp touch target; 40dp visual below
        ) {
            // Unclipped wrapper: the background + icon are clipped to the
            // pressed shape, but the badge is a sibling drawn on top so the
            // circle clip can't shave it into a crescent.
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
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
                }
                if (badge > 0) {
                    // Surface-colored ring separates the dot from the icon/tint
                    // so it reads as a deliberate notification mark, not a smudge.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(14.dp)
                            .background(cs.surfaceContainerHighest, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(cs.error, CircleShape),
                        )
                    }
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
