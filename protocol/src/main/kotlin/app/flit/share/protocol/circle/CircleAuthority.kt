package app.flit.share.protocol.circle

import java.util.Locale

/**
 * The shared, JVM-pure fold-time authority gate for Circle events.
 *
 * The decision reads only three event fields — [CircleEvent.circleId],
 * [CircleEvent.type] and [CircleEvent.authorFp] — plus a caller-supplied
 * fingerprint-to-role map. It verifies no signature, reads no clock, owns no
 * state, and keeps no memory between calls; no Android or wire type is used.
 * Every caller (app, CLI, harness) can therefore reach the same answer with the
 * same inputs, without repeating the rules at each fold site.
 *
 * The rules are:
 *  - `join` is never foldable: it is a handshake, not a log entry to fold.
 *  - `rekey` changes the group key, so it also needs an OWNER or ADMIN author.
 *  - any other known verb is foldable once the circle matches.
 *  - an unknown or blank type, a blank author, or a foreign circle is refused.
 */
object CircleAuthority {
    /** The only roles allowed to change the group key. */
    private val KEY_ROLES: Set<String> = setOf("OWNER", "ADMIN")

    /**
     * Whether [event] may enter the fold for [circleId], given the current
     * [roles] keyed by uppercase, trimmed author fingerprints.
     *
     * Author fingerprints are trimmed and uppercased before the role lookup;
     * role values are trimmed and uppercased before the OWNER/ADMIN check.
     * The function is total: malformed or hostile values simply refuse.
     */
    fun foldable(event: CircleEvent, circleId: String, roles: Map<String, String>): Boolean {
        if (event.type !in CircleEvent.TYPES) return false
        val author = event.authorFp.trim().uppercase(Locale.ROOT)
        if (author.isBlank()) return false
        if (event.circleId != circleId) return false
        return when (event.type) {
            CircleEvent.JOIN -> false
            CircleEvent.REKEY -> roles[author]?.trim()?.uppercase(Locale.ROOT) in KEY_ROLES
            else -> true
        }
    }
}
