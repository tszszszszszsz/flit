package app.flit.share.harness

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendReport
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.discovery.UdpDiscovery
import app.flit.share.protocol.relay.RelaySubscriber
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.server.UploadResult
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.system.exitProcess

// ==== tiny arg parser ========================================================

internal class ParsedArgs(argv: Array<String>) {
    val flags = LinkedHashMap<String, String>()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) {
                if (i + 1 < argv.size && VALUE_FLAGS.contains(a)) {
                    flags[a] = argv[i + 1]; i += 2
                } else {
                    flags[a] = ""; i++
                }
            } else {
                positional.add(a); i++
            }
        }
    }

    fun opt(k: String): String? = flags[k]?.takeIf { it.isNotEmpty() }
    fun optInt(k: String, def: Int): Int = opt(k)?.toIntOrNull() ?: def
    fun has(k: String): Boolean = flags.containsKey(k)

    companion object {
        val VALUE_FLAGS = setOf(
            "--alias", "--port", "--dir", "--dest", "--udp-port", "--wait", "--pin",
            "--parallel", "--announce-to", "--bytes", "--sender", "--token", "--park-timeout",
            "--relay", "--text", "--circle", "--invite", "--file", "--target", "--option", "--host", "--role",
            "--bag", "--to", "--cert", "--ttl", "--out", "--sha", "--drop-id",
        )
    }
}

/**
 * Stable identity at ~/.config/flit/flit-harness so fingerprints survive not
 * just restarts but rebuilds: installDist syncs build/install and would wipe
 * any identity tracked inside the build tree (proven 2026-10-25 — the flit‑
 * harness identity vanished, and serve minted a fresh fingerprint).
 */
internal fun harnessHome(): Path =
    System.getenv("FLIT_HARNESS_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".config", "flit", "flit-harness")

internal fun loadOrCreateIdentity(): TlsIdentity {
    val base = harnessHome()
    Files.createDirectories(base)
    val file = base.resolve("identity.p12")
    return if (Files.exists(file)) TlsIdentity.load(file.toFile())
    else TlsIdentity.generate().also { it.save(file.toFile()) }
}

/** --announce-to a:port,b:port — unicast announce copies for hosts where multicast is stripped. */
private fun announceTargets(arg: String?): List<InetSocketAddress> =
    arg?.split(',')?.mapNotNull { token ->
        val parts = token.trim().split(':')
        if (parts.size != 2) return@mapNotNull null
        runCatching { InetSocketAddress(parts[0], parts[1].toInt()) }.getOrNull()
    }.orEmpty()

private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
internal fun ts(): String = LocalTime.now().format(fmt)

fun main(argv: Array<String>) {
    val args = ParsedArgs(argv)
    val cmd = args.positional.firstOrNull() ?: run { usage(); return }
    try {
        when (cmd) {
            "serve" -> kotlinx.coroutines.runBlocking { serve(args) }
            "discover" -> kotlinx.coroutines.runBlocking { discover(args) }
            "send" -> kotlinx.coroutines.runBlocking { send(args) }
            "info" -> kotlinx.coroutines.runBlocking { info(args) }
            "bt-sim" -> kotlinx.coroutines.runBlocking { btSim(args) }
            "relay" -> relay(args)
            "panic" -> kotlinx.coroutines.runBlocking { panicCmd(args) }
            "parse-qr" -> parseQr(args)
            "circle" -> kotlinx.coroutines.runBlocking { circle(args) }
            "courier" -> {
                val code = CourierCli.run(args)
                if (code != 0) exitProcess(code)
            }
            "parcel" -> {
                val code = ParcelCli.run(args)
                if (code != 0) exitProcess(code)
            }
            else -> usage()
        }
    } catch (e: Exception) {
        System.err.println("!! ${e.message ?: e.javaClass.simpleName}")
        if (System.getenv("FLIT_TRACE") != null) e.printStackTrace()
        else System.err.println("   (set FLIT_TRACE=1 for a stack trace)")
        exitProcess(1)
    }
}

private fun usage() {
    println(
        """flit-cli — the LocalSend v2.2 command line (same stack as the Flit app)

serve [--alias N] [--port P] [--dir D] [--udp-port N] [--http] [--hidden] [--announce-to ip:port,...]
      [--relay host:port --token T] -- serve only inside a blind pairer (milestone E)
discover [--wait S] [--udp-port N] [--http] [--announce-to ip:port,...]
send <ip[:port] | alias> <file...> [--http] [--pin PIN] [--parallel N] [--multi]
info <ip[:port]> [--http]
bt-sim [file...] [--bytes MB] [--no-tls] -- both ends in one process over the pipe carrier
relay [--port P] [--token T] [--park-timeout S] -- blind pairer for unreachable peers (milestone E)
panic <ip[:port]> [--text NOTE] -- send a signed informational panic alert
parse-qr <image...>   pairing codes out of screenshots (png/jpg)
circle <create|join|post|poll|vote|role|kick|leave|sync|file|receipt> [--circle ID] [--invite TOKEN]
       [--text TEXT] [--file PATH] [--target FP] [--role ADMIN|MEMBER] [--option N]
       [--host ip[:port]] -- accountless group coordination over the production Circle routes
       post --file publishes a signed name/size/sha256 reference only;
       file --file publishes it AND sends the real bytes to every current member via sendFiles
       receipt --sha SHA [--drop-id ID] [--host ip[:port]] — signed acknowledgement of a keyed drop
courier <drop|carry|receive|status|sweep> [--bag DIR] [--to FP] [--cert B64] [--ttl MS] -- carry sealed events in a copyable bag
parcel <encode|decode> -- render a file as numbered QR PNGs or fold the PNGs back into bytes, offline
"""
    )
}

private fun circleUsage() {
    println(
        """circle <create|join|post|poll|vote|role|kick|leave|sync|file|receipt>
  create          mint a circle id + invite token; print the host command
  join            --host ip[:port] --circle ID --invite TOKEN — fetch the group key
  post            --text NOTE — signed message; --file PATH — file-drop ANNOUNCEMENT only
                  ({name,size,sha256}: no bytes, no path, no token)
  file            --file PATH — announce the file AND send real bytes to every current
                  member with the production sendFiles; status is local-only
                  (pending → sent/refused/failed); a thrown carrier leaves it pending for retry
  poll            --text QUESTION --option A --option B ... — signed poll event
  vote            --option N — change your answer on the latest poll
  role            --target FP --role ADMIN|MEMBER — OWNER appoints/removes an admin
  kick            --target FP — OWNER/ADMIN kick executes now; a MEMBER opens a vote-kick.
                  The key rotates only when the member actually departs.
  leave           publish a leave event, then discard the local key
  sync            pull the shared log and print the feed + poll tally + confirmed counts
  receipt         --sha SHA [--drop-id ID] [--host ip[:port]] — signed ack of a keyed drop
                  resolves dropId and keyId from the signed keyed file event; no key, no event
"""
    )
}

/** Detailed courier help. Kept internal so CourierCli can reuse it if needed. */
internal fun courierUsage() {
    println(
        """courier <drop|carry|receive|status|sweep>
  drop            --to FP --text NOTE | --file PATH [--circle ID] [--cert B64] [--ttl MS] [--bag DIR]
                  seal one signed Circle event into a parcel for an absent member
  carry           [--bag DIR] — verify the local bag, mark rows carried
  receive         --bag DIR — open parcels for this identity, fold events into circle.state
  status          [--bag DIR] — list rows: id, to, state, expires
  sweep           [--bag DIR] — drop expired parcels and enforce quota
  Parcels move by copying courier-bag/ between machines; the carrier adds no route.
"""
    )
}

/** Detailed parcel help. The usage string itself is owned by ParcelCli. */
internal fun parcelUsage() {
    println(ParcelCli.usage())
}

/** Dev bench: what does the deployed app actually show on screen? */
private fun parseQr(args: ParsedArgs) {
    if (args.positional.size < 2) { usage(); return }
    for (path in args.positional.drop(1)) {
        val img = javax.imageio.ImageIO.read(java.io.File(path)) ?: run {
            println("$path: not an image")
            return
        }
        // Like a real scanner: sample at several scales from one capture —
        // zxing's binarizer has sweet spots by module size.
        var hit: Pair<Int, String>? = null
        val scales = intArrayOf(1, 2, 3, 4)
        for (d in scales) {
            val w = img.width / d
            val h = img.height / d
            val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = small.graphics
            g.drawImage(img, 0, 0, w, h, null)
            g.dispose()
            val px = IntArray(w * h).also { small.getRGB(0, 0, w, h, it, 0, w) }
            val text = try {
                com.google.zxing.MultiFormatReader()
                    .decode(
                        com.google.zxing.BinaryBitmap(
                            com.google.zxing.common.HybridBinarizer(
                                com.google.zxing.RGBLuminanceSource(w, h, px),
                            ),
                        ),
                    )
                    .text
            } catch (_: Exception) {
                null
            }
            if (text != null) { hit = d to text; break }
        }
        println("$path: ${hit?.second ?: "(no code)"}${hit?.let { "  (scale 1/${it.first})" } ?: ""}")
    }
}

/** Send a signed panic alert from the CLI (used to walk the recipient banner). */
private suspend fun panicCmd(args: ParsedArgs) {
    val raw = args.positional.drop(1).firstOrNull() ?: run { println("!! panic needs ip[:port]"); return }
    val host = raw.substringBeforeLast(':')
    val port = raw.substringAfterLast(':').toIntOrNull() ?: Protocol.DEFAULT_PORT
    val identity = loadOrCreateIdentity()
    val text = args.opt("--text") ?: "check in when you can"
    val alert = app.flit.share.protocol.panic.PanicCore.create(identity.fingerprint, text, identity)
    ProtocolClient(identity).use { cli ->
        val ok = cli.sendPanic(SendTarget(host, port, tls = true), alert)
        println("${ts()} panic to $host:$port ${if (ok) "sent" else "refused"}")
    }
}

// ==== Circle: host-side state served through the production onCircle route ====

/** A read-only view of the host's Circle at one instant (used by the CLI + tests). */
internal data class CircleHostSnapshot(
    val circleId: String,
    val invite: String,
    val key: String,
    val members: List<String>,
    /** Normalised fingerprint → OWNER / ADMIN / MEMBER (mirrors the store roster). */
    val roles: Map<String, String>,
    val events: List<CircleEvent>,
    val rekeyGen: Int,
)

/**
 * The box's half of a Circle, in the rig: the append-only event log, the
 * roster with roles, and the group key. It lives behind [ServerDelegate.onCircle]
 * — the production route — so the two-node walk uses the same wire the app does.
 *
 * The decisions mirror `FlitCircleHost` (the app's host): JOIN is the only
 * action exempt from the roster gate, every SYNC event is verified (cert
 * pinned to its own author) before a store-equivalent reducer sees it, only
 * the OWNER appoints admins, and a departure bumps the generation and rotates
 * the key so a later sync from that fingerprint is refused.
 */
internal class CircleHostState {
    companion object {
        const val MAX_MEMBERS = 500
        const val MAX_BODY_CHARS = 8 * 1024
        const val MAX_EVENTS = 500
    }

    private val lock = Any()
    @Volatile private var id: String = ""
    @Volatile private var token: String = ""
    @Volatile private var groupKey: String = ""
    @Volatile private var ownerIdentity: TlsIdentity? = null
    @Volatile private var gen: Int = 0
    /** Normalised fingerprint → role, so a re-join cannot silently demote an admin. */
    private val roster = LinkedHashMap<String, String>()
    private val log = LinkedHashMap<String, CircleEvent>()

    fun seed(circleId: String, invite: String, keyB64: String, ownerFp: String, identity: TlsIdentity) =
        synchronized(lock) {
            if (id.isBlank()) id = circleId
            if (token.isBlank()) token = invite
            if (groupKey.isBlank()) groupKey = keyB64
            ownerIdentity = identity
            normalized(ownerFp)?.let { roster.putIfAbsent(it, "OWNER") }
        }

    fun snapshot(): CircleHostSnapshot = synchronized(lock) {
        CircleHostSnapshot(id, token, groupKey, roster.keys.toList(), roster.toMap(), ordered(), gen)
    }

    fun onCircle(req: CircleRequest): CircleResponse = synchronized(lock) {
        if (id.isBlank()) return@synchronized CircleResponse(false, reason = "circle has no key")
        if (req.circleId != id) return@synchronized CircleResponse(false, reason = "wrong circle")
        when (req.action.trim().lowercase(Locale.ROOT)) {
            CircleRequest.ACTION_JOIN -> onJoinLocked(req)
            CircleRequest.ACTION_SYNC -> onSyncLocked(req)
            else -> CircleResponse(false, reason = "unknown action")
        }
    }

    /** JOIN is the only action exempt from the roster gate, never from the invite. */
    private fun onJoinLocked(req: CircleRequest): CircleResponse {
        val presented = req.invite?.trim()
        if (token.isBlank() || presented.isNullOrBlank() ||
            !token.equals(presented, ignoreCase = true)
        ) return CircleResponse(false, reason = "bad invite")
        val fp = normalized(req.memberFp) ?: return CircleResponse(false, reason = "bad member fingerprint")
        if (groupKey.isBlank()) return CircleResponse(false, reason = "circle has no key")
        if (fp !in roster && roster.size >= MAX_MEMBERS) return CircleResponse(false, reason = "circle full")
        // A re-scan refreshes nothing here: never demote an ADMIN on re-join.
        roster.putIfAbsent(fp, "MEMBER")
        return CircleResponse(true, key = groupKey, members = roster.keys.toList(), events = ordered())
    }

    /** SYNC is roster-gated; every accepted event is verified before governance sees it. */
    private fun onSyncLocked(req: CircleRequest): CircleResponse {
        val fp = normalized(req.memberFp) ?: return CircleResponse(false, reason = "not-a-member")
        if (fp !in roster) return CircleResponse(false, reason = "not-a-member")

        val before = roster.keys.toSet()
        val ids = log.keys.toHashSet()
        val admitted = req.events.asSequence()
            .filter { it.circleId == id }
            .filter { it.type.trim().lowercase(Locale.ROOT) in CircleEvent.TYPES }
            .filter { it.body.length <= MAX_BODY_CHARS }
            // A file announcement is EXACTLY {name,size,sha256}. This is the
            // gate that keeps a sender's local spool path (or a queue token)
            // off the signed, fanned-out wire — recipients locate bytes by hash.
            .filter { it.type != CircleEvent.FILE || CircleCore.validFileBody(it.body) }
            // A receipt is EXACTLY {dropId,sha256,keyId}. The same closed body
            // gate the app's SYNC filter uses keeps a malformed assertion out
            // of the signed log; the signature check below remains the gate
            // for a forged author.
            .filter { it.type != CircleEvent.RECEIPT || CircleCore.validReceiptBody(it.body) }
            .filter { it.cert.isNotBlank() && CircleCore.verify(it) }
            // Local id dedup first, then the production replay/window guard:
            // an old signed join/role/kick/rekey is history, not governance.
            .filter { ids.add(it.id) }
            .filter { CircleCore.evaluate(it) == CircleCore.Decision.Accept }
            .toList()
            .let { CircleCore.order(it).take(MAX_EVENTS) }
        for (e in admitted) {
            putLog(e)
            applyGovernanceLocked(e)
        }
        if (before.any { it !in roster } &&
            admitted.none { it.type.equals(CircleEvent.REKEY, ignoreCase = true) }
        ) rekeyLocked()
        if (groupKey.isBlank()) return CircleResponse(false, reason = "circle has no key")
        return CircleResponse(true, key = groupKey, members = roster.keys.toList(), events = ordered())
    }

    /**
     * Store-equivalent reducers: only the OWNER changes roles, only OWNER/ADMIN
     * execute a kick directly (a plain member opens a vote-kick that lands on a
     * strict majority of current members), and LEAVE removes its own author.
     */
    private fun applyGovernanceLocked(e: CircleEvent) {
        val obj = circleBody(e.body) ?: return
        val author = normalized(e.authorFp) ?: return
        val actor = roster[author] ?: return
        when (e.type.trim().lowercase(Locale.ROOT)) {
            CircleEvent.LEAVE -> departLocked(author)
            CircleEvent.KICK -> {
                if (circleBool(obj, "approve") == false) return
                val target = normalized(circleText(obj, "targetFp") ?: circleText(obj, "target")) ?: return
                if (roster[target] == "OWNER") return
                val mode = circleText(obj, "mode")?.lowercase(Locale.ROOT)
                if ((actor == "OWNER" || actor == "ADMIN") && mode != "vote") {
                    departLocked(target)
                } else {
                    val (yes, _) = CircleCore.kickTally(log.values, target, roster.keys)
                    if (yes > roster.size / 2) departLocked(target)
                }
            }
            CircleEvent.ROLE -> {
                if (actor != "OWNER") return
                val target = normalized(circleText(obj, "targetFp")) ?: return
                if (roster[target] == null || roster[target] == "OWNER") return
                val desired = circleText(obj, "role")?.uppercase(Locale.ROOT) ?: return
                if (desired !in setOf("ADMIN", "MEMBER") || roster[target] == desired) return
                roster[target] = desired
            }
            CircleEvent.REKEY -> {
                circleText(obj, "keyB64")?.let { groupKey = it }
                circleText(obj, "gen")?.toIntOrNull()?.let { gen = maxOf(gen, it) }
            }
            else -> Unit
        }
    }

    private fun departLocked(fp: String) {
        if (roster.remove(fp) == null) return
        // Saturate: a corrupt generation must not wrap and make a stale key look current.
        gen = if (gen == Int.MAX_VALUE) Int.MAX_VALUE else gen + 1
    }

    /** A departure rotates the key and publishes it for the store's rekey reducer. */
    private fun rekeyLocked() {
        groupKey = freshCircleKey()
        val identity = ownerIdentity ?: return
        val body = buildJsonObject {
            put("keyB64", JsonPrimitive(groupKey))
            put("gen", JsonPrimitive(gen))
            putJsonArray("members") { roster.keys.forEach { add(JsonPrimitive(it)) } }
        }.toString()
        val owner = ownerFingerprint(identity)
        val lamport = (log.values.maxOfOrNull { it.lamport } ?: -1L) + 1L
        putLog(CircleCore.create(id, owner, identity, CircleEvent.REKEY, body, lamport = lamport))
    }

    private fun ordered(): List<CircleEvent> = CircleCore.order(log.values)

    private fun putLog(e: CircleEvent) {
        log[e.id] = e
        while (log.size > MAX_EVENTS) log.remove(log.keys.first())
    }

    private fun ownerFingerprint(identity: TlsIdentity): String =
        roster.keys.firstOrNull()?.takeIf { it.equals(identity.fingerprint, ignoreCase = true) }
            ?: identity.fingerprint.trim().uppercase(Locale.ROOT)
}

/** One canonical fingerprint spelling for roster comparison and responses. */
private fun normalized(fp: String?): String? =
    fp?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it.length <= 128 }

private fun freshCircleKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

private fun circleBody(body: String): JsonObject? =
    runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

private fun circleText(obj: JsonObject, key: String): String? =
    (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun circleBool(obj: JsonObject, key: String): Boolean? {
    val p = obj[key] as? JsonPrimitive ?: return null
    return if (p.isString) {
        when (p.content.lowercase()) {
            "true", "yes" -> true
            "false", "no" -> false
            else -> null
        }
    } else p.content.toBooleanStrictOrNull()
}

// ==== shared "console delegate" receiving protocol callbacks ==================

/** Registers collect into `seen` (dedup by fingerprint, both UDP announce + HTTP register). */
internal class ConsoleDelegate(
    /** The serving CLI's own identity, used to unwrap a keyed drop for this device. */
    private val cliIdentity: TlsIdentity? = null,
) : ServerDelegate {
    val seen = ConcurrentHashMap<String, Pair<String, PeerInfo>>() // fingerprint → (ip, info)
    val circleHost = CircleHostState()

    override fun onCircle(request: CircleRequest, ip: String): CircleResponse = circleHost.onCircle(request)

    override fun onRegister(info: PeerInfo, ip: String) {
        if (seen.containsKey(info.fingerprint)) return
        seen[info.fingerprint] = ip to info
        println("${ts()} seen ${info.alias} ($ip:${info.port}) type=${info.deviceType ?: "?"} proto=${info.protocol} v=${info.version}")
    }

    fun announceSeen(ip: InetAddress, info: PeerInfo) {
        if (seen.containsKey(info.fingerprint)) return
        seen[info.fingerprint] = (ip.hostAddress ?: "?") to info
        println("${ts()} seen ${info.alias} (multi from $ip) type=${info.deviceType ?: "?"} proto=${info.protocol} port=${info.port} v=${info.version}")
    }

    override fun onPanic(alert: app.flit.share.protocol.model.PanicAlert, ip: String) {
        println("${ts()} panic from ${alert.senderFp.take(12)} ($ip): ${alert.text} [hops=${alert.hops}]")
    }

    override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision {
        println("${ts()} offer from ${request.info.alias} ($peerIp): ${request.files.size} file(s)")
        for ((id, f) in request.files) {
            println("${ts()}   - $id → ${f.fileName} (${f.size} bytes, ${f.fileType}, sha=${f.sha256?.take(12) ?: "<none>"})")
        }
        return PrepareDecision.Accept(request.files.keys) // harness auto-accepts
    }

    override fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {
        println("${ts()} recv-start ${file.fileName} id=$fileId")
    }

    override fun onResumeStart(sessionId: String, fileId: String, file: FileInfo, prefixBytes: Long) {
        println("${ts()} recv-resume ${file.fileName} from ${prefixBytes} bytes")
    }

    override fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {
        when (result) {
            is UploadResult.Saved -> onLandedFile(file.fileName, result.path, result.bytes)
            is UploadResult.Streamed -> println("${ts()} recv-ok   ${file.fileName} streamed (${result.bytes} bytes)")
            is UploadResult.Mismatch -> println("${ts()} recv-BAD  ${file.fileName} checksum mismatch")
            is UploadResult.Error -> println("${ts()} recv-ERR  ${file.fileName}")
        }
    }

    /**
     * A landed file whose digest matches the `cipherSha` of a signed keyed
     * `file` event in this CLI's own `circle.state` is a sealed drop: unwrap
     * with the local identity, open the bytes, check the announced plaintext
     * sha, then write the plaintext beside the landed ciphertext. A missing
     * wrap, a GCM failure or a plaintext digest mismatch writes no plaintext;
     * an ordinary landed file keeps the legacy `recv-ok` path.
     */
    private fun onLandedFile(wireName: String, path: Path, bytes: Long) {
        val ciphertext = runCatching { Files.readAllBytes(path) }.getOrNull()
        val cipherSha = ciphertext?.let { ProtocolClient.sha256Hex { ByteArrayInputStream(it) } }
        val keyed = cipherSha?.let { sha ->
            loadCircleState()?.events
                ?.mapNotNull { DropKeyCore.parseFileKeyBody(it.body) }
                ?.firstOrNull { it.cipherSha.equals(sha, ignoreCase = true) }
        }
        if (ciphertext == null || keyed == null) {
            println("${ts()} recv-ok   $wireName → $path ($bytes bytes)")
            return
        }
        val identity = cliIdentity ?: loadOrCreateIdentity()
        val key = DropKeyCore.unwrap(keyed.wraps, identity)
        if (key == null) {
            // No wrap for this device: the landed ciphertext stays for cleanup,
            // but no plaintext is written or announced.
            println("${ts()} drop-refused ${keyed.name}: no wrap")
            return
        }
        val plain = DropKeyCore.open(key, ciphertext)
        if (plain == null) {
            println("${ts()} drop-refused ${keyed.name}: open failed")
            runCatching { Files.deleteIfExists(path) }
            return
        }
        val plainSha = ProtocolClient.sha256Hex { ByteArrayInputStream(plain) }
        if (!plainSha.equals(keyed.sha256, ignoreCase = true)) {
            println("${ts()} drop-refused ${keyed.name}: open failed")
            runCatching { Files.deleteIfExists(path) }
            return
        }
        Files.write(path.resolveSibling(keyed.name), plain)
        println("${ts()} drop-open ${keyed.name} $plainSha (${plain.size} bytes)")
    }

    override fun onSessionEnd(sessionId: String, cancelled: Boolean) {
        println("${ts()} session ${sessionId.take(8)} ${if (cancelled) "cancelled" else "finished"}")
    }
}

// ==== commands ===============================================================

private suspend fun serve(args: ParsedArgs) {
    val identity = loadOrCreateIdentity()
    val alias = args.opt("--alias") ?: "Flit (${System.getenv("USER") ?: "dev"})"
            // accept --dir and --dest (same meaning; --dest used in scripts)
        val destDir = Path.of(args.opt("--dir") ?: args.opt("--dest") ?: "flit-downloads")
    val config = ServerConfig(
        alias = alias,
        port = args.optInt("--port", Protocol.DEFAULT_PORT),
        tls = !args.has("--http"),
        destDir = destDir,
        deviceModel = System.getenv("FLIT_MODEL"),
        deviceType = "headless",
    )
    val delegate = ConsoleDelegate(identity)
    val circleId = args.opt("--circle") ?: UUID.randomUUID().toString().replace("-", "").take(16)
    val circleInvite = args.opt("--invite")
        ?: (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
    delegate.circleHost.seed(circleId, circleInvite, freshCircleKey(), identity.fingerprint, identity)
    val server = ProtocolServer(identity, delegate, config)

    // Milestone E's operator door: serve ONLY inside the blind pairer, with
    // no TCP listener and no announces. Direct routes go quiet so a peer's
    // hunt falls through to its relay candidate; the protocol inside the
    // pipe is the same core, with TLS inside it.
    val relayAddr = args.opt("--relay")
    if (relayAddr != null) {
        val host = relayAddr.substringBeforeLast(':')
        val port = relayAddr.substringAfterLast(':').toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            System.err.println("!! bad --relay $relayAddr (expected host:port)")
            exitProcess(2)
        }
        println("${ts()} relay subscription $host:$port dir=${config.destDir.toAbsolutePath()}")
        println("${ts()} fingerprint ${identity.fingerprint}")
        while (true) {
            try {
                val raw = RelaySubscriber.subscribe(host, port, identity.fingerprint, args.opt("--token"))
                server.serve(TlsLink(raw, identity, clientMode = false))
            } catch (t: Throwable) {
                println("${ts()} relay: ${t.message ?: t.javaClass.simpleName} — retry in 2s")
                Thread.sleep(2_000)
            }
        }
        return
    }

    server.start()

    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = args.optInt("--udp-port", Protocol.MULTICAST_PORT),
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, protocol, self ->
            client.register(SendTarget(ip, port, protocol == "https"), self)
        },
        fallbackPeers = announceTargets(args.opt("--announce-to")),
        // Hidden mode, same semantics as the app: never announce, never
        // register back — this machine stays reachable only by address, by
        // a rescue sweep, or by sending first.
        announce = !args.has("--hidden"),
    )
    discovery.create()
    discovery.start()

    println("${ts()} serving ${server.scheme}://0.0.0.0:${server.port} dir=${config.destDir.toAbsolutePath()}")
    if (args.has("--hidden")) {
        println("${ts()} hidden: not announcing — peers find this machine by address, by their own rescue sweep, or when it sends first")
    }
    println("${ts()} fingerprint ${identity.fingerprint}")
    println("${ts()} circle $circleId invite=$circleInvite — join with: flit-cli circle join --host 127.0.0.1:${
        server.port
    } --circle $circleId --invite $circleInvite")

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { discovery.stop(); server.close() }
        println("\n${ts()} bye")
    })
    while (true) {
        Thread.sleep(1_000_000)
    }
}

private suspend fun discover(args: ParsedArgs) {
    val identity = loadOrCreateIdentity()
    val waitSecs = args.optInt("--wait", 8).toLong()
    val tls = !args.has("--http")
    val alias = args.opt("--alias") ?: "Flit-discover"

    val delegate = ConsoleDelegate()
    val server = ProtocolServer(
        identity, delegate,
        ServerConfig(alias = alias, port = 0, tls = tls, destDir = Path.of("flit-tmp-discover")),
    )
    server.start()

    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = args.optInt("--udp-port", Protocol.MULTICAST_PORT),
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, protocol, self ->
            client.register(SendTarget(ip, port, protocol == "https"), self)
        },
        fallbackPeers = announceTargets(args.opt("--announce-to")),
    )
    discovery.create()
    discovery.start(announceIntervalMillis = 600)

    println("${ts()} discovering (udp ${args.optInt("--udp-port", Protocol.MULTICAST_PORT)})…")
    kotlinx.coroutines.delay(waitSecs * 1000)
    discovery.stop()
    server.close()

    if (delegate.seen.isEmpty()) {
        println("${ts()} no peers found")
        exitProcess(2)
    }
    for ((fp, pair) in delegate.seen) {
        val (ip, info) = pair
        println("${ts()} peer ${info.alias} ip=$ip:${info.port} tls=${info.protocol == "https"} type=${info.deviceType} v=${info.version} fp=${fp.take(12)}…")
    }
}

private suspend fun send(args: ParsedArgs) {
    if (args.positional.size < 3) { usage(); return }
    val targetArg = args.positional[1]
    val paths = args.positional.drop(2)
    val identity = loadOrCreateIdentity()
    val alias = args.opt("--alias") ?: "Flit-sender"

    val target: SendTarget = resolveTarget(targetArg, args, identity, alias)

    val specs = expandSpecs(paths)
    if (specs.isEmpty()) {
        System.err.println("no files found in given paths")
        exitProcess(1)
    }

    val self = PeerInfo(
        alias = alias,
        version = Protocol.VERSION,
        deviceModel = System.getenv("FLIT_MODEL"),
        deviceType = "headless",
        fingerprint = identity.fingerprint,
        port = Protocol.DEFAULT_PORT,
        protocol = "https",
    )
    val client = ProtocolClient(identity)
    client.use {
        val report = it.sendFiles(
            target = target,
            self = self,
            files = specs,
            pin = args.opt("--pin"),
            parallelism = args.optInt("--parallel", 4),
            multi = args.has("--multi"),
        )
        println("${ts()} sent ${report.sent.size}/${specs.size} to ${target.ip}:${target.port}")
        for (s in report.sent) println("${ts()}   ok $s")
        for (f in report.failed) println("${ts()}   FAIL ${f.fileName}: ${f.reason}")
        if (report.failed.isNotEmpty()) exitProcess(3)
    }
}

/**
 * Milestone E's operator door: `flit-cli relay`. A blind pairer — it
 * matches a sender's `req <fp>` with a receiver's long-lived `sub <fp>`,
 * then copies bytes; the transfer inside is the peers' own TLS and
 * protocol (docs, milestone E).
 */
private fun relay(args: ParsedArgs) {
    val token = args.opt("--token")
    val server = RelayServer(
        token = token,
        parkTimeoutMs = args.optInt("--park-timeout", 0) * 1000L,
    ).start(args.optInt("--port", Protocol.DEFAULT_PORT + 1))
    println("${ts()} relaying on 0.0.0.0:${server.port} token=${if (token != null) "set" else "none"}")
    println("${ts()} pairing requests and subscriptions by fingerprint; the pipe inside is opaque")
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.close() }
        println("\n${ts()} bye")
    })
    while (true) Thread.sleep(1_000_000)
}

/** ip[:port] directly, or a short discovery rendez-vous by alias. */
private suspend fun resolveTarget(targetArg: String, args: ParsedArgs, identity: TlsIdentity, alias: String): SendTarget {
    if (targetArg.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$"))) {
        val (host, port) = splitHostPort(targetArg)
        return SendTarget(host, port, !args.has("--http"))
    }

    val delegate = ConsoleDelegate()
    val server = ProtocolServer(
        identity, delegate,
        ServerConfig(alias = alias, port = 0, destDir = Path.of("flit-tmp-discover")),
    )
    server.start()
    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = args.optInt("--udp-port", Protocol.MULTICAST_PORT),
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, protocol, self ->
            client.register(SendTarget(ip, port, protocol == "https"), self)
        },
    )
    discovery.create()
    discovery.start(announceIntervalMillis = 500)
    kotlinx.coroutines.delay(args.optInt("--wait", 5) * 1000L)
    discovery.stop()
    server.close()

    val found = delegate.seen.entries
        .map { it.value }
        .firstOrNull { it.second.alias.equals(targetArg, ignoreCase = true) }
        ?: throw IllegalStateException("no device named '$targetArg' found (peers seen: ${delegate.seen.size})")
    val peer = found.second
    return SendTarget(found.first, peer.port, peer.protocol == "https")
}

private fun splitHostPort(s: String): Pair<String, Int> {
    val idx = s.lastIndexOf(':')
    if (idx <= 0) return s to Protocol.DEFAULT_PORT
    val port = s.substring(idx + 1).toIntOrNull() ?: throw IllegalStateException("bad port in '$s'")
    return s.substring(0, idx) to port
}

internal fun expandSpecs(paths: List<String>): List<PathOutgoing> {
    val out = mutableListOf<PathOutgoing>()
    for (raw in paths) {
        val root = Path.of(raw)
        if (Files.isRegularFile(root)) {
            out.add(PathOutgoing(root, root.fileName.toString()))
        } else if (Files.isDirectory(root)) {
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() }.forEach { child ->
                    val relName = root.relativize(child).toString().replace('\\', '/')
                    out.add(PathOutgoing(child, relName))
                }
            }
        }
    }
    return out
}

private suspend fun info(args: ParsedArgs) {
    if (args.positional.size < 2) { usage(); return }
    val (host, port) = splitHostPort(args.positional[1])
    val identity = loadOrCreateIdentity()
    val client = ProtocolClient(identity)
    client.use {
        val peer = it.info(SendTarget(host, port, !args.has("--http")))
        if (peer == null) {
            System.err.println("no answer from $host:$port")
            exitProcess(2)
        }
        println(WireJson.encode(peer.sansAnnounce()))
    }
}

// ==== circle CLI: one-shot commands over the production Circle routes ========

internal class CircleCliState(
    var circleId: String = "",
    var invite: String = "",
    var key: String = "",
    var host: String = "",
    var members: MutableList<String> = mutableListOf(),
    var events: MutableList<CircleEvent> = mutableListOf(),
    var rekeyGen: Int = 0,
)

private fun circleStatePath(): Path = harnessHome().resolve("circle.state")

private fun loadCircleState(): CircleCliState? {
    val f = circleStatePath()
    if (!Files.exists(f)) return null
    val lines = Files.readAllLines(f)
    if (lines.size < 7) return null
    return runCatching {
        CircleCliState(
            circleId = lines[0],
            invite = lines[1],
            key = lines[2],
            host = lines[3],
            members = lines[4].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList(),
            events = WireJson.json.decodeFromString<List<CircleEvent>>(lines[5]).toMutableList(),
            rekeyGen = lines[6].toIntOrNull() ?: 0,
        )
    }.getOrNull()
}

internal fun saveCircleState(st: CircleCliState) {
    val f = circleStatePath()
    Files.createDirectories(f.parent)
    val lines = listOf(
        st.circleId, st.invite, st.key, st.host,
        st.members.joinToString(","),
        WireJson.encode(st.events),
        st.rekeyGen.toString(),
    )
    Files.write(f, lines.joinToString("\n").toByteArray(Charsets.UTF_8))
}

private fun circleTarget(raw: String): SendTarget {
    val (h, p) = splitHostPort(raw)
    return SendTarget(h, p, tls = true)
}

private suspend fun circleExchange(
    identity: TlsIdentity,
    target: SendTarget,
    st: CircleCliState,
    events: List<CircleEvent>,
): Result<CircleResponse?> = runCatching {
    ProtocolClient(identity).use { cli ->
        cli.circleSync(
            target,
            CircleRequest(
                action = CircleRequest.ACTION_SYNC,
                circleId = st.circleId,
                memberFp = identity.fingerprint,
                events = events,
            ),
        )
    }
}

/**
 * One honest explanation for every refused Circle call: a host refusal is
 * reported with the host's own reason; a transport failure is reported as such
 * instead of being disguised as a refusal (or vice versa).
 */
internal fun explainCircleFailure(resp: CircleResponse?, error: Throwable?, host: String): String = when {
    resp?.ok == false && resp.reason.isNotBlank() -> "host refused: ${resp.reason}"
    resp != null -> "host returned an empty refusal"
    error != null -> "no answer from $host (${error.message ?: error.javaClass.simpleName})"
    else -> "no answer from $host"
}

private fun mergeCircleAnswer(st: CircleCliState, resp: CircleResponse) {
    st.events = CircleCore.order(CircleCore.dedup(st.events + resp.events)).toMutableList()
    if (resp.members.isNotEmpty()) st.members = resp.members.toMutableList()
    if (resp.key.isNotBlank()) st.key = resp.key
    st.rekeyGen = st.events.filter { it.type == CircleEvent.REKEY }.maxOfOrNull { e ->
        circleBody(e.body)?.let { (it["gen"] as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
    } ?: 0
}

private fun circleNextLamport(st: CircleCliState): Long =
    (st.events.maxOfOrNull { it.lamport } ?: -1L) + 1

private fun circleEvent(
    st: CircleCliState,
    identity: TlsIdentity,
    type: String,
    body: String,
): CircleEvent = CircleCore.create(
    circleId = st.circleId,
    authorFp = identity.fingerprint,
    identity = identity,
    type = type,
    body = body,
    lamport = circleNextLamport(st),
)

// ==== Circle delivery receipt: signed ack of one keyed drop ==================

/** The exact, closed receipt body. No `landedMs`, path, status or key material. */
internal fun circleReceiptBody(dropId: String, sha256: String, keyId: String): String =
    buildJsonObject {
        put("dropId", JsonPrimitive(dropId))
        put("sha256", JsonPrimitive(sha256))
        put("keyId", JsonPrimitive(keyId))
    }.toString()

/**
 * What the CLI needs to mint one receipt. [dropId] is the `id` of the signed
 * keyed `file` event, [sha256] its plaintext digest and [keyId] provenance from
 * that event's `keyGen` — never a value invented by the receipt command.
 */
internal data class CircleReceiptTarget(
    val dropId: String,
    val sha256: String,
    val keyId: String,
)

/**
 * Resolve a keyed `file` event in a local log. With [dropId] set the event is
 * found by id, otherwise by the plaintext sha of its keyed body. Either way the
 * body must be the keyed form and carry the same sha: a legacy plaintext drop
 * or an unknown id yields null and the caller mints nothing.
 */
internal fun circleReceiptTarget(
    events: Iterable<CircleEvent>,
    sha256: String,
    dropId: String? = null,
): CircleReceiptTarget? {
    val files = events.filter { it.type == CircleEvent.FILE }
    val file = if (dropId != null) {
        files.firstOrNull { it.id == dropId }
    } else {
        files.firstOrNull { e ->
            DropKeyCore.parseFileKeyBody(e.body)?.sha256?.equals(sha256, ignoreCase = true) == true
        }
    } ?: return null
    val keyed = DropKeyCore.parseFileKeyBody(file.body) ?: return null
    if (!keyed.sha256.equals(sha256, ignoreCase = true)) return null
    return CircleReceiptTarget(file.id, keyed.sha256, DropKeyCore.keyId(file.id, keyed.keyGen))
}

/** Alias used by callers that read the resolution as a lookup. */
internal fun circleReceiptLookup(
    events: Iterable<CircleEvent>,
    sha256: String,
    dropId: String? = null,
): CircleReceiptTarget? = circleReceiptTarget(events, sha256, dropId)

/** Confirmed authors for one known drop id and sha, from the local signed log. */
internal fun circleConfirmedAuthors(
    st: CircleCliState,
    dropId: String,
    sha256: String,
): Set<String> = CircleCore.receiptAuthors(st.events, dropId, sha256, st.members)

/**
 * Confirmed authors for one plaintext sha across all candidate drop ids in the
 * local log. A re-announcement of the same content can carry a different id, so
 * the union keeps one entry per author fingerprint.
 */
internal fun circleConfirmedAuthors(
    events: Iterable<CircleEvent>,
    members: Iterable<String>,
    sha256: String,
): Set<String> {
    val ids = events.filter { e ->
        e.type == CircleEvent.FILE &&
            DropKeyCore.parseFileKeyBody(e.body)?.sha256?.equals(sha256, ignoreCase = true) == true
    }.map { it.id }
    return ids.flatMap { id -> CircleCore.receiptAuthors(events, id, sha256, members) }.toSet()
}

/** Confirmed authors for one plaintext sha in a joined CLI's local log. */
internal fun circleConfirmedAuthors(st: CircleCliState, sha256: String): Set<String> =
    circleConfirmedAuthors(st.events, st.members, sha256)

/** Plain count of confirmed authors, never a per-recipient status list. */
internal fun circleConfirmedCount(st: CircleCliState, sha256: String): Int =
    circleConfirmedAuthors(st, sha256).size

// ==== Circle file drop: real bytes over sendFiles, local-only status =========

/**
 * The exact, closed wire shape of a `file` announcement. No local path and no
 * queue token: `{name,size,sha256}` only, exactly what `CircleCore.validFileBody`
 * and the app's `circleFileBody` accept. Kept here so the CLI mints the same
 * body the phone does.
 */
internal fun circleFileAnnouncement(name: String, size: Long, sha256: String): String =
    buildJsonObject {
        put("name", JsonPrimitive(name))
        put("size", JsonPrimitive(size))
        put("sha256", JsonPrimitive(sha256))
    }.toString()

/**
 * Keyed `file` announcement: the exact seven-key body whose `cipherSha`, `iv`
 * and every wrap are bound by the sender's signature. The three-argument form
 * above stays the legacy `{name,size,sha256}` builder for `circle post --file`,
 * where only a reference is published. `circle file` uses this overload and
 * also pushes the sealed bytes with `sendFiles`.
 */
internal fun circleFileAnnouncement(
    name: String,
    size: Long,
    sha256: String,
    keyGen: Int,
    cipherSha: String,
    iv: String,
    wraps: Map<String, String>,
): String = DropKeyCore.keyedFileBody(
    DropKeyCore.KeyedFile(name, size, sha256, keyGen, cipherSha, iv, wraps),
)

/** Mirror of the app's local-only per-recipient status vocabulary. */
internal object CircleDeliveryStatus {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val REFUSED = "refused"
    const val FAILED = "failed"
}

/**
 * One announced file and its LOCAL-ONLY outcome per recipient fingerprint.
 * Mirrors `FlitCircleStore.CircleDelivery` (the harness module has no app
 * dependency): statuses are never gossiped and a later joiner sees the card
 * but neither these states nor the bytes.
 */
internal data class CircleFileDelivery(
    val sha256: String,
    val name: String,
    val size: Long,
    /** fingerprint → pending | sent | refused | failed. */
    val statuses: LinkedHashMap<String, String> = LinkedHashMap(),
    /** Digest of the sealed bytes announced in a keyed drop; empty for legacy. */
    val cipherSha: String = "",
    /** Uppercase fingerprints the drop key was wrapped to. */
    val wrappedFor: List<String> = emptyList(),
)

/** A current member plus the production route we can reach it on. */
internal data class CircleRecipient(
    val fingerprint: String,
    /** null = no route known yet; the recipient stays pending for retry. */
    val target: SendTarget?,
)

/**
 * Pure conclusion from a *returned* report. A throw is deliberately not a
 * conclusion — the carrier never spoke, so the spool stays for retry and the
 * status remains [CircleDeliveryStatus.PENDING].
 */
internal fun circleDeliveryOutcome(report: SendReport?, wireName: String): String = when {
    report == null -> CircleDeliveryStatus.PENDING
    report.sent.contains(wireName) -> CircleDeliveryStatus.SENT
    report.failed.any { it.fileName == wireName } -> CircleDeliveryStatus.FAILED
    else -> CircleDeliveryStatus.REFUSED // 204 decline: peer spoke, no bytes landed
}

/**
 * Send one dropped file's real bytes to every current recipient with the
 * PRODUCTION [ProtocolClient.sendFiles] — no new wire format. [spoolPath] is
 * opened as [PathOutgoing] named by the announced [name]; the path itself is
 * never put in an event body.
 *
 * Every recipient starts [PENDING] and every transition is reported through
 * [onStatus] (including the initial pending) so callers can observe the
 * pending → sent/refused/failed sequence. A thrown carrier leaves the
 * recipient pending — "throw keeps spool for retry" — exactly the app's
 * delivery contract. Statuses stay local; nothing here is gossiped.
 */
internal suspend fun deliverCircleFile(
    identity: TlsIdentity,
    spoolPath: Path,
    recipients: Collection<CircleRecipient>,
    onStatus: ((fingerprint: String, status: String) -> Unit)? = null,
    cipherSha: String = "",
    wrappedFor: List<String> = emptyList(),
): CircleFileDelivery {
    val outgoing = PathOutgoing(spoolPath, spoolPath.fileName.toString())
    val name = outgoing.wireName
    val sha = ProtocolClient.sha256Hex { outgoing.openStream() }
    val delivery = CircleFileDelivery(sha, name, outgoing.size, cipherSha = cipherSha, wrappedFor = wrappedFor)
    for (r in recipients) {
        delivery.statuses[r.fingerprint] = CircleDeliveryStatus.PENDING
        onStatus?.invoke(r.fingerprint, CircleDeliveryStatus.PENDING)
    }
    ProtocolClient(identity).use { cli ->
        val self = PeerInfo(
            alias = "flit-circle-file",
            version = Protocol.VERSION,
            deviceModel = System.getenv("FLIT_MODEL"),
            deviceType = "headless",
            fingerprint = identity.fingerprint,
            port = Protocol.DEFAULT_PORT,
            protocol = "https",
        )
        for (r in recipients) {
            val target = r.target
            val outcome = if (target == null) {
                CircleDeliveryStatus.PENDING // no route yet — keep the spool for retry
            } else {
                runCatching {
                    cli.sendFiles(target = target, self = self, files = listOf(PathOutgoing(spoolPath, name)))
                }.fold(
                    onSuccess = { report -> circleDeliveryOutcome(report, name) },
                    onFailure = { CircleDeliveryStatus.PENDING },
                )
            }
            if (delivery.statuses[r.fingerprint] != outcome) {
                delivery.statuses[r.fingerprint] = outcome
                onStatus?.invoke(r.fingerprint, outcome)
            }
        }
    }
    return delivery
}

/** Best-effort fingerprint → route sweep on the rig's normal discovery path. */
private suspend fun discoverCircleRoutes(identity: TlsIdentity, waitSecs: Int = 3): MutableMap<String, SendTarget> {
    val routes = LinkedHashMap<String, SendTarget>()
    val delegate = ConsoleDelegate()
    val server = ProtocolServer(
        identity, delegate,
        ServerConfig(alias = "flit-circle-file", port = 0, tls = true, destDir = Path.of("flit-tmp-circle-file")),
    )
    server.start()
    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = Protocol.MULTICAST_PORT,
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, proto, self ->
            client.register(SendTarget(ip, port, proto == "https"), self)
        },
    )
    try {
        discovery.create()
        discovery.start(announceIntervalMillis = 500)
        kotlinx.coroutines.delay(waitSecs * 1000L)
    } finally {
        discovery.stop()
        server.close()
        client.close()
    }
    for ((fp, pair) in delegate.seen) {
        val (ip, info) = pair
        routes[normalized(fp) ?: fp] = SendTarget(ip, info.port, info.protocol == "https")
    }
    return routes
}

/** Every current member except self, with a discovered route where one exists. */
private suspend fun circleRecipients(identity: TlsIdentity, st: CircleCliState, me: String): List<CircleRecipient> {
    val routes = discoverCircleRoutes(identity)
    // The host is always a member; learn its fingerprint from its own info route.
    val hostRaw = st.host.takeIf { it.isNotBlank() }
    if (hostRaw != null) {
        val hostTarget = circleTarget(hostRaw)
        runCatching { ProtocolClient(identity).use { it.info(hostTarget) } }
            .getOrNull()?.fingerprint?.let { fp ->
                routes.putIfAbsent(normalized(fp) ?: fp, hostTarget)
            }
    }
    val meNorm = normalized(me)
    return st.members.mapNotNull { raw ->
        val fp = normalized(raw) ?: return@mapNotNull null
        if (fp == meNorm) return@mapNotNull null
        CircleRecipient(fp, routes[fp])
    }
}

private suspend fun circle(args: ParsedArgs) {
    val sub = args.positional.drop(1).firstOrNull() ?: run { circleUsage(); return }
    val identity = loadOrCreateIdentity()
    val me = identity.fingerprint
    var st = loadCircleState() ?: CircleCliState()

    when (sub) {
        "create" -> {
            st = CircleCliState(
                circleId = args.opt("--circle") ?: UUID.randomUUID().toString().replace("-", "").take(16),
                invite = args.opt("--invite")
                    ?: (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")),
                key = freshCircleKey(),
                host = args.opt("--host") ?: "",
                members = mutableListOf(me),
            )
            saveCircleState(st)
            println("${ts()} circle ${st.circleId} invite=${st.invite}")
            println("${ts()} host it: flit-cli serve --circle ${st.circleId} --invite ${st.invite}")
        }
        "join" -> {
            val host = args.opt("--host") ?: st.host
            if (host.isBlank()) { println("!! join needs --host ip[:port]"); return }
            st.circleId = args.opt("--circle") ?: st.circleId
            st.invite = args.opt("--invite") ?: st.invite
            if (st.circleId.isBlank()) { println("!! join needs --circle ID"); return }
            val result = runCatching {
                ProtocolClient(identity).use { cli ->
                    cli.circleJoin(
                        circleTarget(host),
                        CircleRequest(
                            action = CircleRequest.ACTION_JOIN,
                            circleId = st.circleId,
                            invite = st.invite,
                            memberFp = me,
                        ),
                    )
                }
            }
            val resp = result.getOrNull()
            if (resp == null || !resp.ok) {
                println("!! join refused: ${explainCircleFailure(resp, result.exceptionOrNull(), host)}")
                return
            }
            st.host = host
            st.key = resp.key
            st.members = resp.members.toMutableList()
            st.events = CircleCore.order(CircleCore.dedup(st.events + resp.events)).toMutableList()
            saveCircleState(st)
            println("${ts()} joined ${st.circleId}: ${st.members.size} member(s), ${st.events.size} event(s), key=${st.key.take(8)}…")
        }
        "post" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            val file = args.opt("--file")
            val body: String
            val type: String
            if (file != null) {
                val p = Path.of(file)
                if (!Files.isRegularFile(p)) { println("!! no file: $file"); return }
                val bytes = Files.readAllBytes(p)
                val sha = ProtocolClient.sha256Hex { java.io.FileInputStream(p.toFile()) }
                // Announcement only: {name,size,sha256}, never a path or queue token.
                body = circleFileAnnouncement(p.fileName.toString(), bytes.size.toLong(), sha)
                type = CircleEvent.FILE
            } else {
                val text = args.opt("--text") ?: run { println("!! post needs --text or --file"); return }
                body = buildJsonObject { put("text", JsonPrimitive(text)) }.toString()
                type = CircleEvent.MESSAGE
            }
            val ev = circleEvent(st, identity, type, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)
        }
        "file" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            val file = args.opt("--file") ?: run { println("!! file needs --file PATH"); return }
            val spool = Path.of(file)
            if (!Files.isRegularFile(spool)) { println("!! no file: $file"); return }
            val name = spool.fileName.toString()
            val plaintext = Files.readAllBytes(spool)
            val size = plaintext.size.toLong()
            val sha = ProtocolClient.sha256Hex { ByteArrayInputStream(plaintext) }

            // One fresh key per drop, wrapped to CURRENT members that hold a
            // usable certificate. The roster is never silently trimmed: above
            // the wrap cap, or with no usable cert at all, the drop is refused
            // before any announcement is minted or posted, and the source
            // spool stays on disk.
            val certs = LinkedHashMap<String, X509Certificate>()
            for (raw in st.members) {
                val fp = normalized(raw) ?: continue
                val cert = TlsIdentity.pinned(fp) ?: continue
                certs[fp] = cert
            }
            if (st.members.size > DropKeyCore.MAX_WRAPS) {
                println("!! ${st.members.size} recipients exceeds key wrap cap — nothing posted")
                return
            }
            if (certs.isEmpty()) {
                println("!! no recipient key material — nothing posted")
                return
            }

            val key = DropKeyCore.mint()
            val wraps = DropKeyCore.wrap(key.bytes, certs)
            if (wraps.isEmpty()) {
                println("!! no recipient key material — nothing posted")
                return
            }
            val sealed = DropKeyCore.seal(key, plaintext)
            val cipherSha = ProtocolClient.sha256Hex { ByteArrayInputStream(sealed) }
            val sealedSpool = spool.resolveSibling("$name.flitdrop")
            Files.write(sealedSpool, sealed)
            val body = circleFileAnnouncement(
                name, size, sha, 1, cipherSha,
                Base64.getEncoder().encodeToString(key.iv), wraps,
            )

            // 1. signed keyed announcement — no bytes, no path and no plaintext
            //    key on the wire; the signature binds every wrap and cipherSha.
            val ev = circleEvent(st, identity, CircleEvent.FILE, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)

            // 2. refresh the roster, then deliver the sealed bytes to every member.
            if (st.host.isNotBlank()) {
                val result = circleExchange(identity, circleTarget(st.host), st, emptyList())
                result.getOrNull()?.takeIf { it.ok }?.let { mergeCircleAnswer(st, it) }
                saveCircleState(st)
            }
            val routes = circleRecipients(identity, st, me)
            if (routes.isEmpty()) {
                println("${ts()} no other current member with a route — announcement only; nothing sent")
            }
            val delivery = deliverCircleFile(
                identity, sealedSpool, routes,
                onStatus = { fp, status -> println("${ts()}   ${fp.take(12)} ${status.padEnd(7)} $name") },
                cipherSha = cipherSha,
                wrappedFor = wraps.keys.toList(),
            )
            val tally = delivery.statuses.values.groupingBy { it }.eachCount()
            println("${ts()} file $sha: ${tally.entries.sortedBy { it.key }} (status is local-only, never gossiped)")
            printCircleConfirmation(st, sha)
        }
        "poll" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            val q = args.opt("--text") ?: run { println("!! poll needs --text QUESTION"); return }
            val opts = (args.opt("--option") ?: "").split(',')
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (opts.isEmpty()) { println("!! poll needs --option A,B,C"); return }
            val pollId = UUID.randomUUID().toString().replace("-", "").take(12)
            val body = buildJsonObject {
                put("pollId", JsonPrimitive(pollId))
                put("question", JsonPrimitive(q))
                put("options", buildJsonArray { opts.forEach { add(JsonPrimitive(it)) } })
            }.toString()
            val ev = circleEvent(st, identity, CircleEvent.POLL, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)
            println("${ts()} poll $pollId: $q (${opts.size} options)")
        }
        "vote" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            val option = args.optInt("--option", -1)
            if (option < 0) { println("!! vote needs --option N"); return }
            val pollId = st.events.filter { it.type == CircleEvent.POLL }
                .maxByOrNull { it.lamport }
                ?.let { circleBody(it.body)?.let { o -> circleText(o, "pollId") } }
                ?: run { println("!! no poll known — sync first"); return }
            val body = buildJsonObject {
                put("pollId", JsonPrimitive(pollId))
                put("option", JsonPrimitive(option))
            }.toString()
            val ev = circleEvent(st, identity, CircleEvent.VOTE, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)
            println("${ts()} vote on $pollId → option $option")
        }
        "role" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            val target = args.opt("--target") ?: run { println("!! role needs --target FP"); return }
            val desired = args.opt("--role")?.uppercase(Locale.ROOT)
                ?: run { println("!! role needs --role ADMIN|MEMBER"); return }
            if (desired !in setOf("ADMIN", "MEMBER")) {
                println("!! role must be ADMIN or MEMBER (nobody appoints an OWNER)")
                return
            }
            val body = buildJsonObject {
                put("targetFp", JsonPrimitive(target.trim().uppercase(Locale.ROOT)))
                put("role", JsonPrimitive(desired))
            }.toString()
            val ev = circleEvent(st, identity, CircleEvent.ROLE, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)
            println("${ts()} role event published — only the OWNER's takes effect; sync to confirm")
        }
        "kick" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            val target = args.opt("--target") ?: run { println("!! kick needs --target FP"); return }
            val body = buildJsonObject {
                put("kickId", JsonPrimitive(UUID.randomUUID().toString().replace("-", "").take(12)))
                put("targetFp", JsonPrimitive(target.trim().uppercase()))
                put("approve", JsonPrimitive(true))
            }.toString()
            val ev = circleEvent(st, identity, CircleEvent.KICK, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)
        }
        "leave" -> {
            if (st.circleId.isBlank()) { println("!! no circle"); return }
            val body = buildJsonObject { put("memberFp", JsonPrimitive(me)) }.toString()
            val ev = circleEvent(st, identity, CircleEvent.LEAVE, body)
            if (st.host.isBlank()) {
                Files.deleteIfExists(circleStatePath())
                println("${ts()} left ${st.circleId} locally — no host registered, key discarded")
                return
            }
            val result = circleExchange(identity, circleTarget(st.host), st, listOf(ev))
            val resp = result.getOrNull()
            Files.deleteIfExists(circleStatePath())
            if (resp == null || !resp.ok) {
                println("!! leave: ${explainCircleFailure(resp, result.exceptionOrNull(), st.host)} — left locally, key discarded; host may still list this device")
            } else {
                println("${ts()} left ${st.circleId} — host confirmed, key discarded")
            }
        }
        "sync" -> {
            if (st.circleId.isBlank()) { println("!! no circle — join or create first"); return }
            if (st.host.isBlank()) { println("!! no host — join with --host first"); return }
            val result = circleExchange(identity, circleTarget(st.host), st, emptyList())
            val resp = result.getOrNull()
            if (resp == null || !resp.ok) {
                println("!! sync refused: ${explainCircleFailure(resp, result.exceptionOrNull(), st.host)}")
                return
            }
            mergeCircleAnswer(st, resp)
            saveCircleState(st)
            printCircleFeed(st)
            printCircleConfirmations(st)
        }
        "receipt" -> {
            if (st.circleId.isBlank() || st.key.isBlank()) {
                println("!! no circle — join or create first")
                return
            }
            val host = args.opt("--host") ?: st.host
            if (host.isBlank()) { println("!! receipt needs --host ip[:port]"); return }
            val sha = args.opt("--sha") ?: run { println("!! receipt needs --sha SHA"); return }
            val target = circleReceiptTarget(st.events, sha, args.opt("--drop-id"))
            if (target == null) {
                println("!! no key id for drop — receipts are for keyed drops")
                return
            }
            // The command publishes an assertion, not a remote action: the
            // body is exactly the three signed provenance fields.
            st.host = host
            val body = circleReceiptBody(target.dropId, target.sha256, target.keyId)
            val ev = circleEvent(st, identity, CircleEvent.RECEIPT, body)
            st.events.add(ev)
            pushCircleEvent(st, identity, ev)
        }
        else -> circleUsage()
    }
}

private suspend fun pushCircleEvent(st: CircleCliState, identity: TlsIdentity, ev: CircleEvent) {
    if (st.host.isBlank()) {
        saveCircleState(st)
        println("${ts()} ${ev.type} recorded locally (no host yet — sync after joining)")
        return
    }
    val result = circleExchange(identity, circleTarget(st.host), st, listOf(ev))
    val resp = result.getOrNull()
    if (resp == null || !resp.ok) {
        saveCircleState(st)
        println("!! ${ev.type} not accepted: ${explainCircleFailure(resp, result.exceptionOrNull(), st.host)} (kept locally)")
        return
    }
    mergeCircleAnswer(st, resp)
    saveCircleState(st)
    println("${ts()} ${ev.type} ${ev.id.take(8)} → ${st.events.size} event(s), ${st.members.size} member(s), gen=${st.rekeyGen}")
}

private fun printCircleFeed(st: CircleCliState) {
    println("${ts()} circle ${st.circleId}: ${st.members.size} member(s), key=${st.key.take(8)}…, rekeyGen=${st.rekeyGen}")
    for (e in CircleCore.order(st.events)) {
        println("  ${e.authorFp.take(8)}  ${e.type.padEnd(7)}  ${e.body.take(100)}")
    }
    st.events.filter { it.type == CircleEvent.POLL }.maxByOrNull { it.lamport }?.let { poll ->
        val pid = circleBody(poll.body)?.let { circleText(it, "pollId") } ?: return@let
        val tally = CircleCore.tally(st.events, pid, st.members)
        println("  tally $pid: ${tally.entries.sortedBy { it.key }}")
    }
}

/** One count line for one drop; the authors are derived from the signed log. */
private fun printCircleConfirmation(st: CircleCliState, sha256: String) {
    val authors = circleConfirmedAuthors(st, sha256)
    println("${ts()} confirmed ${sha256.take(12)}: ${authors.size} author(s)")
}

/** Sync prints one count per distinct keyed drop in the log. */
private fun printCircleConfirmations(st: CircleCliState) {
    st.events.mapNotNull { e ->
        if (e.type == CircleEvent.FILE) DropKeyCore.parseFileKeyBody(e.body)?.sha256 else null
    }.distinct().forEach { sha -> printCircleConfirmation(st, sha) }
}
