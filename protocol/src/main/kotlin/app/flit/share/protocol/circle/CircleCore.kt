package app.flit.share.protocol.circle

import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID

/**
 * The pure decision layer of a Circle: canonical bytes, signing, deterministic
 * ordering, bounded dedup, a replay/skew window, vote resolution and kick
 * tallies. No sockets, no Android, no delegate calls — the same discipline as
 * `PanicCore`, reused for an event log instead of a one-shot alert.
 */
object CircleCore {
    /** Older events are stale replay, not news. */
    const val REPLAY_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** Small future tolerance for two phones' unsynchronised clocks. */
    const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    /** Bounded FIFO memory — a bounded log must not grow without limit. */
    const val MAX_SEEN = 4096

    /** Largest accepted event body (a chat line / file-metadata JSON object). */
    const val MAX_BODY_CHARS = 16 * 1024

    /** The exact, closed key set of a legacy [CircleEvent.FILE] announcement body. */
    val FILE_BODY_KEYS: Set<String> = setOf("name", "size", "sha256")

    /** The exact, closed key set of a keyed [CircleEvent.FILE] announcement body. */
    val KEYED_FILE_BODY_KEYS: Set<String> = setOf(
        "name", "size", "sha256", "keyGen", "cipherSha", "iv", "wraps",
    )

    /** The exact, closed key set of a delivery [CircleEvent.RECEIPT] body. */
    val RECEIPT_BODY_KEYS: Set<String> = setOf("dropId", "sha256", "keyId")

    /** Largest accepted id / circleId / authorFp / type string. */
    const val MAX_FIELD_CHARS = 256

    /** Base64 signature cap (a 2048-bit RSA signature is ~344 chars). */
    const val MAX_SIG_CHARS = 1024

    /** Base64 DER cap (a self-signed 2048-bit cert is ~1.2 KB). */
    const val MAX_CERT_CHARS = 8 * 1024

    /** One sync delta is bounded; a bigger log is a split/pagination concern. */
    const val MAX_EVENTS_PER_REQUEST = 1024

    /** Bounded raw request body for the two Circle routes. */
    const val MAX_REQUEST_BYTES = 8 * 1024 * 1024

    /**
     * Exact precedence for [evaluate]/[accept]: Invalid → OutsideWindow →
     * Duplicate → Accept. Only Accept records the id; every other decision
     * leaves the bounded seen set untouched.
     */
    sealed interface Decision {
        /** Structurally fresh and in-window: the id is now reserved. Verify the signature FIRST. */
        data object Accept : Decision

        /** Same id already reserved by [evaluate], [accept] or [fresh]. Not recorded again. */
        data object Duplicate : Decision

        /** createdMs outside [now - REPLAY_WINDOW_MS, now + CLOCK_SKEW_MS]. Not recorded. */
        data object OutsideWindow : Decision

        /** [validShape] failed: blank/oversized fields, bad type, non-positive clock. Not recorded. */
        data object Invalid : Decision
    }

    private val seen = LinkedHashMap<String, Long>()

    /**
     * Deterministic signing input: a domain tag plus length-prefixed UTF-8
     * fields, so no delimiter can be smuggled through a body. Covers every
     * field except [CircleEvent.sig] and [CircleEvent.cert]; [verify] binds
     * the omitted cert to [CircleEvent.authorFp] by its own SHA-256 DER.
     */
    fun canonical(e: CircleEvent): ByteArray = canonical(
        e.id, e.circleId, e.authorFp, e.lamport, e.type, e.body, e.createdMs,
    )

    /** Explicit-field form, handy for forging a test vector. */
    fun canonical(
        id: String,
        circleId: String,
        authorFp: String,
        lamport: Long,
        type: String,
        body: String,
        createdMs: Long,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        field(out, "FLITCIRCLE1")
        field(out, id)
        field(out, circleId)
        field(out, authorFp)
        out.writeLong(lamport)
        field(out, type)
        field(out, body)
        out.writeLong(createdMs)
        out.flush()
        return bytes.toByteArray()
    }

    private fun field(out: DataOutputStream, value: String) {
        val raw = value.toByteArray(Charsets.UTF_8)
        out.writeInt(raw.size)
        out.write(raw)
    }

    /**
     * Builds and signs a fresh event. The signer's certificate is pinned under
     * its own fingerprint so a local [verify] works immediately. Every bound
     * enforced by [validShape] is checked here too: we never mint an event
     * that our own receiver would refuse.
     */
    fun create(
        circleId: String,
        authorFp: String,
        identity: TlsIdentity,
        type: String,
        body: String,
        lamport: Long = 0L,
        createdMs: Long = System.currentTimeMillis(),
    ): CircleEvent {
        require(circleId.isNotBlank()) { "blank circle id" }
        require(circleId.length <= MAX_FIELD_CHARS) { "circle id too long" }
        val fp = authorFp.trim().uppercase()
        require(fp.isNotBlank()) { "blank author fingerprint" }
        require(fp.length <= MAX_FIELD_CHARS) { "author fingerprint too long" }
        require(type in CircleEvent.TYPES) { "unknown circle event type: $type" }
        require(body.isNotBlank()) { "blank event body" }
        require(body.length <= MAX_BODY_CHARS) { "event body too long" }
        require(lamport >= 0L) { "negative lamport" }
        require(createdMs > 0L) { "non-positive createdMs" }
        val event = CircleEvent(
            id = UUID.randomUUID().toString(),
            circleId = circleId.trim(),
            authorFp = fp,
            lamport = lamport,
            type = type,
            body = body,
            sig = "",
            createdMs = createdMs,
            cert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
        )
        return sign(event, identity)
    }

    /**
     * Signs a caller-built event with [identity], filling only [CircleEvent.sig].
     * Use for re-signing a test vector or repairing an unsigned event; it never
     * touches any other field, and refuses an already-signed event so the same
     * canonical bytes cannot be signed twice by accident.
     */
    fun sign(e: CircleEvent, identity: TlsIdentity): CircleEvent {
        require(e.sig.isBlank()) { "event already signed" }
        require(validShape(e)) { "event is not signable" }
        TlsIdentity.pin(identity.certificate)
        return e.copy(sig = identity.sign(canonical(e)))
    }

    /**
     * Signature check only — no dedup, no replay window. A self-declared cert
     * is bound to [CircleEvent.authorFp] by its OWN SHA-256 DER, and checked
     * against that cert before it is pinned, so a forgery never poisons the
     * shared pin store. An event without a cert falls back to an already
     * pinned author (relayed senders).
     */
    fun verify(e: CircleEvent): Boolean {
        if (!validShape(e) || e.sig.isBlank() || e.sig.length > MAX_SIG_CHARS) return false
        val signed = canonical(e)
        if (e.cert.isNotBlank()) {
            val parsed = runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(Base64.getDecoder().decode(e.cert).inputStream())
            }.getOrNull() as? X509Certificate ?: return false
            val fp = runCatching { TlsIdentity.fingerprintOf(parsed) }.getOrNull() ?: return false
            if (!fp.equals(e.authorFp, ignoreCase = true)) return false
            if (!TlsIdentity.verifyWithCertificate(parsed, signed, e.sig)) return false
            TlsIdentity.pin(parsed)
            return true
        }
        return TlsIdentity.verify(e.authorFp, signed, e.sig)
    }

    /**
     * Total order shared by every deterministic operation. The classic
     * `(lamport, authorFp, id)` prefix is unchanged; the remaining signed
     * fields only break exact ties so two peers converge even when one signer
     * minted two different events with the same id.
     */
    private val ORDER = compareBy<CircleEvent> { it.lamport }
        .thenBy { it.authorFp }
        .thenBy { it.id }
        .thenBy { it.type }
        .thenBy { it.body }
        .thenBy { it.createdMs }
        .thenBy { it.sig }
        .thenBy { it.cert }

    /** Deterministic total order: `(lamport, authorFp, id, …signed fields)`. */
    fun order(events: Iterable<CircleEvent>): List<CircleEvent> = events.sortedWith(ORDER)

    /**
     * One event per id. The winner is chosen by [ORDER], never by which copy
     * arrived first, so two peers with different delivery order keep the same
     * event. Output order stays first-occurrence for existing callers.
     */
    fun dedup(events: Iterable<CircleEvent>): List<CircleEvent> {
        val winners = LinkedHashMap<String, CircleEvent>()
        for (e in events) {
            val previous = winners[e.id]
            if (previous == null || ORDER.compare(e, previous) < 0) winners[e.id] = e
        }
        return winners.values.toList()
    }

    /**
     * Latest signed vote per current member wins; changing an answer appends a
     * new vote and the older one stops counting. Result maps option index (as
     * a string) to its count. Votes from departed/non-members and votes for a
     * different poll are ignored.
     */
    fun tally(
        events: Iterable<CircleEvent>,
        pollId: String,
        members: Iterable<String>,
    ): Map<String, Int> {
        val allowed = members.normalized()
        val latest = LinkedHashMap<String, Int>()
        for (e in order(events)) {
            if (e.type != CircleEvent.VOTE || e.authorFp !in allowed) continue
            val obj = bodyObject(e.body) ?: continue
            if (text(obj, "pollId") != pollId) continue
            val option = int(obj, "option")?.takeIf { it >= 0 } ?: continue
            latest[e.authorFp] = option
        }
        return latest.values.groupingBy { it.toString() }.eachCount()
    }

    /**
     * Yes/no counts for a vote-kick against [targetFp]. Both honest wire
     * shapes are accepted: a `kick` opener or vote with `targetFp` (+ optional
     * `approve`), and a `vote` with `kickId` + `approve` whose target comes from
     * the matching opener. A bare `kick` opener counts as its author's yes; an
     * admin kick (`mode: admin`) is a decision, not a vote, and is ignored.
     * As with polls, each current member's LATEST vote wins, so a member can
     * change their mind before the tally is read.
     */
    fun kickTally(
        events: Iterable<CircleEvent>,
        targetFp: String,
        members: Iterable<String>,
    ): Pair<Int, Int> {
        val target = targetFp.trim().uppercase()
        val allowed = members.normalized()
        val all = events.toList()
        val kickTargets = HashMap<String, String>()
        // Same total order as the tally below: two openers with one kickId
        // must resolve to the same target on every peer, not to whichever
        // copy the carrier happened to deliver last.
        for (e in order(all)) {
            if (e.type != CircleEvent.KICK) continue
            val obj = bodyObject(e.body) ?: continue
            val id = text(obj, "kickId") ?: continue
            val named = text(obj, "targetFp") ?: text(obj, "target") ?: continue
            kickTargets[id] = named
        }
        val latest = LinkedHashMap<String, Boolean>()
        for (e in order(all)) {
            if (e.type != CircleEvent.KICK && e.type != CircleEvent.VOTE) continue
            if (e.authorFp !in allowed) continue
            val obj = bodyObject(e.body) ?: continue
            val named = text(obj, "targetFp") ?: text(obj, "target")
                ?: text(obj, "kickId")?.let { kickTargets[it] } ?: continue
            if (!named.equals(target, ignoreCase = true)) continue
            val approve = bool(obj, "approve") ?: when {
                e.type != CircleEvent.KICK -> continue // a vote without a decision is not a vote
                text(obj, "mode")?.lowercase() == "admin" -> continue // already decided
                else -> true // vote-kick opener is its author's yes
            }
            latest[e.authorFp] = approve
        }
        val yes = latest.values.count { it }
        return yes to (latest.size - yes)
    }

    /**
     * Current members who signed a well-formed receipt for one landed drop.
     * An event contributes only when its type is [CircleEvent.RECEIPT], its
     * body passes [validReceiptBody], its `dropId` and `sha256` match the two
     * arguments exactly, its author is in the normalised [members] set (as in
     * [tally]), and its signature passes [verify]. A departed/non-member, a
     * body for a different drop, a malformed body or a forgery is ignored.
     *
     * At most one receipt per author counts, chosen by [ORDER] so two peers
     * that received the same receipts in different orders return the same
     * set. Iterating in [order] and overwriting makes the greatest-by-ORDER
     * receipt the winner ("latest wins"). `keyId` is provenance only: a later
     * rekey that changes it does not invalidate an earlier receipt for the
     * same landed content. No new cap or replay state is introduced; this is
     * a pure fold over the supplied events.
     */
    fun receiptAuthors(
        events: Iterable<CircleEvent>,
        dropId: String,
        sha256: String,
        members: Iterable<String>,
    ): Set<String> {
        val allowed = members.normalized()
        val latest = LinkedHashMap<String, CircleEvent>()
        for (e in order(events)) {
            if (e.type != CircleEvent.RECEIPT) continue
            if (e.authorFp !in allowed) continue
            if (!validReceiptBody(e.body)) continue
            val obj = bodyObject(e.body) ?: continue
            if (text(obj, "dropId") != dropId) continue
            if (text(obj, "sha256") != sha256) continue
            if (!verify(e)) continue
            latest[e.authorFp] = e
        }
        return latest.keys.toSet()
    }

    /**
     * Pure structural gate shared by the route, [verify], [sign] and [evaluate].
     * No replay state, no crypto: just the bounds a well-formed event obeys.
     */
    fun validShape(e: CircleEvent): Boolean =
        e.id.isNotBlank() && e.id.length <= MAX_FIELD_CHARS &&
            e.circleId.isNotBlank() && e.circleId.length <= MAX_FIELD_CHARS &&
            e.authorFp.isNotBlank() && e.authorFp.length <= MAX_FIELD_CHARS &&
            e.type in CircleEvent.TYPES &&
            e.lamport >= 0L && e.createdMs > 0L &&
            e.body.isNotBlank() && e.body.length <= MAX_BODY_CHARS &&
            e.cert.length <= MAX_CERT_CHARS

    /**
     * Additive, type-specific gate for a [CircleEvent.FILE] body: a bounded
     * JSON object whose keys are EXACTLY [FILE_BODY_KEYS] (legacy) or
     * [KEYED_FILE_BODY_KEYS] (per-drop content key). Both forms require
     * non-blank `name` and plaintext `sha256` within [MAX_FIELD_CHARS] and a
     * non-negative integer `size`. The keyed form additionally requires a
     * positive integer `keyGen`, a non-blank `cipherSha`, an `iv` that decodes
     * to exactly [DropKeyCore.IV_BYTES] bytes, and a `wraps` object with
     * 1..[DropKeyCore.MAX_WRAPS] entries keyed by non-blank uppercase
     * fingerprints whose Base64 values are non-blank and at most
     * [DropKeyCore.MAX_WRAPPED_CHARS] characters. Every other string stays
     * within [MAX_FIELD_CHARS]; the whole body stays within [MAX_BODY_CHARS].
     *
     * [validShape] deliberately stays type-agnostic (bounds only), so this
     * never changes an existing event's signature or acceptance, and neither
     * [canonical] nor [CircleEvent.TYPES] is altered by the second key set.
     * Callers use this gate to refuse `token`, `path`, `spoolPath`, a partial
     * key set or any other extra key. That is what keeps a sender's local
     * spool path off the wire: sha256 only correlates a landed file with its
     * card locally; bytes are pushed by the sender with `sendFiles`.
     */
    fun validFileBody(body: String): Boolean {
        if (body.isBlank() || body.length > MAX_BODY_CHARS) return false
        val obj = bodyObject(body) ?: return false
        return when (obj.keys) {
            FILE_BODY_KEYS -> validFileFields(obj)
            KEYED_FILE_BODY_KEYS -> validFileFields(obj) && validKeyedFields(obj)
            else -> false // partial, extra or renamed keys
        }
    }

    /** The bounds shared by the legacy and the keyed file body. */
    private fun validFileFields(obj: JsonObject): Boolean {
        val name = text(obj, "name") ?: return false
        val sha = text(obj, "sha256") ?: return false
        val sizeValue = obj["size"] as? JsonPrimitive ?: return false
        if (sizeValue.isString) return false // "12" is not the integer 12
        val size = sizeValue.content.toLongOrNull() ?: return false
        return name.length <= MAX_FIELD_CHARS && sha.length <= MAX_FIELD_CHARS && size >= 0L
    }

    /** The extra bounds carried only by a keyed file body. */
    private fun validKeyedFields(obj: JsonObject): Boolean {
        val cipherSha = text(obj, "cipherSha") ?: return false
        if (cipherSha.length > MAX_FIELD_CHARS) return false

        val keyGenValue = obj["keyGen"] as? JsonPrimitive ?: return false
        if (keyGenValue.isString) return false // "1" is not the integer 1
        val keyGen = keyGenValue.content.toIntOrNull() ?: return false
        if (keyGen <= 0) return false

        val iv = text(obj, "iv") ?: return false
        if (iv.length > MAX_FIELD_CHARS) return false
        if (decodedSize(iv) != DropKeyCore.IV_BYTES) return false

        val wraps = obj["wraps"] as? JsonObject ?: return false
        if (wraps.isEmpty() || wraps.size > DropKeyCore.MAX_WRAPS) return false
        for ((fp, wrapped) in wraps) {
            if (fp.isBlank() || fp.length > MAX_FIELD_CHARS) return false
            if (fp != fp.trim() || fp != fp.uppercase()) return false
            val value = wrapped as? JsonPrimitive ?: return false
            if (!value.isString) return false
            val b64 = value.content
            if (b64.isBlank() || b64.length > DropKeyCore.MAX_WRAPPED_CHARS) return false
            if (decodedSize(b64) <= 0) return false // strict Base64; junk is not a wrapped key
        }
        return true
    }

    /** Strict Base64 decoded byte count, or -1 when [value] is not Base64. */
    private fun decodedSize(value: String): Int = runCatching {
        Base64.getDecoder().decode(value).size
    }.getOrDefault(-1)

    /**
     * Additive, type-specific gate for a [CircleEvent.RECEIPT] body: a bounded
     * JSON object whose keys are EXACTLY [RECEIPT_BODY_KEYS] — `dropId`,
     * `sha256` and `keyId`. Each value must be a JSON string primitive that is
     * non-blank and within [MAX_FIELD_CHARS]; the whole body stays within
     * [MAX_BODY_CHARS]. A blank, oversized, malformed, partial or extra-key
     * body, or a non-string value (e.g. a `size`-style number), is refused.
     *
     * `keyId` is provenance from the per-drop key layer: it is signed and
     * bounded here but is not a selection key in [receiptAuthors]. There is no
     * `landedMs` field — [CircleEvent.createdMs] is the acknowledgement time.
     * The gate is structural only: it signs nothing, pins nothing and reads no
     * store. [validShape] stays type-agnostic (bounds only), so this never
     * changes an existing event's signature or acceptance, and neither
     * [canonical] nor replay handling is altered.
     */
    fun validReceiptBody(body: String): Boolean {
        if (body.isBlank() || body.length > MAX_BODY_CHARS) return false
        val obj = bodyObject(body) ?: return false
        if (obj.keys != RECEIPT_BODY_KEYS) return false
        for (key in RECEIPT_BODY_KEYS) {
            val value = obj[key] as? JsonPrimitive ?: return false
            if (!value.isString) return false // a number/bool/null is not a string field
            val content = value.content
            if (content.isBlank() || content.length > MAX_FIELD_CHARS) return false
        }
        return true
    }

    /**
     * Production replay guard: atomic check-and-record over ONE bounded id
     * set shared by [accept] and [fresh]. Decisions are checked in this exact
     * order: [Decision.Invalid] ([validShape] failed), [Decision.OutsideWindow]
     * (clock), [Decision.Duplicate] (id already reserved), [Decision.Accept].
     * Only Accept records the id, so a refused event can be retried after the
     * cause is fixed; a second Accept can never happen for the same id.
     *
     * Callers MUST call [verify] first: this function reserves ids before
     * crypto, so verifying afterwards would burn the id of a forgery. The
     * production order is `validShape → verify → evaluate → store`.
     *
     * The window compares two positive timestamps by subtraction — never
     * `now ± window` — so an extreme clock cannot overflow into a false Accept.
     */
    fun evaluate(
        e: CircleEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        if (!validShape(e)) return Decision.Invalid
        if (!withinWindow(e.createdMs, nowMs)) return Decision.OutsideWindow
        synchronized(seen) {
            if (e.id in seen) return Decision.Duplicate
            seen[e.id] = nowMs
            while (seen.size > MAX_SEEN) {
                val oldest = seen.entries.iterator().next()
                seen.remove(oldest.key)
            }
        }
        return Decision.Accept
    }

    private fun withinWindow(createdMs: Long, nowMs: Long): Boolean =
        nowMs > 0L && nowMs - createdMs <= REPLAY_WINDOW_MS && createdMs - nowMs <= CLOCK_SKEW_MS

    /**
     * Alias for [evaluate] — the check-and-record name used by transports.
     * Identical semantics: same decisions, same precedence, same shared set.
     * Do not call both for one event; the second call sees a Duplicate.
     */
    fun accept(
        e: CircleEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision = evaluate(e, nowMs)

    /**
     * Id-only form of the same bounded reservation: true only the first time
     * an id is presented, false for a blank/oversized id or a repeat. It uses
     * the SAME set as [evaluate]/[accept] — reserving an id here makes a later
     * event with that id a [Decision.Duplicate], and vice versa. Use it for
     * caller-owned ids that have no signed event yet; never as a replacement
     * for [evaluate]'s shape/window checks on a real event.
     */
    fun fresh(id: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(seen) {
        if (id.isBlank() || id.length > MAX_FIELD_CHARS || id in seen) return@synchronized false
        seen[id] = nowMs
        while (seen.size > MAX_SEEN) {
            val oldest = seen.entries.iterator().next()
            seen.remove(oldest.key)
        }
        true
    }

    /** Test/lifecycle hook: forget every remembered id. */
    fun clear() = synchronized(seen) { seen.clear() }

    private fun Iterable<String>.normalized(): Set<String> =
        map { it.trim().uppercase() }.toSet()

    private fun bodyObject(body: String): JsonObject? {
        if (body.length > MAX_BODY_CHARS) return null
        return runCatching { WireJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun int(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun bool(obj: JsonObject, key: String): Boolean? {
        val p = obj[key] as? JsonPrimitive ?: return null
        return if (p.isString) {
            when (p.content.lowercase()) {
                "true", "yes" -> true
                "false", "no" -> false
                else -> null
            }
        } else p.content.toBooleanStrictOrNull()
    }
}
