package app.flit.share.harness

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.PARCEL
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The courier carrier: one-shot CLI commands over the 2026-11-27 envelope and
 * carry layers. It owns only storage, transitions, status output and the
 * command dispatch — encryption stays in [CourierEnvelope] and the parcel bag
 * codec stays in [CourierCarryCore]. No route, header or second parser here.
 */
object CourierCli {
    const val BAG_DIR = "courier-bag"
    const val RECEIVED_DIR = "courier-received"
    const val REJECTED_DIR = "courier-rejected"
    const val STATE_FILE = "courier.state"
    const val EXIT_BAD_ARGS = 2
    const val EXIT_OPEN_REFUSED = 3

    /** One local-only bookkeeping row per parcel. Mirrors the tolerant state codec. */
    data class Row(
        val id: String,
        val recipientFp: String,
        val senderFp: String,
        val circleId: String,
        val state: String,
        val createdMs: Long,
        val expiresMs: Long,
    )

    /** The reason a state transition is being applied. A thrown open is not a Kind. */
    enum class Kind { CARRY, RECEIVE_OK, RECEIVE_REFUSED, SWEEP }

    private const val PENDING = "pending"
    private const val CARRIED = "carried"
    private const val DELIVERED = "delivered"
    private const val EXPIRED = "expired"
    private const val REFUSED = "refused"
    private const val PARCEL_SUFFIX = ".parcel"
    private const val CIRCLE_STATE_FILE = "circle.state"

    // ==== state codec =========================================================

    /** One JSON object per line, in row order. No annotation/plugin needed here. */
    fun encodeRows(rows: List<Row>): String = rows.joinToString("\n") { row ->
        buildJsonObject {
            put("id", JsonPrimitive(row.id))
            put("recipientFp", JsonPrimitive(row.recipientFp))
            put("senderFp", JsonPrimitive(row.senderFp))
            put("circleId", JsonPrimitive(row.circleId))
            put("state", JsonPrimitive(row.state))
            put("createdMs", JsonPrimitive(row.createdMs))
            put("expiresMs", JsonPrimitive(row.expiresMs))
        }.toString()
    }

    /**
     * Tolerant like `circle.state`: absent, blank or corrupt lines are dropped
     * and never throw. Unknown keys are ignored, as `WireJson.json` does.
     */
    fun decodeRows(raw: String?): List<Row> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { parseRow(it) }
        }
    }

    private fun parseRow(line: String): Row? = runCatching {
        val obj = WireJson.json.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
        fun text(key: String): String? =
            (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        fun number(key: String): Long? =
            (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()
        val id = text("id") ?: return@runCatching null
        val recipientFp = text("recipientFp") ?: return@runCatching null
        val senderFp = text("senderFp") ?: return@runCatching null
        val circleId = text("circleId") ?: return@runCatching null
        val state = text("state") ?: return@runCatching null
        val createdMs = number("createdMs") ?: return@runCatching null
        val expiresMs = number("expiresMs") ?: return@runCatching null
        Row(id, recipientFp, senderFp, circleId, state, createdMs, expiresMs)
    }.getOrNull()

    // ==== transitions =========================================================

    /**
     * `pending → carried` on CARRY; `pending|carried → delivered` on RECEIVE_OK;
     * anything → refused on RECEIVE_REFUSED; anything past its deadline →
     * expired on SWEEP. A thrown `open` is handled in [receive]: the row stays
     * as-is for a later attempt, so no Kind maps to that case.
     */
    fun nextState(row: Row, kind: Kind, nowMs: Long): String = when (kind) {
        Kind.CARRY -> if (row.state == PENDING) CARRIED else row.state
        Kind.RECEIVE_OK ->
            if (row.state == PENDING || row.state == CARRIED) DELIVERED else row.state
        Kind.RECEIVE_REFUSED -> REFUSED
        Kind.SWEEP -> if (nowMs >= row.expiresMs) EXPIRED else row.state
    }

    // ==== paths ===============================================================

    /** `--bag DIR` overrides the handoff directory; a relative path is rooted at harness home. */
    internal fun bagDir(args: ParsedArgs): Path {
        val override = args.opt("--bag") ?: return harnessHome().resolve(BAG_DIR)
        val p = Path.of(override)
        return if (p.isAbsolute) p else harnessHome().resolve(p)
    }

    internal fun statePath(args: ParsedArgs): Path = harnessHome().resolve(STATE_FILE)

    fun usage(): String = """courier <drop|carry|receive|status|sweep> [--bag DIR]
  drop    --to FP --text NOTE | --file PATH [--circle ID] [--cert B64] [--ttl MS] [--bag DIR]
  carry   [--bag DIR]  verify the local bag, mark rows carried
  receive --bag DIR    open parcels for this identity, fold events, quarantine refusals
  status  [--bag DIR]  list rows: id, to, state, expires
  sweep   [--bag DIR]  drop expired parcels and enforce the bag quota
  --ttl MS overrides the current time used for sealing and expiry checks"""

    // ==== command dispatch ====================================================

    /** Loads identity once, does one operation, saves state once. Returns an exit code. */
    internal fun run(args: ParsedArgs): Int {
        val sub = args.positional.drop(1).firstOrNull() ?: run {
            println(usage())
            return EXIT_BAD_ARGS
        }
        val nowMs: Long
        if (args.has("--ttl")) {
            val injected = args.opt("--ttl")?.toLongOrNull()
            if (injected == null) {
                println("!! bad --ttl ${args.flags["--ttl"]} (expected milliseconds)")
                return EXIT_BAD_ARGS
            }
            nowMs = injected
        } else {
            nowMs = System.currentTimeMillis()
        }
        if (nowMs <= 0L) {
            println("!! bad --ttl $nowMs (expected a positive millisecond value)")
            return EXIT_BAD_ARGS
        }
        return try {
            when (sub) {
                "drop" -> drop(args, nowMs)
                "carry" -> carry(args, nowMs)
                "receive" -> receive(args, nowMs)
                "status" -> status(args)
                "sweep" -> sweep(args, nowMs)
                else -> {
                    println(usage())
                    EXIT_BAD_ARGS
                }
            }
        } catch (e: Throwable) {
            System.err.println("!! courier ${e.message ?: e.javaClass.simpleName}")
            EXIT_BAD_ARGS
        }
    }

    // ==== drop ================================================================

    private fun drop(args: ParsedArgs, nowMs: Long): Int {
        val toFp = norm(args.opt("--to")) ?: run {
            println("!! courier drop needs --to FP")
            return EXIT_BAD_ARGS
        }
        val text = args.opt("--text")
        val file = args.opt("--file")
        if ((text == null) == (file == null)) {
            println("!! courier drop needs --text NOTE or --file PATH")
            return EXIT_BAD_ARGS
        }

        val identity = loadOrCreateIdentity()
        val me = norm(identity.fingerprint) ?: return EXIT_BAD_ARGS
        val events = loadCircleEvents()
        val circleId = args.opt("--circle")?.takeIf { it.isNotBlank() }
            ?: events.firstOrNull()?.circleId
            ?: UUID.randomUUID().toString().replace("-", "").take(16)

        val contentEvent: CircleEvent = if (file != null) {
            val p = Path.of(file)
            if (!Files.isRegularFile(p)) {
                println("!! no file: $file")
                return EXIT_BAD_ARGS
            }
            val bytes = Files.readAllBytes(p)
            val sha = ProtocolClient.sha256Hex { ByteArrayInputStream(bytes) }
            val body = circleFileAnnouncement(p.fileName.toString(), bytes.size.toLong(), sha)
            CircleCore.create(circleId, me, identity, CircleEvent.FILE, body,
                lamport = nextLamport(events), createdMs = nowMs)
        } else {
            val body = buildJsonObject { put("text", JsonPrimitive(text!!)) }.toString()
            CircleCore.create(circleId, me, identity, CircleEvent.MESSAGE, body,
                lamport = nextLamport(events), createdMs = nowMs)
        }

        // An explicit --cert is authoritative: a malformed value is a refusal,
        // never a silent fallback to a certificate found in the event log.
        // Both paths are gated on the fingerprint named by --to before seal.
        val cert = if (args.has("--cert")) {
            val explicit = args.opt("--cert")?.let { parseCert(it) }
            if (explicit == null) {
                println("!! bad --cert — expected Base64 X.509, nothing sealed")
                return EXIT_BAD_ARGS
            }
            explicit
        } else {
            events.firstOrNull {
                norm(it.authorFp) == toFp && it.cert.isNotBlank() && CircleCore.verify(it)
            }?.let { parseCert(it.cert) } ?: run {
                println("!! no certificate for ${toFp.take(12)} — pass --cert B64")
                return EXIT_BAD_ARGS
            }
        }
        if (!certMatchesTo(cert, toFp)) {
            val certFp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull()
            println("!! certificate ${certFp?.take(12) ?: "?"} differs from --to ${toFp.take(12)} — nothing sealed")
            return EXIT_BAD_ARGS
        }

        val envelope = CourierEnvelope.seal(listOf(contentEvent), circleId, me, toFp, cert, identity, nowMs)
            ?: run {
                println("!! seal refused — nothing written")
                return EXIT_BAD_ARGS
            }
        val parcelBody = buildJsonObject {
            put("toFp", JsonPrimitive(toFp))
            put("envelope", JsonPrimitive(CourierEnvelope.encode(envelope)))
        }.toString()
        // The parcel event id is the envelope id, so one id names the envelope,
        // the bag row and the <id>.parcel file.
        val unsigned = CircleEvent(
            id = envelope.id,
            circleId = circleId,
            authorFp = me,
            lamport = contentEvent.lamport,
            type = PARCEL,
            body = parcelBody,
            sig = "",
            createdMs = envelope.createdMs,
            cert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
        )
        val signed = runCatching { CircleCore.sign(unsigned, identity) }.getOrNull()
            ?: run {
                println("!! parcel event refused — nothing written")
                return EXIT_BAD_ARGS
            }
        val parcel = CourierCarryCore.parcelOf(signed) ?: run {
            println("!! parcel refused — nothing written")
            return EXIT_BAD_ARGS
        }

        val dir = bagDir(args)
        val rows = loadRows(args)
        if (rows.any { it.id == parcel.id }) {
            println("!! duplicate id ${parcel.id.take(8)} — nothing written")
            return EXIT_BAD_ARGS
        }
        if (!withinQuota(rows.size, parcelFiles(dir).size)) {
            println("!! bag full (${CourierCarryCore.MAX_PARCELS} parcel(s)) — nothing dropped")
            return EXIT_BAD_ARGS
        }
        val wrote = runCatching {
            Files.createDirectories(dir)
            Files.write(
                dir.resolve(parcelFileName(parcel.id)),
                CourierCarryCore.encode(CarryState(listOf(parcel))).toByteArray(Charsets.UTF_8),
            )
        }.isSuccess
        if (!wrote) {
            println("!! could not write parcel ${parcel.id.take(8)}")
            return EXIT_BAD_ARGS
        }

        val row = Row(parcel.id, toFp, me, circleId, PENDING, parcel.createdMs, parcel.expiresMs)
        if (!saveRows(args, rows + row)) {
            // The parcel file is already on disk; remove it so a failed state
            // commit never leaves an orphan with no row to track it.
            runCatching { Files.deleteIfExists(dir.resolve(parcelFileName(parcel.id))) }
            println("!! could not save courier state — parcel ${parcel.id.take(8)} rolled back")
            return EXIT_BAD_ARGS
        }
        println("${ts()} courier drop ${parcel.id.take(8)} -> ${toFp.take(12)} exp=${parcel.expiresMs}")
        return 0
    }

    // ==== carry ===============================================================

    /** Verifies the local bag's parcel files and moves verified rows to carried. */
    private fun carry(args: ParsedArgs, nowMs: Long): Int {
        val dir = bagDir(args)
        if (!Files.isDirectory(dir)) {
            println("!! bag missing: $dir")
            return EXIT_BAD_ARGS
        }
        val rows = loadRows(args)
        var changed = false
        val updated = rows.map { row ->
            val f = dir.resolve(parcelFileName(row.id))
            val verified = Files.isRegularFile(f) && runCatching {
                val parcel = CourierCarryCore.decode(Files.readString(f, Charsets.UTF_8))
                    ?.parcels?.firstOrNull()
                if (parcel == null || parcel.id != row.id) return@runCatching false
                val envelope = envelopeOf(parcel.event) ?: return@runCatching false
                CourierCarryCore.parcelOf(parcel.event) != null &&
                    CircleCore.verify(parcel.event) &&
                    CourierEnvelope.verify(envelope)
            }.getOrDefault(false)
            if (verified) {
                val next = nextState(row, Kind.CARRY, nowMs)
                if (next != row.state) changed = true
                row.copy(state = next)
            } else {
                println("${ts()}   ${row.id.take(8)} bag row unreadable, kept ${row.state}")
                row
            }
        }
        if (changed && !saveRows(args, updated)) {
            println("!! could not save courier state — nothing marked carried")
            return EXIT_BAD_ARGS
        }
        println("${ts()} courier carry ${countLine(updated)}")
        return 0
    }

    // ==== receive =============================================================

    /**
     * Opens parcels addressed to the local identity and folds events through the
     * same gates as the app: a signature check, a circle-id match, the per-type
     * body gate, and the member/JOIN/REKEY exclusion. No unverified event ever
     * reaches `circle.state`. Both state files are committed before a single
     * parcel is archived; a failed commit leaves rows, circle state and files
     * exactly as they were.
     */
    private fun receive(args: ParsedArgs, nowMs: Long): Int {
        val dir = bagDir(args)
        if (!Files.isDirectory(dir)) {
            println("!! bag missing: $dir")
            return EXIT_BAD_ARGS
        }
        val identity = loadOrCreateIdentity()
        val me = norm(identity.fingerprint) ?: return EXIT_BAD_ARGS
        val rows = loadRows(args).toMutableList()
        // The pre-receive rows are the rollback image if the commit fails.
        val originalRows = rows.toList()
        val receivedDir = harnessHome().resolve(RECEIVED_DIR)
        val rejectedDir = harnessHome().resolve(REJECTED_DIR)
        var refused = false

        // Stage everything in memory: no circle.state write, no file move and
        // no row conclusion happens until both state files are written.
        val st = loadCliCircleState() ?: CircleCliState()
        val foldedEvents = st.events.toMutableList()
        var folded = false
        val archive = mutableListOf<Path>()
        val quarantine = mutableListOf<Path>()
        val deliveredLogs = mutableListOf<Pair<String, Int>>()
        val members = st.members.mapNotNull { norm(it) }.toSet()

        for (f in parcelFiles(dir)) {
            val id = f.fileName.toString().removeSuffix(PARCEL_SUFFIX)
            val row = rows.firstOrNull { it.id == id } ?: continue // no row ⇒ never folded
            val parcel = runCatching {
                CourierCarryCore.decode(Files.readString(f, Charsets.UTF_8))?.parcels?.firstOrNull()
            }.getOrNull()
            if (parcel == null || parcel.id != row.id) {
                println("${ts()}   ${id.take(8)} envelope unreadable, kept ${row.state}")
                continue
            }
            if (nowMs >= parcel.expiresMs) {
                println("${ts()}   ${id.take(8)} expired, left for sweep")
                continue
            }
            if (!parcel.toFp.equals(me, ignoreCase = true)) {
                println("${ts()}   ${id.take(8)} holding for ${parcel.toFp.take(12)}")
                continue
            }
            val envB64 = runCatching {
                (WireJson.json.parseToJsonElement(parcel.event.body) as? JsonObject)?.get("envelope")
            }.getOrNull() as? JsonPrimitive
            val envelope = CourierEnvelope.decode(envB64?.content)
            if (envelope == null) {
                stageRefusal(rows, row, id, nowMs)
                quarantine.add(f)
                refused = true
                continue
            }

            var threw = false
            val opened = try {
                CourierEnvelope.open(envelope, identity, nowMs)
            } catch (t: Throwable) {
                threw = true
                null
            }
            if (threw) {
                // The carrier never concluded: keep the file and the row for a
                // later attempt, exactly as before.
                println("${ts()}   ${id.take(8)} open threw, kept ${row.state} for retry")
                continue
            }
            if (opened == null) {
                stageRefusal(rows, row, id, nowMs)
                quarantine.add(f)
                refused = true
                continue
            }

            // Fold-time gates mirror FlitCircleSync.openAtMe + fresh. The
            // envelope is ciphertext to every carrier, yet once opened its
            // author is untrusted: JOIN and REKEY are governance verbs, only a
            // current local member may mint a foldable event, the circle must
            // match, the type body must be well formed and the signature must
            // verify before anything is staged for circle.state.
            val allowed = opened.filter { event ->
                event.type != CircleEvent.JOIN &&
                    event.type != CircleEvent.REKEY &&
                    norm(event.authorFp) in members &&
                    event.circleId == st.circleId &&
                    (event.type != CircleEvent.FILE || CircleCore.validFileBody(event.body)) &&
                    (event.type != CircleEvent.PARCEL || CourierCarryCore.validParcelBody(event.body)) &&
                    bodyReadable(event.body) &&
                    CircleCore.verify(event)
            }
            if (allowed.isEmpty()) {
                println("${ts()}   ${id.take(8)} no allowed event(s), kept ${row.state}")
                continue
            }
            val refusedCount = opened.size - allowed.size
            if (refusedCount > 0) {
                println("${ts()}   ${id.take(8)} refused $refusedCount event(s) outside the local fold gates")
            }
            foldedEvents.clear()
            foldedEvents.addAll(CircleCore.order(CircleCore.dedup(foldedEvents + allowed)))
            folded = true
            archive.add(f)
            deliveredLogs.add(id.take(8) to allowed.size)
            val idx = rows.indexOfFirst { it.id == row.id }
            if (idx >= 0) rows[idx] = row.copy(state = nextState(row, Kind.RECEIVE_OK, nowMs))
        }

        // Commit. saveRows is an atomic temp+move; circle.state is written only
        // when an event actually folds. Archive/quarantine moves happen AFTER
        // both writes succeed, so a failed commit has no archive to roll back
        // and no row is left falsely delivered.
        if (!saveRows(args, rows)) {
            println("!! could not save courier state — nothing received")
            return EXIT_BAD_ARGS
        }
        if (folded) {
            st.events = foldedEvents
            if (!runCatching { saveCircleState(st) }.isSuccess) {
                saveRows(args, originalRows)
                println("!! could not save circle state — nothing received")
                return EXIT_BAD_ARGS
            }
        }
        moveAll(receivedDir, archive, "archive")
        moveAll(rejectedDir, quarantine, "quarantine")
        for ((shortId, count) in deliveredLogs) {
            println("${ts()}   $shortId delivered $count event(s)")
        }
        println("${ts()} courier receive ${countLine(rows)}")
        return if (refused) EXIT_OPEN_REFUSED else 0
    }

    /** Mark a row refused for a quarantined parcel without touching disk. */
    private fun stageRefusal(rows: MutableList<Row>, row: Row, id: String, nowMs: Long) {
        val idx = rows.indexOfFirst { it.id == row.id }
        if (idx >= 0) rows[idx] = row.copy(state = nextState(row, Kind.RECEIVE_REFUSED, nowMs))
        println("${ts()}   ${id.take(8)} refused")
    }

    /** Move only committed envelopes into [dir]; one failure is reported, not fatal. */
    private fun moveAll(dir: Path, files: List<Path>, label: String) {
        if (files.isEmpty()) return
        runCatching { Files.createDirectories(dir) }
        files.forEach { f ->
            val ok = runCatching {
                Files.move(f, dir.resolve(f.fileName), StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
            if (!ok) println("${ts()}   ${f.fileName.toString().removeSuffix(PARCEL_SUFFIX).take(8)} could not $label the envelope")
        }
    }

    /** Fold-time body gate: one bounded JSON object, as `circleBodyReadable` in the app. */
    private fun bodyReadable(body: String): Boolean =
        body.isNotBlank() && body.length <= CircleCore.MAX_BODY_CHARS &&
            runCatching { WireJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull() != null

    // ==== status ==============================================================

    private fun status(args: ParsedArgs): Int {
        // Status reads only the local rows, so an absent bag simply means no
        // envelopes have been dropped yet; it still prints the count line.
        val rows = loadRows(args)
        for (r in rows) {
            println("${ts()}   ${r.id.take(8)}  ${r.state.padEnd(9)}  to=${r.recipientFp.take(12)}  exp=${r.expiresMs}")
        }
        println("${ts()} courier ${countLine(rows)}")
        return 0
    }

    // ==== sweep ===============================================================

    private fun sweep(args: ParsedArgs, nowMs: Long): Int {
        val dir = bagDir(args)
        if (!Files.isDirectory(dir)) {
            println("!! bag missing: $dir")
            return EXIT_BAD_ARGS
        }
        val updated = loadRows(args).map { row ->
            val next = nextState(row, Kind.SWEEP, nowMs)
            if (next == EXPIRED || row.state == REFUSED) {
                runCatching { Files.deleteIfExists(dir.resolve(parcelFileName(row.id))) }
            }
            if (next != row.state) row.copy(state = next) else row
        }.toMutableList()

        // Enforce the quota on the local rows as well as on new drops.
        while (updated.size > CourierCarryCore.MAX_PARCELS) {
            val oldest = updated.minByOrNull { it.createdMs } ?: break
            updated.remove(oldest)
            runCatching { Files.deleteIfExists(dir.resolve(parcelFileName(oldest.id))) }
        }
        if (!saveRows(args, updated)) {
            println("!! could not save courier state — nothing swept")
            return EXIT_BAD_ARGS
        }
        println("${ts()} courier sweep ${countLine(updated)}")
        return 0
    }

    // ==== pure helpers and local state ========================================

    /** The deterministic on-disk name of a parcel: exactly `<id>.parcel`. */
    internal fun parcelFileName(id: String): String = "$id$PARCEL_SUFFIX"

    /** One drop is allowed only while both the rows and the bag files have room. */
    internal fun withinQuota(rowCount: Int, fileCount: Int): Boolean =
        rowCount < CourierCarryCore.MAX_PARCELS && fileCount < CourierCarryCore.MAX_PARCELS

    /** Closing status line, with every state present in a stable order. */
    internal fun countLine(rows: List<Row>): String {
        val tally = rows.groupingBy { it.state }.eachCount()
        return buildString {
            append(rows.size).append(" row(s)")
            for (s in listOf(PENDING, CARRIED, DELIVERED, EXPIRED, REFUSED)) {
                append(' ').append(s).append('=').append(tally[s] ?: 0)
            }
        }
    }

    /** True only when a certificate's own digest names the requested recipient. */
    internal fun certMatchesTo(cert: X509Certificate?, toFp: String): Boolean {
        if (cert == null) return false
        val fp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull() ?: return false
        return fp.equals(norm(toFp), ignoreCase = true)
    }

    private fun loadRows(args: ParsedArgs): List<Row> {
        val f = statePath(args)
        val raw = if (Files.exists(f)) runCatching { Files.readString(f, Charsets.UTF_8) }.getOrNull() else null
        return decodeRows(raw)
    }

    /**
     * Persists the rows via a temp file plus an atomic move, so a crashed or
     * failed write never leaves a half-written ledger. Returns false only when
     * the write itself failed; every caller treats that as fatal (non-zero exit,
     * no success line). In [drop] the parcel file is rolled back, and in [receive]
     * no archive or delivered mark survives, on a failed commit.
     */
    private fun saveRows(args: ParsedArgs, rows: List<Row>): Boolean {
        val f = statePath(args)
        val tmp = f.resolveSibling("${f.fileName}.tmp-${UUID.randomUUID()}")
        return runCatching {
            Files.createDirectories(f.parent)
            Files.write(tmp, encodeRows(rows).toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrDefault(false).also { ok ->
            if (!ok) runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun parcelFiles(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) {
            runCatching {
                Files.list(dir).use { stream ->
                    stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(PARCEL_SUFFIX) }
                        .toList()
                }
            }.getOrDefault(emptyList())
        } else emptyList()

    /** The CLI's Circle view, loaded with the same seven-line shape as Harness.kt. */
    private fun loadCliCircleState(): CircleCliState? {
        val f = harnessHome().resolve(CIRCLE_STATE_FILE)
        if (!Files.exists(f)) return null
        val lines = runCatching { Files.readAllLines(f) }.getOrNull() ?: return null
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

    private fun loadCircleEvents(): List<CircleEvent> = loadCliCircleState()?.events ?: emptyList()

    private fun nextLamport(events: List<CircleEvent>): Long =
        (events.maxOfOrNull { it.lamport } ?: -1L) + 1L

    private fun parseCert(b64: String?): X509Certificate? {
        if (b64.isNullOrBlank()) return null
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(b64).inputStream()) as? X509Certificate
        }.getOrNull()
    }

    /** The signed envelope inside a parcel body, decoded but not opened. */
    private fun envelopeOf(event: CircleEvent): CourierEnvelope? {
        val blob = runCatching {
            (WireJson.json.parseToJsonElement(event.body) as? JsonObject)?.get("envelope")
        }.getOrNull() as? JsonPrimitive ?: return null
        return CourierEnvelope.decode(blob.content)
    }

    private fun norm(fp: String?): String? =
        fp?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it.length <= 128 }
}
