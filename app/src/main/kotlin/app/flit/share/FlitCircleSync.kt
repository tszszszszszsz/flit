package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.security.SecureRandom
import java.util.Base64
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

/** Per-peer retry backoff: 5s, 10s, 20s … capped, and reset on any answer. */
internal const val CIRCLE_BACKOFF_BASE_MS = 5_000L
internal const val CIRCLE_BACKOFF_MAX_MS = 120_000L

internal fun circleBackoffMs(attempt: Int): Long {
    val n = attempt.coerceIn(0, 5)
    return minOf(CIRCLE_BACKOFF_BASE_MS * (1L shl n), CIRCLE_BACKOFF_MAX_MS)
}

/** Next Lamport value for an author: one past the highest value seen locally. */
internal fun circleNextLamport(values: List<Long>): Long = (values.maxOrNull() ?: 0L) + 1L

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
 * is normal in an offline-first group); a per-peer backoff keeps repeated
 * [sync] calls from ever hammering a device that is offline.
 */
object FlitCircleSync {

    private val nextDialAt = ConcurrentHashMap<String, Long>()
    private val misses = ConcurrentHashMap<String, Int>()
    @Volatile private var nextSyncAt = 0L
    @Volatile private var cachedIdentity: TlsIdentity? = null

    // ==== public suspend API ====================================================

    /** Join a founded Circle from its QR code (bearer invite, no key in the QR). */
    suspend fun join(code: QrAdd) {
        val ctx = context() ?: return
        val identity = identity() ?: return
        val me = identity.fingerprint.trim().uppercase()
        val host = code.fingerprint.trim().uppercase()
        val circleId = code.circle?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (host.isBlank()) return

        val route = FlitEngine.circleDial(host) ?: return
        val request = CircleRequest(
            action = "join",
            circleId = circleId,
            invite = code.invite.orEmpty(),
            memberFp = me,
            events = emptyList(),
            body = "",
        )
        val response = runCatching { route.client.circleJoin(route.target, request) }.getOrNull() ?: return
        if (!response.ok || response.key.isBlank()) return

        val roster = response.members.map { fp ->
            val up = fp.trim().uppercase()
            Member(
                fp = up,
                alias = "",
                role = if (up == host) Role.OWNER else Role.MEMBER,
            )
        }.distinctBy { it.fp }.let { list ->
            if (list.any { it.fp == me }) list else list + Member(me, "", Role.MEMBER)
        }
        val joined = CircleState(
            id = circleId,
            keyB64 = response.key,
            role = Role.MEMBER,
            members = roster,
            events = emptyList(),
            rekeyGen = 0,
        )
        // Fold seed events through the store's reducers so the roster and the
        // rekey generation agree with what the host's log actually says.
        FlitCircleStore.save(ctx, FlitCircleStore.merge(joined, fresh(joined, response.events)))
    }

    /** Pull events from every other member, once per calm interval. */
    suspend fun sync() {
        val ctx = context() ?: return
        val state = FlitCircleStore.load(ctx) ?: return
        val identity = identity() ?: return
        val me = identity.fingerprint.trim().uppercase()
        val now = System.currentTimeMillis()
        if (now < nextSyncAt) return
        nextSyncAt = now + CIRCLE_SYNC_MIN_INTERVAL_MS

        val request = CircleRequest(
            action = "sync",
            circleId = state.id,
            invite = "",
            memberFp = me,
            events = state.events,
            body = "",
        )
        for (fp in state.members.map { it.fp }.distinct().filterNot { it.equals(me, ignoreCase = true) }) {
            val dialAt = nextDialAt[fp] ?: 0L
            if (now < dialAt) continue
            val route = FlitEngine.circleDial(fp)
            if (route == null) {
                FlitEngine.logLine("circle sync: no route to ${fp.take(12)}")
                noteFailure(fp, now)
                continue
            }
            val response = runCatching { route.client.circleSync(route.target, request) }.getOrNull()
            if (response == null || !response.ok) {
                FlitEngine.logLine("circle sync: ${fp.take(12)} refused ${response?.reason ?: "no answer"}")
                noteFailure(fp, now)
                continue
            }
            noteSuccess(fp)
            val incoming = fresh(state, response.events)
            if (incoming.isNotEmpty()) {
                FlitEngine.logLine("circle sync: +${incoming.size} event(s) from ${fp.take(12)}")
                FlitCircleStore.upsert(ctx, incoming)
            }
        }
    }

    /**
     * Sign, store and fan out one raw event body. [type] is one of the
     * CircleCore event kinds; [body] is passed through untouched.
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

    /** Open a vote-kick. The body's `kickId` is what others vote on. */
    suspend fun startKick(targetFp: String) {
        post(CircleEvent.KICK, circleKickOpenBody(UUID.randomUUID().toString(), targetFp.trim().uppercase()))
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
        post(CircleEvent.KICK, circleKickExecuteBody(UUID.randomUUID().toString(), targetFp.trim().uppercase()))
    }

    /** Full leave: tell the others, then drop our key and feed locally. */
    suspend fun leave() {
        val ctx = context() ?: return
        val identity = identity() ?: return
        if (FlitCircleStore.load(ctx) == null) return
        post(CircleEvent.LEAVE, circleLeaveBody(identity.fingerprint.trim().uppercase()))
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
            circleRekeyBody(key, state.rekeyGen + 1, remaining.map { it.trim().uppercase() }),
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
        val identity = identity() ?: return null
        val current = FlitCircleStore.load(ctx) ?: state
        val event = CircleCore.create(
            circleId = current.id,
            authorFp = identity.fingerprint.trim().uppercase(),
            identity = identity,
            type = type,
            body = body,
            lamport = circleNextLamport(current.events.map { it.lamport }),
            createdMs = System.currentTimeMillis(),
        )
        if (!CircleCore.verify(event)) return null
        FlitCircleStore.upsert(ctx, listOf(event))
        return event
    }

    /** Verified, in-window, deduplicated and deterministically ordered events. */
    /**
     * Incoming dedup is against the local log, not a one-shot global
     * reservation: `CircleCore.evaluate` consumes ids, so using it here would
     * silently drop an event whose persistence failed on an earlier tick.
     */
    private fun fresh(state: CircleState, events: List<CircleEvent>): List<CircleEvent> =
        CircleCore.dedup(CircleCore.order(events.filter { event ->
            event.circleId.equals(state.id, ignoreCase = false) &&
                CircleCore.verify(event) &&
                state.events.none { it.id == event.id }
        }))

    /** Best-effort one-shot delivery to every remaining member. */
    private suspend fun fanout(state: CircleState, events: List<CircleEvent>) {
        if (events.isEmpty()) return
        val me = identity()?.fingerprint?.trim()?.uppercase() ?: return
        val request = CircleRequest(
            action = "sync",
            circleId = state.id,
            invite = "",
            memberFp = me,
            events = events,
            body = "",
        )
        for (fp in state.members.map { it.fp }.distinct().filterNot { it.equals(me, ignoreCase = true) }) {
            val route = FlitEngine.circleDial(fp) ?: run {
                FlitEngine.logLine("circle: no live route to ${fp.take(12)}")
                continue
            }
            val resp = runCatching { route.client.circleSync(route.target, request) }.getOrNull()
            if (resp == null || !resp.ok) {
                FlitEngine.logLine("circle: fan-out to ${fp.take(12)} refused: ${resp?.reason ?: "no answer"}")
            } else {
                FlitEngine.logLine("circle: fan-out ok to ${fp.take(12)}")
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
