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

    /** The carrier a route rides. TCP is the default so old prefs stay valid. */
    @Serializable
    enum class Kind { TCP, BLUETOOTH }

    /** One way a peer was reachable: a host, a serving port, a scheme. */
    @Serializable
    data class Route(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val kind: Kind = Kind.TCP,
    ) {
        val isBluetooth: Boolean get() = kind == Kind.BLUETOOTH
    }

    /** Bluetooth route keys are MACs, not IPs; their port is always zero. */
    fun bluetooth(host: String) = Route(host, 0, tls = true, kind = Kind.BLUETOOTH)

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
     * Milestone E: parse the one typed relay address ("host:port", off when
     * empty). A malformed value is invisible rather than a crash — E is an
     * opt-in last resort, never a route the user did not deliberately type.
     */
    fun parseRelay(raw: String?): Pair<String, Int>? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val host = value.substringBeforeLast(':')
        val port = value.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

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
