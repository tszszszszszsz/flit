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

    sealed interface Decision {
        /** Signature is the caller's concern; structurally fresh, safe to store. */
        data object Accept : Decision

        /** Same id seen before: answered politely, never delivered twice. */
        data object Duplicate : Decision

        /** Outside the replay window (too old or implausibly in the future). */
        data object OutsideWindow : Decision

        /** Structurally unusable: blank id/author, bad type, negative clock. */
        data object Invalid : Decision
    }

    private val seen = LinkedHashMap<String, Long>()

    /**
     * Deterministic signing input: a domain tag plus length-prefixed UTF-8
     * fields, so no delimiter can be smuggled through a body. Covers every
     * field except [CircleEvent.sig].
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
     * its own fingerprint so a local [verify] works immediately.
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
        val fp = authorFp.trim().uppercase()
        require(fp.isNotBlank()) { "blank author fingerprint" }
        require(type in CircleEvent.TYPES) { "unknown circle event type: $type" }
        require(body.isNotBlank()) { "blank event body" }
        require(lamport >= 0L) { "negative lamport" }
        TlsIdentity.pin(identity.certificate)
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
        return event.copy(sig = identity.sign(canonical(event)))
    }

    /**
     * Signature check only — no dedup, no replay window. Trust is the pinned
     * certificate whose SHA-256 DER equals the claimed [CircleEvent.authorFp].
     */
    fun verify(e: CircleEvent): Boolean {
        if (e.sig.isBlank()) return false
        if (e.cert.isNotBlank()) {
            val parsed = runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(Base64.getDecoder().decode(e.cert).inputStream())
            }.getOrNull() as? X509Certificate ?: return false
            val fp = runCatching { TlsIdentity.fingerprintOf(parsed) }.getOrNull() ?: return false
            // Self-declared cert must match the author field; pin it for the
            // first-time verifier, then the normal signature check applies.
            if (!fp.equals(e.authorFp, ignoreCase = true)) return false
            TlsIdentity.pin(parsed)
        }
        return TlsIdentity.verify(e.authorFp, canonical(e), e.sig)
    }

    /** Deterministic total order: `(lamport, authorFp, id)`. */
    fun order(events: Iterable<CircleEvent>): List<CircleEvent> =
        events.sortedWith(
            compareBy<CircleEvent> { it.lamport }
                .thenBy { it.authorFp }
                .thenBy { it.id },
        )

    /** Keeps one event per id (the first encountered), replay copies dropped. */
    fun dedup(events: Iterable<CircleEvent>): List<CircleEvent> {
        val ids = HashSet<String>()
        return events.filter { ids.add(it.id) }
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
            val option = int(obj, "option") ?: continue
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
        for (e in all) {
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
     * Atomic check-and-record: an [Decision.Accept] reserves the id in the
     * bounded seen set, so the next presentation is a [Decision.Duplicate].
     * Window checks run before dedup, so an old replay reports OutsideWindow.
     */
    fun evaluate(
        e: CircleEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        if (e.id.isBlank() || e.circleId.isBlank() || e.authorFp.isBlank() ||
            e.lamport < 0L || e.createdMs <= 0L || e.body.isBlank() ||
            e.type !in CircleEvent.TYPES
        ) return Decision.Invalid
        if (e.createdMs < nowMs - REPLAY_WINDOW_MS || e.createdMs > nowMs + CLOCK_SKEW_MS) {
            return Decision.OutsideWindow
        }
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

    /** Alias for [evaluate] — the check-and-record name used by transports. */
    fun accept(
        e: CircleEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision = evaluate(e, nowMs)

    /** Tiny FIFO dedup primitive: true only the first time an id is presented. */
    fun fresh(id: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(seen) {
        if (id.isBlank() || id in seen) return@synchronized false
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

    private fun bodyObject(body: String): JsonObject? =
        runCatching { WireJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull()

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
