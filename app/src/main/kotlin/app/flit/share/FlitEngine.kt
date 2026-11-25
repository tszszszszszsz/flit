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
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendReport
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.discovery.UdpDiscovery
import app.flit.share.protocol.relay.RelayLinkProvider
import app.flit.share.protocol.relay.RelaySubscriber
import app.flit.share.protocol.server.IncomingSink
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PanicAlert
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.panic.PanicCore
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
        val kind: FlitRoute.Kind = FlitRoute.Kind.TCP,
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
    /** Digest of a landed file by its ledger key, computed once at receive time. */
    private val receivedDigests = ConcurrentHashMap<String, String>()

    private fun receivedKey(r: ReceivedFile): String = r.path?.absolutePath ?: r.uri.orEmpty()

    private fun digestOf(r: ReceivedFile): String? {
        val stream = r.path?.inputStream() ?: runCatching {
            r.uri?.let { appCtx.contentResolver.openInputStream(Uri.parse(it)) }
        }.getOrNull() ?: return null
        return stream.use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }

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
        // Compute once off the UI thread so "Open file" in Circle is O(1).
        scope.launch(Dispatchers.IO) {
            digestOf(r)?.let { receivedDigests[receivedKey(r)] = it }
        }
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

    /** QR-paired devices: the social graph lives on this device, not in a directory. */
    private val _knownDevices = MutableStateFlow<List<FlitKnown.Device>>(emptyList())
    val knownDevices: StateFlow<List<FlitKnown.Device>> = _knownDevices

    /** Panic alerts are notifications only — recipients decide what to do. */
    data class PanicEvent(
        val id: String,
        val senderFp: String,
        val alias: String?,
        val text: String,
        val atMs: Long,
        val hops: Int,
    )

    private val _panicEvents = MutableStateFlow<List<PanicEvent>>(emptyList())
    val panicEvents: StateFlow<List<PanicEvent>> = _panicEvents
    /** Signed alerts retained by id so Forward re-signs the original, not a copy. */
    private val panicAlerts = ConcurrentHashMap<String, PanicAlert>()

    /** Circle: one active group, its members and the bounded event feed. */
    data class CircleRoute(val client: ProtocolClient, val target: SendTarget)

    /** Bearer invite for this device's Circle; never the group key. */
    private var circleInvite: String? = null

    private val _circleState = MutableStateFlow<FlitCircleStore.CircleState?>(null)
    val circleState: StateFlow<FlitCircleStore.CircleState?> = _circleState

    private val _circleEvents = MutableStateFlow<List<CircleEvent>>(emptyList())
    val circleEvents: StateFlow<List<CircleEvent>> = _circleEvents

    /** Local delivery state for Circle file drops, keyed by sha256. */
    private val _circleDeliveries = MutableStateFlow<Map<String, FlitCircleStore.CircleDelivery>>(emptyMap())
    val circleDeliveries: StateFlow<Map<String, FlitCircleStore.CircleDelivery>> = _circleDeliveries
    /** One delivery pass per sha at a time — no duplicate bytes or status clobber. */
    private val circleDeliveryLocks = ConcurrentHashMap<String, Mutex>()
    /** sha → (next attempt time, consecutive misses), capped 60 s. */
    private val circleDeliveryBackoff = ConcurrentHashMap<String, Pair<Long, Int>>()

    private var lastCircleSignature: String? = null

    private fun refreshCircle() {
        if (!::appCtx.isInitialized) return
        // The invite is a bearer token but it is this device's own circle;
        // persisting it keeps the host able to admit members after a restart.
        circleInvite = prefs().getString("circleInvite", null)
        val st = FlitCircleStore.load(appCtx)
        val signature = listOf(
            st?.id.orEmpty(), st?.keyB64.orEmpty(),
            st?.events?.size.toString(), st?.events?.lastOrNull()?.id.orEmpty(),
            st?.members?.map { "${it.fp}:${it.role.name}" }.orEmpty().joinToString(","),
            st?.rekeyGen.toString(),
            st?.deliveries?.map { "${it.key}:${it.value.statuses.values.joinToString(",")}" }
                .orEmpty().joinToString(";"),
        ).joinToString("|")
        if (signature == lastCircleSignature) return // no-op tick: do not invalidate Compose
        lastCircleSignature = signature
        _circleState.value = st
        _circleEvents.value = st?.events?.let { CircleCore.order(it) } ?: emptyList()
        _circleDeliveries.value = st?.deliveries.orEmpty()
    }

    /** Incoming alert: signed, from a QR-known device, deduped, never auto-acting. */
    internal fun onPanicReceived(alert: PanicAlert, ip: String) {
        // A forward is re-signed by an intermediary; the ORIGINAL author is
        // the trust anchor, so gate on originFp (defaulting to senderFp).
        val origin = alert.originFp.ifBlank { alert.senderFp }
        if (!PanicCore.verify(alert) || !knownFingerprint(origin)) {
            logLine("panic: ignored unverified or QR-unknown origin ${origin.take(12)}")
            return
        }
        if (_panicEvents.value.any { it.id == alert.id }) return
        panicAlerts[alert.id] = alert
        val who = _knownDevices.value.firstOrNull { it.fingerprint.equals(origin, true) }
        val via = if (alert.senderFp.equals(origin, true)) "" else " · via ${alert.senderFp.take(12)}"
        val event = PanicEvent(
            id = alert.id, senderFp = origin,
            alias = who?.alias?.ifBlank { null } ?: who?.label?.ifBlank { null },
            text = alert.text + via, atMs = alert.sentMs, hops = alert.hops,
        )
        _panicEvents.value = (listOf(event) + _panicEvents.value).take(20)
        logLine("panic: ${alert.senderFp.take(12)} — ${alert.text.take(80)}")
        FlitProgress.panic(appCtx, event)
    }

    fun acknowledgePanic(id: String) {
        _panicEvents.value = _panicEvents.value.filterNot { it.id == id }
        logLine("panic acknowledged")
    }

    fun dismissPanic(id: String) {
        _panicEvents.value = _panicEvents.value.filterNot { it.id == id }
    }

    /** Explicit forward only: re-signed by us, hop-limit checked, never a remote action. */
    fun forwardPanic(id: String) {
        val e = _panicEvents.value.firstOrNull { it.id == id } ?: return
        val idf = identity ?: return
        // Re-sign the ORIGINAL alert: id, text and origin survive, so a
        // looped alert is deduplicated instead of reappearing as "new".
        val original = panicAlerts[id]
        val out = if (original != null) {
            PanicCore.forward(original, idf.fingerprint, idf)
        } else {
            PanicCore.create(idf.fingerprint, e.text, idf, e.hops)
        }
        if (out == null) {
            toast("alert reached its hop limit — it cannot be forwarded further")
            return
        }
        scope.launch(Dispatchers.IO) { fanOutPanic(out) }
        dismissPanic(id)
    }

    /** Fan out to QR-known devices over each device's allowed carrier. */
    fun sendPanic(text: String) {
        val idf = identity ?: run { toast("sharing is not ready yet"); return }
        val alert = PanicCore.create(idf.fingerprint, text.trim().take(240), idf)
        scope.launch(Dispatchers.IO) { fanOutPanic(alert) }
    }

    /** Returns only devices that actually answered 200; never claims more. */
    private suspend fun fanOutPanic(alert: PanicAlert): Int {
        var reached = 0
        val devices = _knownDevices.value
        for (d in devices) {
            val policy = d.routePolicy
            val alias = d.alias.ifBlank { d.fingerprint.take(12) }
            var sent = false
            // Bluetooth for AUTO/BLUETOOTH_ONLY device policies.
            if (policy == FlitKnown.RoutePolicy.AUTO || policy == FlitKnown.RoutePolicy.BLUETOOTH_ONLY) {
                if (!d.mac.isNullOrBlank() && btClient != null) {
                    sent = runCatching {
                        btClient!!.sendPanic(SendTarget(d.mac, 0, tls = true), alert) == true
                    }.getOrDefault(false)
                }
            }
            // Relay last for AUTO/RELAY_ONLY, and only when the device opted in.
            if (!sent && d.relayFor && (policy == FlitKnown.RoutePolicy.AUTO || policy == FlitKnown.RoutePolicy.RELAY_ONLY)) {
                val relay = d.relay?.let { FlitRoute.parseRelay(it) } ?: relayConfig()?.let {
                    it.host to it.port
                }
                if (relay != null) {
                    val dialer = relayClientFor(
                        d.fingerprint, d.token ?: relayConfig()?.token, relay.first, relay.second,
                    )
                    if (dialer != null) {
                        val target = SendTarget(relay.first, relay.second, tls = true, relayFor = d.fingerprint)
                        sent = runCatching { dialer.sendPanic(target, alert) == true }.getOrDefault(false)
                    }
                }
            }
            // Direct Wi-Fi route for AUTO/WIFI_ONLY.
            if (!sent && (policy == FlitKnown.RoutePolicy.AUTO || policy == FlitKnown.RoutePolicy.WIFI_ONLY)) {
                val r = runCatching { FlitRoster.routeFor(d.fingerprint) }.getOrNull()
                if (r != null && !r.isBluetooth && r.port in 1..65535) {
                    sent = runCatching {
                        client?.sendPanic(SendTarget(r.host, r.port, r.tls), alert) == true
                    }.getOrDefault(false)
                }
            }
            if (sent) reached++ else logLine("panic: no live route to $alias")
        }
        logLine("panic: $reached/${devices.size} known device(s) confirmed the alert")
        return reached
    }

    private val circleDeliveryTerminal = setOf(
        FlitCircleStore.CircleDelivery.SENT,
        FlitCircleStore.CircleDelivery.REFUSED,
        FlitCircleStore.CircleDelivery.FAILED,
    )

    /** Create/merge a delivery record, then push real bytes to current members. */
    private suspend fun startCircleDelivery(name: String, size: Long, sha: String, spoolPath: String) {
        val key = sha.trim()
        val st = FlitCircleStore.load(appCtx)
        if (st == null) {
            runCatching { File(spoolPath).delete() }
            return
        }
        val me = (st.selfFp.ifBlank { identity?.fingerprint.orEmpty() }).trim().uppercase()
        val members = st.members.map { it.fp.trim().uppercase() }
            .filter { it.isNotBlank() && it != me }.distinct()
        val existing = st.deliveries[key]
        // A repeated drop of the same bytes keeps honest outcomes and adds
        // only new recipients; the superseded spool is reclaimed.
        if (existing != null && existing.spoolPath != spoolPath) {
            runCatching { File(existing.spoolPath).delete() }
        }
        val statuses = members.associateWith { fp ->
            existing?.statuses?.get(fp)?.takeIf { it in circleDeliveryTerminal }
                ?: FlitCircleStore.CircleDelivery.PENDING
        }
        if (statuses.isEmpty()) {
            runCatching { File(spoolPath).delete() }
            if (existing != null) FlitCircleStore.dropDelivery(appCtx, key)
            return
        }
        val delivery = existing?.copy(
            name = name, size = size, spoolPath = spoolPath,
            statuses = statuses, createdMs = System.currentTimeMillis(),
        ) ?: FlitCircleStore.CircleDelivery(
            id = UUID.randomUUID().toString(),
            name = name, size = size, sha256 = key, spoolPath = spoolPath,
            statuses = statuses, createdMs = System.currentTimeMillis(),
        )
        FlitCircleStore.persistDelivery(appCtx, delivery)?.let { _circleDeliveries.value = it.deliveries }
        deliverCirclePending(delivery)
    }

    /**
     * One serialized pass per sha. A returned report concludes that recipient;
     * a thrown carrier or a missing route stays PENDING for a later retry.
     * Only current circle members are ever sent bytes — a kicked/left member
     * does not receive the retry.
     */
    private suspend fun deliverCirclePending(delivery: FlitCircleStore.CircleDelivery) {
        val key = delivery.sha256.trim()
        val lock = circleDeliveryLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            val state = FlitCircleStore.load(appCtx) ?: return@withLock
            val current = state.deliveries[key] ?: return@withLock
            val spool = File(current.spoolPath)
            if (!spool.exists()) {
                // The cache copy is gone; no honest retry is possible.
                FlitCircleStore.dropDelivery(appCtx, key)?.let { _circleDeliveries.value = it.deliveries }
                refreshCircle()
                return@withLock
            }
            val self = server?.selfInfo() ?: return@withLock
            val me = (state.selfFp.ifBlank { identity?.fingerprint.orEmpty() }).trim().uppercase()
            val allowed = state.members.map { it.fp.trim().uppercase() }
                .filter { it.isNotBlank() && it != me }.toSet()
            var latestState = state
            for ((fp, status) in current.statuses) {
                if (fp !in allowed || status in circleDeliveryTerminal) continue
                val next = runCatching { circleDial(fp) }.getOrNull()?.let { route ->
                    val report = runCatching {
                        route.client.sendFiles(
                            route.target, self,
                            listOf(PathOutgoing(spool.toPath(), current.name)),
                            multi = false,
                        )
                    }.getOrNull()
                    when {
                        report == null -> FlitCircleStore.CircleDelivery.PENDING
                        report.declined -> FlitCircleStore.CircleDelivery.REFUSED
                        report.sent.any { it == current.name } -> FlitCircleStore.CircleDelivery.SENT
                        report.failed.any { it.fileName == current.name } -> FlitCircleStore.CircleDelivery.FAILED
                        else -> FlitCircleStore.CircleDelivery.PENDING
                    }
                } ?: FlitCircleStore.CircleDelivery.PENDING
                if (next != status) {
                    latestState = FlitCircleStore.persistDeliveryStatus(appCtx, key, fp, next) ?: latestState
                }
            }
            val latest = latestState.deliveries[key] ?: current
            _circleDeliveries.value = latestState.deliveries
            if (latest.statuses.values.all { it in circleDeliveryTerminal }) {
                runCatching { spool.delete() } // every current recipient concluded
                circleDeliveryBackoff.remove(key)
            } else {
                val attempt = (circleDeliveryBackoff[key]?.second ?: 0) + 1
                val wait = minOf(60_000L, 10_000L shl minOf(attempt, 3))
                circleDeliveryBackoff[key] = System.currentTimeMillis() + wait to attempt
            }
            refreshCircle()
        }
    }

    /** Calm retry tick for failed/pending drops; a report concludes, a throw stays owed. */
    private fun startCircleDeliveryWorker(seq: Int) {
        scope.launch(Dispatchers.IO) {
            while (isActive && seq == startSeq) {
                delay(30_000)
                for (d in _circleDeliveries.value.values) {
                    if (!isActive || seq != startSeq) return@launch
                    val backoff = circleDeliveryBackoff[d.sha256]
                    if (backoff != null && System.currentTimeMillis() < backoff.first) continue
                    if (d.statuses.values.any {
                            it == FlitCircleStore.CircleDelivery.PENDING ||
                                it == FlitCircleStore.CircleDelivery.FAILED
                        }
                    ) {
                        deliverCirclePending(d)
                    }
                }
            }
        }
    }

    /** Calm Circle sync: one pull shortly after start, then on the 30 s gate. */
    private fun startCircleSyncWorker(seq: Int) {
        scope.launch(Dispatchers.IO) {
            var first = true
            while (isActive && seq == startSeq) {
                delay(if (first) 3_000L else 30_000L)
                val active = _circleState.value ?: continue
                first = false
                runCatching { FlitCircleSync.sync() }
                    .onFailure { logLine("circle sync: ${it.message ?: it.javaClass.simpleName}") }
                refreshCircle()
            }
        }
    }

    // ==== Circle ================================================================

    /** Create a Circle: owner role, fresh id/invite/key, self as first member. */
    fun startCircle(): QrAdd? {
        val idf = identity ?: return null
        if (!::appCtx.isInitialized) return null
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val invite = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val st = FlitCircleStore.CircleState(
            id = id,
            keyB64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP),
            role = FlitCircleStore.Role.OWNER,
            members = listOf(
                FlitCircleStore.Member(idf.fingerprint, currentAlias(), FlitCircleStore.Role.OWNER, System.currentTimeMillis()),
            ),
            events = emptyList(),
            selfFp = idf.fingerprint.trim().uppercase(),
        )
        FlitCircleStore.save(appCtx, st)
        circleInvite = invite
        prefs().edit().putString("circleInvite", invite).apply()
        refreshCircle()
        logLine("circle: started $id as owner")
        return QrAdd(
            ip = pickAnnounceAddress() ?: "",
            port = server?.port ?: Protocol.DEFAULT_PORT,
            tls = true,
            fingerprint = idf.fingerprint,
            alias = currentAlias(),
            mac = bluetoothAddress(),
            relay = relayConfig()?.let { "${it.host}:${it.port}" },
            token = relayConfig()?.token,
            circle = id,
            invite = invite,
            v = 3,
        )
    }

    fun joinCircle(code: QrAdd) {
        if (!::appCtx.isInitialized) return
        // Never persist a bearer invite before the host proves it accepted.
        scope.launch(Dispatchers.IO) {
            runCatching { FlitCircleSync.join(code) }
                .onFailure { logLine("circle join failed: ${it.message ?: it.javaClass.simpleName}") }
            refreshCircle()
        }
    }

    fun circlePost(text: String) = scope.launch(Dispatchers.IO) {
        val body = buildJsonObject { put("text", JsonPrimitive(text)) }.toString()
        runCatching { FlitCircleSync.post("message", body) }
            .onFailure { logLine("circle post failed: ${it.message}") }
        refreshCircle()
    }

    /**
     * Resolve a Circle file event's sha256 to a file that actually landed
     * here. The received ledger has no digest field, so re-hash its entries;
     * it is bounded to the last 20 files and this runs on IO.
     */
    suspend fun circleReceivedFor(sha: String): ReceivedFile? = withContext(Dispatchers.IO) {
        val target = sha.trim().lowercase()
        if (target.isBlank()) return@withContext null
        for (r in _received.value) {
            val cached = receivedDigests[receivedKey(r)]
            val match = cached?.equals(target, ignoreCase = true) ?: digestOf(r)?.equals(target, ignoreCase = true)
            if (match == true) return@withContext r
        }
        return@withContext null
    }

    /**
     * Announce a file drop, then deliver the bytes through the existing
     * sendFiles path. [spoolPath] is local only — never put it on the wire.
     */
    fun circlePostFile(name: String, size: Long, sha: String, spoolPath: String) =
        scope.launch(Dispatchers.IO) {
            val posted = runCatching { FlitCircleSync.postFile(name, size, sha) }
            if (posted.isFailure) {
                logLine("circle file event failed: ${posted.exceptionOrNull()?.message}")
                return@launch
            }
            startCircleDelivery(name, size, sha, spoolPath)
            refreshCircle()
        }

    fun circlePostPoll(question: String, options: List<String>) = scope.launch(Dispatchers.IO) {
        val pollId = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("pollId", JsonPrimitive(pollId))
            put("question", JsonPrimitive(question))
            put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        runCatching { FlitCircleSync.post("poll", body) }
            .onFailure { logLine("circle poll failed: ${it.message}") }
        refreshCircle()
    }

    fun circleVote(pollId: String, option: Int) = scope.launch(Dispatchers.IO) {
        runCatching { FlitCircleSync.vote(pollId, option) }
            .onFailure { logLine("circle vote failed: ${it.message}") }
        refreshCircle()
    }

    fun circleStartKick(targetFp: String) = scope.launch(Dispatchers.IO) {
        runCatching { FlitCircleSync.startKick(targetFp) }
            .onFailure { logLine("circle kick vote failed: ${it.message}") }
        refreshCircle()
    }

    fun circleVoteKick(kickId: String, approve: Boolean) = scope.launch(Dispatchers.IO) {
        runCatching { FlitCircleSync.voteKick(kickId, approve) }
            .onFailure { logLine("circle kick vote failed: ${it.message}") }
        refreshCircle()
    }

    fun circleKick(targetFp: String) = scope.launch(Dispatchers.IO) {
        runCatching { FlitCircleSync.kick(targetFp) }
            .onFailure { logLine("circle kick failed: ${it.message}") }
        refreshCircle()
    }

    /** Only a verified JOIN may adopt an invite as this device's circle bearer. */
    internal fun circleInviteAccepted(invite: String) {
        if (!::appCtx.isInitialized || invite.isBlank()) return
        circleInvite = invite
        prefs().edit().putString("circleInvite", invite).apply()
    }

    /** Owner-only role change; the sync worker's guard and store reducer are the authority. */
    fun circleSetRole(fp: String, role: FlitCircleStore.Role) = scope.launch(Dispatchers.IO) {
        runCatching { FlitCircleSync.circleSetRole(fp, role) }
            .onFailure { logLine("circle role change failed: ${it.message}") }
        refreshCircle()
    }

    fun circleLeave() {
        if (!::appCtx.isInitialized) return
        scope.launch(Dispatchers.IO) {
            runCatching { FlitCircleSync.leave() }
            FlitCircleStore.clear(appCtx)
            refreshCircle()
            logLine("circle: left fully — key discarded")
        }
    }

    /** Per-peer carrier choice: direct Wi‑Fi, then Bluetooth, relay strictly last. */
    internal suspend fun circleDial(fp: String): CircleRoute? {
        val known = _knownDevices.value.firstOrNull { it.fingerprint.equals(fp, true) }
        val policy = known?.routePolicy ?: FlitKnown.RoutePolicy.AUTO

        // A live remembered route always wins; relay is never a first choice.
        if (policy == FlitKnown.RoutePolicy.AUTO || policy == FlitKnown.RoutePolicy.WIFI_ONLY) {
            val r = runCatching { FlitRoster.routeFor(fp) }.getOrNull()
            if (r != null && !r.isBluetooth && r.port in 1..65535 && client != null) {
                return CircleRoute(client!!, SendTarget(r.host, r.port, r.tls))
            }
        }
        if (policy == FlitKnown.RoutePolicy.AUTO || policy == FlitKnown.RoutePolicy.BLUETOOTH_ONLY) {
            if (!known?.mac.isNullOrBlank() && btClient != null) {
                return CircleRoute(btClient!!, SendTarget(known!!.mac, 0, tls = true))
            }
        }
        // The per-device switch is the authority: an opted-out device never
        // gets its Circle traffic routed through a relay.
        if (known?.relayFor == true &&
            (policy == FlitKnown.RoutePolicy.AUTO || policy == FlitKnown.RoutePolicy.RELAY_ONLY)
        ) {
            val relay = known.relay?.let { FlitRoute.parseRelay(it) }
                ?: relayRouteFor[fp]?.let { it.host to it.port }
                ?: relayConfig()?.let { it.host to it.port }
            if (relay != null) {
                val dialer = relayClientFor(
                    fp, known.token ?: relayConfig()?.token, relay.first, relay.second,
                ) ?: return null
                return CircleRoute(
                    dialer,
                    SendTarget(relay.first, relay.second, tls = true, relayFor = fp),
                )
            }
        }
        return null
    }

    /**
     * Server-side Circle route. The host state machine owns the truth:
     * invite/JOIN, roster-gated SYNC, signature+replay verification, rekey
     * on depart, and refusal of a departed fingerprint's later sync.
     */
    internal fun onCircleRequest(req: CircleRequest, ip: String = ""): CircleResponse {
        val st = _circleState.value ?: return CircleResponse(false, reason = "no circle")
        val idf = identity ?: return CircleResponse(false, reason = "identity unavailable")
        val host = FlitCircleHost(idf, circleInvite.orEmpty(), st.keyB64)
        val answer = host.onRequest(req, st)
        if (answer.ok) {
            // A member that reaches us by invite also gives us a return route;
            // without this, later fan-out has an address-less fingerprint.
            if (req.action == CircleRequest.ACTION_JOIN && ip.isNotBlank() && req.memberFp.isNotBlank()) {
                runCatching {
                    FlitRoster.rememberRoute(
                        req.memberFp.trim().uppercase(), ip,
                        Protocol.DEFAULT_PORT, tls = true,
                    )
                }
            }
            val next = host.accept(answer, st)
            FlitCircleStore.save(appCtx, next)
            _circleState.value = next
            _circleEvents.value = CircleCore.order(next.events)
        }
        if (host.isEvidence(answer, st) && ::appCtx.isInitialized) {
            logLine("circle host: ${req.action} ${req.memberFp.take(12)} — ${answer.events.size} event(s)")
        }
        return answer
    }

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
    /** Bluetooth data carrier, built only when the option is on. */
    private var btClient: ProtocolClient? = null
    private var btServerSocket: android.bluetooth.BluetoothServerSocket? = null
    /** Per-device relay dialers: a QR can carry a different relay + token. */
    private val relayClients = ConcurrentHashMap<String, ProtocolClient>()

    private fun relayClientFor(
        fp: String, token: String?, host: String, port: Int,
    ): ProtocolClient? {
        val idf = identity ?: return null
        if (!::appCtx.isInitialized) return null
        // A rotated token or a different relay must never reuse a stale dialer.
        val key = "$fp|$host|$port|${token.orEmpty()}"
        return relayClients.getOrPut(key) { ProtocolClient(idf, RelayLinkProvider(idf, token)) }
    }

    // Milestone E (opt-in, off by default): the subscription worker and the
    // per-peer mark that the last fingerprint-matched answer came through a
    // relay, so the flush dials the relay rather than the transport address
    // the register carried. A later direct answer clears the mark.
    private class RelayConfig(val host: String, val port: Int, val token: String?)
    private var relayJob: Job? = null
    private val relayParked = AtomicReference<Socket?>(null)
    private val relayRouteFor = ConcurrentHashMap<String, RelayConfig>()

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

        override fun onPanic(alert: PanicAlert, ip: String) {
            FlitEngine.onPanicReceived(alert, ip)
        }

        override fun onCircle(request: CircleRequest, ip: String): CircleResponse =
            FlitEngine.onCircleRequest(request, ip)

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision {
            // QR-only is a carrier gate, not just a discoverability setting:
            // a device that never entered the graph by QR never gets a send.
            if (qrOnly() && !knownFingerprint(request.info.fingerprint)) {
                logLine("declined QR-unknown device ${request.info.alias} (${request.info.fingerprint.take(12)})")
                return PrepareDecision.Decline
            }
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

    /** Options changed the Bluetooth link: rebind the carrier now. */
    fun bluetoothChanged() {
        if (!::appCtx.isInitialized) return
        if (_state.value.running || _state.value.starting) {
            stop()
            start(appCtx)
        }
    }

    /** Options changed E's address or token: rebind now (empty = invisible). */
    fun relayChanged() {
        if (!::appCtx.isInitialized) return
        if (_state.value.running || _state.value.starting) {
            stop()
            start(appCtx)
        }
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
        // E's parked read is blocking; close the socket so the worker wakes
        // and sees the new sequence instead of lingering on a dead engine.
        relayJob?.cancel()
        relayJob = null
        relayParked.getAndSet(null)?.let { runCatching { it.close() } }
        relayRouteFor.clear()
        FlitBluetooth.stopServer() // cancels a waiting retry loop too
        runCatching { btServerSocket?.close() }
        btServerSocket = null
        runCatching { btClient?.close() }
        btClient = null
        relayClients.values.forEach { runCatching { it.close() } }
        relayClients.clear()
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
        // The carrier is bound at engine start. If a grant/power consent
        // arrived after the Bluetooth option was turned on, rebind once so
        // the listener and connectable advertising actually come up.
        if (prefs().getBoolean("bluetoothTransfer", false) &&
            (btClient == null || btServerSocket == null)
        ) {
            bluetoothChanged()
        }
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

            // Milestone E is opt-in: with no typed relay the client is the
            // unchanged TCP one; with one, the same client can dial the relay
            // for a peer whose last answer came through it.
            val relayCfg = relayConfig()
            val cli = if (relayCfg != null) {
                ProtocolClient(id, RelayLinkProvider(id, relayCfg.token))
            } else {
                ProtocolClient(id)
            }
            // Bluetooth is a separate carrier with its own dialer: a target's
            // port is 0 and its host is a MAC, so it must never ride the TCP
            // provider by accident. Both clients share one identity/brain.
            val btOn = prefs().getBoolean("bluetoothTransfer", false)
            val bluetoothClient = if (btOn && FlitBluetooth.hasPermission(appCtx)) {
                ProtocolClient(id, FlitBluetooth.LinkProvider(appCtx, id))
            } else null

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
                // Hidden or QR-only: stay off other devices' rosters entirely.
                announce = !qrOnly() && !prefs().getBoolean("hiddenMode", false),
            )
            if (!qrOnly()) disc.create()

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

            if (!qrOnly()) disc.start() else logLine("qr-only: no ambient discovery")

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
            if (relayCfg != null) {
                startRelayWorker(seq, id, relayCfg, srv)
                logLine("relay: subscribed at ${relayCfg.host}:${relayCfg.port} as ${id.fingerprint.take(12)}")
            }
            btClient = bluetoothClient
            if (bluetoothClient != null) {
                // FlitBluetooth logs only when a listener is actually published;
                // starting the carrier is async and may retry or fail.
                FlitBluetooth.startServer(appCtx, id, srv, scope, ::logLine) { btServerSocket = it }
            }
            if (prefs().getBoolean("hiddenMode", false)) {
                logLine("hidden mode: not announcing — others find this device by address, or when you send first")
            }

            refreshKnown()
            refreshCircle()
            startPruner(seq)
            if (!qrOnly()) {
                startManualProber(seq)
                startBleFlipper(seq)
                startSweeper(seq)
            } else {
                runCatching { radio.stopAll() }
                bleAdvertised = false
            }
            startHunter(seq)
            startCircleSyncWorker(seq)
            startCircleDeliveryWorker(seq)
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
        if (route.isBluetooth) {
            val dialer = btClient ?: return null
            return runCatching {
                dialer.register(SendTarget(route.host, 0, tls = true), self, timeoutMs = 4_000)
            }.getOrNull()
        }
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
            FlitRoute.Route(
                it.ip, it.info.port, it.info.protocol == "https", it.kind,
            )
        }?.takeIf { it.isBluetooth || it.port in 1..65535 }
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
            logLine(
                if (route.isBluetooth) "hunt: bluetooth ${route.host} answered as ${answer.alias.ifBlank { fp.take(12) }}"
                else "hunt: ${route.host}:${route.port} answered as ${answer.alias.ifBlank { fp.take(12) }}",
            )
            huntAttempts.remove(fp)
            huntNextAt.remove(fp)
            huntLogged.remove(fp)
            // The ordinary door does the rest: the row's queued-check
            // flushes the owed batch, and the flush's first-attempt probe
            // resumes from the receiver's own disk.
            if (route.isBluetooth) touchPeerBluetooth(route.host, answer)
            else touchPeer(route.host, answer)
            return
        }
        // Milestone E: the relay is the last candidate, never the first.
        // A direct answer above always won; only an owed peer nobody on the
        // direct routes knows gets one register through the typed relay.
        val relay = relayConfig() ?: return
        val answered = probeRelay(cli, self, seq, fp, relay) ?: return
        if (!FlitRoute.accepts(answered.fingerprint, fp)) return
        logLine("hunt: relay answered as ${answered.alias.ifBlank { fp.take(12) }}")
        huntAttempts.remove(fp)
        huntNextAt.remove(fp)
        huntLogged.remove(fp)
        touchPeerViaRelay(relay.host, answered)
    }

    /** The one typed relay from Options; null means E is off and invisible. */
    private fun relayConfig(): RelayConfig? {
        val (host, port) = FlitRoute.parseRelay(prefs().getString("relayAddress", null)) ?: return null
        val token = prefs().getString("relayToken", "")?.trim()?.ifEmpty { null }
        return RelayConfig(host, port, token)
    }

    /**
     * Receiver half of E: hold one subscription under our fingerprint, hand
     * each paired pipe to the same server door TCP and Bluetooth ride (TLS
     * runs inside the blind pipe), then re-subscribe — prepare and upload
     * arrive on their own pipes. Backoff is the keeper's calm cadence; the
     * parked socket is held so shutdown can unblock the blocking read.
     */
    private fun startRelayWorker(seq: Int, id: TlsIdentity, cfg: RelayConfig, srv: ProtocolServer) {
        relayJob?.cancel()
        relayParked.getAndSet(null)?.let { runCatching { it.close() } }
        relayJob = scope.launch(Dispatchers.IO) {
            var waitMs = 2_000L
            while (isActive && seq == startSeq) {
                try {
                    RelaySubscriber.subscribe(
                        cfg.host, cfg.port, id.fingerprint, cfg.token,
                        parkTimeoutMs = 0, // a subscription's true lifetime
                        onSocket = { relayParked.set(it) },
                    ).let { raw -> srv.serve(TlsLink(raw, id, clientMode = false)) }
                    waitMs = 2_000L
                } catch (t: Throwable) {
                    if (seq != startSeq || !isActive) break
                    logLine("relay: ${t.message ?: t.javaClass.simpleName} — retry in ${waitMs / 1000}s")
                    delay(waitMs)
                    waitMs = minOf(waitMs * 2, 60_000L)
                } finally {
                    relayParked.set(null)
                }
            }
        }
    }

    /** E's last candidate, after every direct route has had its say. */
    private suspend fun probeRelay(
        cli: ProtocolClient,
        self: PeerInfo,
        seq: Int,
        fp: String,
        cfg: RelayConfig,
    ): PeerInfo? {
        if (seq != startSeq) return null
        return runCatching {
            cli.register(
                SendTarget(cfg.host, cfg.port, tls = true, relayFor = fp),
                self,
                timeoutMs = 5_000,
            )
        }.getOrNull()
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
                val hidden = prefs().getBoolean("hiddenMode", false)
                val livePeer = _peers.value.keys.any { !it.startsWith(FlitBle.MANUAL_ROW_PREFIX) }
                val stance = FlitBle.stance(
                    apUp = apUp,
                    perms = permsOk,
                    radioOn = radioOn,
                    hidden = hidden,
                    livePeer = livePeer,
                )
                runCatching {
                    radio.apply(
                        stance, info.alias, info.fingerprint, dump,
                        connectable = btClient != null,
                    )
                }
                // Duty phases flip the radio silently; only a real change in
                // the no-AP facts earns a line in the activity log.
                val hardOn = !apUp && permsOk && radioOn && !hidden && !livePeer
                if (hardOn != bleAdvertised) {
                    bleAdvertised = hardOn
                    when {
                        hardOn -> logLine("no Wi\u2011Fi network; direct discovery on")
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
        touchPeerBluetooth(addr, info)
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

    private fun touchPeer(ip: String, info: PeerInfo) = touchPeer(ip, info, markRelay = false)

    /** A relay answer marks this peer's next flush as a relay dial, not a LAN one. */
    private fun touchPeerViaRelay(host: String, info: PeerInfo) = touchPeer(host, info, markRelay = true)

    /** A BLE announce carries a MAC as the row address — a Bluetooth route. */
    private fun touchPeerBluetooth(host: String, info: PeerInfo) =
        touchPeer(host, info, markRelay = false, kind = FlitRoute.Kind.BLUETOOTH)

    private fun touchPeer(ip: String, info: PeerInfo, markRelay: Boolean) =
        touchPeer(ip, info, markRelay, FlitRoute.Kind.TCP)

    private fun touchPeer(ip: String, info: PeerInfo, markRelay: Boolean, kind: FlitRoute.Kind) {
        if (info.fingerprint == identity?.fingerprint) return
        if (markRelay) {
            relayRouteFor[info.fingerprint] = relayConfig() ?: return
        } else {
            // A direct answer wins again: E is a last resort, never a sticky route.
            relayRouteFor.remove(info.fingerprint)
        }
        val now = System.currentTimeMillis()
        _peers.update { m ->
            val entry = m[info.fingerprint]?.copy(ip = ip, info = info, lastSeenMs = now, kind = kind)
                ?: PeerEntry(ip, info, now, kind)
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
        if (!markRelay) {
            runCatching {
                if (kind == FlitRoute.Kind.BLUETOOTH) {
                    FlitRoster.rememberRoute(info.fingerprint, ip, 0, true, kind)
                } else {
                    FlitRoster.rememberRoute(info.fingerprint, ip, info.port, info.protocol == "https")
                }
            }
        }
        // A peer we owe files to just came back: dispatch its send-later batch
        // (guarded so an announce storm cannot double-send).
        if (FlitRoster.queued().any { it.fingerprint == info.fingerprint }) {
            flushQueueFor(PeerEntry(ip, info, now, kind))
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
     *
     * The per-peer claim is taken HERE, before the batch is spooled — not
     * deeper in [dispatchTo]. The hardware walk caught what the shallower
     * claim allowed: a hunter answer every two seconds started five flushes
     * for one 800 MB shot, each spooled its own full copy into cache, the
     * near-full /data partition refused every copy, and the old empty-spool
     * branch quietly dropped the row. One peer, one spool, one wire attempt;
     * [dispatchTo] runs under this claim (`claimHeld`) and the release stays
     * in the `finally` below.
     */
    private fun flushQueueFor(peer: PeerEntry) {
        val fp = peer.info.fingerprint
        if (FlitRoster.queued().none { it.fingerprint == fp }) return
        if (!dispatching.add(fp)) return
        val batch = FlitRoster.queued().first { it.fingerprint == fp }
        scope.launch(Dispatchers.IO) {
            try {
                val opened = batch.uris.mapIndexedNotNull { i, token ->
                    spoolFileFor(token, "queue")?.let { Triple(token, batch.names.getOrNull(i) ?: it.name, it) }
                }
                val spools = opened.map { it.third }
                if (spools.isEmpty()) {
                    // None of the row's items could be opened right now (a
                    // full disk, a revoked grant, a mid-write camera file).
                    // The row is a promise, not a cache: keep it, say why,
                    // and leave "retry now" / cancel in the user's hands.
                    logLine(
                        "queue: none of ${batch.uris.size} item(s) for ${peer.displayName} " +
                            "could be opened — kept, retry when the reason is gone",
                    )
                } else {
                    logLine("queue: ${spools.size} queued for ${peer.displayName} — sending now")
                    try {
                        // Synchronous with the row's fate: a returned report
                        // means the far side SPOKE (sent, refused, declined)
                        // — the story concluded, so the row's tokens leave
                        // now, after the bytes. A throw means the carrier
                        // died with no answer at all: the row survives and
                        // the receiver's on-disk prefix carries the resume
                        // (docs, milestones C and A).
                        dispatchTo(
                            peer, SourceList(spools, emptyList()), null,
                            fromQueue = true, claimHeld = true,
                        )
                        runCatching {
                            // Only tokens that actually reached the wire may
                            // leave the row. A token that could not be opened
                            // stays owed — the row is a promise, not a cache.
                            val concluded = batch.copy(
                                uris = opened.map { it.first },
                                names = opened.map { it.second },
                            )
                            dropSent(concluded, spools)
                            if (concluded.uris.size != batch.uris.size) {
                                logLine(
                                    "queue: ${batch.uris.size - concluded.uris.size} unopenable item(s) " +
                                        "still owed to ${peer.displayName}",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // The row's keep IS the queue's promise: a send that
                        // never settled (the peer walked out mid-transfer,
                        // the engine not serving yet) leaves the batch and
                        // its grants exactly as they were — the next
                        // announce, hunt answer or "retry now" resumes.
                        val why = e.message ?: e.javaClass.simpleName
                        logLine("$why — kept, sends when it reappears")
                    } finally {
                        // The row keeps TOKENS, never spool paths: the next
                        // flush re-spools from source. Reclaim the copies on
                        // every outcome so a failed run cannot fill /data.
                        cleanSpools(spools)
                    }
                }
            } finally {
                dispatching.remove(fp)
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
    private fun refreshKnown() {
        if (::appCtx.isInitialized) _knownDevices.value = FlitKnown.all(appCtx)
    }

    internal fun knownFingerprint(fp: String): Boolean =
        _knownDevices.value.any { it.fingerprint.equals(fp, ignoreCase = true) }

    /** QR-only: no ambient announce, no scan, no sweep — QR-paired devices only. */
    fun qrOnly(): Boolean = if (::appCtx.isInitialized) FlitKnown.qrOnly(appCtx) else false

    fun setQrOnly(on: Boolean) {
        if (!::appCtx.isInitialized) return
        prefs().edit().putBoolean("qrOnly", on).apply()
        FlitKnown.setQrOnly(appCtx, on)
        refreshKnown()
        if (_state.value.running || _state.value.starting) {
            stop()
            start(appCtx)
        }
    }

    fun forgetKnown(fp: String) {
        if (!::appCtx.isInitialized) return
        FlitKnown.forget(appCtx, fp)
        refreshKnown()
        FlitRoster.forgetRoute(fp)
        logLine("known device forgotten: ${fp.take(12)}")
    }

    fun setKnownAlias(fp: String, alias: String) {
        if (!::appCtx.isInitialized) return
        val d = FlitKnown.all(appCtx).firstOrNull { it.fingerprint == fp } ?: return
        FlitKnown.put(appCtx, d.copy(alias = alias.trim().ifBlank { d.alias }))
        refreshKnown()
    }

    fun setRoutePolicy(fp: String, policy: FlitKnown.RoutePolicy) {
        if (!::appCtx.isInitialized) return
        val d = FlitKnown.all(appCtx).firstOrNull { it.fingerprint == fp } ?: return
        FlitKnown.put(appCtx, d.copy(routePolicy = policy))
        refreshKnown()
        logLine("route for ${d.alias.ifBlank { fp.take(12) }}: ${policy.name}")
    }

    fun setRelayFor(fp: String, on: Boolean) {
        if (!::appCtx.isInitialized) return
        val d = FlitKnown.all(appCtx).firstOrNull { it.fingerprint == fp } ?: return
        FlitKnown.put(appCtx, d.copy(relayFor = on))
        refreshKnown()
    }

    fun selfQrCode(): String? = selfQrAdd()?.uri()

    /**
     * Payload for the "My code" QR: who we are and exactly where a scanner
     * should connect. IPv4 on a multicast-capable interface (Wi‑Fi beats
     * ethernet on the sort order), the real serving port, our fingerprint
     * for the trust badge.
     */
    fun selfQrAdd(): QrAdd? {
        if (identity == null) return null
        val ip = pickAnnounceAddress()
        if (ip == null) {
            // A relay-only code is still a usable pairing when local Wi-Fi is
            // down or deliberately dark (QR-only mode, jamming).
            val relay = relayConfig() ?: return null
            return QrAdd(
                ip = "", port = 0, tls = true,
                fingerprint = identity?.fingerprint ?: "",
                alias = currentAlias(),
                mac = bluetoothAddress(),
                relay = "${relay.host}:${relay.port}",
                token = relay.token,
            )
        }
        return QrAdd(
            ip = ip,
            port = server?.port ?: Protocol.DEFAULT_PORT,
            tls = true,
            fingerprint = identity?.fingerprint ?: "",
            alias = currentAlias(),
            mac = bluetoothAddress(),
            relay = relayConfig()?.let { "${it.host}:${it.port}" },
            token = relayConfig()?.token,
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

    /** Our Bluetooth controller address, when the platform still tells us. */
    private fun bluetoothAddress(): String? =
        runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address }
            .getOrNull()?.takeIf { it.isNotBlank() }

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
        addFromScan(code)
    }

    /** A scanned QR is the only way a device enters the known-devices graph. */
    fun addFromScan(code: QrAdd) {
        val fp = code.fingerprint.trim().uppercase()
        if (fp.isBlank()) {
            logLine("scan: code carries no fingerprint — not a pairing")
            toast("that code has no device identity")
            return
        }
        val known = FlitKnown.Device(
            fingerprint = fp,
            alias = code.alias.trim(),
            mac = code.mac?.trim()?.ifBlank { null },
            relay = code.relay?.trim()?.ifBlank { null },
            token = code.token?.trim()?.ifBlank { null },
            scannedMs = System.currentTimeMillis(),
        )
        // Relay-only / MAC-only codes have no address to probe: the QR itself
        // is the human-eyeball verification, so the fingerprint pins directly.
        if (code.ip.isBlank()) {
            if (!::appCtx.isInitialized) return
            FlitKnown.put(appCtx, known)
            refreshKnown()
            FlitRoster.pin(fp, known.alias)
            logLine("scan: known ${fp.take(12)} via ${if (known.relay != null) "relay" else "bluetooth"} (QR verified)")
            toast("device added from code")
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
                if (::appCtx.isInitialized) {
                    FlitKnown.put(
                        appCtx,
                        known.copy(fingerprint = real.fingerprint, alias = real.alias),
                    )
                    refreshKnown()
                }
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
    private suspend fun dispatchTo(
        peer: PeerEntry,
        src: SourceList,
        text: String?,
        fromQueue: Boolean,
        claimHeld: Boolean = false,
    ): SendReport? {
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
        // hunter consults the same claim. A queue flush already took it
        // before spooling (`claimHeld`) — it owns the release.
        if (!claimHeld && !dispatching.add(peer.info.fingerprint)) {
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
            // Milestone E: an owed peer whose last answer came through the
            // relay is dialed there. `host:port` names the relay; the hint
            // names the peer, so the blind pairer can match a subscription.
            val relay = relayRouteFor[peer.info.fingerprint]
            val known = _knownDevices.value.firstOrNull { it.fingerprint.equals(peer.info.fingerprint, true) }
            val policy = known?.routePolicy ?: FlitKnown.RoutePolicy.AUTO
            val dialer: ProtocolClient
            val target = when {
                policy == FlitKnown.RoutePolicy.BLUETOOTH_ONLY ||
                    (peer.kind == FlitRoute.Kind.BLUETOOTH && policy != FlitKnown.RoutePolicy.WIFI_ONLY) -> {
                    dialer = btClient ?: throw IOException("Bluetooth link is off")
                    SendTarget(peer.ip, 0, tls = true)
                }
                known?.relayFor != false && (relay != null || policy == FlitKnown.RoutePolicy.RELAY_ONLY) -> {
                    val address = known?.relay?.let { FlitRoute.parseRelay(it) }
                    val host = address?.first ?: relay?.host ?: relayConfig()?.host
                        ?: throw IOException("relay is not configured")
                    val port = address?.second ?: relay?.port ?: relayConfig()?.port
                        ?: throw IOException("relay is not configured")
                    dialer = relayClientFor(
                        peer.info.fingerprint, known?.token ?: relayConfig()?.token, host, port,
                    ) ?: cli
                    SendTarget(host, port, tls = true, relayFor = peer.info.fingerprint)
                }
                else -> {
                    dialer = cli
                    SendTarget(peer.ip, peer.info.port, peer.info.protocol == "https")
                }
            }
            prog.begin(FlitProgress.Kind.SEND, outgoing.associate { it.wireName to it.size })
            val report = dialer.sendFiles(target, self, outgoing, multi = prefs().getBoolean("parallelStreams", true), progress = { name, soFar, _ ->
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
            // A queue flush took the claim before spooling and releases it
            // after this returns (flushQueueFor's finally); fresh sends own
            // their claim here.
            if (!claimHeld) dispatching.remove(peer.info.fingerprint)
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
        val uri = runCatching { Uri.parse(token) }.getOrNull() ?: run {
            logLine("$tag: unreadable token — $token")
            return null
        }
        if (uri.scheme == "content" || uri.scheme == "file") {
            // Never silent: a spool that fails is the moment a row's promise
            // is at stake (the hardware walk's full disk), so the reason goes
            // to the engine log instead of vanishing into a null.
            return runCatching { spoolUri(uri, tag) }.onFailure { e ->
                logLine("$tag: can't open $token — ${e.message ?: e.javaClass.simpleName}")
            }.getOrNull()
        }
        logLine("$tag: unsupported token — $token")
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
        try {
            resolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("no stream for $uri")
        } catch (t: Throwable) {
            // A copy that died (ENOSPC on a full phone is the real one) must
            // not leave a partial file behind — the next attempt needs that
            // room, and nothing will ever read the fragment.
            f.delete()
            throw t
        }
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
