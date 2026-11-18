package app.flit.share.protocol.relay

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
 * exact match, a relay without one ignores whatever arrived.
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
            if (fp.isEmpty() || fp.length > MAX_FINGERPRINT || fp.any { it.isWhitespace() }) return null
            val token = if (parts.size == 4) parts[3] else null
            if (token != null && (token.isEmpty() || token.length > MAX_TOKEN)) return null
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
 * E, deliberately free of I/O and clocks so it is tested without sockets.
 *
 * The harness owns every byte and every timeout; this core only answers
 * the pairing question for connections that present a [RelayHello]:
 *
 *  - a request parks until a subscription for the same fingerprint
 *    arrives (or vice versa), FIFO per fingerprint, one partner per side;
 *  - a counter-side arrival pairs with the oldest parked ticket — the
 *    harness wakes that ticket and starts copying;
 *  - the expected token, when configured, gates every hello (constant-time
 *    compare; a token-less hello is refused);
 *  - [withdraw] retires a parked ticket whose connection died, so a ghost
 *    can never be paired;
 *  - same-mode hellos never pair (two senders are not a story; two
 *    receivers are not either).
 *
 * The [ticket] is caller-chosen (any connection id) and never invented
 * here: the core has no notion of a connection, only of pairs to make.
 */
class RelayCore(private val token: String? = null) {

    sealed interface Decision {
        /** Nothing to pair with yet: this ticket waits in its queue. */
        data class Parked(val ticket: String) : Decision

        /** Paired: the harness connects [ticket] to [partnerTicket] and goes blind. */
        data class Paired(val ticket: String, val partnerTicket: String) : Decision

        /** Refused before pairing: the reason is safe to send back on the wire. */
        data class Refused(val reason: String) : Decision
    }

    private val requests = HashMap<String, ArrayDeque<String>>() // target fingerprint -> parked tickets
    private val subs = HashMap<String, ArrayDeque<String>>() // self fingerprint -> parked tickets

    /** Offer one hello for [ticket]; returns the pairing decision. */
    @Synchronized
    fun offer(hello: RelayHello, ticket: String): Decision {
        require(ticket.isNotBlank()) { "ticket must not be blank" }
        if (!tokenMatches(hello.token)) return Decision.Refused("bad-token")
        // A re-offer supersedes whatever this ticket earlier parked as.
        withdrawLocked(ticket)
        val counterpart = when (hello) {
            is RelayHello.Request -> subs
            is RelayHello.Subscribe -> requests
        }
        val parked = counterpart[hello.fingerprint]?.removeFirstOrNull()
        if (parked != null) return Decision.Paired(ticket, parked)
        val own = when (hello) {
            is RelayHello.Request -> requests
            is RelayHello.Subscribe -> subs
        }
        own.getOrPut(hello.fingerprint) { ArrayDeque() }.addLast(ticket)
        return Decision.Parked(ticket)
    }

    /** Retire a parked ticket (its connection died); a paired ticket is already forgotten. */
    @Synchronized
    fun withdraw(ticket: String) {
        withdrawLocked(ticket)
    }

    private fun withdrawLocked(ticket: String) {
        requests.values.forEach { it.remove(ticket) }
        subs.values.forEach { it.remove(ticket) }
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
