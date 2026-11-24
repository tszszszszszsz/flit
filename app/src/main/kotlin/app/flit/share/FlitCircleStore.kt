package app.flit.share

import android.content.Context
import app.flit.share.protocol.circle.CircleEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

// The bare names stay usable in this package (the real declarations are nested
// in FlitCircleStore so callers can also spell them FlitCircleStore.Role etc.).
typealias Role = FlitCircleStore.Role
typealias Member = FlitCircleStore.Member
typealias CircleState = FlitCircleStore.CircleState

/** Pure JSON codec over the Circle state (no Android involved). */
fun encode(state: CircleState): String = FlitCircleStore.encode(state)

/**
 * Note: a top-level `decode(String?): CircleState?` cannot coexist with
 * FlitKnown's `decode(String?): List<Device>` (identical Kotlin signature,
 * different return type). Use [FlitCircleStore.decode] or [decodeCircle].
 */
fun decodeCircle(raw: String?): CircleState? = FlitCircleStore.decode(raw)

/** Top-level alias of the pure reducer; see [FlitCircleStore.reduce]. */
fun reduce(state: CircleState, event: CircleEvent): CircleState =
    FlitCircleStore.reduce(state, event)

/**
 * One device's local view of a Circle: the group key, its own role, the roster,
 * the bounded signed event log, and the local rekey generation.
 *
 * Event bodies are JSON. The store reads these fields (unknown keys are
 * ignored, so Agent C can add metadata freely):
 *
 * - `join`   `{"alias": "..."}` — author is the joining member.
 * - `leave`  `{}` — author is the departing member.
 * - `kick`   `{"targetFp": "...", "kickId": "...", "mode": "admin"|"vote"}`
 *            ADMIN/OWNER author with mode `admin` kicks at once; any other
 *            author (or explicit mode `vote`) opens a vote-kick instead.
 * - `role`   `{"targetFp": "...", "role": "ADMIN"|"MEMBER"}` — OWNER only.
 * - `vote`   `{"kickId": "...", "approve": true}` (or `{"targetFp":...,"approve":...}`)
 *            for a kick; `{"pollId":..., "option": n}` for a poll. Votes are
 *            changeable: the latest vote per member wins.
 * - `rekey`  `{"keyB64": "...", "gen": n}` — installs a fresh group key.
 *
 * Governance: the founder is OWNER; only OWNER appoints/removes ADMINs; ADMIN
 * (and OWNER) may kick MEMBER/ADMIN but never OWNER; a vote-kick needs a simple
 * majority of current members. Leave, admin kick and successful vote-kick all
 * remove the departed member from the roster, bump [CircleState.rekeyGen] and
 * append the event — no remote device is touched, and no further event is
 * readable locally under the invalidated generation until a `rekey` arrives.
 */
object FlitCircleStore {

    /** Bounded local log, matching the design's ≈500 events. */
    const val EVENT_CAP = 500

    private const val PREFS_NAME = "flit"
    private const val KEY_CIRCLE = "circle.state"

    enum class Role { OWNER, ADMIN, MEMBER }

    @Serializable
    data class Member(
        val fp: String,
        val alias: String,
        val role: Role = Role.MEMBER,
        val joinedMs: Long = 0,
    )

    @Serializable
    data class CircleState(
        val id: String,
        val keyB64: String,
        /** This device's own role; remote role events update the roster entry. */
        val role: Role,
        val members: List<Member>,
        val events: List<CircleEvent> = emptyList(),
        val rekeyGen: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // ==== pure codec ============================================================

    fun encode(state: CircleState): String = json.encodeToString(CircleState.serializer(), state)

    /** Missing fields take defaults, future keys are ignored, corrupt → null. */
    fun decode(raw: String?): CircleState? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(CircleState.serializer(), raw) }.getOrNull()
    }

    // ==== persistence (thin SharedPreferences adapter) ==========================

    private fun store(ctx: Context): FlitKnown.Store {
        val prefs = (ctx.applicationContext ?: ctx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return object : FlitKnown.Store {
            override fun string(key: String): String? = prefs.getString(key, null)
            override fun putString(key: String, value: String) {
                prefs.edit().putString(key, value).apply()
            }
            override fun bool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
            override fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
        }
    }

    fun load(ctx: Context): CircleState? = decode(store(ctx).string(KEY_CIRCLE))

    fun save(ctx: Context, state: CircleState) = store(ctx).putString(KEY_CIRCLE, encode(state))

    /** Leaving deletes the local key and hides the feed: no row left to decode. */
    fun clear(ctx: Context) = store(ctx).putString(KEY_CIRCLE, "")

    /** Fold incoming events into the stored state, then persist the result. */
    fun upsert(ctx: Context, events: List<CircleEvent>): CircleState? {
        val current = load(ctx) ?: return null
        val next = merge(current, events)
        save(ctx, next)
        return next
    }

    // ==== pure reducers =========================================================

    /** Deterministic fold; each event is applied once, in the given order. */
    fun merge(state: CircleState, events: List<CircleEvent>): CircleState =
        events.fold(state) { acc, event -> reduce(acc, event) }

    /**
     * Total reducer: wrong-circle, duplicate and malformed events are appended
     * as history (or ignored) but never crash and never change membership.
     */
    fun reduce(state: CircleState, event: CircleEvent): CircleState {
        if (!event.circleId.equals(state.id, ignoreCase = false)) return state
        if (state.events.any { it.id == event.id }) return state
        return when (event.type.trim().lowercase(Locale.ROOT)) {
            "join" -> applyJoin(state, event)
            "leave" -> applyLeave(state, event)
            "kick" -> applyKick(state, event)
            "role" -> applyRole(state, event)
            "rekey" -> applyRekey(state, event)
            "vote" -> applyVote(state, event)
            else -> append(state, event)
        }
    }

    fun applyJoin(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        val fp = event.authorFp
        if (fp.isBlank()) return next
        val alias = bodyOf(event)?.str("alias") ?: bodyOf(event)?.str("name")
        val known = next.members.firstOrNull { it.fp == fp }
        if (known != null) {
            // A repeated join refreshes the local label, never the role: an
            // ADMIN must not be silently demoted by re-scanning the QR.
            if (alias.isNullOrBlank() || alias == known.alias) return next
            return next.copy(members = next.members.map { if (it.fp == fp) it.copy(alias = alias) else it })
        }
        val added = Member(
            fp = fp,
            alias = alias?.takeIf { it.isNotBlank() } ?: fp.take(8),
            role = Role.MEMBER,
            joinedMs = event.createdMs,
        )
        return next.copy(members = next.members + added)
    }

    /** Full leave: the author leaves the roster and the generation advances. */
    fun applyLeave(state: CircleState, event: CircleEvent): CircleState =
        depart(append(state, event), event.authorFp)

    /**
     * ADMIN/OWNER kicks execute at once; a kick opened by a plain member (or
     * with `"mode":"vote"`) only starts a vote-kick and is finalised by votes.
     * OWNER is never kickable.
     */
    fun applyKick(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        val body = bodyOf(event) ?: return next
        val target = body.target() ?: return next
        val actor = next.members.firstOrNull { it.fp == event.authorFp }?.role ?: return next
        val isVote = body.str("mode")?.lowercase(Locale.ROOT) == "vote" || actor == Role.MEMBER
        if (isVote) {
            val kickId = body.kickId() ?: event.id
            return finaliseVoteKick(next, kickId, target)
        }
        if (actor != Role.OWNER && actor != Role.ADMIN) return next
        val member = next.members.firstOrNull { it.fp == target } ?: return next
        if (member.role == Role.OWNER) return next
        return depart(next, target)
    }

    /** OWNER appoints/removes ADMIN; nobody (not even OWNER) appoints an OWNER. */
    fun applyRole(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        val body = bodyOf(event) ?: return next
        if (next.members.firstOrNull { it.fp == event.authorFp }?.role != Role.OWNER) return next
        val target = body.target() ?: return next
        val member = next.members.firstOrNull { it.fp == target } ?: return next
        if (member.role == Role.OWNER) return next
        val desired = body.role() ?: return next
        if (desired == Role.OWNER) return next
        if (desired == member.role) return next
        return next.copy(members = next.members.map { if (it.fp == target) it.copy(role = desired) else it })
    }

    /** Installs the fresh group key after a rekey; no second generation bump. */
    fun applyRekey(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        val body = bodyOf(event) ?: return next
        val key = body.str("keyB64") ?: body.str("key") ?: return next
        val gen = body.str("gen")?.toIntOrNull() ?: body.str("rekeyGen")?.toIntOrNull()
        return next.copy(
            keyB64 = key,
            rekeyGen = gen?.let { maxOf(next.rekeyGen, it) } ?: next.rekeyGen,
        )
    }

    /**
     * A vote is recorded for the feed; only kick votes can have an effect, and
     * only when the latest votes of the current members form a strict majority.
     */
    fun applyVote(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        val body = bodyOf(event) ?: return next
        val kickId = body.kickId()
        val target = body.target()
        if (kickId == null && target == null) return next
        return finaliseVoteKick(next, kickId, target)
    }

    // ==== internals =============================================================

    private fun append(state: CircleState, event: CircleEvent): CircleState {
        if (state.events.any { it.id == event.id }) return state
        return state.copy(events = (state.events + event).takeLast(EVENT_CAP))
    }

    /** Remove a departed member and invalidate the generation in one step. */
    private fun depart(state: CircleState, fp: String): CircleState {
        if (fp.isBlank() || state.members.none { it.fp == fp }) return state
        return state.copy(
            members = state.members.filterNot { it.fp == fp },
            rekeyGen = state.rekeyGen + 1,
        )
    }

    /**
     * Find the kick the votes belong to and count each current member's latest
     * vote. A changed vote overwrites the old one, so a kick can be blocked
     * again before it lands; once it lands the member is gone and later votes
     * are inert.
     */
    private fun finaliseVoteKick(state: CircleState, kickId: String?, targetHint: String?): CircleState {
        val kick = state.events.firstOrNull { e ->
            if (!e.type.equals("kick", ignoreCase = true)) return@firstOrNull false
            val b = bodyOf(e) ?: return@firstOrNull false
            (kickId != null && (b.kickId() == kickId || e.id == kickId)) ||
                (targetHint != null && b.target() == targetHint)
        } ?: return state
        val kickBody = bodyOf(kick) ?: return state
        val target = kickBody.target() ?: targetHint ?: return state
        val member = state.members.firstOrNull { it.fp == target } ?: return state
        if (member.role == Role.OWNER) return state
        val id = kickBody.kickId() ?: kick.id
        val current = state.members.map { it.fp }.toSet()
        val latest = linkedMapOf<String, Boolean>()
        state.events
            .asSequence()
            .filter { it.type.equals("vote", ignoreCase = true) }
            .filter { it.authorFp in current }
            .filter { e ->
                val b = bodyOf(e) ?: return@filter false
                b.kickId() == id || b.target() == target
            }
            .sortedWith(compareBy<CircleEvent> { it.lamport }.thenBy { it.createdMs }.thenBy { it.id })
            .forEach { latest[it.authorFp] = bodyOf(it)?.approved() ?: false }
        val yes = latest.values.count { it }
        if (yes > state.members.size / 2) return depart(state, target)
        return state
    }

    private fun bodyOf(event: CircleEvent): JsonObject? =
        runCatching { Json.parseToJsonElement(event.body) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
        val value = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: return default
        return when (value.lowercase(Locale.ROOT)) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> default
        }
    }

    private fun JsonObject.target(): String? =
        str("targetFp") ?: str("target") ?: str("memberFp") ?: str("fp")

    private fun JsonObject.kickId(): String? = str("kickId") ?: str("pollId")

    private fun JsonObject.role(): Role? = when (str("role")?.uppercase(Locale.ROOT)) {
        "OWNER" -> Role.OWNER
        "ADMIN" -> Role.ADMIN
        "MEMBER" -> Role.MEMBER
        else -> if (bool("admin")) Role.ADMIN else null
    }

    private fun JsonObject.approved(): Boolean {
        if (containsKey("approve")) return bool("approve")
        if (containsKey("yes")) return bool("yes")
        if (containsKey("vote")) return bool("vote")
        return str("option")?.toIntOrNull() == 1
    }
}
