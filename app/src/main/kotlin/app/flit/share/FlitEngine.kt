package app.flit.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
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

private const val CHANNEL_SERVICE = "service"
private const val CHANNEL_TRANSFERS = "transfers"

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

    data class ReceivedFile(val name: String, val bytes: Long, val whenMs: Long, val path: File)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private val _peers = MutableStateFlow<Map<String, PeerEntry>>(emptyMap())
    val peers: StateFlow<Map<String, PeerEntry>> = _peers

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log

    private val _received = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val received: StateFlow<List<ReceivedFile>> = _received

    // ==== internal fields =======================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appCtx: Context
    private var identity: TlsIdentity? = null
    private var server: ProtocolServer? = null
    private var client: ProtocolClient? = null
    private var discovery: UdpDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var startSeq = 0

    private val delegate = FlitDelegate()

    private class FlitDelegate : ServerDelegate {

        override fun onRegister(info: PeerInfo, ip: String) {
            FlitEngine.touchPeer(ip, info)
        }

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision {
            FlitEngine.logLine("offer from ${request.info.alias} ($peerIp): ${request.files.size} file(s)")
            return PrepareDecision.Accept(request.files.keys)
        }

        override fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {
            FlitEngine.logLine("receiving ${file.fileName}")
        }

        override fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {
            when (result) {
                is UploadResult.Saved -> {
                    FlitEngine.logLine("saved ${file.fileName} (" + FlitEngine.fmtBytes(result.bytes) + ")")
                    FlitEngine.publishReceived(result.path.toFile(), file)
                }
                UploadResult.Mismatch -> FlitEngine.logLine("BAD ${file.fileName}: checksum mismatch")
                UploadResult.Error -> FlitEngine.logLine("error writing ${file.fileName}")
            }
        }

        override fun onSessionEnd(sessionId: String, cancelled: Boolean) {
            if (cancelled) FlitEngine.logLine("transfer cancelled")
        }
    }

    // ==== lifecycle =============================================================

    fun prefs(): android.content.SharedPreferences = appCtx.getSharedPreferences("flit", Context.MODE_PRIVATE)

    fun currentAlias(): String = prefs().getString("alias", null) ?: defaultAlias()

    private fun defaultAlias(): String = "Flit \u00b7 ${Build.MODEL.take(24)}"

    fun setAlias(alias: String) {
        prefs().edit().putString("alias", alias.trim().ifEmpty { defaultAlias() }).apply()
        // Applies on the next start; the UI hints about toggling sharing to renew.
    }

    fun start(ctx: Context) {
        if (_state.value.running || _state.value.starting) return
        appCtx = ctx.applicationContext
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

            startPruner(seq)
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
                _peers.update { m -> m.filterValues { it.lastSeenMs >= cutoff } }
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

    fun send(peer: PeerEntry, uris: List<Uri>) {
        val cli = client ?: run { logLine("not sharing \u2014 start sharing first"); return }
        val self = server?.selfInfo() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val outgoing = uris.mapNotNull { uri -> outgoingFrom(appCtx, uri) }
                if (outgoing.isEmpty()) {
                    logLine("nothing openable selected")
                    return@launch
                }
                val target = SendTarget(peer.ip, peer.info.port, peer.info.protocol == "https")
                val report = cli.sendFiles(target, self, outgoing)
                if (report.sent.isNotEmpty()) {
                    logLine("sent ${report.sent.size}/${outgoing.size} to ${peer.displayName}: ${report.sent.joinToString(", ")}")
                }
                for (f in report.failed) logLine("FAIL ${f.fileName}: ${f.reason}")
                if (report.sent.isEmpty() && report.failed.isEmpty()) logLine("declined by ${peer.displayName}")
            } catch (e: Exception) {
                logLine("send failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ==== receive publishing ====================================================

    private fun publishReceived(file: File, info: FileInfo) {
        _received.update { list -> (list + ReceivedFile(file.name, file.length(), System.currentTimeMillis(), file)).takeLast(20) }
        notifyReceived(file)
        // Republish into the user-visible Downloads/Flit bucket (API 29+).
        if (Build.VERSION.SDK_INT >= 29) {
            scope.launch(Dispatchers.IO) {
                runCatching { copyToDownloads(file) }
                    .onSuccess {
                        runCatching { file.delete() }
                        logLine("\u2192 Downloads/Flit/${it.lastPathSegment}")
                    }
            }
        }
    }

    private fun copyToDownloads(file: File): Uri {
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

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "pdf" -> "application/pdf"; "txt", "md" -> "text/plain"; "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    // ==== notifications =========================================================

    private fun notifyReceived(file: File) {
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            (file.absolutePath).hashCode(),
            NotificationCompat.Builder(appCtx, CHANNEL_TRANSFERS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("File received")
                .setContentText("${file.name} (" + fmtBytes(file.length()) + ")")
                .setContentIntent(servicePendingIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

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

    private fun logLine(text: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.update { list -> (list + LogLine(ts, text)).takeLast(60) }
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
        if (size < 0) return null
        return UriOutgoing(resolver, uri, name, size, mime)
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
}
