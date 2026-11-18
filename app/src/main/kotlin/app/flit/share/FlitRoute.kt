package app.flit.share

import kotlinx.serialization.Serializable

/**
 * Carrier migration (docs, milestone A): the sender re-probes in priority
 * order and re-attaches a running transfer onto whichever route answers.
 * The Android wiring (the hunter loop, the probes, the touch that resumes
 * the flush) lives in [FlitEngine]; everything that must hold without a
 * device lives here.
 */
object FlitRoute {

    /** One way a peer was reachable: a host, a serving port, a scheme. */
    @Serializable
    data class Route(val host: String, val port: Int, val tls: Boolean)

    /**
     * Candidates for an owed fingerprint, in priority order: the live row's
     * address (the freshest wire answer), the remembered route (it answered
     * once), the typed addresses last — they carry no memory of which peer
     * they belong to, so they are candidates for every owed fingerprint and
     * the accept rule ([accepts]) is what binds an answer to the right one.
     * Duplicates die — a route listed twice is one dial wasted, nothing more.
     */
    fun candidates(live: Route?, remembered: Route?, manual: List<Route>): List<Route> =
        (listOfNotNull(live, remembered) + manual).distinct()

    /**
     * The hunter's beat: calm by design — a first probe ~2 s after the
     * failure or the engine start, then 5, 15, 30, and one probe a minute
     * for as long as the row is owed. Attempt indexes past the list ride the
     * last cadence; a row the user cancelled ends the schedule with it.
     */
    fun huntDelayMs(attempt: Int): Long =
        listOf(2_000L, 5_000L, 15_000L, 30_000L).getOrElse(attempt) { 60_000L }

    /**
     * Whether a probe answer belongs to the fingerprint being hunted. A
     * register answer carries the peer's full identity, so the fingerprint
     * is the whole rule: a reassigned address can never hand the batch to a
     * stranger, and a typed address that now serves a different device is
     * simply not this peer's route.
     */
    fun accepts(answerFingerprint: String?, owedFingerprint: String): Boolean =
        answerFingerprint != null && answerFingerprint == owedFingerprint
}
