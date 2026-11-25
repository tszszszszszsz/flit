package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.tls.TlsIdentity
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
 * A `file` event is a REFERENCE only — name/size/sha/token. The bytes never
 * enter the event log; the existing queue/resume path moves them.
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
 * Pure fan-out target selection. A peer we just failed to reach is skipped
 * until its backoff expires; the calm [FlitCircleSync.sync] pass retries it
 * with the full log instead. This is what keeps a post from hammering an
 * offline device — and it makes the decision testable without a socket.
 */
internal fun circleFanoutTargets(
    state: CircleState,
    me: String,
    nowMs: Long,
    retryAt: Map<String, Long>,
): List<String> = circlePeers(state, me).filter { nowMs >= (retryAt[it] ?: 0L) }

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
        CircleEvent.FILE -> text("name") != null &&
            (obj["size"] as? JsonPrimitive)?.content?.toLongOrNull() != null &&
            text("sha256") != null && text("token") != null
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

internal fun circleFileBody(name: String, size: Long, sha: String, token: String): String = buildJsonObject {
    put("name", name)
    put("size", size)
    put("sha256", sha)
    put("token", token)
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
 * device that is offline. Delivery is self-healing: every POST/SYNC carries
 * the full bounded local log, so a lost one-shot is repaired by the next tick.
 */
object FlitCircleSync {

    private val nextDialAt = ConcurrentHashMap<String, Long>()
    private val misses = ConcurrentHashMap<String, Int>()
    @Volatile private var nextSyncAt = 0L
    @Volatile private var cachedIdentity: TlsIdentity? = null

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

    /** Test-only: drop fakes and rate-limit state so tests cannot leak into each other. */
    internal fun resetForTest() {
        dialHook = null
        stateHook = null
        meHook = null
        clockHook = null
        logHook = null
        nextDialAt.clear()
        misses.clear()
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

    // ==== public suspend API ====================================================

    /** Join a founded Circle from its QR code (bearer invite, no key in the QR). */
    suspend fun join(code: QrAdd) {
        val ctx = context() ?: return
        val identity = identity() ?: return
        val me = circleFp(identity.fingerprint)
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
        )
        // Fold seed events through the store's reducers so the roster and the
        // rekey generation agree with what the host's log actually says.
        FlitCircleStore.save(ctx, FlitCircleStore.merge(joined, fresh(joined, response.events)))
    }

    /** Pull the full log from every other member, once per calm interval. */
    suspend fun sync() {
        val seed = storedState() ?: return
        val me = meFp() ?: return
        val now = nowMs()
        if (now < nextSyncAt) return
        nextSyncAt = now + CIRCLE_SYNC_MIN_INTERVAL_MS

        for (fp in circlePeers(seed, me)) {
            if (now < (nextDialAt[fp] ?: 0L)) continue
            // Re-read per peer: an earlier answer may have folded new events
            // (or a departure) that the next peer is entitled to see.
            val state = storedState(seed) ?: seed
            val request = CircleRequest(
                action = CircleRequest.ACTION_SYNC,
                circleId = state.id,
                invite = "",
                memberFp = me,
                events = state.events.takeLast(CircleCore.MAX_EVENTS_PER_REQUEST),
                body = "",
            )
            val route = dial(fp)
            if (route == null) {
                log("circle sync: no route to ${fp.take(12)}")
                noteFailure(fp, now)
                continue
            }
            val response = runCatching { route.sync(request) }.getOrNull()
            if (response == null || !response.ok) {
                log("circle sync: ${fp.take(12)} refused ${response?.reason ?: "no answer"}")
                noteFailure(fp, now)
                continue
            }
            noteSuccess(fp)
            val incoming = fresh(state, response.events)
            if (incoming.isNotEmpty()) {
                log("circle sync: +${incoming.size} event(s) from ${fp.take(12)}")
                context()?.let { FlitCircleStore.upsert(it, incoming) }
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
        val ctx = context() ?: return
        val state = FlitCircleStore.load(ctx) ?: return
        val event = emit(ctx, state, type, body) ?: return
        fanout(state, listOf(event))
    }

    /** Changeable poll answer: a new vote event, the old one stays in history. */
    suspend fun vote(pollId: String, option: Int) {
        post(CircleEvent.VOTE, circleVoteBody(pollId, option))
    }

    /** Publish a poll card. [id] is the id later votes refer to. */
    internal suspend fun poll(id: String, question: String, options: List<String>) {
        post(CircleEvent.POLL, circlePollBody(id, question, options))
    }

    /** Publish a file-drop reference — no bytes, just name/size/sha/token. */
    internal suspend fun postFile(name: String, size: Long, sha: String, token: String) {
        post(CircleEvent.FILE, circleFileBody(name, size, sha, token))
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
        ctx: android.content.Context,
        state: CircleState,
        type: String,
        body: String,
    ): CircleEvent? {
        if (type.trim().lowercase(Locale.ROOT) !in CircleEvent.TYPES) return null
        if (!circleBodyValid(type, body)) return null
        val identity = identity() ?: return null
        val current = FlitCircleStore.load(ctx) ?: state
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
        FlitCircleStore.upsert(ctx, listOf(event))
        return event
    }

    /**
     * Verified, in-window, deduplicated and deterministically ordered events.
     *
     * Incoming dedup is against the local log, not a one-shot global
     * reservation: `CircleCore.evaluate` consumes ids, so using it here would
     * silently drop an event whose persistence failed on an earlier tick.
     * Bodies must parse as one bounded JSON object before a reducer sees them.
     */
    private fun fresh(state: CircleState, events: List<CircleEvent>): List<CircleEvent> =
        CircleCore.dedup(CircleCore.order(events.filter { event ->
            event.circleId == state.id &&
                circleBodyReadable(event.body) &&
                CircleCore.verify(event) &&
                state.events.none { it.id == event.id }
        })).take(FlitCircleStore.EVENT_CAP)

    /**
     * Best-effort delivery to every remaining member. Self-healing: we send
     * the whole bounded local log, not just the event that triggered this
     * call, so one lost one-shot is repaired by the next post or calm tick.
     * Failures are recorded in the same per-peer backoff the pull worker uses;
     * a later attempt waits its turn instead of hammering a sleeping device.
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
        val pending = current.events.takeLast(CircleCore.MAX_EVENTS_PER_REQUEST)
        if (pending.isEmpty()) return

        val now = nowMs()
        val targets = circleFanoutTargets(current, me, now, nextDialAt)
        val skipped = circlePeers(current, me).size - targets.size
        // Stable evidence line: the device walk needs to see this path ran.
        log(
            "circle fan-out: ${events.size} new event(s), ${targets.size} peer(s)" +
                if (skipped > 0) ", $skipped backed off" else "",
        )
        if (targets.isEmpty()) return

        val request = CircleRequest(
            action = CircleRequest.ACTION_SYNC,
            circleId = current.id,
            invite = "",
            memberFp = me,
            events = pending,
            body = "",
        )
        for (fp in targets) {
            val route = dial(fp)
            if (route == null) {
                log("circle fan-out: no route to ${fp.take(12)}")
                noteFailure(fp, now)
                continue
            }
            val resp = runCatching { route.sync(request) }.getOrNull()
            if (resp == null || !resp.ok) {
                log("circle fan-out: ${fp.take(12)} refused ${resp?.reason ?: "no answer"}")
                noteFailure(fp, now)
            } else {
                log("circle fan-out: ok to ${fp.take(12)} (${pending.size} event(s) offered)")
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

    /** Backoff entries for departed peers are dead weight; drop them on a tick. */
    private fun pruneBackoff(peers: Set<String>) {
        nextDialAt.keys.toList().forEach { if (it !in peers) nextDialAt.remove(it) }
        misses.keys.toList().forEach { if (it !in peers) misses.remove(it) }
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
        cachedIdentity?.let { return it }
        return synchronized(this) {
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
