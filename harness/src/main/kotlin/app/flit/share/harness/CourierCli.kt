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

    /** `--bag DIR` overrides the portable handoff directory for every command. */
    internal fun bagDir(args: ParsedArgs): Path =
        args.opt("--bag")?.let { Path.of(it) } ?: harnessHome().resolve(BAG_DIR)

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

        // The certificate is the signer's own event cert, or an out-of-band B64.
        // Both paths are gated on the fingerprint named by --to before seal.
        val cert = args.opt("--cert")?.let { parseCert(it) }
            ?: events.firstOrNull {
                norm(it.authorFp) == toFp && it.cert.isNotBlank() && CircleCore.verify(it)
            }?.let { parseCert(it.cert) }
        if (cert == null) {
            println("!! no certificate for ${toFp.take(12)} — pass --cert B64")
            return EXIT_BAD_ARGS
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
        saveRows(args, rows + row)
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
                parcel != null && parcel.id == row.id && CourierCarryCore.parcelOf(parcel.event) != null
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
        if (changed) saveRows(args, updated)
        println("${ts()} courier carry ${countLine(updated)}")
        return 0
    }

    // ==== receive =============================================================

    /**
     * Opens parcels addressed to the local identity, folds returned events into
     * `circle.state`, quarantines refusals and leaves thrown opens pending.
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
        val receivedDir = harnessHome().resolve(RECEIVED_DIR)
        val rejectedDir = harnessHome().resolve(REJECTED_DIR)
        var refused = false

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
                refusedRow(rows, row, id, nowMs, f, rejectedDir)
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
                // The carrier never spoke: keep the file and the row for retry.
                println("${ts()}   ${id.take(8)} open threw, kept ${row.state} for retry")
                continue
            }
            if (opened == null) {
                refusedRow(rows, row, id, nowMs, f, rejectedDir)
                refused = true
                continue
            }

            val st = loadCliCircleState() ?: CircleCliState()
            st.events = CircleCore.order(CircleCore.dedup(st.events + opened)).toMutableList()
            saveCircleState(st)
            val moved = runCatching {
                Files.createDirectories(receivedDir)
                Files.move(f, receivedDir.resolve(f.fileName), StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
            if (!moved) println("${ts()}   ${id.take(8)} folded but could not archive the envelope")
            val idx = rows.indexOfFirst { it.id == row.id }
            if (idx >= 0) rows[idx] = row.copy(state = nextState(row, Kind.RECEIVE_OK, nowMs))
            println("${ts()}   ${id.take(8)} delivered ${opened.size} event(s)")
        }
        saveRows(args, rows)
        println("${ts()} courier receive ${countLine(rows)}")
        return if (refused) EXIT_OPEN_REFUSED else 0
    }

    private fun refusedRow(
        rows: MutableList<Row>,
        row: Row,
        id: String,
        nowMs: Long,
        file: Path,
        rejectedDir: Path,
    ) {
        val moved = runCatching {
            Files.createDirectories(rejectedDir)
            Files.move(file, rejectedDir.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!moved) println("${ts()}   ${id.take(8)} could not quarantine the envelope")
        val idx = rows.indexOfFirst { it.id == row.id }
        if (idx >= 0) rows[idx] = row.copy(state = nextState(row, Kind.RECEIVE_REFUSED, nowMs))
        println("${ts()}   ${id.take(8)} refused and quarantined")
    }

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
        saveRows(args, updated)
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

    private fun saveRows(args: ParsedArgs, rows: List<Row>) {
        val f = statePath(args)
        runCatching {
            Files.createDirectories(f.parent)
            Files.write(f, encodeRows(rows).toByteArray(Charsets.UTF_8))
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

    private fun norm(fp: String?): String? =
        fp?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it.length <= 128 }
}
