package app.flit.share.protocol.model

import app.flit.share.protocol.circle.CircleEvent
import kotlinx.serialization.Serializable

/**
 * Flit-only Circle request, additive under `/api/flit/v1`. One shape serves
 * both the join handshake and the event-sync exchange; [action] is
 * [ACTION_JOIN] or [ACTION_SYNC].
 *
 * [invite] is the bearer token from the QR (never a group key). [key] lives
 * only in the response, inside the authenticated TLS session. [events] is the
 * caller's bounded log delta; [body] is spare JSON for future verbs.
 */
@Serializable
data class CircleRequest(
    val action: String,
    val circleId: String,
    val invite: String? = null,
    val memberFp: String = "",
    val events: List<CircleEvent> = emptyList(),
    val body: String? = null,
) {
    companion object {
        const val ACTION_JOIN = "join"
        const val ACTION_SYNC = "sync"
    }
}

/**
 * The answer to a [CircleRequest]. A refused join/sync is still a well-formed
 * 200 response with [ok] false and a human-readable [reason], so a caller can
 * tell "not enabled / bad invite" from a dead carrier.
 *
 * [members] is the list of current member fingerprints (the app owns aliases
 * and roles). [key] is the group key for a successful join, delivered inside
 * the pinned TLS session — never in a QR code or over a blind relay in clear.
 */
@Serializable
data class CircleResponse(
    val ok: Boolean,
    val key: String = "",
    val members: List<String> = emptyList(),
    val events: List<CircleEvent> = emptyList(),
    val reason: String = "",
) {
    companion object {
        fun refused(reason: String): CircleResponse = CircleResponse(ok = false, reason = reason)
    }
}
