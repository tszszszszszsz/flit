package app.flit.share.protocol.circle

import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
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

    /**
     * How long a reservation is retained. It covers the whole past window
     * plus the future-skew allowance, so an id is never dropped while a signed
     * event carrying it could still pass [withinWindow].
     */
    private const val RETAIN_MS = REPLAY_WINDOW_MS + CLOCK_SKEW_MS

    /**
     * Bounded memory for LIVE reservations — a bounded log must not grow
     * without limit. Stale reservations are pruned; a live (in-window) id is
     * never dropped to make room, so a full set refuses the newcomer instead.
     */
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

    /** The exact, closed key set of a stateless JOIN proof body. */
    val JOIN_PROOF_BODY_KEYS: Set<String> = setOf("proof")

    /** Separate signing domain: a Circle signature can never double as a JOIN proof. */
    private const val JOIN_PROOF_TAG = "FLITJOIN1"

    /** A proof is accepted only in the bucket that contains the host's current time. */
    const val JOIN_PROOF_BUCKET_MS: Long = CLOCK_SKEW_MS

    /** SHA-256 of the domain-tagged nonce: fixed size, no caller-controlled framing. */
    private const val JOIN_PROOF_BYTES = 32

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
     * Duplicate → Full → Accept. Only Accept records the id; every other
     * decision leaves the bounded seen set untouched. Expired reservations are
     * purged before the capacity check, so a live id is never evicted: when
     * every slot is still live the newcomer is refused as [Full].
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

        /**
         * All [MAX_SEEN] slots are still live, so admitting the new id would
         * have to drop an in-window replay record. The id is NOT recorded and
         * the caller must refuse the event; a later retry can succeed once a
         * slot expires. [fresh] maps this to false.
         */
        data object Full : Decision
    }

    /** Reservation key → reserved-until-ms. Shared by [evaluate] and [fresh]. */
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Reservation deadline for a key reserved at [nowMs]. Saturates at
     * [Long.MAX_VALUE] so an extreme clock cannot wrap into a past deadline.
     */
    private fun reservedUntil(nowMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - RETAIN_MS) Long.MAX_VALUE else nowMs + RETAIN_MS

    /** Drops only reservations that can no longer be presented in-window. */
    private fun purgeExpiredLocked(nowMs: Long) {
        val entries = seen.entries.iterator()
        while (entries.hasNext()) {
            if (entries.next().value < nowMs) entries.remove()
        }
    }

    private fun isReservedLocked(key: String): Boolean = key in seen

    /**
     * Atomic check-and-record under the [seen] monitor. Purging runs FIRST,
     * then duplicate detection, then capacity; an in-window id is never
     * evicted, so a full set returns [Decision.Full] instead of admitting one.
     */
    private fun reserveLocked(key: String, nowMs: Long): Decision {
        purgeExpiredLocked(nowMs)
        if (isReservedLocked(key)) return Decision.Duplicate
        if (seen.size >= MAX_SEEN) return Decision.Full
        seen[key] = reservedUntil(nowMs)
        return Decision.Accept
    }

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
     * Index of [createdMs] on the JOIN proof grid. The bucket is exactly one
     * clock-skew wide, so a captured proof is useful only until the grid rolls
     * over; this is the stateless half of the replay defence and adds no store.
     * `floorDiv` keeps the function total for a hostile negative timestamp.
     */
    fun joinProofBucket(createdMs: Long): Long = Math.floorDiv(createdMs, JOIN_PROOF_BUCKET_MS)

    /**
     * Deterministic, domain-separated nonce for one JOIN handshake. It uses the
     * SAME four-byte length-prefixed UTF-8 field framing as [canonical] (plus
     * the eight-byte bucket), so no delimiter can be smuggled through an
     * invite or fingerprint, and a `FLITCIRCLE1` event signature can never be
     * replayed here. Every string field is trimmed and uppercased with
     * [Locale.ROOT] before framing, so casing/whitespace differences on the
     * two devices cannot change the bytes. No clock, store or pin is touched.
     */
    fun joinProofNonce(
        hostFp: String,
        circleId: String,
        invite: String?,
        memberFp: String,
        bucket: Long,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        field(out, JOIN_PROOF_TAG)
        field(out, hostFp.trim().uppercase(Locale.ROOT))
        field(out, circleId.trim().uppercase(Locale.ROOT))
        field(out, (invite ?: "").trim().uppercase(Locale.ROOT))
        field(out, memberFp.trim().uppercase(Locale.ROOT))
        out.writeLong(bucket)
        out.flush()
        return bytes.toByteArray()
    }

    /** SHA-256 of the domain-tagged nonce — exactly 32 bytes, Base64URL (no padding). */
    private fun joinProofDigest(hostFp: String, circleId: String, invite: String?, memberFp: String, bucket: Long): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(joinProofNonce(hostFp, circleId, invite, memberFp, bucket))

    /**
     * Builds the exact body accepted by [validJoinProofBody] for one bucket.
     * The `proof` is a digest of the nonce, not a secret: authorship comes from
     * the signed JOIN event that carries this body, so a carrier cannot mint a
     * proof for another member, circle, host, invite or bucket.
     */
    fun joinProofBody(
        hostFp: String,
        circleId: String,
        invite: String?,
        memberFp: String,
        bucket: Long,
    ): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(joinProofDigest(hostFp, circleId, invite, memberFp, bucket))
        val body = buildJsonObject { put("proof", JsonPrimitive(encoded)) }.toString()
        require(body.length <= MAX_BODY_CHARS) { "join proof body too long" }
        return body
    }

    /**
     * Strict Base64URL bytes of a well-formed proof body, or null. The decoder
     * refuses the standard `+`/`/` alphabet and whitespace, the round-trip
     * refuses padding and non-canonical trailing bits, and the size check pins
     * the value to one SHA-256 digest. Total on hostile input; never throws.
     */
    private fun joinProofBytes(body: String): ByteArray? {
        if (body.isBlank() || body.length > MAX_BODY_CHARS) return null
        val obj = bodyObject(body) ?: return null
        if (obj.keys != JOIN_PROOF_BODY_KEYS) return null
        val value = obj["proof"] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val text = value.content
        if (text.isBlank() || text.length > MAX_FIELD_CHARS) return null
        val decoded = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
        if (decoded.size != JOIN_PROOF_BYTES) return null
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != text) return null
        return decoded
    }

    /** Structural gate for a JOIN proof body: see [joinProofBytes]. */
    fun validJoinProofBody(body: String): Boolean = joinProofBytes(body) != null

    /**
     * Signature-only check for a JOIN proof. Mirrors [verify]'s shape,
     * fingerprint binding and signature check, but NEVER writes a pin: the
     * parsed certificate is returned so [joinProofFailure] can pin it only
     * after every gate (bucket, nonce, digest, single-use) has accepted.
     * An embedded cert is bound to its OWN SHA-256 DER before the signature is
     * checked; a cert-less relayed event is checked against an existing pin,
     * which reads but never writes. Returned pair: (signature valid, cert to pin).
     */
    private fun verifyProofSignature(e: CircleEvent): Pair<Boolean, X509Certificate?> {
        if (!validShape(e) || e.sig.isBlank() || e.sig.length > MAX_SIG_CHARS) return false to null
        val signed = canonical(e)
        if (e.cert.isBlank()) {
            return TlsIdentity.verify(e.authorFp, signed, e.sig) to null
        }
        val cert = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(e.cert).inputStream())
        }.getOrNull() as? X509Certificate ?: return false to null
        val fp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull() ?: return false to null
        if (!fp.equals(e.authorFp, ignoreCase = true)) return false to null
        if (!TlsIdentity.verifyWithCertificate(cert, signed, e.sig)) return false to null
        return true to cert
    }

    /**
     * Refusal reason for a JOIN request, or null when the proof is admissible.
     * Other actions need no proof and return null. The precedence is:
     * `join proof required` (no JOIN event and no `proof` field at all),
     * `invalid join proof` (bad body, wrong author/circle, a forged or
     * unparseable signature, or a nonce that does not match this host, circle,
     * invite and member), `join proof replay` (a bucket other than the host's
     * current one, the same proof presented twice in one request, a digest
     * already reserved in this process, or a full replay set).
     *
     * The signature check is [verifyProofSignature], which does NOT pin: a
     * validly self-signed proof with a wrong invite, bucket or nonce must leave
     * the pin store untouched. An embedded certificate is bound to its OWN
     * SHA-256 DER before the signature is checked, and is pinned only after the
     * full gate accepts and the single-use reservation succeeds. Single-use
     * state is keyed on the proof's domain-tagged digest — not the event id,
     * which a re-signer can change — in the same bounded set as
     * [evaluate]/[fresh]; that reservation fails closed when the set is full of
     * live entries. This never changes canonical bytes, TYPES, create, sign or
     * verify.
     */
    fun joinProofFailure(req: CircleRequest, hostFp: String, nowMs: Long): String? {
        if (req.action != CircleRequest.ACTION_JOIN) return null
        val joins = req.events.filter { it.type == CircleEvent.JOIN }
        if (joins.isEmpty()) return "join proof required"
        if (joins.none { bodyObject(it.body)?.containsKey("proof") == true }) return "join proof required"

        val member = req.memberFp.trim().uppercase(Locale.ROOT)
        val circle = req.circleId.trim().uppercase(Locale.ROOT)
        val proof = joins.firstOrNull {
            it.authorFp.trim().uppercase(Locale.ROOT) == member &&
                it.circleId.trim().uppercase(Locale.ROOT) == circle &&
                validJoinProofBody(it.body)
        } ?: return "invalid join proof"

        val (signatureOk, certToPin) = verifyProofSignature(proof)
        if (!signatureOk || nowMs <= 0L) return "invalid join proof"
        val bucket = joinProofBucket(proof.createdMs)
        val current = joinProofBucket(nowMs)
        val lower = if (current > Long.MIN_VALUE + 1L) current - 1L else current
        val upper = if (current < Long.MAX_VALUE - 1L) current + 1L else current
        if (bucket < lower || bucket > upper) return "join proof replay"
        val expected = joinProofDigest(hostFp, req.circleId, req.invite, req.memberFp, bucket)
        val presented = joinProofBytes(proof.body) ?: return "invalid join proof"
        if (!MessageDigest.isEqual(expected, presented)) return "invalid join proof"
        if (joins.count { it.id == proof.id && it.body == proof.body } > 1) return "join proof replay"
        // A captured proof is a bearer token until its bucket rolls over.
        // Reserve the DOMAIN-TAGGED PROOF DIGEST, not the carrying event id:
        // re-signing with a fresh id reproduces the same 32 bytes, so an
        // id-keyed reservation would admit the same proof again. The shared
        // bounded set now fails closed with false when it is full of live ids.
        val proofKey = JOIN_PROOF_TAG + ":" + presented.joinToString("") { "%02X".format(it) }
        if (!fresh(proofKey, nowMs)) return "join proof replay"
        // Pin only now: every gate accepted and the proof is single-use. A
        // refused proof never writes a pin under any path above.
        certToPin?.let { TlsIdentity.pin(it) }
        return null
    }

    /**
     * Production replay guard: atomic check-and-record over ONE bounded id
     * set shared by [accept] and [fresh]. Decisions are checked in this exact
     * order: [Decision.Invalid] ([validShape] failed), [Decision.OutsideWindow]
     * (clock), [Decision.Duplicate] (id already reserved), [Decision.Full] (all
     * slots still live), [Decision.Accept]. Only Accept records the id, so a
     * refused event can be retried after the cause is fixed; a second Accept
     * can never happen for the same id.
     *
     * Reservations carry a reserved-until deadline of `now + [RETAIN_MS]`
     * (saturating at [Long.MAX_VALUE]). [purgeExpiredLocked] removes ONLY
     * expired entries, never a live one, so a flood of fresh ids cannot drop
     * the replay record of an event that still passes the window.
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
        return synchronized(seen) { reserveLocked(e.id, nowMs) }
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
     * Key-only form of the same bounded reservation: true only the first time
     * a key is presented, false for a blank/oversized key, a repeat, or a full
     * set of live reservations. It uses the SAME set as [evaluate]/[accept] —
     * reserving a key here makes a later event with that id a
     * [Decision.Duplicate], and vice versa. JOIN proofs reserve their
     * domain-tagged digest here; saturation fails closed as a replay refusal.
     * Use it for caller-owned keys that have no signed event yet; never as a
     * replacement for [evaluate]'s shape/window checks on a real event.
     */
    fun fresh(id: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(seen) {
        if (id.isBlank() || id.length > MAX_FIELD_CHARS) return@synchronized false
        reserveLocked(id, nowMs) == Decision.Accept
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
