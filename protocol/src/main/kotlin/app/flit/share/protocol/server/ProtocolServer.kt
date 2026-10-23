package app.flit.share.protocol.server

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PartialInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.netty.channel.ChannelOption
import io.netty.channel.FixedRecvByteBufAllocator
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/** Receiver's decision for a prepare-upload request. */
sealed interface PrepareDecision {
    data object Decline : PrepareDecision
    data class Accept(val ids: Set<String>) : PrepareDecision
}

sealed interface UploadResult {
    data class Saved(val path: Path, val bytes: Long) : UploadResult

    /** The bytes went straight into a caller-provided stream (no temp file). */
    data class Streamed(val bytes: Long) : UploadResult
    data object Mismatch : UploadResult
    data object Error : UploadResult
}

/** Application-side hooks; the product decides, the server does wire details. */
interface ServerDelegate {
    fun onRegister(info: PeerInfo, ip: String) {}
    fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision
    fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {}

    /** Resumed an interrupted file: [prefixBytes] already sat on the receiver. */
    fun onResumeStart(sessionId: String, fileId: String, file: FileInfo, prefixBytes: Long) {}

    /** Byte-stream progress for one file. Emitted at most ~10\u00d7/s per file. */
    fun onUploadProgress(sessionId: String, fileId: String, file: FileInfo, bytes: Long, totalBytes: Long) {}
    fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {}
    fun onSessionEnd(sessionId: String, cancelled: Boolean) {}
}

data class ServerConfig(
    val alias: String = "Flit",
    val port: Int = Protocol.DEFAULT_PORT,
    val host: String = "0.0.0.0",
    val tls: Boolean = true,
    val destDir: Path = Path.of("flit-downloads"),
    val deviceModel: String? = "Flit",
    val deviceType: String? = "headless",
    val downloadApiEnabled: Boolean = false,

    /**
     * Keep incomplete uploads on disk and let a Flit sender resume them
     * after a connection loss (Flit↔Flit extension; v2.2 senders are
     * unaffected — they just never send the resume header).
     */
    val resume: Boolean = true,

    /**
     * When provided, upload bodies are piped straight into these streams
     * instead of temp files (the product streams into MediaStore/SAF without
     * the double copy). Return null per file to keep the classic file path.
     * [IncomingSink.done] must be safe to call exactly once.
     */
    val incomingSink: ((fileId: String, info: FileInfo) -> IncomingSink?)? = null,
)

/** Caller-owned destination stream for one incoming file. */
class IncomingSink(
    val open: OutputStream,
    val done: (ok: Boolean) -> Unit,
)

/**
 * Interrupted uploads kept on disk, keyed by content identity token
 * (Protocol.resumeToken). Part files are only ever resumed by senders
 * holding the matching sha256 — assembly is checked before accept.
 */
internal class PartRegistry {
    class Entry(val path: Path, val senderIp: String, val added: Long)

    private val map = LinkedHashMap<String, Entry>()
    private val lock = Any()

    fun peek(token: String): Entry? = synchronized(lock) { prune(); map[token] }

    fun put(token: String, path: Path, senderIp: String): Entry? = synchronized(lock) {
        prune()
        val entry = Entry(path, senderIp, System.currentTimeMillis())
        map[token] = entry
        if (map.size > CAP) {
            // evict oldest — and its bytes, registry entries own their file
            map.entries.removeIf { (k, e) ->
                if (map.size <= CAP / 2) false else {
                    runCatching { Files.deleteIfExists(e.path) }
                    map.remove(k)
                    true
                }
            }
        }
        entry
    }

    fun remove(token: String) = synchronized(lock) { map.remove(token) }

    private fun prune() {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val (_, e) = it.next()
            if (now - e.added > TTL_MS || !Files.exists(e.path)) {
                if (now - e.added > TTL_MS) runCatching { Files.deleteIfExists(e.path) }
                it.remove()
            }
        }
    }

    companion object {
        const val CAP = 8
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}

internal class PendingFile(
    val file: FileInfo,
    val token: String,
    val target: Path,
) {
    val temp: Path = target.resolveSibling("." + target.fileName.toString() + ".part")
    var attempts: Int = 0
    var settled: Boolean = false
}

internal class ActiveSession(
    val id: String,
    val senderIp: String,
    val files: MutableMap<String, PendingFile> = LinkedHashMap(),
) {
    var lastTouch: Long = System.currentTimeMillis()

    /** Dead senders must not block a reconnecting one's new prepare. */
    fun stale(): Boolean = System.currentTimeMillis() - lastTouch > STALE_SESSION_MS
}

/** A session whose last byte arrived this long ago is replaceable. */
internal const val STALE_SESSION_MS = 10_000L

/** Raw socket address of the peer, captured before ktor wraps the call. */
internal val peerAddrAttr = AttributeKey<InetSocketAddress>("flit-peer-addr")

/**
 * LocalSend v2.2 HTTP server: /info, /register, /prepare-upload, /upload, /cancel.
 * TLS (self-signed) is the default, mirroring upstream behavior.
 */
class ProtocolServer(
    private val identity: TlsIdentity,
    private val delegate: ServerDelegate,
    private val config: ServerConfig = ServerConfig(),
) : AutoCloseable {

    private val mutex = Mutex()
    private var session: ActiveSession? = null
    private val parts = PartRegistry()
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val actualPort = AtomicInteger(-1)

    val port: Int get() = actualPort.get().takeIf { it > 0 } ?: config.port
    val scheme: String get() = if (config.tls) "https" else "http"

    fun selfInfo(): PeerInfo = PeerInfo(
        alias = config.alias,
        version = Protocol.VERSION,
        deviceModel = config.deviceModel,
        deviceType = config.deviceType,
        fingerprint = identity.fingerprint,
        port = port,
        protocol = scheme,
        download = config.downloadApiEnabled,
    )

    fun start() {
        check(server == null) { "server already started" }
        Files.createDirectories(config.destDir)

        val module: suspend Application.() -> Unit = {
            // Capture the peer's raw socket address at the earliest pipeline
            // phase, while `call` is still the engine's NettyApplicationCall
            // (routing wraps it later, hiding the channel). Later readers must
            // use this attribute — see peerIp() for why `request.origin` is
            // unusable (it reverse-resolves, blocking route handlers ~5s on
            // LAN IPs).
            intercept(ApplicationCallPipeline.Setup) {
                (context as? NettyApplicationCall)?.let { nc ->
                    (nc.context.channel().remoteAddress() as? InetSocketAddress)?.let {
                        context.attributes.put(peerAddrAttr, it)
                    }
                }
            }
            install(ContentNegotiation) { json(WireJson.json) }
            routing {
                get(Protocol.PATH_INFO) { infoRoute(call) }
                get(Protocol.PATH_REGISTER) { registerRoute(call, decodeBody = false) }
                post(Protocol.PATH_REGISTER) { registerRoute(call, decodeBody = true) }
                post(Protocol.PATH_PREPARE_UPLOAD) { prepareRoute(call) }
                post(Protocol.PATH_UPLOAD) { uploadRoute(call) }
                post(Protocol.PATH_CANCEL) { cancelRoute(call) }
                get(Protocol.PATH_PARTIAL) { partialRoute(call) }
            }
        }

        val configBlock: io.ktor.server.engine.ApplicationEngine.Configuration.() -> Unit = {
            if (config.tls) {
                sslConnector(identity.keyStore, identity.alias, { identity.secretChars() }, { identity.secretChars() }) {
                    host = config.host
                    this.port = config.port
                }
            } else {
                connectors = mutableListOf<io.ktor.server.engine.EngineConnectorConfig>(
                    EngineConnectorBuilder().apply {
                        host = config.host
                        this.port = config.port
                    },
                )
            }
            if (this is io.ktor.server.netty.NettyApplicationEngine.Configuration) {
                // HTTP/2 drags ALPN negotiation into every TLS connection. Peers that
                // don't offer ALPN (JDK/Conscrypt stacks, some Dart/jvm clients) then
                // get their connection reset — on Android Conscrypt this even throws
                // an AssertionError inside netty's JdkAlpnSslEngine and RSTs mid
                // handshake. LocalSend v2.2 is HTTP/1.1 anyway.
                enableHttp2 = false
                // Bulk-upload throughput: netty's adaptive allocator grows slowly
                // and the default SO_RCVBUF trims the TCP window on phones, so
                // large-file transfers stream in small costly slices. Pin big
                // allocations up front.
                configureBootstrap = {
                    childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                    childOption(ChannelOption.RCVBUF_ALLOCATOR, FixedRecvByteBufAllocator(256 * 1024))
                    childOption(ChannelOption.TCP_NODELAY, true)
                }
            }
        }

        sweepOldParts(config.destDir)
        val e = embeddedServer(Netty, applicationEnvironment { }, configBlock, module).let { it.start(wait = false) }
        server = e
        runBlocking {
            actualPort.set(e.engine.resolvedConnectors().first().port)
        }
    }

    override fun close() {
        server?.stop(gracePeriodMillis = 300, timeoutMillis = 2000)
        server = null
        runBlocking { mutex.withLock { session = null } }
    }

    // ---- routes --------------------------------------------------------------------------

    private suspend fun infoRoute(call: ApplicationCall) {
        call.respondText(WireJson.encode(selfInfo().sansAnnounce()), ContentType.Application.Json)
    }

    private suspend fun registerRoute(call: ApplicationCall, decodeBody: Boolean) {
        if (decodeBody) {
            val raw = call.receiveText()
            if (raw.isNotBlank()) {
                runCatching { WireJson.decode<PeerInfo>(raw) }.getOrNull()?.let { peer ->
                    if (peer.fingerprint.isNotBlank()) delegate.onRegister(peer, call.peerIp())
                }
            }
        }
        call.respondText(WireJson.encode(selfInfo().sansAnnounce()), ContentType.Application.Json)
    }

    private suspend fun prepareRoute(call: ApplicationCall) {
        val peerIp = call.peerIp()
        val raw = call.receiveText()
        val req: PrepareUploadRequest? = if (raw.isBlank()) null else {
            runCatching { WireJson.decode<PrepareUploadRequest>(raw) }.getOrNull()
        }
        if (req == null || req.files.isEmpty()) {
            call.respondText("Invalid body", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }

        val decision = delegate.onPrepare(req, peerIp)
        if (decision is PrepareDecision.Decline) {
            call.respondText("Rejected", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return
        }
        val acceptIds = (decision as? PrepareDecision.Accept)?.ids ?: req.files.keys

        val accepted = req.files.entries.filter { it.key in acceptIds }
        if (accepted.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val candidate = ActiveSession(sessionId, peerIp)

        val sessionId0 = session?.id // for the stale check below
        var badNames = false
        mutex.withLock {
            if (session != null) {
                // A reconnecting sender gets a new session; the old one was
                // likely killed mid-transfer (resume) or abandoned.
                val evict = session?.stale() ?: true
                if (!evict) {
                    call.respondText("Blocked by another session", ContentType.Text.Plain, HttpStatusCode.Conflict)
                    return
                }
                session = null
            }
            for ((id, f) in accepted) {
                val rel = sanitizeRelative(f.fileName)
                if (rel.isEmpty()) { badNames = true; continue }
                val parent = Path.of(rel).parent
                val dir = if (parent != null) config.destDir.resolve(parent) else config.destDir
                Files.createDirectories(dir)
                val target = uniqueTarget(dir, Path.of(rel).fileName.toString())
                candidate.files[id] = PendingFile(f, UUID.randomUUID().toString(), target)
            }
            if (candidate.files.isEmpty()) badNames = true else session = candidate
        }
        if (badNames) call.respond(HttpStatusCode.NoContent)

        val tokens = candidate.files.entries.associate { it.key to it.value.token }
        call.respondText(WireJson.encode(PrepareUploadResponse(sessionId, tokens)), ContentType.Application.Json)
    }

    private suspend fun uploadRoute(call: ApplicationCall) {
        val peerIp = call.peerIp()
        val sessionId = call.request.queryParameters["sessionId"]
        val fileId = call.request.queryParameters["fileId"]
        val token = call.request.queryParameters["token"]
        if (sessionId == null || fileId == null || token == null) {
            call.respondText("Missing parameters", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }

        val claim = mutex.withLock {
            val s = session
            val f = s?.files?.get(fileId)
            if (s != null && s.id == sessionId && s.senderIp == peerIp && f != null &&
                f.token == token && !f.settled && f.attempts < MAX_ATTEMPTS
            ) {
                f.attempts++
                s.lastTouch = System.currentTimeMillis()
                f
            } else {
                null
            }
        }
        if (claim == null) {
            call.respondText("Invalid token or IP address", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return
        }

        val pf = claim
        delegate.onUploadStart(sessionId, fileId, pf.file)
        val md = MessageDigest.getInstance("SHA-256")
        var bytes: Long = 0
        var ok = false

        // Flit↔Flit resume: append the new body onto an interrupted prefix
        // (or a caller-provided stream straight through — those can't append,
        // so resume always takes the file path).
        val resumeToken = call.request.headers["X-Flit-Resume"]?.takeIf { config.resume && it.isNotBlank() }
        var resumed = false
        if (resumeToken != null) {
            val e = parts.peek(resumeToken)
            if (e != null && e.senderIp == peerIp) {
                if (e.path != pf.temp && Files.exists(e.path)) {
                    Files.createDirectories(pf.temp.parent)
                    Files.copy(e.path, pf.temp, StandardCopyOption.REPLACE_EXISTING)
                }
                resumed = runCatching {
                    val have = Files.size(pf.temp)
                    have > 0 && have < pf.file.size
                }.getOrDefault(false)
            }
        }
        if (resumed) {
            // The prefix is already on disk; fold it into the digest so the
            // final hash covers the assembled whole.
            runCatching {
                Files.newInputStream(pf.temp).use { ins ->
                    val b = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(b)
                        if (n <= 0) break
                        md.update(b, 0, n)
                        bytes += n
                    }
                }
            }.getOrDefault(Unit)
            delegate.onResumeStart(sessionId, fileId, pf.file, bytes)
        }

        val sink: IncomingSink? = if (resumed) null else try {
            config.incomingSink?.invoke(fileId, pf.file)
        } catch (_: Throwable) {
            null
        }
        try {
            val rawOut = when {
                resumed -> Files.newOutputStream(
                    pf.temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                else -> sink?.open ?: Files.newOutputStream(pf.temp)
            }
            rawOut.use { out ->
                val buf = ByteArray(256 * 1024)
                var lastEmit = 0L
                val channel = call.receiveChannel()
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    bytes += n
                    val now = System.nanoTime()
                    if (now - lastEmit > 100_000_000L) { // ~= 10 Hz cap
                        lastEmit = now
                        delegate.onUploadProgress(sessionId, fileId, pf.file, bytes, pf.file.size)
                    }
                }
                out.flush()
            }

            val digestHex = md.digest().joinToString("") { "%02x".format(it) }
            val declared = pf.file.sha256?.trim()
            ok = declared.isNullOrEmpty() || digestHex.equals(declared, ignoreCase = true)

            if (ok) {
                if (sink == null) {
                    Files.createDirectories(pf.target.parent)
                    Files.move(pf.temp, pf.target, StandardCopyOption.REPLACE_EXISTING)
                }
                if (resumed) resumeToken?.let { parts.remove(it) }
            } else if (sink == null) {
                if (resumed) {
                    // keep the prefix — the sender (or a retry) can extend it
                    parts.put(resumeToken!!, pf.temp, peerIp)
                } else {
                    // A truncated body (sender died) looks exactly like a
                    // digest mismatch: as bytes they are an honest prefix of
                    // what was shipped. Keep it; resume + final sha still
                    // gate acceptance.
                    parts.put(Protocol.resumeToken(pf.file), pf.temp, peerIp)
                }
            }
            sink?.done(ok)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity)
        } catch (e: CancellationException) {
            runCatching { sink?.done(false) }
            if (sink == null) {
                val t = resumeToken ?: Protocol.resumeToken(pf.file)
                keepPartAfterAbort(pf.temp, t, resumed || (config.resume && bytes > 0), peerIp)
            }
            pf.settled = true
            markSessionEnded(sessionId, cancelled = false)
            throw e
        } catch (e: Throwable) {
            runCatching { sink?.done(false) }
            if (sink == null) {
                val t = resumeToken ?: Protocol.resumeToken(pf.file)
                keepPartAfterAbort(pf.temp, t, resumed || (config.resume && bytes > 0), peerIp)
            }
            pf.settled = true
            markSessionEnded(sessionId, cancelled = false)
            call.respondText("Unknown error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        }

        if (ok) {
            pf.settled = true
            delegate.onUploadFinish(
                sessionId,
                fileId,
                pf.file,
                if (sink == null) UploadResult.Saved(pf.target, bytes) else UploadResult.Streamed(bytes),
            )
            markSessionEnded(sessionId, cancelled = false)
        } else {
            delegate.onUploadFinish(sessionId, fileId, pf.file, UploadResult.Mismatch)
            if (pf.attempts >= MAX_ATTEMPTS) {
                pf.settled = true
                markSessionEnded(sessionId, cancelled = false)
            }
        }
    }

    /** Interrupted without claim of completion: keep any usable prefix. */
    private fun keepPartAfterAbort(
        temp: Path,
        token: String,
        keepable: Boolean,
        peerIp: String,
    ) {
        val keep = keepable && runCatching { Files.size(temp) }.getOrDefault(0L) > 0
        if (keep) {
            parts.put(token, temp, peerIp)
        } else {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    /**
     * Flit extension: how many bytes of an interrupted file the receiver
     * still holds. Old peers never call this; a 404 just means "start over".
     */
    private suspend fun partialRoute(call: ApplicationCall) {
        val token = call.request.queryParameters["token"]
        val ip = call.peerIp()
        val e = if (token.isNullOrBlank()) null else parts.peek(token)
        if (e != null && (e.senderIp == ip || e.senderIp.isEmpty())) {
            val size = runCatching { Files.size(e.path) }.getOrDefault(0L)
            if (size > 0) {
                call.respondText(
                    WireJson.encode(PartialInfo(size)),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
                return
            }
        }
        call.respondText("{}", ContentType.Application.Json, HttpStatusCode.NotFound)
    }

    /** Ends + notifies once every file is terminal. */
    private suspend fun markSessionEnded(sessionId: String, cancelled: Boolean) {
        val ended = mutex.withLock {
            val s = session
            if (s != null && s.id == sessionId && s.files.values.all { it.settled || it.attempts >= MAX_ATTEMPTS }) {
                session = null
                s
            } else null
        }
        if (ended != null) delegate.onSessionEnd(ended.id, cancelled)
    }

    private suspend fun cancelRoute(call: ApplicationCall) {
        val peerIp = call.peerIp()
        val sessionId = call.request.queryParameters["sessionId"]
        val removed = mutex.withLock {
            val s = session
            if (sessionId != null && s?.id == sessionId && s.senderIp == peerIp) {
                session = null
                s
            } else null
        }
        removed?.let { r -> delegate.onSessionEnd(r.id, cancelled = true) }
        call.respond(HttpStatusCode.OK)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * Peer IP read straight from the socket address. MUST NOT go through
 * `request.origin`: ktor's netty connection point calls
 * `InetSocketAddress.getHostName()`, which blocks ~5s per call on a literal
 * LAN IP doing a reverse-DNS lookup that never resolves — stalling the route
 * handler past any client timeout (and poisoning the event loop that serves
 * other connections). `address.hostAddress` is a pure string conversion.
 */
/**
 * Peer IP from the Setup-phase attribute (raw `InetSocketAddress` of the
 * channel). MUST NOT go through `request.origin`: ktor's netty connection
 * point calls `InetSocketAddress.getHostName()`, which blocks ~5s per call on
 * a literal LAN IP doing reverse-DNS that never resolves — stalling the route
 * handler past any client timeout. `address.hostAddress` is a pure string
 * conversion.
 */
internal fun ApplicationCall.peerIp(): String =
    attributes.getOrNull(peerAddrAttr)?.address?.hostAddress ?: ""

/** Keeps only relative, traversal-safe path segments; returns "" when invalid. */
internal fun sanitizeRelative(fileName: String): String {
    val raw = fileName.trim()
    if (raw.isEmpty()) return ""
    val p = Path.of(raw)
    if (p.isAbsolute) return ""
    val normalized = p.normalize()
    if (normalized.startsWith("..") || normalized.toString().contains("..")) return ""
    return normalized.toString().split("/").joinToString("/") { seg ->
        seg.replace(Regex("[\\\\:*?\"<>|\\r\\n]"), "_").ifEmpty { "_" }
    }
}

/** Interrupted uploads left by earlier runs: parts older than a day go. */
private fun sweepOldParts(dest: Path) {
    runCatching {
        Files.walk(dest).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".part") }
                .forEach { p ->
                    runCatching {
                        val age = System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis()
                        if (age > 24 * 3_600_000L) Files.deleteIfExists(p)
                    }
                }
        }
    }
}

internal fun uniqueTarget(dir: Path, fileName: String): Path {
    var candidate = dir.resolve(fileName)
    if (!Files.exists(candidate)) return candidate
    val base = candidate.nameWithoutExtension
    val ext = candidate.extension
    var i = 1
    while (true) {
        candidate = if (ext.isEmpty()) dir.resolve("$base ($i)") else dir.resolve("$base ($i).$ext")
        if (!Files.exists(candidate)) return candidate
        i++
    }
}
