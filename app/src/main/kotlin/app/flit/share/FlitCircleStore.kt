package app.flit.share

import android.content.Context
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CourierCarryCore
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
typealias CircleDelivery = FlitCircleStore.CircleDelivery

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
 * the bounded signed event log, the local rekey generation, and any file
 * deliveries this device announced ([CircleState.deliveries], keyed by sha256).
 * Delivery status is local-only: it is never signed, never gossiped, and never
 * carries the sender's spool path onto the wire.
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
 *            Only a known participant may rotate (a current member, or an
 *            author already present in the signed log, so a device can hand
 *            off a fresh key in the same batch as its leave), and a generation
 *            older than the local one is stale replay: it cannot replace the key.
 *
 * Governance: the founder is OWNER; only OWNER appoints/removes ADMINs; ADMIN
 * (and OWNER) may kick MEMBER/ADMIN but never OWNER; a vote-kick needs a simple
 * majority of current members. Leave, admin kick and successful vote-kick all
 * remove the departed member from the roster, bump [CircleState.rekeyGen] and
 * append the event — no remote device is touched. A `rekey` installs the fresh
 * group key. Hiding old-generation events from a departed reader is a
 * read-path decision for the caller; this reducer does not filter on reads.
 *
 * Every entry point is total: malformed ids, blanks, duplicate ids, corrupt
 * bodies and oversized payloads are either appended as inert history or
 * ignored. Nothing here throws, allocates without bound, or mutates its input.
 */
object FlitCircleStore {

    /** Bounded local log, matching the design's ≈500 events. */
    const val EVENT_CAP = 500

    /** A local label is decoration, never identity; bound it before drawing. */
    const val MAX_ALIAS_CHARS = 64

    /** A group key is one Base64 blob; refuse implausibly large replacements. */
    const val MAX_KEY_CHARS = 256

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

    /**
     * One file this device announced to the Circle, plus the locally recorded
     * outcome for each recipient fingerprint. Stored in
     * [CircleState.deliveries] under [sha256] (the same value the `file` event
     * and the received-file lookup use).
     *
     * [spoolPath] is a local cache path: it never enters a signed event body.
     * [statuses] is local-only (pending / sent / refused / failed); a member
     * who joins later sees the announcement card but neither the bytes nor
     * these states. Additive fields all have defaults so older rows decode.
     *
     * [cipherSha] is the digest of the sealed bytes announced in a keyed drop
     * (blank for a legacy plaintext drop); [wrappedFor] lists the uppercase
     * fingerprints the drop's content key was wrapped to. Both are local-only
     * bookkeeping — no plaintext key is persisted here. Additive with defaults.
     */
    @Serializable
    data class CircleDelivery(
        val id: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val spoolPath: String,
        val statuses: Map<String, String> = emptyMap(),
        val createdMs: Long = 0L,
        val cipherSha: String = "",
        val wrappedFor: List<String> = emptyList(),
    ) {
        companion object {
            const val PENDING = "pending"
            const val SENT = "sent"
            const val REFUSED = "refused"
            const val FAILED = "failed"
            /** Terminal local outcome: no wrap for this device in a keyed drop. */
            const val NO_KEY = "no-key"
        }
    }

    @Serializable
    data class CircleState(
        val id: String,
        val keyB64: String,
        /** This device's own role; a role event targeting self updates it too. */
        val role: Role,
        val members: List<Member>,
        val events: List<CircleEvent> = emptyList(),
        val rekeyGen: Int = 0,
        /**
         * This device's own fingerprint, when known. Additive with a default so
         * old rows and existing named-argument constructors keep working.
         * [applyRole] uses it to keep [role] in step with the roster when the
         * local device is promoted or demoted. Blank means "legacy row": the
         * reducer falls back to a uniqueness inference (only when exactly one
         * member holds the local role), never a guess on an ambiguous roster.
         */
        val selfFp: String = "",
        /**
         * Announced files awaiting (or finished with) delivery, keyed by
         * sha256. Additive with a default, so old rows and existing named
         * argument constructors keep working; the codec normalises it on read.
         */
        val deliveries: Map<String, CircleDelivery> = emptyMap(),
        /**
         * The carrier's durable parcel bag, encoded by `CourierCarry.encode`
         * (a `CarryState`). A blank value is an empty bag. Additive with a
         * default, so old rows and existing named-argument constructors keep
         * working; [decode] repairs a corrupt value back to blank. Local-only:
         * never signed or gossiped. Every write goes through [update], which
         * holds [ioLock] for the whole decode → fold → encode → persist sequence.
         */
        val courierBag: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** One JVM monitor serialises every SharedPreferences read/modify/write. */
    private val ioLock = Any()

    // ==== pure codec ============================================================

    fun encode(state: CircleState): String = json.encodeToString(CircleState.serializer(), state)

    /**
     * Missing fields take defaults, future keys are ignored, corrupt → null.
     * Decoded rows are normalised: duplicate fingerprints/ids collapse to the
     * first occurrence, the log is trimmed to [EVENT_CAP], a negative
     * generation is floored at zero, and a second OWNER is demoted (there is
     * exactly one founder). Delivery rows with a blank key or sha are dropped
     * and blank status fingerprints are stripped. This makes old and
     * hand-edited rows safe to fold; a missing `deliveries` key becomes an
     * empty map. A missing `courierBag` key becomes blank, and a corrupt bag
     * value is repaired to blank through the tolerant carry codec, so no
     * exception reaches a caller.
     */
    fun decode(raw: String?): CircleState? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching { json.decodeFromString(CircleState.serializer(), raw) }.getOrNull()
            ?: return null
        return normalize(parsed)
    }

    /** Idempotent invariant pass shared by every read of persisted state. */
    private fun normalize(state: CircleState): CircleState {
        val members = linkedMapOf<String, Member>()
        for (m in state.members) {
            if (m.fp.isBlank() || m.fp in members) continue
            members[m.fp] = m
        }
        var ownerKept = false
        val safeMembers = members.values.map { m ->
            if (m.role != Role.OWNER) m
            else if (!ownerKept) { ownerKept = true; m }
            else m.copy(role = Role.MEMBER)
        }
        val events = linkedMapOf<String, CircleEvent>()
        for (e in state.events) {
            if (e.id.isBlank() || e.id in events) continue
            events[e.id] = e
        }
        // A delivery with no sha cannot be looked up from a file event; drop it.
        // Blank fingerprints are decoration-free and stripped from statuses.
        val deliveries = linkedMapOf<String, CircleDelivery>()
        for ((key, delivery) in state.deliveries) {
            val k = key.trim()
            if (k.isBlank() || delivery.sha256.isBlank() || k in deliveries) continue
            deliveries[k] = if (delivery.statuses.keys.all { it.isNotBlank() }) delivery
            else delivery.copy(statuses = delivery.statuses.filterKeys { it.isNotBlank() })
        }
        // A corrupt `courierBag` must not break the whole Circle row. The carry
        // codec is tolerant (corrupt → empty CarryState), so use it as the gate
        // and store a corrupt or empty value back as blank — the canonical empty
        // bag. A valid, non-empty blob is kept verbatim; the app never rewrites
        // envelope bytes held inside it.
        val bagRaw = state.courierBag
        val safeBag = if (bagRaw.isBlank()) {
            ""
        } else {
            val decoded = runCatching { CourierCarryCore.decode(bagRaw) }.getOrNull()
            if (decoded == null || decoded.parcels.isEmpty()) "" else bagRaw
        }
        return state.copy(
            members = safeMembers,
            events = events.values.toList().takeLast(EVENT_CAP),
            rekeyGen = maxOf(0, state.rekeyGen),
            deliveries = deliveries,
            courierBag = safeBag,
        )
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

    /** All four Context helpers take the same monitor, so a concurrent fold can
     *  never interleave a load with another writer's save. */
    fun load(ctx: Context): CircleState? = synchronized(ioLock) {
        decode(store(ctx).string(KEY_CIRCLE))
    }

    fun save(ctx: Context, state: CircleState): Unit = synchronized(ioLock) {
        store(ctx).putString(KEY_CIRCLE, encode(state))
    }

    /** Leaving deletes the local key and hides the feed: no row left to decode. */
    fun clear(ctx: Context): Unit = synchronized(ioLock) {
        store(ctx).putString(KEY_CIRCLE, "")
    }

    /**
     * Atomic read-modify-write: decode, fold every event, encode and persist
     * under one lock. Two callers can no longer both read the same old row and
     * have the later save clobber the earlier one's events or roster change.
     */
    fun upsert(ctx: Context, events: List<CircleEvent>): CircleState? = synchronized(ioLock) {
        val current = decode(store(ctx).string(KEY_CIRCLE)) ?: return@synchronized null
        val next = merge(current, events)
        store(ctx).putString(KEY_CIRCLE, encode(next))
        next
    }

    /**
     * Atomic, local-only read-modify-write for delivery bookkeeping. The whole
     * decode → fold → encode → persist sequence holds [ioLock], so a delivery
     * update can never interleave with a sync `upsert` or another delivery
     * write and clobber its event log, roster or statuses. Returns the new
     * state (for the caller's StateFlow) or null when no row exists to update.
     * [transform] must be a pure reducer; it is called exactly once per row.
     */
    fun update(ctx: Context, transform: (CircleState) -> CircleState): CircleState? =
        synchronized(ioLock) {
            val current = decode(store(ctx).string(KEY_CIRCLE)) ?: return@synchronized null
            val next = transform(current)
            store(ctx).putString(KEY_CIRCLE, encode(next))
            next
        }

    /** Atomic convenience wrappers around the three pure delivery reducers. */
    fun persistDelivery(ctx: Context, delivery: CircleDelivery): CircleState? =
        update(ctx) { upsertDelivery(it, delivery) }

    fun persistDeliveryStatus(ctx: Context, sha: String, fp: String, status: String): CircleState? =
        update(ctx) { setDeliveryStatus(it, sha, fp, status) }

    fun dropDelivery(ctx: Context, sha: String): CircleState? =
        update(ctx) { removeDelivery(it, sha) }

    // ==== pure reducers =========================================================

    /** Deterministic fold; each event is applied once, in the given order. */
    fun merge(state: CircleState, events: List<CircleEvent>): CircleState =
        events.fold(state) { acc, event -> reduce(acc, event) }

    /**
     * Total reducer: wrong-circle, duplicate and malformed events are appended
     * as history (or ignored) but never crash and never change membership.
     * Events with no id or no author are dropped before dispatch: they cannot
     * be deduplicated or attributed, so they must not enter the log at all.
     */
    fun reduce(state: CircleState, event: CircleEvent): CircleState {
        if (event.id.isBlank() || event.authorFp.isBlank()) return state
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
        val alias = bodyOf(event)?.let { it.str("alias") ?: it.str("name") }
            ?.take(MAX_ALIAS_CHARS)
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
     * OWNER is never kickable, nobody kicks themselves, and an explicit
     * `"approve":false` opener is inert rather than a decision.
     */
    fun applyKick(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        val body = bodyOf(event) ?: return next
        if (body.containsKey("approve") && !body.bool("approve")) return next
        val target = body.target() ?: return next
        if (target.equals(event.authorFp, ignoreCase = true)) return next
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
        if (desired == Role.OWNER || desired == member.role) return next
        // Exact when the row knows its own fingerprint. Legacy rows have no
        // selfFp: infer only when the local role is unique in the roster, so
        // the target must be this device. Ambiguous rosters never infer.
        val selfChanged = if (state.selfFp.isNotBlank()) {
            target.equals(state.selfFp, ignoreCase = true)
        } else {
            state.role == member.role && state.members.count { it.role == state.role } == 1
        }
        return next.copy(
            members = next.members.map { if (it.fp == target) it.copy(role = desired) else it },
            role = if (selfChanged) desired else next.role,
        )
    }

    /**
     * Installs a fresh group key after a departure. Only a known signer may
     * rotate: someone on the roster or already present as an author in the
     * log (so a departing device can hand off a key in the same batch as its
     * leave). `gen` is mandatory, non-negative and at least the local
     * generation, so an old rekey replay cannot roll the live key back. A
     * successful install never bumps the generation a second time — the
     * departure already did.
     */
    fun applyRekey(state: CircleState, event: CircleEvent): CircleState {
        val next = append(state, event)
        // Check `state`, not `next`: the rekey event must not vouch for itself.
        val known = state.members.any { it.fp.equals(event.authorFp, ignoreCase = true) } ||
            state.events.any { it.authorFp.equals(event.authorFp, ignoreCase = true) }
        if (!known) return next
        val body = bodyOf(event) ?: return next
        val key = body.str("keyB64") ?: body.str("key") ?: return next
        if (key.length > MAX_KEY_CHARS) return next
        val gen = body.str("gen")?.toIntOrNull() ?: body.str("rekeyGen")?.toIntOrNull() ?: return next
        if (gen < 0 || gen < next.rekeyGen) return next
        return next.copy(keyB64 = key, rekeyGen = maxOf(next.rekeyGen, gen))
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

    // ==== file delivery reducers (local-only, pure) =============================

    /**
     * Insert or replace a delivery record keyed by its [CircleDelivery.sha256].
     * A retry that re-announces the same file with no statuses keeps the
     * outcomes already recorded; an incoming non-empty status map replaces
     * them. Blank keys and blank fingerprints are never stored.
     */
    fun upsertDelivery(state: CircleState, delivery: CircleDelivery): CircleState {
        val sha = delivery.sha256.trim()
        if (sha.isBlank()) return state
        val key = deliveryKey(state, sha) ?: sha
        val statuses = delivery.statuses.filterKeys { it.isNotBlank() }
            .takeIf { it.isNotEmpty() } ?: state.deliveries[key]?.statuses.orEmpty()
        val stored = delivery.copy(
            id = delivery.id.trim(),
            sha256 = sha,
            statuses = statuses,
        )
        return state.copy(deliveries = state.deliveries + (key to stored))
    }

    /**
     * Record one recipient's local outcome for a delivery. Unknown sha, blank
     * fingerprint or blank status are inert: no phantom records are created.
     * The latest status per fingerprint wins, matching vote semantics.
     */
    fun setDeliveryStatus(state: CircleState, sha: String, fp: String, status: String): CircleState {
        val key = deliveryKey(state, sha) ?: return state
        val who = fp.trim()
        val value = status.trim()
        if (who.isBlank() || value.isBlank()) return state
        val delivery = state.deliveries[key] ?: return state
        return state.copy(
            deliveries = state.deliveries + (
                key to delivery.copy(statuses = delivery.statuses + (who to value))
                ),
        )
    }

    /** Forget a delivery record; other records and the rest of the state survive. */
    fun removeDelivery(state: CircleState, sha: String): CircleState {
        val key = deliveryKey(state, sha) ?: return state
        return state.copy(deliveries = state.deliveries - key)
    }

    // ==== internals =============================================================

    /** Accept a sha, the map key, a delivery id or a case variant of any of them. */
    private fun deliveryKey(state: CircleState, ref: String): String? {
        val key = ref.trim()
        if (key.isBlank()) return null
        if (key in state.deliveries) return key
        return state.deliveries.entries.firstOrNull { (k, d) ->
            k.equals(key, ignoreCase = true) ||
                d.sha256.equals(key, ignoreCase = true) ||
                d.id.equals(key, ignoreCase = true)
        }?.key
    }

    private fun append(state: CircleState, event: CircleEvent): CircleState {
        if (event.id.isBlank()) return state
        if (state.events.any { it.id == event.id }) return state
        return state.copy(events = (state.events + event).takeLast(EVENT_CAP))
    }

    /** Remove a departed member and invalidate the generation in one step. */
    private fun depart(state: CircleState, fp: String): CircleState {
        if (fp.isBlank() || state.members.none { it.fp == fp }) return state
        return state.copy(
            members = state.members.filterNot { it.fp == fp },
            // Saturate: a corrupt row at Int.MAX_VALUE must not wrap to a
            // negative generation and make stale rekeys look current.
            rekeyGen = if (state.rekeyGen == Int.MAX_VALUE) Int.MAX_VALUE else state.rekeyGen + 1,
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
            if (b.containsKey("approve") && !b.bool("approve")) return@firstOrNull false
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
