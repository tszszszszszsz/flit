package app.flit.share.protocol.circle

import app.flit.share.protocol.model.WireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The shared, JVM-pure fold-time authority gate for Circle events.
 *
 * [foldable] reads only three event fields — [CircleEvent.circleId],
 * [CircleEvent.type] and [CircleEvent.authorFp] — plus a caller-supplied
 * fingerprint-to-role map. It verifies no signature, reads no clock, owns no
 * state, and keeps no memory between calls; no Android or wire type is used.
 * Every caller (app, CLI, harness) can therefore reach the same answer with the
 * same inputs, without repeating the rules at each fold site.
 *
 * The additive [authorityOf] helper names the authority each verb needs, so a
 * fold site can refuse a privileged verb before any role data is consulted:
 *  - `join` is never foldable: it is a handshake, not a log entry to fold.
 *  - `role` needs the author to hold OWNER on the supplied snapshot.
 *  - `rekey` changes the group key, so it needs an OWNER or ADMIN author.
 *  - `kick` with `"mode":"admin"` needs an officer; a mode-less or `"vote"`
 *    kick needs only membership (the majority rule is decided by the reducer).
 *  - any other known verb is foldable once the circle matches.
 *  - an unknown or blank type, a blank author, or a foreign circle is refused.
 *
 * The fourth [foldable] argument defaults to [authorityOf], so existing
 * three-argument call sites keep compiling and switch rules at the same
 * instant. A caller without roster data passes `emptyMap()`; then no privileged
 * verb folds, including for an officer-signed event.
 */
object CircleAuthority {
    /** The only role allowed to change the roster. */
    const val OWNER_ROLE = "OWNER"

    /** The roles allowed to change the group key or execute a kick. */
    val OFFICER_ROLES: Set<String> = setOf(OWNER_ROLE, "ADMIN")

    /** Verbs that change roster or key; never admitted from an opened parcel. */
    val PRIVILEGED: Set<String> = setOf(
        CircleEvent.JOIN, CircleEvent.ROLE, CircleEvent.KICK, CircleEvent.REKEY,
    )

    /** The authority a verb demands of its author, independent of any roster. */
    enum class Authority { NONE, MEMBER, OFFICER, OWNER }

    /**
     * Classify a verb from its type and, for KICK, its `mode` field. Total on
     * hostile bodies: an unreadable or mistyped body is an ordinary vote-kick,
     * and the role map still decides whether its author may fold it.
     */
    fun authorityOf(event: CircleEvent): Authority =
        when (event.type.trim().lowercase(Locale.ROOT)) {
            CircleEvent.ROLE -> Authority.OWNER
            CircleEvent.REKEY -> Authority.OFFICER
            CircleEvent.KICK -> if (kickMode(event.body) == "admin")
                Authority.OFFICER else Authority.MEMBER
            else -> Authority.NONE // JOIN is refused explicitly by foldable
        }

    /**
     * Whether [event] may enter the fold for [circleId], given the current
     * [roles] keyed by uppercase, trimmed author fingerprints.
     *
     * Author fingerprints are trimmed and uppercased before the role lookup;
     * role values are trimmed and uppercased before the officer/owner check.
     * [authority] is additive: callers may pass [authorityOf] explicitly, but
     * the default keeps every existing call site on the one shared rule.
     * The function is total: malformed or hostile values simply refuse.
     */
    fun foldable(
        event: CircleEvent,
        circleId: String,
        roles: Map<String, String>,
        authority: Authority = authorityOf(event),
    ): Boolean {
        if (event.type !in CircleEvent.TYPES) return false
        val author = event.authorFp.trim().uppercase(Locale.ROOT)
        if (author.isBlank() || event.circleId != circleId) return false
        if (event.type == CircleEvent.JOIN) return false
        val held = roles[author]?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        return when (authority) {
            Authority.NONE -> true
            Authority.MEMBER -> held != null
            Authority.OFFICER -> held in OFFICER_ROLES
            Authority.OWNER -> held == OWNER_ROLE
        }
    }

    /** The executive kick mode, or null for a vote-kick / hostile body. */
    private fun kickMode(body: String): String? = runCatching {
        (WireJson.json.parseToJsonElement(body) as? JsonObject)?.get("mode")
            ?.let { (it as? JsonPrimitive)?.content }
    }.getOrNull()?.trim()?.lowercase(Locale.ROOT)
}
