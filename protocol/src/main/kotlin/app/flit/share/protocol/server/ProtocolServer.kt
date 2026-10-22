package app.flit.share.protocol.server

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationEngine
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.nio.file.Files
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
    data object Mismatch : UploadResult
    data object Error : UploadResult
}

/** Application-side hooks; the product decides, the server does wire details. */
interface ServerDelegate {
    fun onRegister(info: PeerInfo, ip: String) {}
    fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision
    fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {}
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
)

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
)

/**
 * LocalSend v2.2 HTTP server: /info, /register, /prepare-upload, /upload, /cancel.
 * TLS (self-signed) is the default; the peer's claim is only secured by transport.
 */
class ProtocolServer(
    private val identity: TlsIdentity,
    private val delegate: ServerDelegate,
    private val config: ServerConfig = ServerConfig(),
) : AutoCloseable {

    private val mutex = Mutex()
    private var session: ActiveSession? = null
    private var engine: ApplicationEngine? = null
    private val actualPort = AtomicInteger(-1)

    val port: Int get() = actualPort.get().takeIf { it > 0 } ?: config.port
    val scheme: String get() = if (config.tls) "https" else "http"

    private fun selfPort(): Int = port

    fun selfInfo(): PeerInfo = PeerInfo(
        alias = config.alias,
        version = Protocol.VERSION,
        deviceModel = config.deviceModel,
        deviceType = config.deviceType,
        fingerprint = identity.fingerprint,
        port = selfPort(),
        protocol = scheme,
        download = config.downloadApiEnabled,
    )

    fun start() {
        check(engine == null) { "server already started" }
        Files.createDirectories(config.destDir)

        val application: Application.() -> Unit = {
            install(ContentNegotiation) { json(WireJson.json) }
            routing {
                get(Protocol.PATH_INFO) { infoRoute(call) }
                get(Protocol.PATH_REGISTER) { registerRoute(call, decodeBody = false) }
                post(Protocol.PATH_REGISTER) { registerRoute(call, decodeBody = true) }
                post(Protocol.PATH_PREPARE_UPLOAD) { prepareRoute(call) }
                post(Protocol.PATH_UPLOAD) { uploadRoute(call) }
                post(Protocol.PATH_CANCEL) { cancelRoute(call) }
            }
        }

        val env = applicationEngineEnvironment {
            module { application() }
            if (config.tls) {
                sslConnector(
                    identity.keyStore,
                    identity.alias,
                    { identity.password },
                    { identity.password },
                    config.port,
                    config.host,
                ) {}
            } else {
                connector {
                    this.host = config.host
                    this.port = config.port
                }
            }
        }

        val e = embeddedServer(CIO, env).start(wait = false)
        engine = e
        runBlocking {
            actualPort.set(e.resolvedConnectors().first().port)
        }
    }

    override fun close() {
        engine?.stop(gracePeriodMillis = 300, timeoutMillis = 2000)
        engine = null
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

        mutex.withLock {
            if (session != null) return respondConflict(call)
            for ((id, f) in accepted) {
                val rel = sanitizeRelative(f.fileName)
                if (rel.isEmpty()) continue // invalid name → skip, mirrors excluded files
                val parent = Path.of(rel).parent
                val dir = if (parent != null) config.destDir.resolve(parent) else config.destDir
                Files.createDirectories(dir)
                val target = uniqueTarget(dir, Path.of(rel).fileName.toString())
                candidate.files[id] = PendingFile(f, UUID.randomUUID().toString(), target)
            }
            if (candidate.files.isEmpty()) {
                return respondBadRequest(call)
            }
            session = candidate
        }

        val tokens = candidate.files.entries.associate { it.key to it.value.token }
        call.respondText(WireJson.encode(PrepareUploadResponse(sessionId, tokens)), ContentType.Application.Json)
    }

    private suspend fun respondConflict(call: ApplicationCall) {
        call.respondText("Blocked by another session", ContentType.Text.Plain, HttpStatusCode.Conflict)
    }

    private suspend fun respondBadRequest(call: ApplicationCall) {
        // pretend there was nothing to transfer — valid but degenerate offers
        call.respond(HttpStatusCode.NoContent)
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

        val pf: PendingFile? = mutex.withLock {
            val s = session
            val f = s?.files?.get(fileId)
            if (s != null && s.id == sessionId && s.senderIp == peerIp && f != null &&
                f.token == token && !f.settled && f.attempts < MAX_ATTEMPTS
            ) {
                f.attempts++
                f // claim: flash window; concurrent duplicate upload of same file would race past here
            } else {
                null
            }
        } ?: run {
            call.respondText("Invalid token or IP address", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return
        }

        delegate.onUploadStart(sessionId, fileId, pf.file)
        val md = MessageDigest.getInstance("SHA-256")
        var bytes: Long = 0
        try {
            Files.newOutputStream(pf.temp).use { rawOut ->
                val out = BufferedOutputStream(rawOut, 64 * 1024)
                val channel = call.receiveChannel()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    bytes += n
                }
                out.flush()
            }

            val digestHex = md.digest().joinToString("") { "%02x".format(it) }
            val declared = pf.file.sha256?.trim()
            val ok = declared.isNullOrEmpty() || digestHex.equals(declared, ignoreCase = true)

            if (ok) {
                Files.createDirectories(pf.target.parent)
                Files.move(pf.temp, pf.target, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.deleteIfExists(pf.temp)
            }

            mutex.withLock {
                pf.settled = ok || pf.attempts >= MAX_ATTEMPTS // don't loop forever on a bad source
                if (session?.id == sessionId) {
                    val allDone = session?.files?.all { it.value.settled || it.value.attempts >= MAX_ATTEMPTS } ?: true
                    if (allDone) {
                        val ended = session
                        session = null
                        ended
                    } else null
                } else null
            }?.let { ended -> delegate.onSessionEnd(ended.id, cancelled = ok.not()) }

            if (ok) {
                dFinished(sessionId, pf, UploadResult.Saved(pf.target, bytes), call)
            } else {
                dFinished(sessionId, pf, UploadResult.Mismatch, call)
            }
        } catch (e: CancellationException) {
            runCatching { Files.deleteIfExists(pf.temp) }
            markFailed(sessionId, pf)
            throw e
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(pf.temp) }
            markFailed(sessionId, pf)
            dFinished(sessionId, pf, UploadResult.Error, call, status = HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun dFinished(
        sessionId: String,
        pf: PendingFile,
        result: UploadResult,
        call: ApplicationCall,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        delegate.onUploadFinish(sessionId, pf.file.id ?: "", pf.file, result)
        call.respondText("", ContentType.Text.Plain, status)
    }

    private suspend fun markFailed(sessionId: String, pf: PendingFile) {
        mutex.withLock {
            pf.settled = true
            if (session?.id == sessionId) {
                val allDone = session?.files?.all { it.value.settled || it.value.attempts >= MAX_ATTEMPTS } ?: true
                if (allDone) {
                    val ended = session
                    session = null
                    ended
                } else null
            } else null
        }?.let { ended -> delegate.onSessionEnd(ended.id, cancelled = false) }
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

    private suspend fun finishAndRespondInternal(
        sessionId: String,
        pf: PendingFile,
        result: UploadResult,
        ok: Boolean,
    ) {
        delegate.onUploadFinish(sessionId, pf.file.id ?: "", pf.file, result)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

private suspend fun applyUpload(call: ApplicationCall, pf: PendingFile, ok: Boolean, status: HttpStatusCode) {
    call.respondText("", ContentType.Text.Plain, status)
}

internal fun ApplicationCall.peerIp(): String = request.origin.remoteHost ?: ""

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
