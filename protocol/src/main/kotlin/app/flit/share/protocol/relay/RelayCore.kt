package app.flit.share.protocol.relay

import kotlin.math.log2

/**
 * The relay's entire wire vocabulary: one line, then the pipe goes blind.
 *
 * A sender dials the relay and asks for a peer by fingerprint; a receiver
 * holds a long-lived subscription under its own fingerprint. The relay
 * pairs the two and copies bytes both ways without ever parsing the
 * protocol inside — TLS, register, upload and `/partial` all run
 * end-to-end between the peers, as they do over TCP (docs, milestone E).
 *
 * Line shape, space-separated, ASCII, one line, newline-terminated:
 *
 * ```
 * FLITRLY1 req <targetFp> [<token>]
 * FLITRLY1 sub <selfFp>   [<token>]
 * ```
 *
 * [MAX_LINE] bounds the read: anything longer is not one of ours. The
 * token is optional on the wire; a relay configured with one requires an
 * exact match, a relay without one ignores whatever arrived. The grammar
 * has not changed: every hello a v2.2 peer could send still parses.
 */
sealed interface RelayHello {
    val fingerprint: String
    val token: String?

    /** A sender-side dial: "pair me with the device holding this fingerprint". */
    data class Request(override val fingerprint: String, override val token: String? = null) : RelayHello

    /** A receiver-side subscription: "I am this fingerprint; pair me with whoever asks". */
    data class Subscribe(override val fingerprint: String, override val token: String? = null) : RelayHello

    fun encode(): String = buildString {
        append(MAGIC).append(' ')
        append(if (this@RelayHello is Request) "req" else "sub").append(' ')
        append(fingerprint)
        if (token != null) append(' ').append(token)
    }

    companion object {
        const val MAGIC = "FLITRLY1"

        /** The longest hello we accept; anything bigger is garbage by definition. */
        const val MAX_LINE = 512

        /** Fingerprints are hex strings in this protocol; keep the bound honest. */
        const val MAX_FINGERPRINT = 128

        /** Tokens are user secrets; bound them like fingerprints. */
        const val MAX_TOKEN = 128

        /** Parse one hello line, or null when it is not a well-formed hello. */
        fun parse(line: String): RelayHello? {
            if (line.length > MAX_LINE) return null
            val parts = line.trim().split(' ')
            if (parts.size !in 3..4 || parts[0] != MAGIC) return null
            val fp = parts[2]
            if (fp.isEmpty() || fp.length > MAX_FINGERPRINT ||
                fp.any { it.isWhitespace() || it.isISOControl() }
            ) return null
            val token = if (parts.size == 4) parts[3] else null
            if (token != null && (token.isEmpty() || token.length > MAX_TOKEN ||
                    token.any { it.isWhitespace() || it.isISOControl() })
            ) return null
            return when (parts[1]) {
                "req" -> Request(fp, token)
                "sub" -> Subscribe(fp, token)
                else -> null
            }
        }
    }
}

/**
 * The relay's pairing registry: the correctness-critical half of milestone
 * E, deliberately free of sockets so it is tested without them. Time is
 * injected as [clock] so a test can advance a park or a backoff window
 * without sleeping; the default is the wall clock.
 *
 * The harness owns every byte on the wire; this core only answers the
 * pairing question for connections that present a [RelayHello]:
 *
 *  - a request parks until a subscription for the same fingerprint
 *    arrives (or vice versa), FIFO per fingerprint, one partner per side;
 *  - a counter-side arrival pairs with the oldest parked ticket — the
 *    harness wakes that ticket and starts copying;
 *  - the expected token, when configured, gates every hello (constant-time
 *    compare; a token-less hello is refused);
 *  - a caller that supplies [clientIp] gets per-IP backoff after repeated
 *    wrong tokens, so a password guesser cannot hammer the gate;
 *  - the number of parked tickets is capped globally, and a park ages out
 *    on [parkTtlMs], so a flood cannot grow the registry without bound;
 *  - [withdraw] retires a parked ticket whose connection died, so a ghost
 *    can never be paired;
 *  - same-mode hellos never pair (two senders are not a story; two
 *    receivers are not either).
 *
 * The [ticket] is caller-chosen (any connection id) and never invented
 * here: the core has no notion of a connection, only of pairs to make.
 */
class RelayCore(
    private val token: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val failureBackoffMs: Long = DEFAULT_FAILURE_BACKOFF_MS,
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val maxParked: Int = DEFAULT_MAX_PARKED,
    private val parkTtlMs: Long = DEFAULT_PARK_TIMEOUT_MS,
    private val requireStrongToken: Boolean = false,
) {
    companion object {
        /** A wrong token opens this window on the offending source address. */
        const val DEFAULT_FAILURE_BACKOFF_MS = 1_000L

        /** Number of wrong-token attempts from one address before throttling. */
        const val DEFAULT_MAX_FAILURES = 3

        /** Global ceiling across parked subscriptions *and* queued requests. */
        const val DEFAULT_MAX_PARKED = 1_024

        /** Finite park lifetime: a ticket older than this is never paired. */
        const val DEFAULT_PARK_TIMEOUT_MS = 300_000L

        /** A relay exposed beyond loopback must not run on a guessable token. */
        const val MIN_STRONG_TOKEN_LENGTH = 12
        const val MIN_STRONG_TOKEN_BITS = 48.0

        private const val MAX_TRACKED_IPS = 4_096
        private const val MAX_BACKOFF_MS = 300_000L

        /** Rough Shannon estimate: length times log2 of the distinct alphabet. */
        fun isStrongToken(token: String?): Boolean {
            if (token == null || token.length < MIN_STRONG_TOKEN_LENGTH) return false
            if (token.any { it.isWhitespace() || it.isISOControl() }) return false
            val alphabet = token.toSet().size
            if (alphabet < 4) return false
            return token.length * log2(alphabet.toDouble()) >= MIN_STRONG_TOKEN_BITS
        }

        /**
         * Why a bind is unsafe, or null when it is fine. Loopback stays
         * usable with no token at all (local development and the harness);
         * anything reachable off-box needs a high-entropy token.
         */
        fun bindTokenProblem(token: String?, loopback: Boolean): String? {
            if (loopback) return null
            return when {
                token == null -> "missing-token"
                !isStrongToken(token) -> "weak-token"
                else -> null
            }
        }
    }

    sealed interface Decision {
        /** Nothing to pair with yet: this ticket waits in its queue. */
        data class Parked(val ticket: String) : Decision

        /** Paired: the harness connects [ticket] to [partnerTicket] and goes blind. */
        data class Paired(val ticket: String, val partnerTicket: String) : Decision

        /** Refused before pairing: the reason is safe to send back on the wire. */
        data class Refused(val reason: String) : Decision
    }

    private class Failure(var count: Int = 0, var lastMs: Long = 0L, var untilMs: Long = 0L)

    private val requests = HashMap<String, ArrayDeque<String>>() // target fingerprint -> parked tickets
    private val subs = HashMap<String, ArrayDeque<String>>()     // self fingerprint -> parked tickets
    private val parkedAt = HashMap<String, Long>()               // ticket -> park time
    private val failures = LinkedHashMap<String, Failure>()      // client ip -> wrong-token state

    init {
        require(failureBackoffMs > 0) { "failureBackoffMs must be positive" }
        require(maxFailures > 0) { "maxFailures must be positive" }
        require(maxParked > 0) { "maxParked must be positive" }
        require(parkTtlMs > 0) { "parkTtlMs must be positive" }
    }

    /** Offer one hello for [ticket]; returns the pairing decision. */
    @Synchronized
    fun offer(hello: RelayHello, ticket: String, clientIp: String? = null): Decision {
        require(ticket.isNotBlank()) { "ticket must not be blank" }
        val now = clock()
        purgeExpiredLocked(now)
        // A relay that demanded strong tokens but was given none refuses
        // every hello; the socket half must not serve such a configuration.
        if (requireStrongToken) {
            val problem = bindTokenProblem(token, loopback = false)
            if (problem != null) return Decision.Refused(problem)
        }
        if (clientIp != null) {
            val failure = failures[clientIp]
            if (failure != null && now < failure.untilMs) return Decision.Refused("throttled")
        }
        if (!tokenMatches(hello.token)) {
            if (clientIp != null) recordFailureLocked(clientIp, now)
            return Decision.Refused("bad-token")
        }
        if (clientIp != null) failures.remove(clientIp) // a good hello clears the guesser's slate

        // A re-offer supersedes whatever this ticket earlier parked as.
        withdrawLocked(ticket)
        val counterpart = when (hello) {
            is RelayHello.Request -> subs
            is RelayHello.Subscribe -> requests
        }
        val parked = counterpart[hello.fingerprint]?.removeFirstOrNull()
        if (parked != null) {
            parkedAt.remove(parked)
            dropEmptyLocked()
            return Decision.Paired(ticket, parked)
        }
        // The cap is global: subscriptions and requests share one budget.
        if (parkedAt.size >= maxParked) return Decision.Refused("cap")
        val own = when (hello) {
            is RelayHello.Request -> requests
            is RelayHello.Subscribe -> subs
        }
        own.getOrPut(hello.fingerprint) { ArrayDeque() }.addLast(ticket)
        parkedAt[ticket] = now
        return Decision.Parked(ticket)
    }

    /** Retire a parked ticket (its connection died); a paired ticket is already forgotten. */
    @Synchronized
    fun withdraw(ticket: String) {
        withdrawLocked(ticket)
    }

    /** Total tickets currently waiting in either queue. */
    @Synchronized
    fun parkedCount(): Int = parkedAt.size

    @Synchronized
    fun parkedRequestCount(): Int = requests.values.sumOf { it.size }

    @Synchronized
    fun parkedSubscriptionCount(): Int = subs.values.sumOf { it.size }

    /** Addresses with wrong-token state; bounded so a spoofed flood cannot grow it. */
    @Synchronized
    fun trackedIpCount(): Int = failures.size

    @Synchronized
    fun isThrottled(clientIp: String, nowMs: Long = clock()): Boolean =
        failures[clientIp]?.let { nowMs < it.untilMs } ?: false

    private fun withdrawLocked(ticket: String) {
        parkedAt.remove(ticket)
        requests.values.forEach { it.remove(ticket) }
        subs.values.forEach { it.remove(ticket) }
    }

    /** Age out parks that outlived [parkTtlMs] before any pairing decision. */
    private fun purgeExpiredLocked(now: Long) {
        if (parkedAt.isEmpty()) return
        val it = parkedAt.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value >= parkTtlMs) {
                val stale = entry.key
                it.remove()
                requests.values.forEach { it.remove(stale) }
                subs.values.forEach { it.remove(stale) }
            }
        }
        dropEmptyLocked()
    }

    private fun dropEmptyLocked() {
        val ri = requests.entries.iterator()
        while (ri.hasNext()) if (ri.next().value.isEmpty()) ri.remove()
        val si = subs.entries.iterator()
        while (si.hasNext()) if (si.next().value.isEmpty()) si.remove()
    }

    private fun recordFailureLocked(ip: String, now: Long) {
        if (failures.size >= MAX_TRACKED_IPS) {
            val staleBefore = now - failureBackoffMs * 4
            val it = failures.entries.iterator()
            while (it.hasNext()) if (it.next().value.lastMs < staleBefore) it.remove()
            while (failures.size >= MAX_TRACKED_IPS) {
                val oldest = failures.keys.firstOrNull() ?: break
                failures.remove(oldest)
            }
        }
        val failure = failures.getOrPut(ip) { Failure() }
        if (now - failure.lastMs > failureBackoffMs * 2) failure.count = 0
        failure.lastMs = now
        failure.count += 1
        if (failure.count >= maxFailures) {
            val step = (failure.count - maxFailures).coerceIn(0, 4)
            failure.untilMs = now + minOf(failureBackoffMs * (1L shl step), MAX_BACKOFF_MS)
        }
    }

    /**
     * Constant-time compare: the token is the only access control a
     * self-hosted relay has, and a comparison that leaks length/prefix
     * timing would weaken it for free. Null core token = open relay.
     */
    private fun tokenMatches(given: String?): Boolean {
        val expected = token ?: return true
        if (given == null) return false
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = given.toByteArray(Charsets.UTF_8)
        var diff = a.size xor b.size
        for (i in 0 until minOf(a.size, b.size)) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
