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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Peers vanish from the list after this long without an announce/register. */
const val PEER_TTL_MS = 30_000L

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
        val displayName: String get() = info.alias
        val address: String get() = "$ip:${info.port}"
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

    private val _peers = MutableStateFlow<Map<String, PeerEntry>>(emptyMap())
    val peers: StateFlow<Map<String, PeerEntry>> = _peers

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log

    private val _received = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val received: StateFlow<List<ReceivedFile>> = _received

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
    private var multicastLock: WifiManager.MulticastLock? = null
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
    }

    fun prefs(): android.content.SharedPreferences =
        (if (::appCtx.isInitialized) appCtx else bootCtx)
            ?.getSharedPreferences("flit", Context.MODE_PRIVATE)
            ?: throw IllegalStateException("no context yet for prefs")

    /** For nested/delegate access to the app context. */
    internal fun ctx(): Context = appCtx

    internal fun toast(msg: String) {
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
        runCatching { discovery?.stop() }
        runCatching { server?.close() }
        runCatching { client?.close() }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        server = null
        client = null
        discovery = null
        _peers.value = emptyMap()
        _state.value = _state.value.copy(running = false, starting = false)
        logLine("stopped")
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
            val wifiNet = cm?.allNetworks?.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true
            }
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

            if (seq != startSeq) { // stopped/restarted meanwhile
                runCatching { disc.stop(); srv.close(); cli.close() }
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
            _lastPeer.value = lastPeerEntry()
        } catch (t: Throwable) {
            _state.value = _state.value.copy(running = false, starting = false, error = t.message ?: t.javaClass.simpleName)
            logLine("start failed: ${t.message ?: t.javaClass.simpleName}")
            // Full stack goes to logcat (obfuscated in release; use mapping.txt to deobfuscate).
            android.util.Log.e("FlitEngine", "engine start failed", t)
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
        // A peer we owe files to just came back: dispatch its send-later batch
        // (guarded so an announce storm cannot double-send).
        if (FlitRoster.queued().any { it.fingerprint == info.fingerprint }) {
            flushQueueFor(PeerEntry(ip, info, now))
        }
    }

    /**
     * Dispatches this peer's queued send-later batch, or no-ops quietly when
     * nothing is queued / a dispatch is already in flight. Also the entry
     * point for the queue's manual "retry now".
     */
    private fun flushQueueFor(peer: PeerEntry) {
        if (FlitRoster.queued().none { it.fingerprint == peer.info.fingerprint }) return
        if (!dispatching.add(peer.info.fingerprint)) return
        val batch = FlitRoster.queued().first { it.fingerprint == peer.info.fingerprint }
        scope.launch(Dispatchers.IO) {
            try {
                val spools = batch.uris.mapNotNull { spoolFileFor(it, "queue") }
                if (spools.isEmpty()) {
                    logLine("queue: nothing left for ${peer.displayName} — dropping")
                    FlitRoster.dequeue(peer.info.fingerprint)
                } else {
                    logLine("queue: ${spools.size} queued for ${peer.displayName} — sending now")
                    dispatchTo(peer, SourceList(spools, emptyList()), null, fromQueue = true)
                    FlitRoster.dequeue(peer.info.fingerprint)
                }
            } finally {
                dispatching.remove(peer.info.fingerprint)
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
        FlitRoster.addManual("$host:$port")
        insertManualStub(host, port)
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
        dispatchTo(peer, SourceList(emptyList(), uris), null, fromQueue)
    }

    /** Share-sheet dispatch: spooled files + optional text note; clears intake. */
    fun sendShare(peer: PeerEntry) {
        val intake = _share.value ?: return
        if (!intake.ready) {
            logLine("still preparing the share…")
            return
        }
        val picked = intake.uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        dispatchTo(peer, SourceList(intake.files, picked), intake.text, fromQueue = false)
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

    private fun dispatchTo(peer: PeerEntry, src: SourceList, text: String?, fromQueue: Boolean) {
        val cli = client ?: run { logLine("not sharing — start sharing first"); return }
        val self = server?.selfInfo() ?: return
        scope.launch(Dispatchers.IO) {
            var outgoing: List<OutgoingFile> = emptyList()
            var prog = FlitProgress(appCtx, peer.info.fingerprint.hashCode() and 0x7fffffff)
            val crushedTmp = ArrayList<File>()
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
                    return@launch
                }
                val target = SendTarget(peer.ip, peer.info.port, peer.info.protocol == "https")
                prog.begin(FlitProgress.Kind.SEND, outgoing.associate { it.wireName to it.size })
                val report = cli.sendFiles(target, self, outgoing, progress = { name, soFar, _ ->
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
                _share.value = null
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                var queuedLog = false
                if (!fromQueue) {
                    val tokens = queueTokensFor(src)
                    if (!tokens.isNullOrEmpty()) {
                        val ok = FlitRoster.enqueue(
                            QueuedSend(
                                fingerprint = peer.info.fingerprint,
                                alias = peer.displayName,
                                uris = tokens,
                                names = outgoing.map { it.wireName },
                                createdMs = System.currentTimeMillis(),
                            )
                        )
                        if (ok) {
                            _queue.value = FlitRoster.queued()
                            logLine("${peer.displayName} unreachable ($reason) — kept, sends when it reappears")
                            prog.finish("queued for later", ok = false, iconDone = false)
                            queuedLog = true
                        }
                    }
                }
                if (!queuedLog) {
                    logLine("send failed: $reason")
                    prog.finish("send failed — $reason", ok = false, iconDone = false)
                }
                _share.value = null
            } finally {
                crushedTmp.forEach { runCatching { it.delete() } }
            }
        }
    }

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
        FlitRoster.dequeue(fingerprint)
        _queue.value = FlitRoster.queued()
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

    /** Called by MainActivity for ACTION_SEND / ACTION_SEND_MULTIPLE intents. */
    fun handleShareIntent(intent: Intent?, referrerHost: String?) {
        if (intent == null) return
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
                            _received.update { list ->
                                (list + ReceivedFile(name, size, System.currentTimeMillis(), null, url.toString())).takeLast(20)
                            }
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
                        _received.update { list ->
                            (list + ReceivedFile(name, info.size, System.currentTimeMillis(), null, docUri.toString())).takeLast(20)
                        }
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
        _received.update { list -> (list + ReceivedFile(file.name, file.length(), System.currentTimeMillis(), file)).takeLast(20) }
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
