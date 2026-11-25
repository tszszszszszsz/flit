package app.flit.share.protocol.server

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PanicAlert
import app.flit.share.protocol.model.PartialInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.panic.PanicCore
import app.flit.share.protocol.tls.TlsIdentity
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
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

    /** Byte-stream progress for one file. Emitted at most ~10×/s per file. */
    fun onUploadProgress(sessionId: String, fileId: String, file: FileInfo, bytes: Long, totalBytes: Long) {}
    fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {}
    fun onSessionEnd(sessionId: String, cancelled: Boolean) {}

    /**
     * Flit-only panic alert, delivered only after the signature, replay
     * window, dedup and hop checks pass. Default no-op keeps every existing
     * delegate (harnesses included) source-compatible.
     *
     * Receiving this NEVER acts on this device — the app decides what a
     * human sees, and any forwarding is an explicit user action.
     */
    fun onPanic(alert: PanicAlert, ip: String) {}

    /**
     * Flit-only Circle join/sync, delivered after the request body decodes.
     * The app owns governance and the local event store: this protocol layer
     * never acts on a membership change itself. The default refusal keeps
     * every existing delegate (harnesses included) source-compatible.
     *
     * A returned [CircleResponse] is always encoded as a 200 body — a refused
     * join/sync is an honest answer with `ok = false` and a [reason], not a
     * dead carrier. Signature/replay checks belong to the delegate, which can
     * reuse `CircleCore.verify` / `CircleCore.evaluate`.
     */
    fun onCircle(request: CircleRequest, ip: String = ""): CircleResponse =
        CircleResponse(ok = false, reason = "Circle support not enabled")
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
                    runCatching { Files.deleteIfExists(tokenSidecar(e.path)) }
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
                // The sidecar is the part's discoverability — no part, no
                // sidecar, whatever killed the part.
                runCatching { Files.deleteIfExists(tokenSidecar(e.path)) }
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

    /** Piece ledger for multi-socket uploads; null until the first chunk claim. */
    @Volatile
    var tracker: ChunkTracker? = null

    /** Guards finalization (assembled hash + move) against racing chunk streams. */
    val finalizeLock = Mutex()

    /** Back-reference for keep-alive touching while long chunk bodies flow. */
    var sessionRef: ActiveSession? = null
}

internal class ActiveSession(
    val id: String,
    val senderIp: String,
    val files: MutableMap<String, PendingFile> = LinkedHashMap(),
) {
    /**
     * The sender asked for multi-socket sending (X-Flit-Multi), so this
     * session accepts Content-Range chunk claims. v2.2 sessions never set
     * it; their wiring is untouched.
     */
    var turbo: Boolean = false

    var lastTouch: Long = System.currentTimeMillis()

    /** Dead senders must not block a reconnecting one's new prepare. */
    fun stale(): Boolean = System.currentTimeMillis() - lastTouch > STALE_SESSION_MS
}

/** A session whose last byte arrived this long ago is replaceable. */
internal const val STALE_SESSION_MS = 10_000L

private const val MIME_JSON = "application/json"
private const val MIME_TEXT = "text/plain"

/**
 * The whole LocalSend v2.2 server semantics — routes, session machinery,
 * multi-socket chunks, resume bookkeeping — expressed only against
 * [HttpExchange], so the identical logic serves TCP (the ktor binder) and
 * any other pipe (e.g. Bluetooth RFCOMM via LinkServer). There are no
 * socket/ktor touches in this class.
 */
class ServerCore(
    private val identity: TlsIdentity,
    val config: ServerConfig = ServerConfig(),
    private val delegate: ServerDelegate,
) {
    private val mutex = Mutex()
    private var session: ActiveSession? = null
    private val parts = PartRegistry()

    /** The TCP listener reports its resolved port here after binding. */
    @Volatile
    private var actualPort = -1

    val port: Int get() = actualPort.takeIf { it > 0 } ?: config.port
    val scheme: String get() = if (config.tls) "https" else "http"

    fun notePort(p: Int) {
        actualPort = p
    }

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

    /** Interrupted-parts hygiene, run once per server start. */
    fun sweepOnStart() {
        sweepOldParts(config.destDir)
        sweepPieceSidecars(config.destDir)
        // Parts from an earlier run of this server go back into the registry,
        // so a probe after a process death still answers with the prefix.
        repopulateParts(config.destDir, parts)
    }

    // ---- dispatch ------------------------------------------------------------------------

    suspend fun handle(x: HttpExchange) {
        when {
            x.method == "GET" && x.path == Protocol.PATH_INFO -> infoRoute(x)
            x.method == "GET" && x.path == Protocol.PATH_REGISTER -> registerRoute(x, decodeBody = false)
            x.method == "POST" && x.path == Protocol.PATH_REGISTER -> registerRoute(x, decodeBody = true)
            x.method == "POST" && x.path == Protocol.PATH_PREPARE_UPLOAD -> prepareRoute(x)
            x.method == "POST" && x.path == Protocol.PATH_UPLOAD -> uploadRoute(x)
            x.method == "POST" && x.path == Protocol.PATH_CANCEL -> cancelRoute(x)
            x.method == "GET" && x.path == Protocol.PATH_PARTIAL -> partialRoute(x)
            x.method == "POST" && x.path == Protocol.PATH_PANIC -> panicRoute(x)
            x.method == "POST" && x.path == Protocol.PATH_CIRCLE_JOIN -> circleRoute(x)
            x.method == "POST" && x.path == Protocol.PATH_CIRCLE_SYNC -> circleRoute(x)
            else -> x.respondStatus(404)
        }
    }

    // ---- routes --------------------------------------------------------------------------

    private suspend fun infoRoute(x: HttpExchange) {
        x.respondText(200, MIME_JSON, WireJson.encode(selfInfo().sansAnnounce()))
    }

    private suspend fun registerRoute(x: HttpExchange, decodeBody: Boolean) {
        if (decodeBody) {
            val raw = x.readBodyText()
            if (raw.isNotBlank()) {
                runCatching { WireJson.decode<PeerInfo>(raw) }.getOrNull()?.let { peer ->
                    if (peer.fingerprint.isNotBlank()) delegate.onRegister(peer, x.origin)
                }
            }
        }
        x.respondText(200, MIME_JSON, WireJson.encode(selfInfo().sansAnnounce()))
    }

    private suspend fun prepareRoute(x: HttpExchange) {
        val peerIp = x.origin
        val raw = x.readBodyText()
        val req: PrepareUploadRequest? = if (raw.isBlank()) null else {
            runCatching { WireJson.decode<PrepareUploadRequest>(raw) }.getOrNull()
        }
        if (req == null || req.files.isEmpty()) {
            x.respondText(400, MIME_TEXT, "Invalid body")
            return
        }

        val decision = delegate.onPrepare(req, peerIp)
        if (decision is PrepareDecision.Decline) {
            // A deliberate refusal is a concluded story, not a dead carrier.
            // Only a Flit-marked sender gets the extra header, so a v2.2 peer
            // still sees the unchanged `403 Rejected` answer and keeps
            // treating it as its own business.
            if (x.header(Protocol.HEADER_FLIT_CLIENT) == Protocol.FLIT_CLIENT_VALUE) {
                x.respondText(
                    403, MIME_TEXT, "Rejected",
                    mapOf(Protocol.HEADER_FLIT_REFUSAL to Protocol.REFUSAL_DECLINED),
                )
            } else {
                x.respondText(403, MIME_TEXT, "Rejected")
            }
            return
        }
        val acceptIds = (decision as? PrepareDecision.Accept)?.ids ?: req.files.keys

        val accepted = req.files.entries.filter { it.key in acceptIds }
        if (accepted.isEmpty()) {
            x.respondStatus(204)
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val candidate = ActiveSession(sessionId, peerIp)
        candidate.turbo = x.header("X-Flit-Multi")?.isNotBlank() == true

        val sessionId0 = session?.id // for the stale check below
        var badNames = false
        mutex.withLock {
            if (session != null) {
                // A reconnecting sender gets a new session; the old one was
                // likely killed mid-transfer (resume) or abandoned.
                val evict = session?.stale() ?: true
                if (!evict) {
                    x.respondText(409, MIME_TEXT, "Blocked by another session")
                    return
                }
                session = null
            }
            for ((id, f) in accepted) {
                val rel = sanitizeRelative(f.fileName)
                if (rel.isEmpty()) {
                    badNames = true
                    continue
                }
                val parent = Path.of(rel).parent
                val dir = if (parent != null) config.destDir.resolve(parent) else config.destDir
                Files.createDirectories(dir)
                val target = uniqueTarget(dir, Path.of(rel).fileName.toString())
                candidate.files[id] = PendingFile(f, UUID.randomUUID().toString(), target)
            }
            if (candidate.files.isEmpty()) badNames = true else {
                candidate.files.values.forEach { it.sessionRef = candidate }
                session = candidate
            }
        }
        if (badNames) x.respondStatus(204)

        val tokens = candidate.files.entries.associate { it.key to it.value.token }
        // The chunk hint travels only when this session is a Flit↔Flit
        // multi-socket one; plain v2.2 responses are byte-for-byte unchanged.
        val resp = PrepareUploadResponse(
            sessionId,
            tokens,
            multipart = if (candidate.turbo) TURBO_CHUNK_BYTES else null,
        )
        x.respondText(200, MIME_JSON, WireJson.encode(resp))
    }

    /** Outcome of the upload-route claim, including the two honest refusals. */
    private sealed class ClaimResult {
        class Ok(
            val pf: PendingFile,
            val freshChunks: Boolean,
            /** Prefix the ledger was seeded with when the chunk lane opened. */
            val resumedBase: Long = 0,
        ) : ClaimResult()
        object Refused : ClaimResult()
        object ResumeMismatch : ClaimResult()
    }

    private suspend fun uploadRoute(x: HttpExchange) {
        val peerIp = x.origin
        val sessionId = x.query("sessionId")
        val fileId = x.query("fileId")
        val token = x.query("token")
        if (sessionId == null || fileId == null || token == null) {
            x.respondText(400, MIME_TEXT, "Missing parameters")
            return
        }
        val range = x.header("Content-Range")?.trim()?.takeIf { it.isNotBlank() }

        // Second element: first claim after a new piece ledger → the delegate
        // learns about the upload exactly once, no matter how many streams run.
        val claim = mutex.withLock {
            val s = session
            val f = if (s != null && s.id == sessionId && s.senderIp == peerIp) s.files[fileId] else null
            when {
                f == null || f.token != token || f.settled -> ClaimResult.Refused
                range != null -> {
                    // Multi-socket claims bypass the plain attempts cap: a
                    // healthy 4-stream upload must never be miscounted as
                    // three failed single-stream tries.
                    if (!s!!.turbo) ClaimResult.Refused
                    else {
                        s.lastTouch = System.currentTimeMillis()
                        val fresh = f.tracker == null
                        var seededBase = 0L
                        if (fresh) {
                            // The turbo lane resumes too: the sender probes
                            // first and starts its ranges at the base we
                            // advertise, so the ledger is seeded with that
                            // prefix. If the sender starts past our prefix a
                            // hole would remain that no finalize could ever
                            // hash — refuse the lane and let it re-probe on
                            // the plain path.
                            val held = resumableFor(token, f, peerIp)
                            val claimedBase =
                                x.header("X-Flit-Base")?.trim()?.toLongOrNull() ?: 0L
                            if (held != null && held.from != f.temp) {
                                return@withLock ClaimResult.ResumeMismatch
                            }
                            val base = held?.base ?: 0L
                            if (claimedBase > base) {
                                return@withLock ClaimResult.ResumeMismatch
                            }
                            seededBase = base
                            f.tracker = ChunkTracker(f.file.size).also {
                                if (base > 0) it.add(0, base)
                            }
                        }
                        ClaimResult.Ok(f, fresh, seededBase)
                    }
                }

                else -> {
                    if (f.attempts < MAX_ATTEMPTS) {
                        f.attempts++
                        s!!.lastTouch = System.currentTimeMillis()
                        // Never touch the temp or the chunk ledger here: this
                        // very request may carry X-Flit-Resume, and the
                        // attach below must see exactly the base the probe
                        // advertised. A fresh body truncates on open; a
                        // resumed one trims to its own confirmed base. (The
                        // 2026-11-18 walk caught the old trim-to-tracker-base
                        // deleting the kept part after a failed turbo claim.)
                        ClaimResult.Ok(f, false)
                    } else ClaimResult.Refused
                }
            }
        }
        val accepted = when (claim) {
            is ClaimResult.Ok -> claim
            ClaimResult.Refused -> {
                x.respondText(403, MIME_TEXT, "Invalid token or IP address")
                return
            }
            ClaimResult.ResumeMismatch -> {
                x.respondText(409, MIME_TEXT, "Resume base changed — start over")
                return
            }
        }
        val pf = accepted.pf
        if (range != null) {
            uploadChunkRoute(
                x, sessionId, fileId, pf, range, peerIp,
                accepted.freshChunks, accepted.resumedBase,
            )
            return
        }

        // Flit↔Flit resume: append the new body onto an interrupted prefix
        // (or a caller-provided stream straight through — those can't append,
        // so resume always takes the file path). The probe and this attach
        // share one base function, so the bytes the sender skipped are
        // exactly the bytes on disk. A resume header whose base we no longer
        // hold is refused: writing that tail as a fresh file would guarantee
        // a checksum mismatch and leave a misaligned "prefix" behind (the
        // walk's 711 MB tail did exactly that).
        val resumeToken = x.header("X-Flit-Resume")?.takeIf { config.resume && it.isNotBlank() }
        val resume = resumeToken?.let { resumableFor(it, pf, peerIp) }
            ?.takeIf { it.base in 1 until pf.file.size }
        if (resumeToken != null && resume == null) {
            forgetTracking(pf)
            x.respondText(409, MIME_TEXT, "Resume base no longer held")
            return
        }
        delegate.onUploadStart(sessionId, fileId, pf.file)

        val md = MessageDigest.getInstance("SHA-256")
        var bytes: Long = 0
        var ok = false
        var resumed = false
        if (resume != null) {
            val usable = if (resume.from == pf.temp) true else runCatching {
                Files.createDirectories(pf.temp.parent)
                Files.copy(resume.from, pf.temp, StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
            if (!usable) {
                forgetTracking(pf)
                x.respondText(409, MIME_TEXT, "Resume base no longer held")
                return
            }
            // The base is authoritative: a sparse part may be longer on disk
            // than its contiguous prefix. Trim before appending, so the fold
            // and the append meet at the same byte.
            truncateTo(pf.temp, resume.base)
            if (resume.from != pf.temp) {
                runCatching { Files.deleteIfExists(resume.from) }
                deletePiecesSidecar(resume.from)
            }
            resumed = true
        }
        // A plain body now owns the whole file: the chunk ledger and its
        // sparse sidecars are history. This runs after the resume decision,
        // never before it.
        forgetTracking(pf)
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
                resumed -> {
                    // A second death during the resume must not lose the
                    // sidecar either; it is idempotent.
                    markPartDiscoverable(pf)
                    Files.newOutputStream(
                        pf.temp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                    )
                }
                else -> sink?.open ?: run {
                    // Milestone C's process-death case: the receiver can be
                    // SIGKILLed before any catch block ever runs, so the
                    // part's discoverability cannot wait for keepPart. The
                    // sidecar lands BEFORE the first body byte; a restarting
                    // server then answers the sender's /partial probe and
                    // the resume appends into this same temp.
                    markPartDiscoverable(pf)
                    Files.newOutputStream(pf.temp)
                }
            }
            rawOut.use { out ->
                val buf = ByteArray(256 * 1024)
                var lastEmit = 0L
                while (true) {
                    val n = x.readBody(buf, 0, buf.size)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    bytes += n
                    val now = System.nanoTime()
                    if (now - lastEmit > 100_000_000L) { // ~= 10 Hz cap
                        lastEmit = now
                        pf.sessionRef?.lastTouch = System.currentTimeMillis()
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
                    // The part's whole story is over — the sidecar that made
                    // it discoverable across restarts goes with the part.
                    runCatching { Files.deleteIfExists(tokenSidecar(pf.temp)) }
                }
                if (resumed) resumeToken?.let { parts.remove(it) }
            } else if (sink == null) {
                if (bytes < pf.file.size) {
                    // A short body is a genuine prefix: the sender died (or
                    // was cut) before shipping the rest, and sequential
                    // writes mean every byte up to `bytes` is real. Keep it
                    // for the next resume; the final sha still gates.
                    keepPart(resumeToken ?: Protocol.resumeToken(pf.file), pf.temp, peerIp)
                } else {
                    // Full length but the digest failed: these bytes are not
                    // a prefix of anything trustworthy. Keeping them would
                    // let the next probe advertise a corrupt base, so drop
                    // the part, its sidecar and its registry entry — the
                    // sender starts clean.
                    val t = resumeToken ?: Protocol.resumeToken(pf.file)
                    runCatching { Files.deleteIfExists(pf.temp) }
                    runCatching { Files.deleteIfExists(tokenSidecar(pf.temp)) }
                    parts.remove(t)
                }
            }
            sink?.done(ok)
            x.respondStatus(if (ok) 200 else 422)
        } catch (e: CancellationException) {
            runCatching { sink?.done(false) }
            if (sink == null) {
                val t = resumeToken ?: Protocol.resumeToken(pf.file)
                keepPartAfterAbort(pf.temp, t, resumed || (config.resume && bytes > 0), peerIp)
            }
            pf.settled = true
            // The carrier died mid-upload: this is a cancelled session, never
            // a successful receive. The cancel route reports the same truth.
            markSessionEnded(sessionId, cancelled = true)
            throw e
        } catch (e: Throwable) {
            runCatching { sink?.done(false) }
            if (sink == null) {
                val t = resumeToken ?: Protocol.resumeToken(pf.file)
                keepPartAfterAbort(pf.temp, t, resumed || (config.resume && bytes > 0), peerIp)
            }
            pf.settled = true
            markSessionEnded(sessionId, cancelled = true)
            x.respondText(500, MIME_TEXT, "Unknown error")
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
    /**
     * A kept part must answer probes from a LATER run of this server, not
     * just this one: the registry gets it now, and the token goes into a
     * sidecar beside the part so the start sweep can re-register it after
     * a process death (docs, milestone C).
     */
    private fun keepPart(token: String, temp: Path, senderIp: String) {
        parts.put(token, temp, senderIp)
        runCatching { Files.writeString(tokenSidecar(temp), token) }
    }

    /**
     * The part's token sidecar, written before the first body byte of a
     * plain upload. [keepPart] only runs when a failure is actually caught;
     * a SIGKILLed receiver (the case milestone C exists for) never gets
     * there, and without the sidecar the restarted server cannot
     * re-register the part — the sender's probe answers 404 and the walk
     * restarts from zero (found on the 2026-11-18 hardware walk).
     */
    private fun markPartDiscoverable(pf: PendingFile) {
        runCatching {
            Files.createDirectories(pf.temp.parent)
            Files.writeString(tokenSidecar(pf.temp), Protocol.resumeToken(pf.file))
        }
    }

    private fun keepPartAfterAbort(
        temp: Path,
        token: String,
        keepable: Boolean,
        peerIp: String,
    ) {
        val keep = keepable && runCatching { Files.size(temp) }.getOrDefault(0L) > 0
        if (keep) {
            keepPart(token, temp, peerIp)
        } else {
            runCatching { Files.deleteIfExists(temp) }
            runCatching { Files.deleteIfExists(tokenSidecar(temp)) }
        }
    }

    // ---- multi-socket (Flit↔Flit) chunk upload ---------------------------------------------

    /**
     * One piece of a multi-socket upload. The sender opens up to
     * [MAX_TURBO_STREAMS] concurrent POSTs against the normal upload route,
     * each carrying its exact byte range in `Content-Range: bytes s-e/total`.
     * Chunks land at their offsets; [PendingFile.tracker] keeps coverage.
     * The request that completes coverage assembles and hashes the whole
     * temp — the same digest gate, byte-for-byte, that judges plain uploads.
     */
    private suspend fun uploadChunkRoute(
        x: HttpExchange,
        sessionId: String,
        fileId: String,
        pf: PendingFile,
        rangeStr: String,
        peerIp: String,
        fresh: Boolean,
        resumedBase: Long,
    ) {
        val info = pf.file
        val total = info.size
        val r = parseContentRange(rangeStr)
        if (r == null || r.third != total || r.first >= total || r.second < r.first || r.second >= total) {
            x.respondText(400, MIME_TEXT, "Bad Content-Range")
            return
        }
        if (fresh) {
            delegate.onUploadStart(sessionId, fileId, info)
            if (resumedBase > 0) delegate.onResumeStart(sessionId, fileId, info, resumedBase)
        }

        val start = r.first
        val len = r.second - start + 1
        val tracker = pf.tracker!!
        var sentInChunk = 0L
        try {
            java.io.RandomAccessFile(pf.temp.toFile(), "rw").use { raf ->
                val buf = ByteArray(256 * 1024)
                var lastEmit = 0L
                while (true) {
                    val n = x.readBody(buf, 0, buf.size)
                    if (n <= 0) break
                    raf.seek(start + sentInChunk)
                    raf.write(buf, 0, n)
                    sentInChunk += n
                    val now = System.nanoTime()
                    if (now - lastEmit > 100_000_000L) { // ~= 10 Hz cap
                        lastEmit = now
                        pf.sessionRef?.lastTouch = System.currentTimeMillis()
                        delegate.onUploadProgress(
                            sessionId,
                            fileId,
                            info,
                            minOf(total, tracker.bytesWritten + sentInChunk),
                            total,
                        )
                    }
                }
            }
            // Only bytes that actually landed may count as covered: a short
            // body (the sender died) must never advance a resume base past
            // real data. The old unconditional add(len) did exactly that.
            tracker.add(start, sentInChunk)
            delegate.onUploadProgress(sessionId, fileId, info, minOf(total, tracker.bytesWritten), total)
            when {
                sentInChunk < len -> x.respondText(400, MIME_TEXT, "Short chunk body")
                tracker.complete() -> finalizeChunk(x, sessionId, fileId, pf, tracker)
                else -> x.respondStatus(200)
            }
        } catch (e: CancellationException) {
            // Sender went away mid-chunk. The bytes so far sit at a known
            // offset: mark them covered so the salvage base is as long as
            // the data actually on disk. Peer recovery re-reads it via
            // /partial and re-sends the rest single-stream.
            runCatching { if (sentInChunk in 1 until len) tracker.add(start, sentInChunk) }
            runCatching { keepChunkPart(pf, tracker, peerIp) }
            pf.sessionRef?.lastTouch = System.currentTimeMillis()
            markSessionEnded(sessionId, cancelled = true)
            throw e
        } catch (e: Throwable) {
            runCatching { if (sentInChunk in 1 until len) tracker.add(start, sentInChunk) }
            runCatching { keepChunkPart(pf, tracker, peerIp) }
            pf.sessionRef?.lastTouch = System.currentTimeMillis()
            markSessionEnded(sessionId, cancelled = true)
            x.respondText(500, MIME_TEXT, "Unknown error")
        }
    }

    /**
     * Coverage complete: hash the assembled temp once, gate on the declared
     * sha256, promote to the final name exactly like a plain upload would.
     * Only one racing claim gets this far with [pf.finalizeLock]; the second
     * sees `settled` and answers its chunk calmly.
     */
    private suspend fun finalizeChunk(
        x: HttpExchange,
        sessionId: String,
        fileId: String,
        pf: PendingFile,
        tracker: ChunkTracker,
    ) {
        pf.finalizeLock.withLock {
            if (pf.settled) {
                x.respondStatus(200)
                return
            }
            val bytes = runCatching { Files.size(pf.temp) }.getOrDefault(0L)
            val digestHex = runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(pf.temp).use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            }.getOrNull()
            val declared = pf.file.sha256?.trim()
            val ok = digestHex != null && (declared.isNullOrEmpty() || digestHex.equals(declared, ignoreCase = true))

            if (ok) {
                Files.createDirectories(pf.target.parent)
                Files.move(pf.temp, pf.target, StandardCopyOption.REPLACE_EXISTING)
                parts.remove(Protocol.resumeToken(pf.file))
                runCatching { Files.deleteIfExists(tokenSidecar(pf.temp)) }
                runCatching { deletePiecesSidecar(pf.temp) }
                tracker.clear()
                pf.tracker = null
                x.respondStatus(200)
                delegate.onUploadFinish(sessionId, fileId, pf.file, UploadResult.Saved(pf.target, bytes))
                markSessionEnded(sessionId, cancelled = false)
            } else {
                // Assembled-but-wrong: the bytes are not salvageable as a
                // prefix-suffix story. Leaving them would let the next probe
                // advertise a corrupt base (the walk's 711 MB tail climbed
                // exactly that way); drop the part, its sidecars and its
                // registry entry so the sender starts clean.
                pf.attempts++
                if (pf.attempts >= MAX_ATTEMPTS) pf.settled = true
                runCatching { Files.deleteIfExists(pf.temp) }
                runCatching { Files.deleteIfExists(tokenSidecar(pf.temp)) }
                deletePiecesSidecar(pf.temp)
                parts.remove(Protocol.resumeToken(pf.file))
                x.respondStatus(422)
                delegate.onUploadFinish(sessionId, fileId, pf.file, UploadResult.Mismatch)
                markSessionEnded(sessionId, cancelled = false)
            }
        }
    }

    /**
     * Persist what a half-finished multi-socket exchange still holds: the
     * sparse temp stays, the contiguous salvage base is mirrored into the
     * `.pieces` sidecar (survives session evictions), and the registry
     * exposes the part to the sender's /partial probe.
     */
    private fun keepChunkPart(pf: PendingFile, tracker: ChunkTracker, peerIp: String) {
        runCatching {
            val base = tracker.base()
            val token = Protocol.resumeToken(pf.file)
            if (base <= 0 || runCatching { Files.size(pf.temp) }.getOrDefault(0L) <= 0) {
                // Nothing contiguous from zero: nothing a plain resume could
                // extend. Gather nothing; sweep handles the temp.
                return
            }
            Files.writeString(piecesSidecar(pf.temp), base.toString())
            keepPart(token, pf.temp, peerIp)
            if (base >= pf.file.size) tracker.clear() // complete-but-unhashed: base is all there is
        }
    }

    private fun piecesSidecar(temp: Path): Path =
        temp.resolveSibling(temp.fileName.toString() + ".pieces")

    private fun deletePiecesSidecar(temp: Path) {
        runCatching { Files.deleteIfExists(piecesSidecar(temp)) }
    }

    /** Shrinks `p` to `len` bytes if it is currently longer. Never grows. */
    private fun truncateTo(p: Path, len: Long) {
        if (len <= 0) {
            runCatching { Files.deleteIfExists(p) }
            return
        }
        runCatching {
            java.io.RandomAccessFile(p.toFile(), "rw").use { if (it.length() > len) it.setLength(len) }
        }
    }

    /**
     * Flit-only panic route. The sender's self-signed cert travels in a
     * header and is pinned under its own SHA-256 DER: a claimed senderFp is
     * trusted only if the cert's real fingerprint matches it and the
     * signature verifies over the canonical bytes. Bad wire → 400; bad
     * identity → 403; anything valid (including a duplicate/stale relay)
     * gets a quiet 200 so senders never retry a delivered alert forever.
     */
    private suspend fun panicRoute(x: HttpExchange) {
        val raw = x.readBodyText()
        val alert = runCatching { WireJson.decode<PanicAlert>(raw) }.getOrNull()
        if (alert == null || alert.id.isBlank() || alert.senderFp.isBlank() ||
            alert.sig.isBlank() || alert.hops < 0
        ) {
            x.respondText(400, MIME_TEXT, "Invalid panic alert")
            return
        }

        val encodedCert = x.header(Protocol.HEADER_PANIC_CERT)?.trim()
        if (encodedCert.isNullOrBlank()) {
            x.respondText(403, MIME_TEXT, "Missing sender certificate")
            return
        }
        val der = runCatching { Base64.getDecoder().decode(encodedCert) }.getOrNull()
        val cert = der?.let {
            runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate
            }.getOrNull()
        }
        if (cert == null) {
            x.respondText(403, MIME_TEXT, "Malformed sender certificate")
            return
        }
        val pinnedFp = TlsIdentity.pin(cert)
        if (!pinnedFp.equals(alert.senderFp.trim(), ignoreCase = true) || !PanicCore.verify(alert)) {
            x.respondText(403, MIME_TEXT, "Forged panic alert")
            return
        }

        if (PanicCore.accept(alert) == PanicCore.Decision.Accept) {
            delegate.onPanic(alert, x.origin)
        }
        x.respondStatus(200)
    }

    /**
     * Flit-only Circle route. Thin by design: decode the request, hand it to
     * the delegate (which owns governance, the key and the event store) and
     * encode whatever decision it returns. Malformed/oversized wire gets 400,
     * a bad identity header gets 403, a delegate that throws gets 500; every
     * real decision — including refusal — is a decodable 200 [CircleResponse].
     * The same semantics serve TCP and pipes. Signatures are still the
     * delegate's concern: the route only checks shape, size and cert binding.
     */
    private suspend fun circleRoute(x: HttpExchange) {
        val raw = x.readBodyCapped(CircleCore.MAX_REQUEST_BYTES)
        if (raw == null) {
            x.respondText(400, MIME_TEXT, "Circle request too large")
            return
        }
        val request = runCatching { WireJson.decode<CircleRequest>(raw) }.getOrNull()
        if (request == null || request.action !in setOf(CircleRequest.ACTION_JOIN, CircleRequest.ACTION_SYNC) ||
            request.circleId.isBlank() ||
            request.events.size > CircleCore.MAX_EVENTS_PER_REQUEST ||
            request.events.any { !CircleCore.validShape(it) }
        ) {
            x.respondText(400, MIME_TEXT, "Invalid circle request")
            return
        }
        // Circle is an authenticated group protocol: every request must carry
        // the requester's certificate and its claimed memberFp must be that
        // certificate's own fingerprint. An absent header used to let anyone
        // who merely knew a member's public fingerprint claim that identity,
        // harvest the group key and rewrite that member's route. No longer.
        val encodedCert = x.header(Protocol.HEADER_CIRCLE_CERT)?.trim()?.takeIf { it.isNotBlank() }
        if (encodedCert == null) {
            x.respondText(403, MIME_TEXT, "Missing sender certificate")
            return
        }
        val der = runCatching { Base64.getDecoder().decode(encodedCert) }.getOrNull()
        val cert = der?.let {
            runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate
            }.getOrNull()
        }
        if (cert == null) {
            x.respondText(403, MIME_TEXT, "Malformed sender certificate")
            return
        }
        val pinnedFp = TlsIdentity.pin(cert)
        if (request.memberFp.isBlank() ||
            !pinnedFp.equals(request.memberFp.trim(), ignoreCase = true)
        ) {
            x.respondText(403, MIME_TEXT, "Certificate does not match member fingerprint")
            return
        }
        val response = try {
            delegate.onCircle(request, x.origin.orEmpty())
        } catch (_: Throwable) {
            x.respondText(500, MIME_TEXT, "Circle delegate error")
            return
        }
        // Sign the answer so a joiner can pin it to the host fingerprint from
        // the QR — a rogue relay or on-path peer cannot substitute the key.
        x.respondText(200, MIME_JSON, WireJson.encode(CircleResponse.sign(response, identity)))
    }

    /**
     * Flit extension: how many bytes of an interrupted file the receiver
     * still holds. Old peers never call this; a 404 just means "start over".
     *
     * This and the resume attach below both call [resumableFor]: the sender
     * skips exactly what this answer advertises, so the two must agree or
     * the appended tail lands at the wrong offset. Multi-socket aware: the
     * answer for a sparse ledger is the contiguous salvage base from zero,
     * never the temp's on-disk length (its tail may hold unorderable pieces).
     */
    private suspend fun partialRoute(x: HttpExchange) {
        val token = x.query("token")
        val ip = x.origin
        if (!token.isNullOrBlank()) {
            val base = mutex.withLock {
                val s = session?.takeIf { it.senderIp == ip || ip.isEmpty() }
                val pf = s?.files?.values?.firstOrNull { Protocol.resumeToken(it.file) == token }
                pf?.let { resumableFor(token, it, ip)?.base } ?: partsBase(token, ip)?.base
            }
            if (base != null && base > 0) {
                x.respondText(200, MIME_JSON, WireJson.encode(PartialInfo(base)))
                return
            }
        }
        x.respondText(404, MIME_JSON, "{}")
    }

    /** Bytes held from zero for a resume, and the part that holds them. */
    private class Resumable(val base: Long, val from: Path)

    /**
     * The one answer to "how much of this file can be resumed?" — shared by
     * the probe and the attach. A live chunk ledger wins; otherwise the
     * registry part's persisted contiguous base (its `.pieces` sidecar) or,
     * for a plain part, its byte length.
     */
    private fun resumableFor(token: String, pf: PendingFile, peerIp: String): Resumable? {
        pf.tracker?.base()?.takeIf { it > 0 }?.let { return Resumable(it, pf.temp) }
        return partsBase(token, peerIp)
    }

    /** The registry's answer for [token], or null when it isn't ours. */
    private fun partsBase(token: String, peerIp: String): Resumable? {
        val e = parts.peek(token) ?: return null
        // Empty senderIp = a part repopulated from disk by an earlier run of
        // this server: safe from any peer (the token binds content identity,
        // and the final digest still gates). The next keepPart re-binds it.
        if (e.senderIp.isNotEmpty() && e.senderIp != peerIp) return null
        val base = piecesBase(e.path) ?: runCatching { Files.size(e.path) }.getOrDefault(0L)
        return if (base > 0) Resumable(base, e.path) else null
    }

    /** A sparse part's persisted contiguous base, if it has one. */
    private fun piecesBase(part: Path): Long? {
        val sc = part.resolveSibling(part.fileName.toString() + ".pieces")
        return runCatching {
            if (!Files.exists(sc)) null else Files.readString(sc).trim().toLongOrNull()
        }.getOrNull()
    }

    /** The plain body owns the file now: chunk ledger + sparse sidecars go. */
    private fun forgetTracking(pf: PendingFile) {
        pf.tracker?.clear()
        pf.tracker = null
        deletePiecesSidecar(pf.temp)
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

    private suspend fun cancelRoute(x: HttpExchange) {
        val peerIp = x.origin
        val sessionId = x.query("sessionId")
        val removed = mutex.withLock {
            val s = session
            if (sessionId != null && s?.id == sessionId && s.senderIp == peerIp) {
                session = null
                s
            } else null
        }
        removed?.let { r -> delegate.onSessionEnd(r.id, cancelled = true) }
        x.respondStatus(200)
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /** Suggested chunk size for multi-socket uploads — receiver's hint. */
        const val TURBO_CHUNK_BYTES = 8L * 1024 * 1024

        /**
         * Cap on concurrent chunk streams per file for one session. The
         * sender uses at most 4; the headroom exists so retry overlap never
         * rounds down a legitimate claim.
         */
        const val MAX_TURBO_STREAMS = 6
    }
}

/**
 * `Content-Range: bytes 123-456/678` (RFC 7233) or the common
 * `bytes=123-456/678` → (start, lastInclusive, total). Accepting only the
 * equals form rejected every turbo chunk the client sent — the lane
 * silently fell back to single-stream on the 2026-11-18 hardware walk.
 * Suffix and unbounded forms are nonsense for a fixed-size file.
 */
internal fun parseContentRange(value: String): Triple<Long, Long, Long>? {
    val head = value.trim()
    if (!head.regionMatches(0, "bytes", 0, 5, ignoreCase = true)) return null
    var i = 5
    if (i < head.length && head[i] == '=') {
        i++
    } else if (i < head.length && head[i].isWhitespace()) {
        while (i < head.length && head[i].isWhitespace()) i++
        if (i < head.length && head[i] == '=') {
            i++
            while (i < head.length && head[i].isWhitespace()) i++
        }
    } else {
        return null
    }
    val slash = head.indexOf('/', i)
    if (slash <= i) return null
    val total = head.substring(slash + 1).trim().toLongOrNull() ?: return null
    val dash = head.indexOf('-', i)
    if (dash <= i || dash > slash) return null
    val start = head.substring(i, dash).trim().toLongOrNull() ?: return null
    val last = head.substring(dash + 1, slash).trim().toLongOrNull() ?: return null
    if (start < 0 || last < start) return null
    return Triple(start, last, total)
}

/** Adds multi-socket sidecar leftovers to the interrupted-parts sweep. */
internal fun sweepPieceSidecars(dest: Path) {
    runCatching {
        Files.walk(dest).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".pieces") }
                .forEach { p ->
                    runCatching {
                        val age = System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis()
                        if (age > 24 * 3_600_000L) Files.deleteIfExists(p)
                    }
                }
        }
    }
}

/** The part's token sidecar: what makes a part discoverable by a LATER run
 *  of this server — the start sweep reads the token and re-registers the
 *  part so the sender's probe still answers with the prefix. */
internal fun tokenSidecar(part: Path): Path =
    part.resolveSibling(part.fileName.toString() + ".token")

/**
 * Parts left behind by a run that died: their `.token` sidecars say which
 * probe token answers for each. Entries re-register with an empty senderIp —
 * the probe and the resume-attach accept those from anyone (the token binds
 * content identity, the final digest gate rules), and the first re-attach
 * re-binds the entry to the live peer's IP. Orphan sidecars whose part is
 * gone are ignored here and aged out by [sweepOldParts].
 */
internal fun repopulateParts(dest: Path, parts: PartRegistry) {
    runCatching {
        Files.walk(dest).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".part") }
                .forEach { p ->
                    runCatching {
                        val token = Files.readString(tokenSidecar(p)).trim()
                        if (token.isNotEmpty()) parts.put(token, p, "")
                    }
                }
        }
    }
}

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

/** Interrupted uploads left by earlier runs: parts older than a day go —
 *  and token sidecars whose part is already gone (an aborted story) age out
 *  with them. */
private fun sweepOldParts(dest: Path) {
    runCatching {
        Files.walk(dest).use { walk ->
            walk.filter { Files.isRegularFile(it) && (
                it.fileName.toString().endsWith(".part") ||
                    it.fileName.toString().endsWith(".part.token")
                ) }
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
