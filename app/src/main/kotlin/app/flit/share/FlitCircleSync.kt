package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.CourierParcel
import app.flit.share.protocol.courier.Decision
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * Wire-body schemas used by the Circle event log. `body` is an opaque JSON
 * string to CircleCore; these builders are the single place that defines what
 * "JSON for that type's fields" means on this side. They are deliberately
 * pure so the shape can be tested without an Android context or a network.
 *
 * A `file` event is a REFERENCE only — name/size/sha256, and nothing else.
 * The sender's local spool path (the queue token) is deliberately NEVER on
 * the wire. Bytes are pushed to members separately with `sendFiles`; sha256
 * only lets a receiver correlate a landed file with its card locally. There
 * is no pull-by-hash in v1 — a later joiner sees the card but not the bytes.
 */

/** Minimum spacing between two [FlitCircleSync.sync] passes — no busy polling. */
internal const val CIRCLE_SYNC_MIN_INTERVAL_MS = 30_000L

/**
 * Per-peer retry backoff, capped and reset on any answer. The first recorded
 * refusal is attempt 1, so real delays are 10s, 20s, 40s, 80s, 120s; the 5s
 * base is the zero-attempt value used by the pure helper and its tests.
 */
internal const val CIRCLE_BACKOFF_BASE_MS = 5_000L
internal const val CIRCLE_BACKOFF_MAX_MS = 120_000L

internal fun circleBackoffMs(attempt: Int): Long {
    val n = attempt.coerceIn(0, 5)
    return minOf(CIRCLE_BACKOFF_BASE_MS * (1L shl n), CIRCLE_BACKOFF_MAX_MS)
}

/** Next Lamport value for an author: one past the highest value seen locally. */
internal fun circleNextLamport(values: List<Long>): Long = (values.maxOrNull() ?: 0L) + 1L

/** One spelling for fingerprints everywhere (roster, backoff, request headers). */
internal fun circleFp(fp: String): String = fp.trim().uppercase(Locale.ROOT)

/**
 * Dialable peers of a local view: normalised, deduplicated, never ourselves.
 * The store drops departed members, so a kicked/left device stops being a
 * fan-out target as soon as its event is folded locally.
 */
internal fun circlePeers(state: CircleState, me: String): List<String> {
    val self = circleFp(me)
    return state.members.map { circleFp(it.fp) }
        .filter { it.isNotBlank() && it != self }
        .distinct()
}

/**
 * Pure peer target selection, shared by posts and the calm repair tick. A
 * peer we just failed to reach is skipped until its backoff expires; the calm
 * [FlitCircleSync.sync] pass then retries it with the full bounded log. This
 * keeps a post (or a tick) from hammering an offline device — and makes the
 * decision testable without a socket.
 */
internal fun circleFanoutTargets(
    state: CircleState,
    me: String,
    nowMs: Long,
    retryAt: Map<String, Long>,
): List<String> = circlePeers(state, me).filter { nowMs >= (retryAt[it] ?: 0L) }

/** Hard ceiling on simultaneous peer dials (fan-out and repair share it). */
internal const val CIRCLE_PEER_DIALS = 4

/**
 * What a peer acknowledged: the largest event of the batch it answered OK to.
 * `(lamport, id)` is the stable identity used for delta selection, so a tie
 * cannot make two devices disagree about what was already delivered.
 */
internal data class CircleWatermark(val lamport: Long, val id: String) {
    companion object {
        fun of(event: CircleEvent): CircleWatermark = CircleWatermark(event.lamport, event.id)
    }
}

/**
 * Pure delta selection. An unseen peer (or an ack whose event has since been
 * trimmed out of the bounded log) gets a bounded full log; otherwise only the
 * events after the watermark are offered. The cap also protects against a
 * hand-edited, oversized state snapshot.
 */
internal fun circleDelta(
    log: List<CircleEvent>,
    watermark: CircleWatermark?,
    limit: Int = CircleCore.MAX_EVENTS_PER_REQUEST,
): List<CircleEvent> {
    if (log.isEmpty() || limit <= 0) return emptyList()
    if (watermark == null || log.none { it.id == watermark.id }) return log.takeLast(limit)
    return CircleCore.order(log).filter { event ->
        event.lamport > watermark.lamport ||
            (event.lamport == watermark.lamport && event.id > watermark.id)
    }.takeLast(limit)
}

/** Bounded full-log batch used only by the calm repair pass. */
internal fun circleRepairBatch(events: List<CircleEvent>): List<CircleEvent> =
    events.takeLast(minOf(CircleCore.MAX_EVENTS_PER_REQUEST, FlitCircleStore.EVENT_CAP))

/** The ack recorded after an OK answer: the greatest event actually offered. */
internal fun circleAckOf(batch: List<CircleEvent>): CircleWatermark? =
    CircleCore.order(batch).lastOrNull()?.let(CircleWatermark::of)

/**
 * Strict shape for an event WE mint. A malformed body must never be signed,
 * stored or fanned out — a bad vote or kick would otherwise travel to every
 * peer as a perfectly valid signature over unusable data. The rules mirror
 * the store's reducers; a body they cannot act on is refused here, honestly.
 */
internal fun circleBodyValid(type: String, body: String): Boolean {
    if (body.isBlank() || body.length > CircleCore.MAX_BODY_CHARS) return false
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
    fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    fun number(key: String): Int? = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()
    fun flag(key: String): Boolean? = (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    val target = text("targetFp") ?: text("target")
    return when (type.trim().lowercase(Locale.ROOT)) {
        CircleEvent.MESSAGE -> text("text") != null
        // A file announcement carries only the legacy {name, size, sha256}
        // set or the keyed set {name, size, sha256, keyGen, cipherSha, iv,
        // wraps}. Both are exact, closed key sets owned by CircleCore, which
        // also bounds every wrap value by MAX_WRAPPED_CHARS. The sender's local
        // spool path (the queue token) is never minted into a signed, fanned-
        // out event. Bytes are pushed with `sendFiles`, not fetched by this
        // digest; sha256 only matches a landed file to its card locally.
        CircleEvent.FILE -> CircleCore.validFileBody(body)
        CircleEvent.POLL -> text("pollId") != null && text("question") != null &&
            (obj["options"] as? JsonArray)?.isNotEmpty() == true
        CircleEvent.VOTE -> (text("pollId") != null && number("option") != null) ||
            (text("kickId") != null && flag("approve") != null) ||
            (target != null && flag("approve") != null)
        CircleEvent.JOIN, CircleEvent.LEAVE -> true // alias / memberFp are optional
        CircleEvent.KICK -> target != null
        CircleEvent.ROLE -> target != null &&
            (text("role")?.uppercase(Locale.ROOT)?.let {
                it == "OWNER" || it == "ADMIN" || it == "MEMBER"
            } == true || flag("admin") != null)
        CircleEvent.REKEY -> (text("keyB64") != null || text("key") != null) &&
            (number("gen") ?: number("rekeyGen")) != null
        // The tenth verb carries ONLY {toFp, envelope}. The closed key set,
        // field bounds and envelope cap belong to the protocol carry core;
        // this gate delegates to it and never restates those rules.
        CircleEvent.PARCEL -> CourierCarryCore.validParcelBody(body)
        // A delivery receipt carries ONLY the exact {dropId, sha256, keyId}
        // set landed from a keyed file event — no landedMs, path, status or
        // key material. The closed key set, string-primitive and field/body
        // bounds belong to CircleCore; this gate delegates to it and never
        // restates those rules.
        CircleEvent.RECEIPT -> CircleCore.validReceiptBody(body)
        // A pad carries one of two closed shapes from PadCore. The gate and its
        // builders live in that one JVM-pure file, so this side only delegates;
        // no route, header or wire field is added for the pad verb.
        CircleEvent.PAD -> PadCore.validPadBody(body)
        else -> false
    }
}

/** Inbound gate: one bounded JSON object. Semantics remain the store's job. */
internal fun circleBodyReadable(body: String): Boolean =
    body.isNotBlank() && body.length <= CircleCore.MAX_BODY_CHARS &&
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() != null

internal fun circleVoteBody(pollId: String, option: Int): String = buildJsonObject {
    put("pollId", pollId)
    put("option", option)
}.toString()

internal fun circlePollBody(id: String, question: String, options: List<String>): String = buildJsonObject {
    put("pollId", id)
    put("question", question)
    putJsonArray("options") { options.forEach { add(JsonPrimitive(it)) } }
}.toString()

/** Wire body for a file announcement: name/size/sha256 only — no local path. */
internal fun circleFileBody(name: String, size: Long, sha: String): String = buildJsonObject {
    put("name", name)
    put("size", size)
    put("sha256", sha)
}.toString()

/**
 * Wire body for a courier parcel: exactly `toFp` and the Base64 envelope.
 * No sha, name, size, spool path or queue token is ever added here — the inner
 * events are sealed inside the envelope and this carrier cannot read them.
 */
internal fun circleParcelBody(toFp: String, envelopeB64: String): String = buildJsonObject {
    put("toFp", circleFp(toFp))
    put("envelope", envelopeB64)
}.toString()

internal fun circleKickOpenBody(kickId: String, targetFp: String): String = buildJsonObject {
    put("kickId", kickId)
    put("targetFp", targetFp)
    put("approve", true)
    put("mode", "vote")
}.toString()

internal fun circleKickVoteBody(kickId: String, targetFp: String, approve: Boolean): String = buildJsonObject {
    put("kickId", kickId)
    put("targetFp", targetFp)
    put("approve", approve)
}.toString()

/** Mirror of a kick answer as a `kick` event, for CircleCore.kickTally. */
internal fun circleKickVoteEventBody(kickId: String, targetFp: String, approve: Boolean): String = buildJsonObject {
    put("kickId", kickId)
    put("targetFp", targetFp)
    put("approve", approve)
    put("mode", "vote")
}.toString()

internal fun circleKickExecuteBody(kickId: String, targetFp: String): String = buildJsonObject {
    put("kickId", kickId)
    put("targetFp", targetFp)
    put("mode", "admin")
}.toString()

internal fun circleRekeyBody(keyB64: String, gen: Int, remaining: List<String>): String = buildJsonObject {
    put("keyB64", keyB64)
    put("gen", gen)
    putJsonArray("members") { remaining.forEach { add(JsonPrimitive(it)) } }
}.toString()

internal fun circleLeaveBody(fp: String): String = buildJsonObject {
    put("memberFp", fp)
}.toString()

/** Owner-only role change, published as a signed `role` event. */
internal fun circleRoleBody(targetFp: String, role: FlitCircleStore.Role): String = buildJsonObject {
    put("targetFp", circleFp(targetFp))
    put("role", role.name)
}.toString()

/**
 * Resolve the target of an open vote-kick from the local log. `voteKick` is
 * handed only a kickId, but both tallies need the target fingerprint too.
 */
internal fun circleKickTarget(events: List<CircleEvent>, kickId: String): String? {
    val id = kickId.trim()
    val match = events.firstOrNull { event ->
        if (!event.type.equals(CircleEvent.KICK, ignoreCase = true)) return@firstOrNull false
        val body = runCatching { Json.parseToJsonElement(event.body).jsonObject }.getOrNull() ?: return@firstOrNull false
        body["kickId"]?.let { (it as? JsonPrimitive)?.content == id } == true || event.id == id
    } ?: return null
    return runCatching { Json.parseToJsonElement(match.body).jsonObject["targetFp"] }
        .getOrNull()
        ?.let { (it as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
}

/**
 * Circle sync worker — no UI, no filesystem transfer, no timers of its own.
 *
 * Dialling is delegated to [FlitEngine.circleDial], which picks Wi‑Fi,
 * Bluetooth or the blind relay per known-device policy. Transport is the
 * additive circle route on the protocol client (`circleJoin` / `circleSync`).
 * The durable log lives in [FlitCircleStore]; this object never keeps its own
 * copy of the events, so the engine's StateFlows and the store cannot diverge.
 *
 * Every public entry point is a suspend function that is safe to call from a
 * single engine-owned coroutine. Failures are swallowed (a peer being asleep
 * is normal in an offline-first group); a per-peer backoff shared by the pull
 * worker and the outbound fan-out keeps repeated calls from ever hammering a
 * device that is offline. Posts carry only the delta since that peer last
 * acknowledged an offered event; delivery stays self-healing because the calm
 * [sync] tick re-offers the bounded full log as a repair pass. Dial exchanges
 * are admitted through a small semaphore so a large roster cannot open an
 * unbounded number of concurrent connections.
 */
object FlitCircleSync {

    private val nextDialAt = ConcurrentHashMap<String, Long>()
    private val misses = ConcurrentHashMap<String, Int>()
    /** Last acked event per peer; never read on the wire, only for delta math. */
    private val watermarks = ConcurrentHashMap<String, CircleWatermark>()
    private val peerGate = Semaphore(CIRCLE_PEER_DIALS)
    @Volatile private var nextSyncAt = 0L
    @Volatile private var cachedIdentity: TlsIdentity? = null

    /** Parcel event id → file shas learned when this device sealed the events. */
    private val parcelShas = ConcurrentHashMap<String, List<String>>()

    private const val COURIER_WAITING = "waiting"
    private const val COURIER_HANDED = "handed"
    private const val COURIER_FAILED = "failed"

    // ==== injectable seams (internal, null in production) ========================

    /**
     * One peer carrier, as seen by this worker. [dialHook] supplies a fake in
     * pure JVM tests; the production implementation delegates to the engine's
     * real [FlitEngine.circleDial] and protocol client. No socket in the seam.
     */
    internal class Route(
        val peer: String,
        val sync: suspend (CircleRequest) -> CircleResponse?,
        val join: suspend (CircleRequest) -> CircleResponse?,
    )

    internal var dialHook: (suspend (String) -> Route?)? = null
    internal var stateHook: (() -> CircleState?)? = null
    internal var meHook: (() -> String?)? = null
    internal var clockHook: (() -> Long)? = null
    internal var logHook: ((String) -> Unit)? = null

    /**
     * Test seam for the commit at the end of [join]. Production persists atomically
     * through the store; a pure test observes the exact state that would be saved
     * without needing an Android context or SharedPreferences.
     */
    internal var joinPersistHook: ((CircleState) -> Unit)? = null

    /** Signing identity for pure tests; production reads the persisted keystore. */
    internal var identityHook: (() -> TlsIdentity?)? = null
    /** Observes events that would be upserted into the signed log (pure tests). */
    internal var upsertHook: ((List<CircleEvent>) -> Unit)? = null
    /** Receives the encoded bag on every write; null in production uses the store. */
    internal var bagPersistHook: ((String) -> Unit)? = null
    /** Receives a normalised sha and one of waiting/handed/failed. The engine owns the map. */
    internal var handoffHook: ((String, String) -> Unit)? = null
    /** Receives a typed handoff built from an opened file event (pure tests). */
    internal var handoffPublishHook: ((CourierCardUi.CourierHandoff) -> Unit)? = null

    /** Test-only: drop fakes and rate-limit state so tests cannot leak into each other. */
    internal fun resetForTest() {
        dialHook = null
        stateHook = null
        meHook = null
        clockHook = null
        logHook = null
        joinPersistHook = null
        identityHook = null
        upsertHook = null
        bagPersistHook = null
        handoffHook = null
        handoffPublishHook = null
        parcelShas.clear()
        nextDialAt.clear()
        misses.clear()
        watermarks.clear()
        nextSyncAt = 0L
    }

    private suspend fun dial(fp: String): Route? {
        dialHook?.let { return it(fp) }
        val route = FlitEngine.circleDial(fp) ?: return null
        return Route(
            peer = fp,
            sync = { request -> route.client.circleSync(route.target, request) },
            join = { request -> route.client.circleJoin(route.target, request) },
        )
    }

    private fun storedState(fallback: CircleState? = null): CircleState? {
        stateHook?.invoke()?.let { return it }
        val ctx = context() ?: return fallback
        return FlitCircleStore.load(ctx) ?: fallback
    }

    private fun meFp(): String? = meHook?.invoke()?.let(::circleFp)
        ?: identity()?.fingerprint?.let(::circleFp)

    private fun nowMs(): Long = clockHook?.invoke() ?: System.currentTimeMillis()

    private fun log(line: String) {
        val hook = logHook
        if (hook != null) hook(line) else FlitEngine.logLine(line)
    }

    /**
     * Run one dial/exchange per peer with at most [CIRCLE_PEER_DIALS] in flight.
     * Permits are taken in target order and children start undispatched, so
     * the dial order stays deterministic even when the caller runs on a thread
     * pool; a permit is held from before the dial until the answer is handled.
     */
    private suspend fun <T> dialBounded(
        peers: List<String>,
        exchange: suspend (String) -> T,
    ): List<T> = coroutineScope {
        peers.map { fp ->
            peerGate.acquire()
            async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    exchange(fp)
                } finally {
                    peerGate.release()
                }
            }
        }.awaitAll()
    }

    // ==== public suspend API ====================================================

    /** Join a founded Circle from its QR code (bearer invite, no key in the QR). */
    suspend fun join(code: QrAdd) {
        // The host only supplies circle data. Our own fingerprint comes from
        // meFp(), which already prefers the test seam and otherwise reads the
        // persisted identity — no separate signing identity is needed to join.
        val me = meFp() ?: return
        val host = circleFp(code.fingerprint)
        val circleId = code.circle?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (host.isBlank()) return

        val route = dial(host) ?: return
        val request = CircleRequest(
            action = CircleRequest.ACTION_JOIN,
            circleId = circleId,
            invite = code.invite.orEmpty(),
            memberFp = me,
            events = emptyList(),
            body = "",
        )
        val response = runCatching { route.join(request) }.getOrNull() ?: return
        if (!response.ok || response.key.isBlank()) return
        // A rogue relay or on-path peer must not be able to substitute the
        // group key: the answer is signed by the host named in the QR.
        // Protocol suites own signature/signing verification; the pure sync
        // seam returns synthetic answers with no Android keystore.
        if (joinPersistHook == null && !CircleResponse.verify(response, host)) {
            log("circle: host answer was not signed by ${host.take(12)} — not joined")
            return
        }
        // Only now, after the host proved itself and accepted the invite, may
        // this device persist the bearer token.
        // Pure JVM tests use joinPersistHook and have no Android runtime.
        if (joinPersistHook == null) FlitEngine.circleInviteAccepted(code.invite.orEmpty())

        // The host's roster is authoritative: never drop the host (or any
        // other member) from our dial list just because a seed event failed
        // to fold. An empty answer still leaves the QR's host as a target.
        val roster = response.members.map { member ->
            Member(
                fp = circleFp(member),
                alias = "",
                role = if (circleFp(member) == host) Role.OWNER else Role.MEMBER,
            )
        }
            .distinctBy { it.fp }
            .let { list ->
                val withHost = if (list.any { it.fp == host }) list else list + Member(host, "", Role.OWNER)
                if (withHost.any { it.fp == me }) withHost else withHost + Member(me, "", Role.MEMBER)
            }
        // Local-only bookkeeping is NOT part of the host's answer. Rejoining
        // replaces the authoritative circle fields (id/key/roster/events/gen)
        // but must keep delivery rows keyed by sha, so a byte push that already
        // landed is not forgotten, and the carried parcel bag, so undelivered
        // parcels survive the rejoin. selfFp is this device's identity, set below.
        val local = storedState()
        val joined = CircleState(
            id = circleId,
            keyB64 = response.key,
            role = Role.MEMBER,
            members = roster,
            events = emptyList(),
            rekeyGen = 0,
            // Exact identity, not the store's legacy uniqueness inference: the
            // owner's role changes must track this device unambiguously.
            selfFp = me,
            deliveries = local?.deliveries.orEmpty(),
            courierBag = local?.courierBag.orEmpty(),
        )
        // Fold seed events through the store's reducers so the roster and the
        // rekey generation agree with what the host's log actually says.
        val seeded = FlitCircleStore.merge(joined, fresh(joined, response.events))
        val persist = joinPersistHook
        if (persist != null) {
            persist(seeded)
            return
        }
        val ctx = context() ?: return
        // Atomic commit: re-read local-only state under the store lock so a
        // delivery or bag write landing between the host answer and this commit
        // is not clobbered. First join has no row to update, so fall back to save.
        val committed = FlitCircleStore.update(ctx) { current ->
            seeded.copy(deliveries = current.deliveries, courierBag = current.courierBag)
        }
        if (committed == null) FlitCircleStore.save(ctx, seeded)
    }

    /**
     * Calm-tick repair: re-offer the bounded full log to every reachable
     * member and pull whatever it has for us. Unlike [fanout] this ignores
     * watermarks on purpose — a peer that missed a delta is healed here.
     * Backed-off peers are skipped, exactly as in fan-out, and retried on a
     * later tick once their backoff expires.
     */
    suspend fun sync() {
        val seed = storedState() ?: return
        val me = meFp() ?: return
        val now = nowMs()
        if (now < nextSyncAt) return
        nextSyncAt = now + CIRCLE_SYNC_MIN_INTERVAL_MS

        val peers = circlePeers(seed, me)
        val targets = circleFanoutTargets(seed, me, now, nextDialAt)
        val skipped = peers.size - targets.size
        log(
            "circle sync: full-log repair to ${targets.size} peer(s)" +
                if (skipped > 0) ", $skipped backed off" else "",
        )
        if (targets.isEmpty()) {
            pruneBackoff(peers.toSet())
            return
        }

        dialBounded(targets) { fp ->
            // Re-read per peer: an earlier answer may have folded new events
            // (or a departure) that the next peer is entitled to see.
            val state = storedState(seed) ?: seed
            val full = circleRepairBatch(state.events)
            val selected = parcelEventsFor(state, fp, now)
            val offered = (full + selected.map { it.event }).distinctBy { it.id }
                .take(CircleCore.MAX_EVENTS_PER_REQUEST)
            val request = CircleRequest(
                action = CircleRequest.ACTION_SYNC,
                circleId = state.id,
                invite = "",
                memberFp = me,
                events = offered,
                body = "",
            )
            val route = dial(fp)
            if (route == null) {
                log("circle sync: no route to ${fp.take(12)}")
                concludeHandoff(selected, ok = false)
                noteFailure(fp, now)
                return@dialBounded
            }
            val response = runCatching { route.sync(request) }.getOrNull()
            if (response == null || !response.ok) {
                log("circle sync: ${fp.take(12)} refused ${response?.reason ?: "no answer"}")
                concludeHandoff(selected, ok = false)
                noteFailure(fp, now)
                return@dialBounded
            }
            noteSuccess(fp)
            // The repair was accepted in full, so later posts may go delta-only.
            circleAckOf(full)?.let { watermarks[fp] = it }
            concludeHandoff(selected, ok = true)
            courierReceive(state, response.events)
            if (response.events.isNotEmpty()) {
                log("circle sync: +${response.events.size} event(s) from ${fp.take(12)}")
            }
        }
        pruneBackoff(circlePeers(storedState(seed) ?: seed, me).toSet())
    }

    /**
     * Sign, store and fan out one raw event body. [type] is one of the
     * CircleCore event kinds; [body] must be a small JSON object of that
     * kind's fields. [body] is otherwise passed through untouched.
     */
    suspend fun post(type: String, body: String) {
        val state = storedState() ?: return
        val event = emit(context(), state, type, body) ?: return
        fanout(state, listOf(event))
    }

    /**
     * Publish one pad line edit as a signed `pad` event. The body comes only
     * from [PadCore.padPutBody], so the closed shape and the key/text bounds
     * stay owned by the pure core; a refusal ends here before anything is
     * signed. Signing, persisting and the `/circle/sync` fan-out are exactly
     * [post]'s — this adds no route, header, DTO field or store field.
     */
    internal suspend fun postPadPut(key: String, text: String) {
        val body = runCatching { PadCore.padPutBody(key, text) }.getOrNull() ?: return
        post(CircleEvent.PAD, body)
    }

    /** Publish one pad line deletion; mirrors [postPadPut] with a `del` body. */
    internal suspend fun postPadDel(key: String) {
        val body = runCatching { PadCore.padDelBody(key) }.getOrNull() ?: return
        post(CircleEvent.PAD, body)
    }

    /** Changeable poll answer: a new vote event, the old one stays in history. */
    suspend fun vote(pollId: String, option: Int) {
        post(CircleEvent.VOTE, circleVoteBody(pollId, option))
    }

    /** Publish a poll card. [id] is the id later votes refer to. */
    internal suspend fun poll(id: String, question: String, options: List<String>) {
        post(CircleEvent.POLL, circlePollBody(id, question, options))
    }

    /**
     * Publish a file-drop announcement — name/size/sha256 only, no bytes and
     * no local path. The engine pushes the bytes via `sendFiles` and keeps the
     * spool path local; sha256 only correlates a landed file with this card.
     * No pull-by-hash in v1: a later joiner sees the card but not the bytes.
     */
    internal suspend fun postFile(name: String, size: Long, sha: String) {
        post(CircleEvent.FILE, circleFileBody(name, size, sha))
    }

    /**
     * Publish a caller-built `file` body verbatim, so the engine can announce a
     * keyed drop (DropKeyCore.keyedFileBody) through the same signed path. The
     * body is gated by [circleBodyValid] before it is signed, so only the exact
     * legacy or keyed key set can be posted. The `/circle/sync` route, headers
     * and DTO are unchanged.
     */
    internal suspend fun postFile(body: String) {
        post(CircleEvent.FILE, body)
    }

    // ==== courier handoff path ===================================================

    /**
     * Publish one signed `parcel` over the existing `/circle/sync` route. No new
     * route, header or DTO: the body is exactly `toFp` + `envelope`, gated by the
     * protocol carry core. The file [shas] learned while sealing the envelope are
     * registered against the emitted event id immediately after `emit` and BEFORE
     * `fanout`, so a synchronous OK can publish `handed` for them. Returns the
     * emitted event so the caller can associate it with the parcel.
     */
    internal suspend fun postParcel(
        toFp: String,
        envelopeB64: String,
        shas: List<String> = emptyList(),
    ): CircleEvent? {
        val target = circleFp(toFp)
        val body = circleParcelBody(target, envelopeB64)
        if (!circleBodyValid(CircleEvent.PARCEL, body)) {
            log("courier: invalid parcel body")
            return null
        }
        val state = storedState() ?: return null
        val event = emit(context(), state, CircleEvent.PARCEL, body) ?: return null
        // Register before any dial: a peer can answer OK synchronously during
        // fanout, and concludeHandoff needs the id → shas mapping in place to
        // publish `handed` for those files.
        courierTrack(event.id, shas)
        fanout(state, listOf(event))
        return event
    }

    /**
     * Remember the file shas learned while sealing a parcel, keyed by the
     * emitted `parcel` event id — the same id the bag row and the engine's local
     * map use. Process-local only: never signed, persisted or sent on the wire.
     * A carrier holding only ciphertext registers nothing and publishes no sha.
     */
    internal fun courierTrack(parcelId: String, shas: List<String>) {
        if (parcelId.isBlank()) return
        val keys = shas.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return
        parcelShas[parcelId.trim()] = keys
    }

    /**
     * Fold verified incoming events: signed parcels enter the bounded bag only
     * on an `Accept` decision; when the parcel is addressed to this device its
     * envelope is opened and the decrypted events enter the signed log through
     * the same verify-and-merge path as any other member's events.
     */
    internal suspend fun courierReceive(state: CircleState, events: List<CircleEvent>) {
        val (parcels, others) = events.partition {
            it.type.equals(CircleEvent.PARCEL, ignoreCase = true)
        }
        val accepted = fresh(state, others)
        if (accepted.isNotEmpty()) persistEvents(accepted)
        // A parcel lives in the durable bag, not behind fresh()'s log-id dedup:
        // a peer may re-offer one whose event is already in the signed log after
        // a bag loss. Carry's evaluate dedups by id, so re-folding is inert while
        // a missing bag row is restored.
        // The carrier cannot read the sealed envelope, so the only trustworthy
        // signal for who minted a parcel is the signer of the outer event. Only
        // a CURRENT roster member may inject a bag row — a departed or never-
        // member must be refused even with a valid signature over the right
        // circle and body. Fingerprints are normalised on both sides.
        val authors = state.members.map { circleFp(it.fp) }.toSet()
        parcels.forEach { event ->
            if (event.circleId != state.id) return@forEach
            if (!circleBodyValid(CircleEvent.PARCEL, event.body)) return@forEach
            if (!CircleCore.verify(event)) return@forEach
            if (circleFp(event.authorFp) !in authors) {
                log("courier: refused parcel from non-member ${event.authorFp.take(12)}")
                return@forEach
            }
            CourierCarryCore.parcelOf(event)?.let { foldParcel(it) }
        }
    }

    /** Owner-only appointment/removal of an ADMIN (the store enforces authority). */
    internal suspend fun circleSetRole(targetFp: String, role: FlitCircleStore.Role) {
        // The owner gate lives here as well as in the store reducer: a member
        // must not even publish an inert role event (and fan it to the group).
        if (storedState()?.role != FlitCircleStore.Role.OWNER) return
        post(CircleEvent.ROLE, circleRoleBody(targetFp, role))
    }

    /** Open a vote-kick. The body's `kickId` is what others vote on. */
    suspend fun startKick(targetFp: String) {
        post(CircleEvent.KICK, circleKickOpenBody(UUID.randomUUID().toString(), circleFp(targetFp)))
    }

    /**
     * One member's (changeable) answer to an open vote-kick. Both consumers
     * are served: FlitCircleStore counts `vote` events, CircleCore.kickTally
     * counts `kick` events. Both carry the same signed decision, so the
     * latest-per-member rule holds in each view.
     */
    suspend fun voteKick(kickId: String, approve: Boolean) {
        val ctx = context() ?: return
        val state = FlitCircleStore.load(ctx) ?: return
        val target = circleKickTarget(state.events, kickId) ?: return
        val vote = emit(ctx, state, CircleEvent.VOTE, circleKickVoteBody(kickId, target, approve)) ?: return
        val mirrored = emit(
            ctx, state, CircleEvent.KICK,
            circleKickVoteEventBody(kickId, target, approve),
        )
        fanout(state, listOfNotNull(vote, mirrored))
    }

    /** Admin direct kick, or the execution of a majority vote-kick. */
    suspend fun kick(targetFp: String) {
        post(CircleEvent.KICK, circleKickExecuteBody(UUID.randomUUID().toString(), circleFp(targetFp)))
    }

    /** Full leave: tell the others, then drop our key and feed locally. */
    suspend fun leave() {
        val ctx = context() ?: return
        val identity = identity() ?: return
        if (FlitCircleStore.load(ctx) == null) return
        post(CircleEvent.LEAVE, circleLeaveBody(circleFp(identity.fingerprint)))
        FlitCircleStore.clear(ctx)
    }

    /** Rotate the group key so a departed member cannot read later events. */
    suspend fun rekey(remaining: List<String>) {
        val ctx = context() ?: return
        val state = FlitCircleStore.load(ctx) ?: return
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getEncoder().encodeToString(it) }
        post(
            CircleEvent.REKEY,
            circleRekeyBody(key, state.rekeyGen + 1, remaining.map(::circleFp)),
        )
    }

    // ==== internals =============================================================

    /**
     * Create, sign and persist one event. Lamport is read from the CURRENT
     * store on every call so two events from one action still get distinct
     * clocks. The id is deliberately not fed to [CircleCore.evaluate] here:
     * that check-and-record belongs to the receiving transport, and consuming
     * it locally would make our own fan-out look like a replay.
     */
    private fun emit(
        ctx: android.content.Context?,
        state: CircleState,
        type: String,
        body: String,
    ): CircleEvent? {
        if (type.trim().lowercase(Locale.ROOT) !in CircleEvent.TYPES) return null
        if (!circleBodyValid(type, body)) return null
        val identity = identity() ?: return null
        val current = ctx?.let { FlitCircleStore.load(it) } ?: state
        val event = CircleCore.create(
            circleId = current.id,
            authorFp = circleFp(identity.fingerprint),
            identity = identity,
            type = type.trim().lowercase(Locale.ROOT),
            body = body,
            lamport = circleNextLamport(current.events.map { it.lamport }),
            createdMs = System.currentTimeMillis(),
        )
        if (!CircleCore.verify(event)) return null
        persistEvents(listOf(event))
        return event
    }

    /** One commit of verified events to the signed log, with a pure-test seam. */
    private fun persistEvents(events: List<CircleEvent>) {
        if (events.isEmpty()) return
        val hook = upsertHook
        if (hook != null) {
            hook(events)
            return
        }
        context()?.let { FlitCircleStore.upsert(it, events) }
    }

    // ==== courier internals =====================================================

    private fun bagFromRaw(raw: String?): CarryState {
        val decoded = CourierCarryCore.decode(raw) ?: CarryState()
        if (!raw.isNullOrBlank() && decoded.parcels.isEmpty()) {
            log("courier: bag row unreadable, starting empty")
        }
        return decoded
    }

    private fun bagOf(state: CircleState?): CarryState =
        bagFromRaw(state?.courierBag)

    /** Atomic bag write: decode → fold → encode → persist under one store lock. */
    private fun updateBag(transform: (CarryState) -> CarryState): CarryState? {
        val hook = bagPersistHook
        if (hook != null) {
            val next = transform(bagFromRaw(stateHook?.invoke()?.courierBag))
            hook(CourierCarryCore.encode(next))
            return next
        }
        val ctx = context() ?: return null
        val updated = FlitCircleStore.update(ctx) { state ->
            val next = transform(bagFromRaw(state.courierBag))
            state.copy(courierBag = CourierCarryCore.encode(next))
        } ?: return null
        return bagFromRaw(updated.courierBag)
    }

    /**
     * Publish a process-local outcome change for an already published sha. The
     * engine owns the map and wires this seam while it is live; a pure test
     * observes through [handoffHook]. With no hook there is no publication, so
     * a stopped engine or a left circle cannot publish stale outcomes.
     */
    private fun publish(sha: String, outcome: String) {
        val key = sha.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return
        handoffHook?.invoke(key, outcome)
    }

    /** Publish a newly learned file event through the engine's typed seam. */
    private fun publishTyped(handoff: CourierCardUi.CourierHandoff) {
        handoffPublishHook?.invoke(handoff)
    }

    /** Verify → evaluate → fold, with expiry decided only by the carry core. */
    private fun foldParcel(parcel: CourierParcel) {
        var decision: Decision? = null
        updateBag { current ->
            decision = CourierCarryCore.evaluate(parcel, current.parcels.size, nowMs())
            if (decision == Decision.Accept) CourierCarryCore.fold(current, parcel) else current
        }
        when (decision) {
            Decision.Accept -> {
                log("courier: holding for ${parcel.toFp.take(12)}")
                if (parcel.toFp.equals(meFp().orEmpty(), ignoreCase = true)) openAtMe(parcel)
            }
            Decision.Duplicate -> log("courier: duplicate parcel id")
            Decision.Expired -> log("courier: parcel expired")
            Decision.OutsideWindow -> log("courier: parcel outside window")
            Decision.Full -> log(
                "courier: bag full, ${CourierCarryCore.MAX_PARCELS} parcel(s) held",
            )
            Decision.Invalid -> log("courier: invalid parcel body")
            null -> Unit // no store row to update; no decision to report
        }
    }

    /** Open only a parcel addressed to us; a refusal keeps the bag row. */
    private fun openAtMe(parcel: CourierParcel) {
        val body = runCatching {
            Json.parseToJsonElement(parcel.event.body).jsonObject
        }.getOrNull() ?: return
        val raw = (body["envelope"] as? JsonPrimitive)?.content
        val envelope = raw?.let { CourierEnvelope.decode(it) }
        val identity = identity()
        if (envelope == null || identity == null) {
            log("courier parcel unreadable — rejected")
            return
        }
        val inner = CourierEnvelope.open(envelope, identity, nowMs())
        if (inner.isNullOrEmpty()) {
            log("courier parcel unreadable — rejected")
            return
        }
        val state = storedState() ?: return
        // The envelope is ciphertext to every carrier but us, yet its author is
        // still untrusted once opened: a member could seal self-signed JOIN or
        // REKEY events and rewrite our roster or group key. Only a current
        // member may mint a foldable inner event, and never those two verbs.
        // fresh() still verifies every signature before anything is persisted.
        val authors = state.members.map { circleFp(it.fp) }.toSet()
        val rejected = inner.filterTo(HashSet()) { event ->
            event.circleId != state.id ||
                circleFp(event.authorFp) !in authors ||
                event.type.equals(CircleEvent.JOIN, ignoreCase = true) ||
                event.type.equals(CircleEvent.REKEY, ignoreCase = true)
        }
        if (rejected.isNotEmpty()) {
            log("courier: rejected ${rejected.size} opened event(s) — foreign circle, " +
                "non-member author or privileged verb")
        }
        val accepted = fresh(state, inner.filterNot { it in rejected })
        if (accepted.isEmpty()) return
        persistEvents(accepted)
        accepted.forEach { event ->
            if (event.type != CircleEvent.FILE) return@forEach
            val obj = runCatching {
                Json.parseToJsonElement(event.body).jsonObject
            }.getOrNull() ?: return@forEach
            val sha = (obj["sha256"] as? JsonPrimitive)?.content?.trim()
                ?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return@forEach
            val name = (obj["name"] as? JsonPrimitive)?.content.orEmpty()
            val size = (obj["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            publishTyped(
                CourierCardUi.CourierHandoff(
                    sha256 = sha,
                    name = name,
                    size = size,
                    toFp = parcel.toFp,
                    courierFp = parcel.fromFp,
                    fromFp = envelope.fromFp,
                    outcome = COURIER_WAITING,
                ),
            )
        }
        log("courier: opened ${accepted.size} event(s) from ${parcel.toFp.take(12)}")
    }

    /** Parcels addressed to [peer] and still live, deterministically ordered. */
    private fun parcelEventsFor(
        state: CircleState,
        peer: String,
        now: Long,
    ): List<CourierParcel> = CourierCarryCore.select(bagOf(state), peer, now)

    /**
     * Conclude offered parcels only after an OK answer: `ack` drops the bag
     * rows and the local outcome becomes `handed`; a refusal or a throw leaves
     * every bag row untouched and the outcome `failed`. Parcels this process
     * never sealed have no sha, so they publish nothing.
     */
    private fun concludeHandoff(selected: List<CourierParcel>, ok: Boolean) {
        if (selected.isEmpty()) return
        val outcome = if (ok) COURIER_HANDED else COURIER_FAILED
        if (ok) updateBag { CourierCarryCore.ack(it, selected.map { it.id }) }
        selected.forEach { parcel ->
            if (ok) {
                parcelShas.remove(parcel.id)?.forEach { sha -> publish(sha, outcome) }
            } else {
                parcelShas[parcel.id]?.forEach { sha -> publish(sha, outcome) }
            }
        }
        log("courier: ${if (ok) "handed off" else "handoff failed for"} " +
            "${selected.size} parcel(s)")
    }

    /**
     * Verified, in-window, deduplicated and deterministically ordered events.
     *
     * Incoming dedup is against the local log, not a one-shot global
     * reservation: `CircleCore.evaluate` consumes ids, so using it here would
     * silently drop an event whose persistence failed on an earlier tick.
     * Bodies must parse as one bounded JSON object before a reducer sees them.
     */
    internal fun fresh(state: CircleState, events: List<CircleEvent>): List<CircleEvent> =
        CircleCore.dedup(CircleCore.order(events.filter { event ->
            event.circleId == state.id &&
                (event.type != CircleEvent.FILE || CircleCore.validFileBody(event.body)) &&
                (event.type != CircleEvent.PARCEL || CourierCarryCore.validParcelBody(event.body)) &&
                circleBodyReadable(event.body) &&
                CircleCore.verify(event) &&
                state.events.none { it.id == event.id }
        })).take(FlitCircleStore.EVENT_CAP)

    /**
     * Best-effort delivery to every remaining member. Each peer gets only the
     * events newer than the last batch it acknowledged, so a post never puts
     * the whole bounded log back on the wire. An OK answer advances that
     * peer's watermark; a refusal leaves it untouched (and applies backoff),
     * so the missing delta is retried — or repaired by [sync]'s full-log pass.
     * An empty delta still makes one tiny probe: it keeps the watermark honest
     * and lets us fold any answer the peer has for us.
     *
     * Failures are recorded in the same per-peer backoff the pull worker uses;
     * a later attempt waits its turn instead of hammering a sleeping device.
     * Dials are bounded by [CIRCLE_PEER_DIALS] rather than roster size.
     *
     * The state snapshot passed in by the caller is used only to detect a
     * stale circle id — the roster, id and event delta all come from a fresh
     * store read, after [emit] persisted the new event.
     */
    internal suspend fun fanout(state: CircleState, events: List<CircleEvent>) {
        if (events.isEmpty()) return
        val me = meFp() ?: return
        val current = storedState(state) ?: return
        if (current.id != state.id) return // a rotated circle must not get old wire data

        val now = nowMs()
        val targets = circleFanoutTargets(current, me, now, nextDialAt)
        val skipped = circlePeers(current, me).size - targets.size
        // Stable evidence line: the device walk needs to see this path ran.
        log(
            "circle fan-out: ${events.size} new event(s), ${targets.size} peer(s)" +
                if (skipped > 0) ", $skipped backed off" else "",
        )
        if (targets.isEmpty()) return

        dialBounded(targets) { fp ->
            // Per-peer delta: two members can be at different points in the log.
            // Re-read so an earlier answer's bag write is visible to this peer.
            val state = storedState(current) ?: current
            val delta = circleDelta(state.events, watermarks[fp])
            val selected = parcelEventsFor(state, fp, now)
            val offered = (delta + selected.map { it.event }).distinctBy { it.id }
                .take(CircleCore.MAX_EVENTS_PER_REQUEST)
            val request = CircleRequest(
                action = CircleRequest.ACTION_SYNC,
                circleId = state.id,
                invite = "",
                memberFp = me,
                events = offered,
                body = "",
            )
            val route = dial(fp)
            if (route == null) {
                log("circle fan-out: no route to ${fp.take(12)}")
                concludeHandoff(selected, ok = false)
                noteFailure(fp, now)
                return@dialBounded
            }
            val resp = runCatching { route.sync(request) }.getOrNull()
            if (resp == null || !resp.ok) {
                log("circle fan-out: ${fp.take(12)} refused ${resp?.reason ?: "no answer"}")
                concludeHandoff(selected, ok = false)
                noteFailure(fp, now)
            } else {
                log("circle fan-out: ok to ${fp.take(12)} (${offered.size} event(s) offered)")
                circleAckOf(delta)?.let { watermarks[fp] = it }
                concludeHandoff(selected, ok = true)
                courierReceive(state, resp.events)
                noteSuccess(fp)
            }
        }
    }

    private fun noteFailure(fp: String, now: Long) {
        val attempt = (misses[fp] ?: 0) + 1
        misses[fp] = attempt
        nextDialAt[fp] = now + circleBackoffMs(attempt)
    }

    private fun noteSuccess(fp: String) {
        misses.remove(fp)
        nextDialAt.remove(fp)
    }

    /**
     * Per-peer bookkeeping for departed members is dead weight; drop it on a
     * tick. A peer that rejoins starts with no watermark and gets a full log.
     */
    private fun pruneBackoff(peers: Set<String>) {
        nextDialAt.keys.toList().forEach { if (it !in peers) nextDialAt.remove(it) }
        misses.keys.toList().forEach { if (it !in peers) misses.remove(it) }
        watermarks.keys.toList().forEach { if (it !in peers) watermarks.remove(it) }
    }

    /** The app context the engine published at boot; null before boot is safe. */
    private fun context(): android.content.Context? = runCatching { FlitEngine.ctx() }.getOrNull()

    /**
     * Same device identity the engine uses — the persisted keystore is the
     * single source of truth, so a signature made here verifies against the
     * fingerprint the QR advertised. Cached per process; no new key material
     * is invented when the engine already loaded one.
     */
    private fun identity(): TlsIdentity? {
        identityHook?.invoke()?.let { return it }
        cachedIdentity?.let { return it }
        return synchronized(this) {
            identityHook?.invoke()?.let { return@synchronized it }
            cachedIdentity?.let { return@synchronized it }
            val dir = context()?.filesDir ?: return@synchronized null
            val file = File(dir, "flit-identity.p12")
            val id = runCatching {
                if (file.exists()) TlsIdentity.load(file) else TlsIdentity.generate().also { it.save(file) }
            }.getOrNull()
            cachedIdentity = id
            id
        }
    }
}
