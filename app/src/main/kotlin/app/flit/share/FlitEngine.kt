package app.flit.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import app.flit.share.protocol.Protocol
import app.flit.share.protocol.client.OutgoingFile
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendReport
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.discovery.UdpDiscovery
import app.flit.share.protocol.server.IncomingSink
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.server.UploadResult
import app.flit.share.protocol.tls.TlsIdentity
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Peers vanish from the list after this long without an announce/register. */
const val PEER_TTL_MS = 30_000L

/** Rescue sweep cadence: one walk per this many ms while discovery is silent. */
const val SWEEP_INTERVAL_MS = 120_000L

/** Found peers stay fresh by cheap targeted re-registers between walks. */
const val SWEEP_KEEP_INTERVAL_MS = 15_000L

/** Consecutive keeper misses that retire a found peer's row. */
const val SWEEP_KEEP_MAX_MISSES = 3

internal const val CHANNEL_SERVICE = "service"
internal const val CHANNEL_TRANSFERS = "transfers"
/**
 * App-side engine: one identity, one server, one discovery socket, one registry.
 * Lives in the process; started by [FlitService] and observed by the UI.
 */
object FlitEngine {

    // ==== public observable state ==============================================

    data class EngineState(
        val running: Boolean = false,
        val starting: Boolean = false,
        val alias: String = "",
        val port: Int = Protocol.DEFAULT_PORT,
        val scheme: String = "https",
        val fingerprintShort: String = "",
        val error: String? = null,
    )

    data class PeerEntry(
        val ip: String,
        val info: PeerInfo,
        val lastSeenMs: Long,
    ) {
        // Trust wins over the wire: a locally pinned/renamed alias shadows
        // whatever the announce carries; a device we know nothing about still
        // labels its row honestly with its address. (Fixes rows rendering
        // blank/raw for peers whose announce lacked an alias.)
        val displayName: String
            get() = FlitRoster.aliasFor(info.fingerprint) ?: info.alias.ifBlank { address }
        // A direct-radio row has no address-bearing socket, hence port 0 — and
        // a port without a network would be a lie on the wire row. Only the
        // address speaks for it.
        val address: String
            get() = if (info.port > 0) "$ip:${info.port}" else ip
    }

    data class LogLine(val ts: String, val text: String)

    data class ReceivedFile(
        val name: String,
        val bytes: Long,
        val whenMs: Long,
        val path: File?,
        /** MediaStore/SAF uri for direct-to-storage receives (tap-to-open, share). */
        val uri: String? = null,
    )

/** Intake from the system share sheet: files and/or a text note. */
/** A shared stream already copied into our cache — grant-proof and queueable. */
data class SpooledFile(val f: File, val name: String, val mime: String) {
    val size: Long get() = f.length()
}

/**
 * Intake from the system share sheet: streams are SPOOLED into app cache the
 * moment the intent lands (before the user picks a peer), so later reads never
 * depend on the sender's non-persistable URI grants.
 */
data class ShareIntake(
    val ready: Boolean,
    val files: List<SpooledFile>,
    val text: String?,
    val sourceApp: String?,
    /** Files picked in-app (dock "Send") — document URIs, valid while staged. */
    val uris: List<String> = emptyList(),
)

private val _share = MutableStateFlow<ShareIntake?>(null)
val share: StateFlow<ShareIntake?> = _share

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    /**
     * A pending ask from the no-AP moment (see [FlitBle.raiseAsk]). The
     * surface carries it to the system dialogs when it can; whichever way
     * they conclude, [directAskAnswered] closes the book — one ask, once.
     */
    private val _directAsk = MutableStateFlow<FlitBle.DirectAsk?>(null)
    val directAsk: StateFlow<FlitBle.DirectAsk?> = _directAsk

    private val _peers = MutableStateFlow<Map<String, PeerEntry>>(emptyMap())

    /**
     * Display helper: a `manual@` stub is hidden once the device it stands for
     * is actually reachable (same ip:port, real fingerprint). The stub itself
     * survives in `_peers`, so the row reappears when the device goes quiet.
     */
    fun dedupedPeers(
        raw: Map<String, PeerEntry> = _peers.value,
        selfFp: String? = identity?.fingerprint,
    ): Map<String, PeerEntry> {
        val m = raw
        val covered = m.entries
            .filterNot { it.key.startsWith("manual@") }
            .map { it.value.ip to it.value.info.port }
            .toSet()
        return m
            // Self-announces that slip in before my identity loads (loopback
            // multicast ghosts) are not devices: this is the UI-seam belt to
            // the fingerprint check at ingest. The pre-fix bug also recorded
            // loopback manual stubs — 127.x is never a neighbor.
            .filterNot { (_, v) -> selfFp != null && v.info.fingerprint == selfFp }
            .filterNot { (k, v) ->
                k.startsWith("manual@") &&
                    v.ip.let { it.startsWith("127.") || it == "::1" || it == "0.0.0.0" }
            }
            .filterNot { (k, v) -> k.startsWith("manual@") && (v.ip to v.info.port) in covered }
    }

    val peers: StateFlow<Map<String, PeerEntry>> = _peers

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log

    private val _received = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val received: StateFlow<List<ReceivedFile>> = _received

    // ==== received history ======================================================

    /** Entries survive restarts: a tiny (~1 KB) JSON in app-private files. */
    private const val RECEIVED_HISTORY_FILE = "received-history.json"
    private var receivedLoaded = false

    /** Disk mirror of [ReceivedFile] (paths/uris as strings — File isn't JSON). */
    @Serializable
    private data class ReceivedRec(
        val name: String,
        val bytes: Long,
        val whenMs: Long,
        val path: String? = null,
        val uri: String? = null,
    )

    private val receivedJson = Json { ignoreUnknownKeys = true }

    /** Payload → rows (pure core, unit-tested without an Android context). */
    internal fun decodeReceived(payload: String): List<ReceivedFile> =
        runCatching {
            receivedJson.decodeFromString(ListSerializer(ReceivedRec.serializer()), payload)
        }.getOrDefault(emptyList()).map {
            ReceivedFile(it.name, it.bytes, it.whenMs, it.path?.let(::File), it.uri)
        }

    internal fun encodeReceived(list: List<ReceivedFile>): String {
        val rows = list.map {
            ReceivedRec(it.name, it.bytes, it.whenMs, it.path?.absolutePath, it.uri)
        }
        return receivedJson.encodeToString(ListSerializer(ReceivedRec.serializer()), rows)
    }

    /** One history row on any receive path, then the store is rewritten. */
    private fun addReceived(r: ReceivedFile) {
        _received.update { (it + r).takeLast(20) }
        persistReceived()
    }

    /** Loaded at app boot: history is visible before the engine ever starts. */
    private fun loadReceived(boot: Context) {
        if (receivedLoaded) return
        receivedLoaded = true
        runCatching {
            val f = boot.filesDir.resolve(RECEIVED_HISTORY_FILE)
            _received.value = decodeReceived(f.takeIf { it.length() > 0 }?.readText() ?: "").takeLast(20)
        }
    }

    private fun persistReceived() {
        runCatching { appCtx.filesDir.resolve(RECEIVED_HISTORY_FILE).writeText(encodeReceived(_received.value)) }
    }

    private val _queue = MutableStateFlow<List<QueuedSend>>(emptyList())
    val queue: StateFlow<List<QueuedSend>> = _queue

    /** Live "send again" target — rehydrated on start, updated after each successful send. */
    private val _lastPeer = MutableStateFlow<PeerEntry?>(null)
    val lastPeerFlow: StateFlow<PeerEntry?> = _lastPeer

    /** True while any transfer (send or receive) runs — used for keep-screen-on. */
    private val _transferActive = MutableStateFlow(false)
    val transferActive: StateFlow<Boolean> = _transferActive
    fun setTransferActive(v: Boolean) { _transferActive.value = v }

    /** Live transfer readout for the dock badge and home hero (null = idle). */
    data class TransferTelemetry(
        val isSend: Boolean,
        val doneBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
    )

    private val _transfer = MutableStateFlow<TransferTelemetry?>(null)
    val transfer: StateFlow<TransferTelemetry?> = _transfer
    internal fun pushTelemetry(t: TransferTelemetry?) { _transfer.value = t }

    /** The last successfully-served peer (fingerprint, alias) for "send again". */
    fun lastPeer(): Pair<String, String>? {
        val fp = prefs().getString("lastPeerFp", null) ?: return null
        val alias = prefs().getString("lastPeerAlias", null) ?: return null
        return fp to alias
    }

    /**
     * Rehydrated last-served peer for the one-tap "send again": works even if
     * the peer is offline right now — a failed dispatch falls into the
     * send-later queue and completes when it reappears.
     */
    fun lastPeerEntry(): PeerEntry? {
        val ip = prefs().getString("lastPeerIp", null) ?: return null
        val fp = prefs().getString("lastPeerFp", null) ?: return null
        val alias = prefs().getString("lastPeerAlias", null) ?: return null
        val port = prefs().getInt("lastPeerPort", Protocol.MULTICAST_PORT)
        return PeerEntry(
            ip = ip,
            info = PeerInfo(
                alias = alias, version = Protocol.VERSION, deviceModel = null,
                deviceType = "device", fingerprint = fp, port = port, protocol = "https",
            ),
            lastSeenMs = 0,
        )
    }
    // ==== internal fields =======================================================

        private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                logLine("internal error: ${e.message ?: e.javaClass.simpleName}")
            }
    )
    private lateinit var appCtx: Context
    private var identity: TlsIdentity? = null
    private var server: ProtocolServer? = null
    private var client: ProtocolClient? = null
    private var discovery: UdpDiscovery? = null
    private var bleRadio: FlitBleRadio? = null

    /** What the flipper last decided for the radio's advertise half (log once per flip). */
    @Volatile
    private var bleAdvertised = false
    private var nextSweepAt = 0L
    private var sweepEmptyLogged = false
    // The keeper: peers a walk found, kept fresh between walks (fp → host).
    private val sweepKept = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sweepKeptMisses = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sweepKeptNextAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var mirrorObserver: android.os.FileObserver? = null
    private var startSeq = 0

    private val delegate = FlitDelegate()

    private class FlitDelegate : ServerDelegate {

        // one receive session at a time; ledger set at prepare, nulled at session end
        private var rxProgress: FlitProgress? = null
        private var rxBegun = false
        private var rxFiles: Map<String, Long> = emptyMap()
        var rxSenderFp: String = ""
        private val rxMarks = HashMap<String, LongArray>()

        override fun onRegister(info: PeerInfo, ip: String) {
            FlitEngine.touchPeer(ip, info)
        }

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision {
            // Trust gate (Options): an untrusted offer is declined outright —
            // the sender's UI shows it the way people expect a refusal to
            // show. Only the sender's fingerprint decides, never its IP.
            if (prefs().getBoolean("onlyTrusted", false) &&
                !FlitRoster.pinned(request.info.fingerprint)
            ) {
                logLine("declined offer from ${request.info.alias} ($peerIp) — device not trusted")
                return PrepareDecision.Decline
            }
            FlitEngine.logLine("offer from ${request.info.alias} ($peerIp): ${request.files.size} file(s)")
            rxFiles = request.files.values.associate { it.fileName to it.size }
            rxSenderFp = request.info.fingerprint
            rxBegun = false
            rxProgress = null
            return PrepareDecision.Accept(request.files.keys)
        }

        override fun onResumeStart(sessionId: String, fileId: String, file: FileInfo, prefixBytes: Long) {
            logLine("resuming ${file.fileName} at ${fmtBytes(prefixBytes)}")
        }

        override fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {
            FlitEngine.logLine("receiving ${file.fileName}")
            if (!rxBegun) {
                rxBegun = true
                (rxProgress ?: FlitProgress(FlitEngine.ctx(), FlitEngine.RECV_PROGRESS_ID)
                    .also { rxProgress = it })
                    .begin(FlitProgress.Kind.RECEIVE, rxFiles)
            }
        }

        override fun onUploadProgress(sessionId: String, fileId: String, file: FileInfo, bytes: Long, totalBytes: Long) {
            rxProgress?.tick(file.fileName, bytes)
            // Paced throughput marks every 16 MiB (and at EOF) so slow
            // receive paths are visible in the log instead of guessed at.
            val m = rxMarks.getOrPut(fileId) { longArrayOf(0, System.nanoTime()) }
            if (bytes - m[0] >= (16L shl 20)) {
                val now = System.nanoTime()
                val dt = ((now - m[1]) / 1e9).coerceAtLeast(0.001)
                val seg = (bytes - m[0]) / dt / (1024.0 * 1024.0)
                logLine("rx ${fmtBytes(bytes)}/${fmtBytes(totalBytes)} pace %.1f MiB/s".format(seg))
                m[0] = bytes
                m[1] = now
            }
        }

        override fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {
            when (result) {
                is UploadResult.Saved -> {
                    FlitEngine.logLine("saved ${file.fileName} (" + FlitEngine.fmtBytes(result.bytes) + ")")
                    FlitEngine.publishReceived(result.path.toFile(), file, rxSenderFp)
                }
                is UploadResult.Streamed -> {
                    // ledger entry + landing log already produced by the sink
                }
                UploadResult.Mismatch -> FlitEngine.logLine("BAD ${file.fileName}: checksum mismatch")
                UploadResult.Error -> FlitEngine.logLine("error writing ${file.fileName}")
            }
            rxProgress?.fileDone(file.fileName)
        }

        override fun onSessionEnd(sessionId: String, cancelled: Boolean) {
            if (cancelled) {
                FlitEngine.logLine("transfer cancelled")
                rxProgress?.finish("receiving cancelled", ok = false, iconDone = false)
            } else {
                val got = rxFiles.size
                val tag = if (got == 1) "received 1 file" else "received $got files"
                rxProgress?.finish("$tag from Flit", ok = true, iconDone = true)
            }
            rxProgress = null
            rxBegun = false
        }
    }

    // ==== lifecycle =============================================================

    /** App-boot context (set by FlitApp), so prefs work before any engine start. */
    @Volatile
    private var bootCtx: Context? = null

    fun initAppCtx(ctx: Context) {
        if (bootCtx == null) bootCtx = ctx.applicationContext
        loadReceived(bootCtx!!)
    }

    fun prefs(): android.content.SharedPreferences =
        (if (::appCtx.isInitialized) appCtx else bootCtx)
            ?.getSharedPreferences("flit", Context.MODE_PRIVATE)
            ?: throw IllegalStateException("no context yet for prefs")

    /** For nested/delegate access to the app context. */
    internal fun ctx(): Context = appCtx

    /**
     * Where user-facing feedback goes: set while the main UI is visible
     * (messages ride the in-app snackbar), null otherwise so they fall back to
     * system toasts — parity for flows started while backgrounded (dock peer
     * send, roster pin renewals, …). Set/cleared from the MAIN thread on
     * lifecycle ON_START/ON_STOP; read on engine threads via volatile.
     */
    @Volatile
    internal var uiSink: ((String) -> Unit)? = null

    internal fun toast(msg: String) {
        // Snapshot once: a message racing the visible/hidden boundary either
        // rides the snackbar or falls back to the system toast — never lost.
        val sink = uiSink
        if (sink != null) {
            sink(msg)
            return
        }
        runCatching { android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** Stable id for the single-session receive progress notification. */
    const val RECV_PROGRESS_ID = 51

    fun openAppIntent() = android.app.PendingIntent.getActivity(
        appCtx, 0,
        android.content.Intent(appCtx, MainActivity::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    fun currentAlias(): String = prefs().getString("alias", null) ?: defaultAlias()

    private fun defaultAlias(): String = "Flit \u00b7 ${Build.MODEL.take(24)}"

    fun setAlias(alias: String) {
        prefs().edit().putString("alias", alias.trim().ifEmpty { defaultAlias() }).apply()
        // Applies on the next start; the UI hints about toggling sharing to renew.
    }

    fun start(ctx: Context) {
        if (_state.value.running || _state.value.starting) return
        appCtx = ctx.applicationContext
        FlitRoster.init(appCtx)
        _queue.value = FlitRoster.queued() // pick up send-later entries from a previous run
        addManualStubs() // previously added by-address devices stay listed
        // Test-only dead-link emulation (see PeerHttpClient.testAbortBytes).
        debugProp("debug.flit.abortbytes")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { app.flit.share.protocol.client.PeerHttpClient.testAbortBytes = it }
        _state.value = _state.value.copy(starting = true, error = null)
        val seq = ++startSeq
        scope.launch(Dispatchers.IO) { run(seq) }
    }

    fun stop() {
        startSeq++
        stopMirrorWatcher()
        runCatching { discovery?.stop() }
        runCatching { server?.close() }
        runCatching { client?.close() }
        runCatching { bleRadio?.stopAll() }
        bleRadio = null
        bleAdvertised = false
        nextSweepAt = 0L
        sweepEmptyLogged = false
        sweepKept.clear()
        sweepKeptMisses.clear()
        sweepKeptNextAt.clear()
        huntNextAt.clear()
        huntAttempts.clear()
        huntLogged.clear()
        _directAsk.value = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        server = null
        client = null
        discovery = null
        _peers.value = emptyMap()
        _state.value = _state.value.copy(running = false, starting = false)
        logLine("stopped")
    }

    /**
     * The surface reported a conclusion of a no-AP ask's system sheet. Each
     * launcher names the question it rode; a stale conclusion (the sheet out
     *living the ask that published it) closes its own book without touching
     * whatever question is pending now. Any conclusion counts — grant, deny,
     * dismissal — that closing is what makes "one ask, once per device"
     * real: the flag lives in the engine's own store and survives every
     * restart. The facts themselves are re-verified every tick, so a grant
     * lights the radio and a grant refusal leaves it dark — quietly.
     */
    fun directAskAnswered(kind: FlitBle.DirectAsk?) {
        when (kind) {
            FlitBle.DirectAsk.PERMISSIONS -> prefs().edit().putBoolean("directAskPermsDone", true).apply()
            FlitBle.DirectAsk.POWER -> prefs().edit().putBoolean("directAskPowerDone", true).apply()
            null -> return
        }
        if (_directAsk.value == kind) _directAsk.value = null
    }

    private suspend fun run(seq: Int) {
        try {
            val id = identity ?: loadOrCreateIdentity(appCtx).also { identity = it }
            val alias = currentAlias()

            // UDP discovery sockets are created BEFORE egress pinning as a
            // precaution: netd-marked sockets showed suspicious multicast
            // behavior on this device, and unmarked discovery sockets cost
            // nothing. The pin runs right after disc.create() and before any
            // TLS client exchange, so registers and transfers still egress
            // via Wi-Fi even when MIUI defaults them to mobile data.
            val dest = (appCtx.getExternalFilesDir(null) ?: appCtx.filesDir)
                .resolve("received").also { it.mkdirs() }

            // One sweep per start: spool copies whose session died (killed
            // process) and that the send-later queue doesn't remember. The
            // queue's whitelist wins; young staging files stay out of this.
            sweepStaleSpools()

            val srv = ProtocolServer(
                id, delegate,
                ServerConfig(
                    alias = alias,
                    destDir = dest.toPath(),
                    deviceModel = Build.MODEL.take(40),
                    deviceType = "mobile",
                    // Receive straight into MediaStore (no temp+copy roundtrip);
                    // files for pinned peers' custom folders still take the
                    // file path so publishReceived can copyToTree them.
                    incomingSink = { _, info -> openIncomingSink(info, receiveTreeFor(delegate.rxSenderFp)) },
                ),
            )
            srv.start()

            val cli = ProtocolClient(id)

            // Android's kernel drops multicast frames unless we hold this lock.
            val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("flit-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }

            val disc = UdpDiscovery(
                udpPort = Protocol.MULTICAST_PORT,
                own = srv::selfInfo,
                onPeerDiscovered = ::touchPeer,
                registerPoster = { ip, port, protocol, self ->
                    val t = SendTarget(ip, port, protocol == "https")
                    try {
                        cli.register(t, self) ?: logLine("register to $ip:$port failed")
                    } catch (e: Exception) {
                        logLine("register to $ip:$port: ${e.message ?: e.javaClass.simpleName}")
                    }
                },
                // Hidden mode: stay off other devices' rosters entirely.
                announce = !prefs().getBoolean("hiddenMode", false),
            )
            disc.create()

            val cm = appCtx.getSystemService(android.net.ConnectivityManager::class.java)
            val wifiNet = wifiNetwork(cm)
            when {
                debugProp("debug.flit.nopin") == "1" ->
                    logLine("egress pin skipped (debug.flit.nopin)")
                wifiNet != null -> {
                    val bound = runCatching { cm.bindProcessToNetwork(wifiNet) }.getOrDefault(false)
                    logLine(if (bound) "egress pinned to Wi\u2011Fi network" else "could not pin egress; using default network")
                }
                else -> logLine("no Wi\u2011Fi network; using default network")
            }

            disc.start()

            // Direct-peer radio: dormant while the roster is Wi-Fi's (the
            // flipper decides from real facts each tick); the radio itself is
            // built here so a start failure below can still tear it down.
            val radio = FlitBleRadio(appCtx, ::touchBlePeer, ::logLine)
            bleRadio = radio

            if (seq != startSeq) { // stopped/restarted meanwhile
                runCatching { disc.stop(); srv.close(); cli.close() }
                runCatching { radio.stopAll() }
                runCatching { lock.takeIf { it.isHeld }?.release() }
                return
            }

            server = srv
            client = cli
            discovery = disc
            multicastLock = lock

            _state.value = EngineState(
                running = true,
                alias = alias,
                port = srv.port,
                scheme = srv.scheme,
                fingerprintShort = id.fingerprint.take(12),
            )
            logLine("serving ${srv.scheme}://0.0.0.0:${srv.port} as \u201c$alias\u201d")
            if (prefs().getBoolean("hiddenMode", false)) {
                logLine("hidden mode: not announcing — others find this device by address, or when you send first")
            }

            startPruner(seq)
            startManualProber(seq)
            startBleFlipper(seq)
            startSweeper(seq)
            startHunter(seq)
            startMirrorWatcher(seq)
            _lastPeer.value = lastPeerEntry()
        } catch (t: Throwable) {
            _state.value = _state.value.copy(running = false, starting = false, error = t.message ?: t.javaClass.simpleName)
            logLine("start failed: ${t.message ?: t.javaClass.simpleName}")
            // Full stack goes to logcat (obfuscated in release; use mapping.txt to deobfuscate).
            android.util.Log.e("FlitEngine", "engine start failed", t)
            runCatching { bleRadio?.stopAll() }
        }
    }

    /** Registry reaper: peers disappear when they stop announcing. */
    private fun startPruner(seq: Int) {
        scope.launch(Dispatchers.IO) {
            while (isActive && seq == startSeq) {
                delay(5_000)
                if (seq != startSeq) return@launch
                val cutoff = System.currentTimeMillis() - PEER_TTL_MS
                _peers.update { m ->
                    m.filterValues { it.lastSeenMs >= cutoff || it.info.fingerprint.startsWith("manual@") }
                }
            }
        }
    }

    /**
     * By-address rows stay honest in the background: every cycle we re-register
     * with the typed address. A live device then shows its real identity (and
     * stays fresh despite the TTL); an offline one keeps its stub row so the
     * user can still queue sends for it.
     */
    private fun startManualProber(seq: Int) {
        scope.launch(Dispatchers.IO) {
            while (isActive && seq == startSeq) {
                delay(20_000)
                if (seq != startSeq) return@launch
                val cli = client ?: return@launch
                val self = server?.selfInfo() ?: continue
                for (addr in _peers.value.keys.filter { it.startsWith("manual@") }) {
                    if (seq != startSeq) return@launch
                    val host = addr.removePrefix("manual@").substringBeforeLast(':')
                    val port = addr.substringAfterLast(':').toIntOrNull() ?: Protocol.DEFAULT_PORT
                    if (host.isBlank()) continue
                    val real = try {
                        cli.register(SendTarget(host, port, true), self, timeoutMs = 3_000)
                    } catch (e: Exception) {
                        logLine("mprober $host:$port: ${e.message ?: e.javaClass.simpleName}")
                        null
                    }
                    if (real != null) {
                        touchPeer(host, real)
                        logLine("mprober $host:$port ok")
                    } else {
                        logLine("mprober $host:$port: no answer")
                    }
                }
            }
        }
    }

    /**
     * The rescue sweep: a multicast-blocked access point makes discovery
     * silent, but both machines sit on the same LAN — addressable, and
     * running the very same v2.2 server. This worker walks the device's own
     * Wi-Fi subnets (station and SoftAP alike) and knocks on the serving
     * port; a knock answered by a real v2.2 peer lands as a row exactly
     * like a manual add does.
     *
     * Restraints, each load-bearing: only while discovery is going silent
     * (no live neighbor row), never in hidden mode (its promise is to stay
     * off other devices' rosters entirely, and a register is an
     * announcement), rested a round per [SWEEP_INTERVAL_MS], twenty-four
     * knocks in flight at most, and whole ranges past 1024 hosts are left
     * alone (a /16 is not a neighborhood). Empty rounds say so once, then
     * keep silent — the rounds keep running, the log stays readable.
     */
    private fun startSweeper(seq: Int) {
        scope.launch(Dispatchers.IO) {
            val burst = Semaphore(24)
            while (isActive && seq == startSeq) {
                delay(5_000)
                if (seq != startSeq) return@launch
                try {
                    sweepKeep(seq)
                } catch (t: Throwable) {
                    logLine("sweep keep: ${t.message ?: t.javaClass.simpleName}")
                }
                try {
                    sweepOnce(seq, burst)
                } catch (t: Throwable) {
                    logLine("sweep: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Between walks, the peers a round found stay fresh: a cheap targeted
     * re-register every [SWEEP_KEEP_INTERVAL_MS] keeps their rows alive —
     * the roster's TTL is shorter than the walk's cadence, and a dial-only
     * peer announces nothing to refresh itself (same reason the mprober
     * re-probes typed addresses). [SWEEP_KEEP_MAX_MISSES] consecutive
     * misses retire the keeper and the row ages out on its own: a peer
     * that stopped serving disappears, exactly like one that stopped
     * announcing. Re-touches stay silent; the retirement is the one line.
     */
    private suspend fun sweepKeep(seq: Int) {
        val cli = client ?: return
        val self = server?.selfInfo() ?: return
        val now = System.currentTimeMillis()
        for ((fp, host) in sweepKept) {
            if (seq != startSeq) return
            if (now < (sweepKeptNextAt[fp] ?: 0L)) continue
            sweepKeptNextAt[fp] = now + SWEEP_KEEP_INTERVAL_MS
            val real = sweepProbe(cli, self, seq, host)
            if (real != null) {
                sweepKeptMisses.remove(fp)
                if (real.fingerprint != fp) {
                    // A different machine now serves this address: the old
                    // keeper retires, the new machine keeps itself.
                    sweepKept.remove(fp)
                    sweepKeptNextAt.remove(fp)
                }
                sweepKept[real.fingerprint] = host
                sweepKeptNextAt[real.fingerprint] = now + SWEEP_KEEP_INTERVAL_MS
                touchPeer(host, real)
            } else {
                val misses = (sweepKeptMisses[fp] ?: 0) + 1
                sweepKeptMisses[fp] = misses
                if (misses >= SWEEP_KEEP_MAX_MISSES) {
                    sweepKept.remove(fp)
                    sweepKeptMisses.remove(fp)
                    sweepKeptNextAt.remove(fp)
                    logLine("sweep: $host quiet — its row retires")
                }
            }
        }
    }

    private suspend fun sweepOnce(seq: Int, burst: Semaphore) {
        if (System.currentTimeMillis() < nextSweepAt) return
        val cli = client ?: return
        val self = server?.selfInfo() ?: return
        if (prefs().getBoolean("hiddenMode", false)) return
        // An alive neighbor means discovery is NOT silent — the roster is fed.
        if (_peers.value.any { !it.key.startsWith("manual@") }) return
        val subnets = wifiSubnets()
        if (subnets.isEmpty()) return
        // Typed addresses stay the mprober's turf; the walk skips them.
        val manualHosts = _peers.value.keys
            .filter { it.startsWith("manual@") }
            .map { it.removePrefix("manual@").substringBeforeLast(':') }
            .toSet()

        val t0 = System.currentTimeMillis()
        val found = ArrayList<String>()
        var probed = 0
        for ((netIp, prefix) in subnets) if (seq == startSeq) {
            val hosts = FlitSubnet.hosts(netIp, prefix).filterNot { it in manualHosts }
            if (hosts.isEmpty()) continue
            probed += hosts.size
            val landed = coroutineScope {
                hosts.map { h -> async { burst.withPermit { sweepProbe(cli, self, seq, h) } } }
                    .awaitAll()
            }
            landed.forEachIndexed { i, peer ->
                if (peer != null) {
                    touchPeer(hosts[i], peer)
                    sweepKept[peer.fingerprint] = hosts[i]
                    sweepKeptMisses.remove(peer.fingerprint)
                    sweepKeptNextAt[peer.fingerprint] =
                        System.currentTimeMillis() + SWEEP_KEEP_INTERVAL_MS
                    found += "${peer.alias}@${hosts[i]}"
                }
            }
        }
        val ms = System.currentTimeMillis() - t0
        val label = subnets.joinToString(" ") { FlitSubnet.label(it.first, it.second) }
        when {
            found.isNotEmpty() -> {
                sweepEmptyLogged = false
                logLine("sweep $label: $probed hosts, found ${found.size} (${found.joinToString()}) in ${ms}ms")
            }
            !sweepEmptyLogged -> {
                sweepEmptyLogged = true
                logLine("sweep $label: $probed hosts, nothing found in ${ms}ms — rounds continue, quiet until a find")
            }
            else -> Unit
        }
        nextSweepAt = System.currentTimeMillis() + SWEEP_INTERVAL_MS
    }

    /**
     * One knock to the sweep's neighborhood: a short dial to skip the
     * port-closed hosts cheaply, then the real question to the v2.2 server —
     * TLS first, plain for the servers that ride http. Only an answered
     * register becomes a row. The default-port special case of [probeRoute].
     */
    private suspend fun sweepProbe(
        cli: ProtocolClient,
        self: PeerInfo,
        seq: Int,
        host: String,
    ): PeerInfo? = probeRoute(cli, self, seq, FlitRoute.Route(host, Protocol.DEFAULT_PORT, true))

    /**
     * One knock to a known route — the sweeper's dial-then-register, but
     * honoring the route's own port and trying its scheme first (a route
     * remembered as plain is asked plain first). Returns the answering
     * peer's identity, or nothing.
     */
    private suspend fun probeRoute(
        cli: ProtocolClient,
        self: PeerInfo,
        seq: Int,
        route: FlitRoute.Route,
    ): PeerInfo? {
        if (seq != startSeq) return null
        if (!tcpOpen(route.host, route.port)) return null
        for (tls in if (route.tls) booleanArrayOf(true, false) else booleanArrayOf(false, true)) {
            try {
                cli.register(SendTarget(route.host, route.port, tls), self, timeoutMs = 400)
                    ?.let { return it }
            } catch (t: Throwable) {
                // The next scheme — or, after the second, nothing.
            }
        }
        return null
    }

    /** A dial that answers inside [timeoutMs], or nothing: the cheap filter. */
    private fun tcpOpen(host: String, port: Int, timeoutMs: Int = 250): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (t: Throwable) {
        false
    }

    // ==== carrier migration ======================================================

    /** Hunter bookkeeping per owed fingerprint: next due time, attempt count, one-line-per-episode flag. */
    private val huntNextAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val huntAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val huntLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The hunter (docs, milestone A): while the queue owes a peer, the sender
     * looks for it instead of waiting for discovery to introduce them. Ticked
     * every couple of seconds over the owed fingerprints — one mid-dispatch
     * is skipped, one due gets its candidate routes probed on the backoff,
     * and the first fingerprint-matched answer walks through the ordinary
     * `touchPeer` door, whose queued-check auto-flushes the row and whose
     * flush resumes from the receiver's disk. The hunter owns no sending.
     */
    private fun startHunter(seq: Int) {
        scope.launch(Dispatchers.IO) {
            while (isActive && seq == startSeq) {
                delay(2_000)
                if (seq != startSeq) return@launch
                try {
                    huntOnce(seq)
                } catch (t: Throwable) {
                    logLine("hunt: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun huntOnce(seq: Int) {
        val cli = client ?: return
        val self = server?.selfInfo() ?: return
        val now = System.currentTimeMillis()
        val owed = FlitRoster.queued().map { it.fingerprint }.distinct()
        // Rows the queue no longer owes end their hunt with themselves.
        huntNextAt.keys.retainAll(owed.toSet())
        huntAttempts.keys.retainAll(owed.toSet())
        huntLogged.retainAll(owed.toSet())
        for (fp in owed) {
            if (seq != startSeq) return
            if (fp in dispatching) continue // a send is being attempted on some route right now
            if (now < (huntNextAt[fp] ?: 0L)) continue
            huntRoutes(seq, cli, self, fp)
        }
    }

    private suspend fun huntRoutes(seq: Int, cli: ProtocolClient, self: PeerInfo, fp: String) {
        val attempt = huntAttempts.getOrDefault(fp, 0)
        huntAttempts[fp] = attempt + 1
        huntNextAt[fp] = System.currentTimeMillis() + FlitRoute.huntDelayMs(attempt)
        val live = _peers.value[fp]?.let {
            FlitRoute.Route(it.ip, it.info.port, it.info.protocol == "https")
        }?.takeIf { it.port in 1..65535 }
        val remembered = runCatching { FlitRoster.routeFor(fp) }.getOrNull()
        val manual = FlitRoster.manual().map { addr ->
            FlitRoute.Route(
                addr.substringBeforeLast(':'),
                addr.substringAfterLast(':').toIntOrNull() ?: Protocol.DEFAULT_PORT,
                true,
            )
        }
        val cands = FlitRoute.candidates(live, remembered, manual)
        val alias = FlitRoster.queued().firstOrNull { it.fingerprint == fp }?.alias
            ?: FlitRoster.aliasFor(fp) ?: fp.take(8)
        if (huntLogged.add(fp)) {
            logLine(
                if (cands.isEmpty()) "hunt: $alias — no known routes; discovery carries the search"
                else "hunt: $alias — probing ${cands.size} known route(s)",
            )
        }
        for (route in cands) {
            if (seq != startSeq) return
            val answer = probeRoute(cli, self, seq, route) ?: continue
            // The fingerprint is the whole accept rule: a reassigned address
            // that serves a stranger is simply not this peer's route.
            if (!FlitRoute.accepts(answer.fingerprint, fp)) continue
            logLine("hunt: ${route.host}:${route.port} answered as ${answer.alias.ifBlank { fp.take(12) }}")
            huntAttempts.remove(fp)
            huntNextAt.remove(fp)
            huntLogged.remove(fp)
            // The ordinary door does the rest: the row's queued-check
            // flushes the owed batch, and the flush's first-attempt probe
            // resumes from the receiver's own disk.
            touchPeer(route.host, answer)
            return
        }
    }

    /**
     * Direct-discovery flipper: each tick recomputes the stance from plain
     * facts — access point, permissions, radio power, hidden mode — and hands
     * it to the radio. Flips are logged once, never per tick. The radios
     * stay dark while the access point owns discovery; nothing asks the
     * surface for a decision because there isn't one to make.
     *
     * debug.flit.forceble=1 fakes the "no access point" fact so the radio half
     * of a device walkthrough runs without touching the real Wi‑Fi setup;
     * debug.flit.bledump=1 scans unfiltered, dumping the radios nearby (the
     * dump doubles as evidence the phone's own announcement is on the air).
     */
    private fun startBleFlipper(seq: Int) {
        scope.launch(Dispatchers.IO) {
            while (isActive && seq == startSeq) {
                delay(5_000)
                if (seq != startSeq) return@launch
                val radio = bleRadio ?: return@launch
                val info = server?.selfInfo() ?: continue
                val forced = debugProp("debug.flit.forceble") == "1"
                val dump = debugProp("debug.flit.bledump") == "1"
                val apUp = !forced && wifiNetwork(cm()) != null
                val permsOk = radio.hasPermissions()
                val radioOn = radio.radioOn()
                val stance = FlitBle.stance(
                    apUp = apUp,
                    perms = permsOk,
                    radioOn = radioOn,
                    hidden = prefs().getBoolean("hiddenMode", false),
                )
                runCatching { radio.apply(stance, info.alias, info.fingerprint, dump) }
                if (stance.advertise != bleAdvertised) {
                    bleAdvertised = stance.advertise
                    when {
                        stance.advertise -> logLine("no Wi\u2011Fi network; direct discovery on")
                        // The borrowed voice tells the AP story only when the
                        // AP is the story: a radio going dark for its own
                        // reasons (ask pending, radio resting) never claims
                        // the access point returned — that log would
                        // contradict the ask's own line in the same tick.
                        apUp -> logLine("access point back; direct discovery dark")
                        else -> Unit
                    }
                }
                // The no-AP moment: if the radio owes anything to the system
                // sheets, the pending ask becomes the surface's business
                // (until answered, or until the facts heal themselves).
                // Self-clearing by facts means a granted state or an access
                // point arriving in the next tick retires the ask without
                // ever having pushed the user.
                val ask = FlitBle.raiseAsk(
                    apUp = apUp,
                    permsOk = permsOk,
                    radioOn = radioOn,
                    askedPerms = prefs().getBoolean("directAskPermsDone", false),
                    askedPower = prefs().getBoolean("directAskPowerDone", false),
                )
                if (ask != _directAsk.value) {
                    _directAsk.value = ask
                    // Engine-log only, and only on the raise: the borrowed
                    // voice says *that* a question is pending, never what its
                    // subject is called — the system sheet speaks for itself.
                    if (ask != null) logLine("no Wi\u2011Fi network; direct discovery waiting on the system")
                }
            }
        }
    }

    /**
     * A direct-radio announce landed. Transports rank silently: a row the
     * address-bearing transports (UDP announces, manual probes) touched
     * within the fresh window is theirs — the radio only fills lapses, so it
     * can never rewrite a Wi-Fi row's address to something without a socket.
     */
    private fun touchBlePeer(addr: String, ann: FlitBle.Ann) {
        if (FlitBle.isSelf(ann.fingerprint, ann.truncated, identity?.fingerprint)) return
        val now = System.currentTimeMillis()
        if (!FlitBle.rowIsFree(_peers.value[ann.fingerprint]?.lastSeenMs, now)) return
        val info = PeerInfo(
            alias = ann.alias,
            version = Protocol.VERSION,
            fingerprint = ann.fingerprint,
            // No address-bearing socket on this row yet: transfers ride the
            // full fingerprint during first contact, and the address label
            // stays the radio address (port would be a lie).
            port = 0,
        )
        touchPeer(addr, info)
    }

    /**
     * A usable Wi\u2011Fi network exists, VPNs excluded — the arbiter's
     * access-point fact and the egress pin's target share this criterion.
     * Self-hosted hotspots are a coarse corner on purpose: their refinement
     * rode the 2026-10-30 walkthrough, which concluded that coarse is
     * honest — a SoftAP appears in allNetworks as its own LOCAL_NETWORK,
     * so a live hotspot (station up or station gone) keeps the fact true,
     * and brief agent churn re-locks within a tick.
     */
    private fun wifiNetwork(cm: android.net.ConnectivityManager?): android.net.Network? =
        cm?.allNetworks?.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true
        }

    private fun cm(): android.net.ConnectivityManager? =
        appCtx.getSystemService(android.net.ConnectivityManager::class.java)

    /**
     * Every v4 (ip, prefix) range this device could knock on: all its Wi-Fi
     * networks — station and SoftAP alike, the sweep's "same LAN" fact made
     * specific — VPNs excluded, loopback never. Empty means no walkable
     * neighborhood: no sweep fires.
     */
    private fun wifiSubnets(cm: android.net.ConnectivityManager? = cm()): List<Pair<String, Int>> {
        if (cm == null) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            ) continue
            val lp = cm.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                if (la.address.address.size != 4 || la.address.isLoopbackAddress) continue
                out += la.address.hostAddress?.let { it to la.prefixLength } ?: continue
            }
        }
        return out
    }

    private fun touchPeer(ip: InetAddress, info: PeerInfo) {
        touchPeer(ip.hostAddress ?: return, info)
    }

    private fun touchPeer(ip: String, info: PeerInfo) {
        if (info.fingerprint == identity?.fingerprint) return
        val now = System.currentTimeMillis()
        _peers.update { m ->
            val entry = m[info.fingerprint]?.copy(ip = ip, info = info, lastSeenMs = now)
                ?: PeerEntry(ip, info, now)
            m + (info.fingerprint to entry)
        }
        // A trusted peer that renamed itself: roster + direct-share shortcuts
        // must not advertise the old name forever.
        val pinned = FlitRoster.all().firstOrNull { it.fingerprint == info.fingerprint }
        if (pinned != null && info.alias.isNotBlank() && pinned.alias != info.alias) {
            FlitRoster.refreshAlias(info.fingerprint, info.alias)
            FlitShortcuts.sync(appCtx)
        }
        // The route that last answered, remembered for the hunter (milestone
        // A): a register answer or an announce — both carry the peer's real
        // identity and its serving port. Address-less rows (direct radio,
        // port 0) remember nothing.
        runCatching {
            FlitRoster.rememberRoute(info.fingerprint, ip, info.port, info.protocol == "https")
        }
        // A peer we owe files to just came back: dispatch its send-later batch
        // (guarded so an announce storm cannot double-send).
        if (FlitRoster.queued().any { it.fingerprint == info.fingerprint }) {
            flushQueueFor(PeerEntry(ip, info, now))
        }
    }

    // ==== album mirror ==========================================================

    /**
     * Album mirror (docs, milestone D): new files the stock camera saves go to
     * the one trusted peer chosen in Options — through the send-later queue,
     * so everything milestone C built carries them: one row per peer that
     * merges, auto-flushes on the peer's touch, checkpoints through process
     * death, and drops precisely on success. The scan runs at every engine
     * start (a MediaStore diff against the ledger — it catches whatever
     * landed while the app was dead), the watcher covers the liveness in
     * between. Tokens are MediaStore uris: shared storage is never readable
     * by raw path (the first walk proved it with an EACCES), and the
     * media-read ask is the mirror's one system dialog.
     */
    private fun startMirrorWatcher(seq: Int) {
        stopMirrorWatcher()
        if (!FlitRoster.mirrorOn() || FlitRoster.mirrorTarget() == null) return
        val dir = cameraDir() ?: return
        scope.launch(Dispatchers.IO) {
            if (seq != startSeq) return@launch
            scanMirror()
        }
        val obs = object : android.os.FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrBlank()) return
                if (event and (CLOSE_WRITE or MOVED_TO) == 0) return
                scope.launch(Dispatchers.IO) {
                    if (seq != startSeq) return@launch
                    noticeMirror(dir, path)
                }
            }
        }
        obs.startWatching()
        mirrorObserver = obs
        logLine("mirror: watching ${dir.absolutePath}")
    }

    fun stopMirrorWatcher() {
        runCatching { mirrorObserver?.stopWatching() }
        mirrorObserver = null
    }

    /** The Options toggles land here: a running engine re-reads its mirror wiring. */
    fun mirrorRefresh() {
        if (!_state.value.running) return
        startMirrorWatcher(startSeq)
    }

    /**
     * Enable-time seeding: the folder as it stands is the baseline. Nothing
     * that exists when mirroring is switched on is replayed — the feature
     * syncs forward. Runs inline and BEFORE [mirrorRefresh], so the scan the
     * watcher starts reads a seeded ledger. Takes the caller's context: the
     * toggle can fire before the engine ever started.
     */
    fun mirrorSeed(ctx: Context) {
        runCatching {
            val tokens = cameraShots(ctx).map { it.ledgerToken }
            if (tokens.isNotEmpty()) {
                FlitRoster.mirrorMark(tokens)
                logLine("mirror: baseline seeded — ${tokens.size} existing camera file(s) stay put")
            }
        }
    }

    /** The stock camera's folder — the only folder the mirror watches. */
    private fun cameraDir(): java.io.File? =
        runCatching {
            java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
                "Camera",
            )
        }.getOrNull()

    /**
     * One MediaStore query answers for the whole camera folder — names,
     * sizes, and the uris the queue can actually open (scoped storage
     * forbids raw paths). Empty without the media-read grant or below
     * API 29 (no RELATIVE_PATH): the mirror simply finds nothing there.
     */
    /**
     * Camera shots come from the Images and Video collections — the homes a
     * granted media read actually answers for (the Files collection proved
     * silent under the very same grant; the walk caught it). One query per
     * collection, merged.
     */
    private fun cameraShots(ctx: Context = appCtx): List<FlitMirror.Shot> {
        if (android.os.Build.VERSION.SDK_INT < 29) return emptyList()
        val cols = arrayOf(
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns._ID,
        )
        val out = ArrayList<FlitMirror.Shot>()
        val seen = HashSet<String>()
        for (uri in listOf(
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )) {
            val cur = try {
                ctx.contentResolver.query(
                    uri, cols,
                    "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf("DCIM/Camera/"), null,
                )
            } catch (t: Throwable) {
                // Never silent: a folder this device's camera doesn't feed,
                // or a grant revoked mid-flight, must say so.
                logLine("mirror: camera query failed — ${t.message ?: t.javaClass.simpleName}")
                continue
            } ?: continue
            cur.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
                val idIdx = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val size = c.getLong(sizeIdx)
                    val token = android.content.ContentUris
                        .withAppendedId(uri, c.getLong(idIdx)).toString()
                    if (seen.add(token)) out.add(FlitMirror.Shot(name, size, token))
                }
            }
        }
        if (out.isEmpty()) logLine("mirror: camera query answered nothing (grant or folder empty?)")
        return out
    }

    private fun scanMirror() {
        val ledger = FlitRoster.mirrorLedger().toSet()
        val fresh = FlitMirror.plan(cameraShots(), ledger)
        if (fresh.isEmpty()) return
        logLine("mirror: scan found ${fresh.size} new camera file(s)")
        mirrorEnqueue(fresh)
    }

    private suspend fun noticeMirror(dir: java.io.File, name: String) {
        val ledger = FlitRoster.mirrorLedger().toSet()
        // A freshly closed file may not be indexed yet: nudge the scanner,
        // then wait a moment for MediaStore. If it never lands, the next
        // scan catches it — nothing is lost, nothing is re-sent. An
        // already-mirrored file (re-saved, re-closed) drops out at the
        // ledger check inside the loop.
        runCatching {
            val path = java.io.File(dir, name).absolutePath
            android.media.MediaScannerConnection.scanFile(
                appCtx, arrayOf(path), arrayOf(guessMime(name)), null,
            )
        }
        var shot: FlitMirror.Shot? = null
        for (i in 0 until 15) {
            delay(200)
            val s = cameraShots().firstOrNull { it.name == name }
            if (s == null) continue
            if (!FlitMirror.notice(s.name, s.size, ledger)) break // already mirrored
            shot = s
            break
        }
        val s = shot ?: return
        logLine("mirror: ${s.name} (${s.size} bytes) — new camera file")
        mirrorEnqueue(listOf(s))
    }

    /**
     * The mirror's only queue interaction: enqueue the batch as a normal
     * QueuedSend (MediaStore uri tokens — exactly what the flush's spool
     * step consumes), marked with the mirror duty so the ledger closes on
     * success; then nudge a live peer at once — an absent one catches the
     * batch on its next touch.
     */
    private fun mirrorEnqueue(shots: List<FlitMirror.Shot>) {
        val fp = FlitRoster.mirrorTarget() ?: return
        val row = QueuedSend(
            fingerprint = fp,
            alias = FlitRoster.aliasFor(fp) ?: FlitRoster.alias(fp) ?: fp.take(8),
            uris = shots.map { it.token },
            names = shots.map { it.name },
            createdMs = System.currentTimeMillis(),
            mirror = true,
        )
        FlitRoster.enqueue(row)
        _queue.value = FlitRoster.queued()
        val entry = _peers.value[fp]
        if (entry != null) {
            flushQueueFor(entry)
        } else {
            logLine("mirror: ${shots.size} shot(s) wait for ${row.alias} to appear")
        }
    }

    /**
     * Dispatches this peer's queued send-later batch, or no-ops quietly when
     * nothing is queued / a dispatch is already in flight. Also the entry
     * point for the queue's manual "retry now".
     */
    private fun flushQueueFor(peer: PeerEntry) {
        if (FlitRoster.queued().none { it.fingerprint == peer.info.fingerprint }) return
        if (peer.info.fingerprint in dispatching) return
        val batch = FlitRoster.queued().first { it.fingerprint == peer.info.fingerprint }
        scope.launch(Dispatchers.IO) {
            try {
                val spools = batch.uris.mapNotNull { spoolFileFor(it, "queue") }
                if (spools.isEmpty()) {
                    logLine("queue: nothing left for ${peer.displayName} — dropping")
                    dropQueue(batch)
                } else {
                    logLine("queue: ${spools.size} queued for ${peer.displayName} — sending now")
                    try {
                        // Synchronous with the row's fate, and the claim is
                        // dispatchTo's (see there): a returned report means
                        // the far side SPOKE (sent, refused, declined) — the
                        // story concluded, so the row's tokens leave now,
                        // after the bytes, and the spooled copies with them.
                        // A throw means the carrier died with no answer at
                        // all: the row and its spools stay exactly as they
                        // were — the hunt re-enters this flush and the
                        // receiver's on-disk prefix carries the resume
                        // (docs, milestones C and A).
                        val report = dispatchTo(peer, SourceList(spools, emptyList()), null, fromQueue = true)
                        if (report == null) {
                            logLine("nothing concluded for ${peer.displayName} — kept")
                        } else {
                            dropSent(batch, spools)
                            cleanSpools(spools)
                        }
                    } catch (e: Exception) {
                        // The row's keep IS the queue's promise: a send that
                        // never settled (the peer walked out mid-transfer,
                        // the engine not serving yet) leaves the batch and
                        // its grants exactly as they were — the next
                        // announce, hunt answer or "retry now" resumes.
                        val why = e.message ?: e.javaClass.simpleName
                        logLine("$why — kept, sends when it reappears")
                    }
                }
            } finally {
                _queue.value = FlitRoster.queued()
            }
        }
    }

    /** Manual "send the waiting files now" for a peer visible this moment. */
    fun retryQueued(fingerprint: String) {
        val peer = _peers.value.values.firstOrNull { it.info.fingerprint == fingerprint }
        if (peer == null) {
            toast("not on the network yet — I'll keep waiting")
            return
        }
        flushQueueFor(peer)
    }

    /** Fingerprints currently being dispatched from the send-later queue. */
    private val dispatching = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Payload for the "My code" QR: who we are and exactly where a scanner
     * should connect. IPv4 on a multicast-capable interface (Wi\u2011Fi beats
     * ethernet on the sort order), the real serving port, our fingerprint
     * for the trust badge.
     */
    fun selfQrCode(): String? = selfQrAdd()?.uri()

    /**
     * Payload for the "My code" QR: who we are and exactly where a scanner
     * should connect. IPv4 on a multicast-capable interface (Wi‑Fi beats
     * ethernet on the sort order), the real serving port, our fingerprint
     * for the trust badge.
     */
    fun selfQrAdd(): QrAdd? {
        if (identity == null) return null
        val ip = pickAnnounceAddress() ?: return null
        return QrAdd(
            ip = ip,
            port = server?.port ?: Protocol.DEFAULT_PORT,
            tls = true,
            fingerprint = identity?.fingerprint ?: "",
            alias = currentAlias(),
        )
    }

    /** Our IPv4s, Wi\u2011Fi first. UDP discovery's interface set, applied to unicast. */
    private fun pickAnnounceAddress(): String? {
        val ifs = app.flit.share.protocol.discovery.UdpDiscovery.eligibleNetworkInterfaces()
        data class Cand(val ifaceWifi: Boolean, val host: String)
        val cands = ifs.flatMap { nii ->
            nii.inetAddresses.asSequence()
                .filter { ia -> ia.address.size == 4 && !ia.isLoopbackAddress }
                .map { Cand(nii.name.startsWith("wlan"), it.hostAddress ?: "") }
        }.filter { it.host.isNotBlank() }.sortedByDescending { it.ifaceWifi }
        return cands.firstOrNull()?.host
    }

    /**
     * Entry from the QR scanner / paste field. A scanned pairing code is
     * human-eyeball verified: the address is inserted like any manual peer,
     * AND \u2014 when the probed device's fingerprint matches the code's \u2014 it
     * is pinned and marked with visual provenance ("\u2713 code"). Pinning
     * happens after the probe, never before: trust attaches to the device
     * that actually answers, not to the round-trip-free copy of the code.
     */
    fun addFromScan(raw: String) {
        val code = parseAddCode(raw)
        if (code == null) {
            logLine("scan: not a Flit pairing code")
            toast("that does not look like a Flit code")
            return
        }
        val cli = client ?: run { logLine("not sharing \u2014 start sharing first"); return }
        val host = code.ip
        if (FlitRoster.addManual("$host:${code.port}")) insertManualStub(host, code.port)
        logLine("scan $host:${code.port}\u2026")
        scope.launch(Dispatchers.IO) {
            val self = server?.selfInfo() ?: run {
                logLine("scan: not serving yet")
                return@launch
            }
            val real = try {
                cli.register(SendTarget(host, code.port, true), self, timeoutMs = 4_000)
            } catch (e: Exception) {
                logLine("scan $host:${code.port} unreachable (${e.message ?: e.javaClass.simpleName})")
                null
            }
            if (real == null) return@launch
            touchPeer(host, real)
            if (code.fingerprint.isNotBlank() && real.fingerprint != code.fingerprint) {
                logLine("scan: $host:${code.port} answers as ${real.fingerprint.take(12)}, code said ${code.fingerprint.take(12)} \u2014 NOT trusted")
                toast("device answered, but its fingerprint changed \u2014 verify with the code again")
                return@launch
            }
            if (code.fingerprint.isNotBlank()) {
                FlitRoster.pin(real.fingerprint, real.alias)
                FlitRoster.markScanned(real.fingerprint)
                logLine("scan: pinned ${real.alias} (code verified)")
            }
        }
    }

    // ==== sending ===============================================================

    /**
     * Manual peer entry for networks that strip multicast (captive APs, phones
     * whose Wi-Fi loses default-egress to mobile data): the user types
     * `host:port`; we insert a stub row immediately and try a real register. On
     * success the stub is replaced by the peer's actual info (and the peer
     * learns about us in the same exchange).
     */
    fun addManualPeer(address: String) {
        val cli = client ?: run { logLine("not sharing \u2014 start sharing first"); return }
        val raw = address.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val host = raw.substringBeforeLast(':').takeIf { it.isNotBlank() } ?: run {
            logLine("cannot parse address \"$raw\"")
            return
        }
        val port = raw.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
            ?: Protocol.DEFAULT_PORT
        // Manual rows persist across restarts (roster) and never age out.
        if (FlitRoster.addManual("$host:$port")) insertManualStub(host, port)
        logLine("probe $host:$port\u2026")
        val self = server?.selfInfo() ?: return
        scope.launch(Dispatchers.IO) {
            val real = try {
                cli.register(SendTarget(host, port, true), self, timeoutMs = 4_000)
            } catch (e: Exception) {
                logLine("probe $host:$port unreachable (${e.message ?: e.javaClass.simpleName})")
                null
            }
            if (real != null) touchPeer(host, real)
        }
    }

    private fun insertManualStub(host: String, port: Int) {
        val stubFp = "manual@$host:$port"
        _peers.update { m ->
            m + (stubFp to PeerEntry(
                ip = host,
                info = PeerInfo(
                    alias = "$host:$port", version = Protocol.VERSION, deviceModel = null,
                    deviceType = "device", fingerprint = stubFp, port = port, protocol = "https",
                ),
                lastSeenMs = System.currentTimeMillis(),
            ))
        }
    }

    /** Engine start: silently re-add the addresses the user added by hand. */
    private fun addManualStubs() {
        for (addr in FlitRoster.manual()) {
            val host = addr.substringBeforeLast(':')
            val port = addr.substringAfterLast(':').toIntOrNull() ?: Protocol.DEFAULT_PORT
            if (host.isNotBlank() && _peers.value.keys.none { it == "manual@$host:$port" }) {
                insertManualStub(host, port)
            }
        }
    }

    /** Files picked in-app (SAF). Persistable grants → these can also queue. */
    fun send(peer: PeerEntry, uris: List<Uri>, fromQueue: Boolean = false) {
        scope.launch(Dispatchers.IO) { dispatchTo(peer, SourceList(emptyList(), uris), null, fromQueue) }
    }

    /** Share-sheet dispatch: spooled files + optional text note; clears intake. */
    fun sendShare(peer: PeerEntry) {
        val intake = _share.value ?: return
        if (!intake.ready) {
            logLine("still preparing the share…")
            return
        }
        val picked = intake.uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        scope.launch(Dispatchers.IO) { dispatchTo(peer, SourceList(intake.files, picked), intake.text, fromQueue = false) }
    }

    /** In-app note composer: stages a text note exactly like the share sheet does. */
    fun stageNote(text: String) {
        if (text.isBlank()) return
        _share.value = ShareIntake(ready = true, files = emptyList(), text = text, sourceApp = null)
    }

    /**
     * Dock "Send": files picked in-app first, a device is tapped afterwards.
     * Document URIs stage without copying (they outlive the picker call); a
     * note staged earlier rides along.
     */
    fun stagePicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _share.value = ShareIntake(
            ready = true,
            files = emptyList(),
            text = _share.value?.text,
            sourceApp = null,
            uris = uris.map { it.toString() },
        )
    }

    /** One of the two lists is non-empty; spools are already local files. */
    private class SourceList(val spools: List<SpooledFile>, val uris: List<Uri>)

    /** Wire-format outgoings backed by a spooled (local, grant-proof) file. */
    private class FileOutgoing(private val s: SpooledFile) : OutgoingFile {
        override val wireName: String = s.name
        override val size: Long get() = s.size
        override val mime: String = s.mime
        override fun openStream() = s.f.inputStream()
    }

    /** Share/auto-send gate: engine has the serving identity AND an outbound client. */
    fun readyForSend(): Boolean = client != null && server?.selfInfo() != null

    /**
     * User said "never mind" on the staged intake: the banner closes, spooled
     * copies-in-waiting wander back to disk, and a still-running spool coroutine
     * will not resurrect the intake (it checks the generation before committing).
     */
    fun discardIntake() {
        intakeGen.incrementAndGet()
        _share.value = null
    }

    private fun cleanSpools(spools: List<SpooledFile>) {
        for (s in spools) runCatching { s.f.delete() }
    }

    /**
     * Phase C hygiene: one sweep per engine start over the spool litter a
     * killed process leaves ([FlitSpool]). Crush temp copies live in their own
     * dir and are never queue-referenced; top-level spools are guarded by the
     * send-later queue's own tokens. Errors kept out of start's way — the
     * sweep is hygiene, not a gate.
     */
    private fun sweepStaleSpools() {
        runCatching {
            val queueTokens = FlitRoster.queued().flatMap { it.uris }
            val now = System.currentTimeMillis()
            val candidates: List<File> =
                (appCtx.cacheDir.listFiles()?.toList() ?: emptyList()) +
                    (File(appCtx.cacheDir, "crush").listFiles()?.toList() ?: emptyList())
            FlitSpool.stale(candidates, queueTokens, now).forEach { runCatching { it.delete() } }
        }
    }

    /**
     * The whole send story for one peer, run INLINE in the caller's
     * coroutine. Returns the receiver's [SendReport] — null only when no
     * exchange could even begin (not serving, nothing openable). Throws
     * when the carrier died and the far side never answered
     * ([app.flit.share.protocol.client.TransferUnreachable], IO failures
     * while re-preparing): the caller owns the row's fate then. The
     * send-later flush rides this synchronously — the row's tokens leave
     * only after a report comes back, never on a throw — and the fresh
     * dispatches ([send], [sendShare]) run it fire-and-forget inside
     * [scope].
     */
    private suspend fun dispatchTo(peer: PeerEntry, src: SourceList, text: String?, fromQueue: Boolean): SendReport? {
        val cli = client ?: run {
            if (fromQueue) throw NotServingYet()
            logLine("not sharing — start sharing first")
            return null
        }
        val self = server?.selfInfo() ?: run {
            if (fromQueue) throw NotServingYet()
            return null
        }
        // One dispatch per peer at a time, fresh or queue-originated: the
        // owe row exists before bytes move, so an announce mid-fresh-send
        // would otherwise re-enter the flush and double-fly the batch. The
        // hunter consults the same claim.
        if (!dispatching.add(peer.info.fingerprint)) {
            logLine("send to ${peer.displayName} already in flight — one at a time")
            return null
        }
        var outgoing: List<OutgoingFile> = emptyList()
        var prog = FlitProgress(appCtx, peer.info.fingerprint.hashCode() and 0x7fffffff)
        val crushedTmp = ArrayList<File>()
        var owe: QueuedSend? = null
        try {
            outgoing = buildList {
                if (text != null && text.isNotBlank()) add(TextNoteOutgoing(text))
                addAll(src.spools.map { FileOutgoing(it) })
                addAll(src.uris.mapNotNull { uri -> outgoingFrom(appCtx, uri) })
            }
            // Crush & send: optional gzip on the wire for compressible files.
            if (prefs().getBoolean("crushSend", false)) {
                outgoing = crushOutgoing(appCtx, outgoing, crushedTmp)
            }
            if (outgoing.isEmpty()) {
                logLine("nothing openable selected")
                _share.value = null
                return null
            }
            // Checkpoint before bytes: a checkpointable fresh dispatch
            // owes its batch to the queue first — the row IS the
            // checkpoint, and it leaves only when the far side spoke.
            // A process death mid-flight leaves the row on disk with its
            // grants; the next restart flushes it back into the same
            // prefix (the receiver's /partial probe answers from its own
            // disk — docs, milestone C).
            if (!fromQueue) {
                val tokens = queueTokensFor(src)
                if (tokens != null) {
                    val row = QueuedSend(
                        fingerprint = peer.info.fingerprint,
                        alias = peer.displayName,
                        uris = tokens,
                        names = outgoing.map { it.wireName },
                        createdMs = System.currentTimeMillis(),
                    )
                    if (FlitRoster.enqueue(row)) {
                        owe = row
                        _queue.value = FlitRoster.queued()
                    }
                }
            }
            val target = SendTarget(peer.ip, peer.info.port, peer.info.protocol == "https")
            prog.begin(FlitProgress.Kind.SEND, outgoing.associate { it.wireName to it.size })
            val report = cli.sendFiles(target, self, outgoing, multi = prefs().getBoolean("parallelStreams", true), progress = { name, soFar, _ ->
                prog.tick(name, soFar)
            })
            val sent = report.sent.size
            val fail = report.failed.size
            val summary = when {
                fail == 0 -> "sent $sent/${outgoing.size} to ${peer.displayName}"
                sent == 0 -> "all $fail failed"
                else -> "sent $sent, $fail failed → ${peer.displayName}"
            }
            prog.finish(summary, ok = sent > 0, iconDone = false)
            if (sent > 0) {
                logLine("$summary: ${report.sent.joinToString(", ")}")
                prefs().edit()
                    .putString("lastPeerFp", peer.info.fingerprint)
                    .putString("lastPeerAlias", peer.displayName)
                    .putString("lastPeerIp", peer.ip)
                    .putInt("lastPeerPort", peer.info.port)
                    .apply()
                _lastPeer.value = peer
            }
            for (f in report.failed) logLine("FAIL ${f.fileName}: ${f.reason}")
            if (sent == 0 && fail == 0) logLine("declined by ${peer.displayName}")
            // The far side spoke: the owe's story concludes — precisely
            // its tokens and names leave the row (a batch that merged in
            // mid-flight stays owed), grants go back with the tokens.
            owe?.let { dropSent(it) }
            // Spools served their session — sent or refused, the cache
            // copy has no future role here (a failed batch sent to the
            // send-later queue keeps its files; the queue owns them
            // now). A queue run's spools die in flushQueueFor, right
            // after its row concludes — never here, where a throw must
            // leave them untouched for the next flush.
            if (!fromQueue) cleanSpools(src.spools)
            _share.value = null
            return report
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            if (owe != null) {
                // The owe row already kept its promise: batch and grants
                // are on disk, so no new decision is needed here — the
                // next touch of this peer re-enters the flush.
                logLine("$reason — kept, sends when ${peer.displayName} reappears")
                prog.finish("queued for later", ok = false, iconDone = false)
            } else {
                logLine("send failed: $reason")
                prog.finish("send failed — $reason", ok = false, iconDone = false)
                // Nothing was owed (nothing checkpointable): the spooled
                // copies have no future. Queue-originated dispatches
                // never clean here: the batch retains its file.
                if (!fromQueue) cleanSpools(src.spools)
            }
            _share.value = null
            // A queue run's break rethrows so flushQueueFor keeps the
            // row's promise (keeps batch and grants, retries later);
            // non-queue runs end here — the story is already logged.
            if (fromQueue) throw e
            return null
        } finally {
            dispatching.remove(peer.info.fingerprint)
            crushedTmp.forEach { runCatching { it.delete() } }
        }
    }

    /** Persistable content grants go back with the tokens that carried them. */
    private fun releaseGrants(tokens: List<String>) {
        for (t in tokens) {
            val uri = runCatching { Uri.parse(t) }.getOrNull() ?: continue
            if (uri.scheme != "content") continue
            runCatching {
                appCtx.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * The only way a whole queue row ends: its tokens return from the roster
     * and the persistable SAF grants taken at queue time go back with them —
     * grants kept forever would fill Android's per-app ledger until
     * takePersistableUriPermission starts failing. Dead or expired grants
     * release as nothing; spool-path tokens bear no grants at all. Cancels
     * and empty rows end here; a SENT batch ends at [dropSent].
     */
    private fun dropQueue(batch: QueuedSend) {
        FlitRoster.dequeue(batch.fingerprint)
        releaseGrants(batch.uris)
        _queue.value = FlitRoster.queued()
    }

    /**
     * The way a sent batch ends: precisely its tokens and names leave the
     * row — a batch that merged in while this one flew stays owed — and the
     * sent tokens' grants go back with them. Dropping the whole row here
     * would eat a merged-in batch that never flew; the owe-before-bytes
     * design makes that merge ordinary, so the drop is surgical.
     */
    private fun dropSent(batch: QueuedSend, spools: List<SpooledFile> = emptyList()) {
        FlitRoster.dequeueTokens(batch.fingerprint, batch)
        releaseGrants(batch.uris)
        // The mirror's ledger closes its own loop here: a mirror-duty batch
        // marks everything it spooled (the camera files it just sent), so the
        // scan never re-offers them. A merged-in manual file over-marks
        // harmlessly — the ledger is only ever consulted for DCIM/Camera
        // diffs, and camera names are timestamped.
        if (batch.mirror && spools.isNotEmpty()) {
            runCatching {
                FlitRoster.mirrorMark(spools.map { FlitMirror.ledgerToken(it.name, it.size) })
            }
        }
        _queue.value = FlitRoster.queued()
    }

    /** A queue flush that could never reach the wire (not serving yet). */
    private class NotServingYet : Exception("queue flush before serving")

    /**
     * Tokens for the send-later queue, or null if this source cannot outlive
     * the session. Spooled files are always queueable; SAF URIs only when every
     * grant is persistable (taken and KEPT at queue time, released on dequeue).
     */
    private fun queueTokensFor(src: SourceList): List<String>? = when {
        src.spools.isNotEmpty() && src.uris.isEmpty() -> src.spools.map { it.f.absolutePath }
        src.spools.isEmpty() && src.uris.isNotEmpty() -> {
            val keepers = src.uris.filter { uri ->
                runCatching {
                    appCtx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    true
                }.getOrDefault(false)
            }
            if (keepers.size == src.uris.size) keepers.map { it.toString() } else null
        }
        else -> null
    }

    fun cancelQueued(fingerprint: String) {
        FlitRoster.queued().firstOrNull { it.fingerprint == fingerprint }?.let { dropQueue(it) }
        logLine("queued send cancelled")
    }

    /** Turn a queue token (spool path or durable SAF uri) into a local file. */
    private fun spoolFileFor(token: String, tag: String): SpooledFile? {
        val f = File(token)
        if (f.isFile) return SpooledFile(f, f.name, guessMime(f.name))
        val uri = runCatching { Uri.parse(token) }.getOrNull() ?: return null
        if (uri.scheme == "content" || uri.scheme == "file") {
            return runCatching { spoolUri(uri, tag) }.getOrNull()
        }
        return null
    }


    // ==== share intake ==========================================================

    /** Bumped whenever the intake lifecycle changes hands (new share, discard). */
    private val intakeGen = java.util.concurrent.atomic.AtomicLong(0)

    /** Called by MainActivity for ACTION_SEND / ACTION_SEND_MULTIPLE intents. */
    fun handleShareIntent(intent: Intent?, referrerHost: String?) {
        if (intent == null) return
        intakeGen.incrementAndGet()
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = intent.extras?.get(Intent.EXTRA_STREAM) as? Uri
                if (u != null) listOf(u) else emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("UNCHECKED_CAST")
                intent.extras?.get(Intent.EXTRA_STREAM) as? List<Uri> ?: emptyList()
            }
            else -> emptyList()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrBlank()) return
        _share.value = ShareIntake(ready = false, files = emptyList(), text = text, sourceApp = referrerHost)
        spoolIntake(uris, text)
    }

    /**
     * Copies the shared streams into app cache so the intake becomes usable.
     * A share may arrive BEFORE the engine has a context (cold start from the
     * share sheet), so wait for the engine to finish starting first.
     */
    private fun spoolIntake(uris: List<Uri>, text: String?) {
        scope.launch(Dispatchers.IO) {
            val gen = intakeGen.get()
            var waitedMs = 0
            while (!::appCtx.isInitialized && waitedMs < 20_000) {
                delay(250)
                waitedMs += 250
            }
            if (!::appCtx.isInitialized) {
                _share.value = null
                logLine("could not prepare the share — start Flit and share again")
                return@launch
            }
            val spooled = mutableListOf<SpooledFile>()
            var dropped = 0
            for (uri in uris) {
                try {
                    spooled += spoolUri(uri, "share")
                } catch (e: Exception) {
                    dropped++
                    logLine("cannot read shared file (${e.message ?: e.javaClass.simpleName})")
                }
            }
            if ((dropped == uris.size || uris.isEmpty()) && spooled.isEmpty() && text.isNullOrBlank()) {
                _share.value = null
                return@launch
            }
            if (intakeGen.get() != gen) {
                // Discarded while we were copying: nobody wants the copies now.
                spooled.forEach { runCatching { it.f.delete() } }
                return@launch
            }
            _share.update { it?.copy(ready = true, files = spooled) }
        }
    }

    /** Copy one shared stream into app cache; returns the local, grant-proof file. */
    private fun spoolUri(uri: Uri, tag: String): SpooledFile {
        val resolver = appCtx.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        val displayName = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val stamp = System.currentTimeMillis()
        val safe = (displayName ?: "shared").replace(Regex("[\\/:*?\"<>|]"), "_").takeLast(80)
        val f = File(appCtx.cacheDir, "$tag-$stamp-${spoolSeq.incrementAndGet()}-$safe")
        resolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("no stream for $uri")
        return SpooledFile(f, safe, mime)
    }
    private val spoolSeq = java.util.concurrent.atomic.AtomicInteger(0)

    // ==== receive publishing ====================================================

    /** Per-peer folder if the sender is pinned, else the user's global default. */
    private fun receiveTreeFor(fp: String?): String? =
        fp?.let { FlitRoster.destTree(it) } ?: prefs().getString("destTreeGlobal", null)

    /**
     * Direct receive paths — the POST body streams into its final home without
     * a temp file, no second copy:
     * - pinned peers with a chosen folder → a document created straight in
     *   their SAF tree;
     * - everyone else (API 29+) → a pending row in MediaStore Downloads/Flit.
     * Returns null only for old devices (API < 29) or when storage refuses,
     * which keeps the classic two-step flow as the fallback.
     */
    private fun openIncomingSink(info: FileInfo, treeUri: String?): IncomingSink? {
        // Crushed (gzip) wires need the assembled whole before the one-shot
        // decode, so they take the server's part-file route; publishReceived
        // unpacks them into the original name/bytes afterwards.
        if (FlitCrush.isCrushed(info.fileName)) return null
        if (Build.VERSION.SDK_INT < 29) return null
        if (debugProp("debug.flit.nosink") == "1") return null // A/B: classic double-copy path
        val resolver = appCtx.contentResolver
        // Pinned peer's own folder: create the target document up front and
        // stream into it directly.
        if (!treeUri.isNullOrBlank()) return runCatching { sinkIntoTree(info, treeUri) }.getOrNull()
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, info.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, info.fileType ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Flit")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val url = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = resolver.openOutputStream(url) ?: return null
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            IncomingSink(
                open = java.io.BufferedOutputStream(out, 256 * 1024),
                done = { ok ->
                    if (finished.compareAndSet(false, true)) {
                        if (ok) {
                            val (name, size) = runCatching { finalizeRow(url) }
                                .getOrDefault(info.fileName to info.size)
                            addReceived(ReceivedFile(name, size, System.currentTimeMillis(), null, url.toString()))
                            logLine("→ Downloads/Flit/$name")
                        } else {
                            runCatching { resolver.delete(url, null, null) }
                        }
                    }
                },
            )
        }.getOrNull()
    }

    /** Stream a receive straight into a chosen SAF folder (pinned peers). */
    private fun sinkIntoTree(info: FileInfo, treeUri: String): IncomingSink {
        val tree = Uri.parse(treeUri)
        val resolver = appCtx.contentResolver
        val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val rootUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
        val name = uniqueNameIn(rootUri, info.fileName)
        val docUri = android.provider.DocumentsContract.createDocument(
            resolver, rootUri, info.fileType ?: guessMime(info.fileName), name,
        ) ?: throw IllegalStateException("SAF rejected createDocument")
        val out = resolver.openOutputStream(docUri)
            ?: throw IllegalStateException("cannot write to tree")
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        return IncomingSink(
            open = java.io.BufferedOutputStream(out, 256 * 1024),
            done = { ok ->
                if (finished.compareAndSet(false, true)) {
                    if (ok) {
                        addReceived(ReceivedFile(name, info.size, System.currentTimeMillis(), null, docUri.toString()))
                        logLine("→ $name (chosen folder)")
                    } else {
                        // Aborted/partial transfer: remove the half-written
                        // document so the folder stays clean.
                        runCatching {
                            android.provider.DocumentsContract.deleteDocument(resolver, docUri)
                        }
                    }
                }
            },
        )
    }

    /** Un-pend a successful pending row and read back its final name/size. */
    private fun finalizeRow(url: Uri): Pair<String, Long> {
        val resolver = appCtx.contentResolver
        resolver.update(
            url,
            android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null,
        )
        var name = url.lastPathSegment ?: "received"
        var size = -1L
        runCatching {
            resolver.query(
                url,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    private fun publishReceived(file0: File, info: FileInfo, senderFp: String) {
        var file = file0
        if (FlitCrush.isCrushed(file.name)) {
            val plain = File(file.parent, FlitCrush.plainName(file.name))
            runCatching { FlitCrush.unpack(file, plain) }
                .onSuccess {
                    runCatching { file.delete() }
                    file = plain
                    logLine("unpacked \u2192 ${plain.name}")
                }
                .onFailure {
                    logLine("could not unpack ${file.name} — keeping the gzip (${it.message ?: it.javaClass.simpleName})")
                }
        }
        addReceived(ReceivedFile(file.name, file.length(), System.currentTimeMillis(), file))
        // A per-peer folder, else the global default, else the Downloads/Flit bucket.
        val tree = receiveTreeFor(senderFp)
        if (tree != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { copyToTree(file, tree) }
                    .onSuccess {
                        runCatching { file.delete() }
                        logLine("\u2192 ${it ?: file.name}")
                    }
                    .onFailure {
                        runCatching { copyToDownloadsBucket(file) }
                        logLine("\u2192 Downloads/Flit/${file.name} (folder unreachable)")
                    }
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            scope.launch(Dispatchers.IO) {
                runCatching { copyToDownloadsBucket(file) }
                    .onSuccess {
                        runCatching { file.delete() }
                        logLine("→ Downloads/Flit/${file.name}")
                    }
            }
        }
        // API < 29: the file stays in the app dir; the Received list points there.
    }


    private fun copyToTree(file: File, treeUri: String): String? {
        val tree = Uri.parse(treeUri)
        val resolver = appCtx.contentResolver
        val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val rootUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
        val name = uniqueNameIn(rootUri, file.name)
        val docUri = android.provider.DocumentsContract.createDocument(
            resolver, rootUri, guessMime(file.name), name,
        ) ?: throw IllegalStateException("SAF rejected createDocument")
        resolver.openOutputStream(docUri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("cannot write to tree")
        return name
    }

    /** SAF providers do not dedupe consistently; do it ourselves. */
    private fun uniqueNameIn(dir: Uri, wanted: String): String {
        val resolver = appCtx.contentResolver
        try {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                dir, android.provider.DocumentsContract.getDocumentId(dir),
            )
            resolver.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val taken = HashSet<String>(c.count)
                while (c.moveToNext()) c.getString(0)?.let { taken.add(it) }
                if (wanted !in taken) return wanted
                val stem = wanted.substringBeforeLast('.', "")
                val ext = wanted.substringAfterLast('.', "").takeIf { wanted.contains('.') }
                var n = 1
                while (true) {
                    val candidate = if (ext != null) "$stem ($n).$ext" else "$stem ($n)"
                    if (candidate !in taken) return candidate
                    n++
                }
            }
        } catch (_: Exception) {
        }
        return wanted
    }

    private fun copyToDownloadsBucket(file: File): Uri {
        val resolver = appCtx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMime(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Flit")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore rejected insert")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("cannot write MediaStore uri")
        return uri
    }

    internal fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "pdf" -> "application/pdf"; "txt", "md" -> "text/plain"; "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    // ==== notifications =========================================================

    private fun servicePendingIntent() = runCatching {
        android.app.PendingIntent.getActivity(
            appCtx, 0,
            android.content.Intent(appCtx, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Sharing status", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, "Transfers", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun serviceNotification(ctx: Context, alias: String, port: Int): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Flit is visible on your Wi\u2011Fi")
            .setContentText("\u201c$alias\u201d \u00b7 port $port \u00b7 tap to manage")
            .setContentIntent(servicePendingIntent())
            .setOngoing(true)
            .build()

    // ==== helpers ===============================================================

    /** Logcat-visible early (boot receiver calls this pre-engine). */
    fun logLine(text: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        android.util.Log.i("FlitSend", text)
        _log.update { list -> (list + LogLine(ts, text)).takeLast(60) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun clearReceived() {
        _received.value = emptyList()
        persistReceived()
    }

    /** Debug escape hatch: `adb shell setprop debug.flit.nopin 1` before starting the engine. */
    private fun debugProp(key: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    private fun loadOrCreateIdentity(ctx: Context): TlsIdentity {
        val f = File(ctx.filesDir, "flit-identity.p12")
        return if (f.exists()) TlsIdentity.load(f) else TlsIdentity.generate().also { it.save(f) }
    }

    private fun outgoingFrom(ctx: Context, uri: Uri): OutgoingFile? {
        val resolver = ctx.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name: String
        val size: Long
        if (uri.scheme == "file") {
            val f = File(uri.path!!)
            name = f.name
            size = f.length()
        } else {
            var n = uri.lastPathSegment ?: "file"
            var s = -1L
            resolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { n = it }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) s = c.getLong(sizeIdx)
                }
            }
            name = n
            size = s
        }
        var effSize = size
        if (effSize < 0) {
            // Share-sheet providers often omit SIZE; stat the PFD instead.
            effSize = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.onFailure {
                android.util.Log.v("FlitShare", "pfd stat failed for $uri: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(-1L)
        }
        android.util.Log.v("FlitShare", "outgoingFrom uri=$uri name=$name qsize=$size eff=$effSize mime=$mime")
        if (effSize < 0) return null
        return UriOutgoing(resolver, uri, name, effSize, mime)
    }

    /**
     * Crush & send pass: compressible entries are spooled as gzip
     * "name.txt.gflit"; the wire's sha/size/progress all derive from the
     * spooled file, so protocol-level checks and resume are unaffected.
     * Non-crushable entries pass through unchanged; text notes are tiny.
     */
    private fun crushOutgoing(
        appCtx: Context,
        files: List<OutgoingFile>,
        sink: MutableList<File>,
    ): List<OutgoingFile> = files.map { of ->
        when {
            of is TextNoteOutgoing -> of
            !FlitCrush.crushable(of.wireName, of.mime, of.size) -> of
            else -> runCatching {
                val dir = File(appCtx.cacheDir, "crush").apply { mkdirs() }
                val tmp = File.createTempFile("crush-", FlitCrush.SUFFIX, dir)
                of.openStream().use { ins -> FlitCrush.crushTo(ins, tmp) }
                sink.add(tmp)
                CrushedOutgoing(tmp, of.wireName, of.mime)
            }.getOrDefault(of)
        }
    }

    private class CrushedOutgoing(
        private val spool: File,
        private val plain: String,
        private val originalMime: String,
    ) : OutgoingFile {
        override val wireName = plain + FlitCrush.SUFFIX
        override val size = spool.length()
        override val mime = originalMime
        override fun openStream() = spool.inputStream()
    }

    fun fmtBytes(v: Long): String = when {
        v >= 1024L * 1024 * 1024 -> "%.1f GiB".format(v / 1024f / 1024 / 1024)
        v >= 1024L * 1024 -> "%.1f MiB".format(v / 1024f / 1024)
        v >= 1024L -> "%.0f KiB".format(v / 1024f)
        else -> "$v B"
    }

    private fun File.toPath() = java.nio.file.Paths.get(absolutePath)

    private class UriOutgoing(
        private val resolver: android.content.ContentResolver,
        val uri: Uri,
        override val wireName: String,
        override val size: Long,
        override val mime: String,
    ) : OutgoingFile {
        override fun openStream() = resolver.openInputStream(uri)
            ?: throw IOException("cannot open $wireName")
    }

    /** A chat-style text note sent through the same file pipeline (v2.2 pure). */
    private class TextNoteOutgoing(
        val text: String,
    ) : OutgoingFile {
        override val wireName: String =
            "Flit note " + SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        override val size: Long = text.toByteArray(Charsets.UTF_8).size.toLong()
        override val mime: String = "text/plain"
        override fun openStream() = java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    }
}
