package app.flit.share.protocol.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Milestone E's pure half: the hello wire format and the pairing registry.
 * Sockets, timeouts and copying belong to the harness core's callers; every
 * rule the design promises is decided here and pinned here.
 */
class RelayCoreTest {

    private val fpA = "A".repeat(64)
    private val fpB = "B".repeat(64)
    private val fpC = "C".repeat(64)
    private val fpD = "D".repeat(64)

    // ---- wire format ------------------------------------------------------

    @Test
    fun `hello round-trips through its line form`() {
        val req = RelayHello.Request(fpA, "s3cret")
        val sub = RelayHello.Subscribe(fpB)
        assertEquals("FLITRLY1 req $fpA s3cret", req.encode())
        assertEquals("FLITRLY1 sub $fpB", sub.encode())
        assertEquals(req, RelayHello.parse(req.encode()))
        assertEquals(sub, RelayHello.parse(sub.encode()))
    }

    @Test
    fun `garbage lines never parse`() {
        val bad = listOf(
            "",
            "FLITRLY1",
            "FLITRLY1 req",
            "FLITRLY1 req $fpA tok extra",
            "FLITRLY2 req $fpA",
            "FLITRLY1 push $fpA",
            "FLITRLY1 req  $fpA", // split(' ') keeps the empty token
            "FLITRLY1 req",
            "FLITRLY1 req ${"f".repeat(RelayHello.MAX_FINGERPRINT + 1)}",
            "FLITRLY1 req $fpA ${"t".repeat(RelayHello.MAX_TOKEN + 1)}",
        )
        for (line in bad) assertNull(RelayHello.parse(line), "parsed: [$line]")
    }

    // ---- pairing ----------------------------------------------------------

    @Test
    fun `a request parks until its subscription arrives`() {
        val core = RelayCore()
        assertEquals(
            RelayCore.Decision.Parked("send-1"),
            core.offer(RelayHello.Request(fpB), "send-1"),
        )
        assertEquals(
            RelayCore.Decision.Paired("recv-1", "send-1"),
            core.offer(RelayHello.Subscribe(fpB), "recv-1"),
        )
    }

    @Test
    fun `a subscription parks until its request arrives`() {
        val core = RelayCore()
        assertEquals(
            RelayCore.Decision.Parked("recv-1"),
            core.offer(RelayHello.Subscribe(fpB), "recv-1"),
        )
        assertEquals(
            RelayCore.Decision.Paired("send-1", "recv-1"),
            core.offer(RelayHello.Request(fpB), "send-1"),
        )
    }

    @Test
    fun `pairs are fifo and isolated per fingerprint`() {
        val core = RelayCore()
        core.offer(RelayHello.Request(fpB), "req-1")
        core.offer(RelayHello.Request(fpB), "req-2")
        core.offer(RelayHello.Request(fpA), "req-other")
        // The oldest request for B pairs with the first subscription for B.
        assertEquals(RelayCore.Decision.Paired("sub-1", "req-1"), core.offer(RelayHello.Subscribe(fpB), "sub-1"))
        // The second subscription gets the second request — not req-other's.
        assertEquals(RelayCore.Decision.Paired("sub-2", "req-2"), core.offer(RelayHello.Subscribe(fpB), "sub-2"))
        // A's request is untouched by B's traffic.
        assertEquals(RelayCore.Decision.Paired("sub-a", "req-other"), core.offer(RelayHello.Subscribe(fpA), "sub-a"))
    }

    @Test
    fun `same-mode hellos never pair`() {
        val core = RelayCore()
        core.offer(RelayHello.Request(fpB), "req-1")
        assertEquals(RelayCore.Decision.Parked("req-2"), core.offer(RelayHello.Request(fpB), "req-2"))
        core.offer(RelayHello.Subscribe(fpB), "sub-1")
        core.offer(RelayHello.Subscribe(fpB), "sub-2")
        // req-1 + sub-1 paired; req-2 + sub-2 paired; nothing cross-paired early.
        assertEquals(RelayCore.Decision.Parked("sub-3"), core.offer(RelayHello.Subscribe(fpB), "sub-3"))
    }

    @Test
    fun `withdraw prevents a ghost pairing`() {
        val core = RelayCore()
        core.offer(RelayHello.Request(fpB), "req-dead")
        core.withdraw("req-dead")
        // The dead ticket must never be handed to a subscriber.
        assertEquals(RelayCore.Decision.Parked("sub-1"), core.offer(RelayHello.Subscribe(fpB), "sub-1"))
        // And its slot is clean for a live request.
        assertEquals(RelayCore.Decision.Paired("req-live", "sub-1"), core.offer(RelayHello.Request(fpB), "req-live"))
    }

    @Test
    fun `withdrawing an unknown or paired ticket is a no-op`() {
        val core = RelayCore()
        core.withdraw("never-seen")
        core.offer(RelayHello.Request(fpB), "req-1")
        core.offer(RelayHello.Subscribe(fpB), "sub-1")
        // The pair is concluded; both tickets are forgotten.
        core.withdraw("req-1")
        core.withdraw("sub-1")
        assertEquals(RelayCore.Decision.Parked("sub-2"), core.offer(RelayHello.Subscribe(fpB), "sub-2"))
    }

    @Test
    fun `a re-offer supersedes the ticket's earlier park`() {
        val core = RelayCore()
        core.offer(RelayHello.Request(fpA), "conn-1")
        // The same connection re-hellos for a different target.
        core.offer(RelayHello.Request(fpB), "conn-1")
        // Its A-park is gone: an A subscriber parks instead of pairing a ghost.
        assertEquals(RelayCore.Decision.Parked("sub-a"), core.offer(RelayHello.Subscribe(fpA), "sub-a"))
        assertEquals(RelayCore.Decision.Paired("sub-b", "conn-1"), core.offer(RelayHello.Subscribe(fpB), "sub-b"))
    }

    // ---- token gate -------------------------------------------------------

    @Test
    fun `a configured token gates every hello`() {
        val core = RelayCore(token = "s3cret")
        assertEquals(
            RelayCore.Decision.Refused("bad-token"),
            core.offer(RelayHello.Request(fpB), "send-1"),
        )
        assertEquals(
            RelayCore.Decision.Refused("bad-token"),
            core.offer(RelayHello.Request(fpB, "wrong"), "send-1"),
        )
        assertEquals(
            RelayCore.Decision.Refused("bad-token"),
            core.offer(RelayHello.Subscribe(fpB), "recv-1"),
        )
        // Right token: the normal pairing story.
        assertEquals(RelayCore.Decision.Parked("send-2"), core.offer(RelayHello.Request(fpB, "s3cret"), "send-2"))
        assertEquals(
            RelayCore.Decision.Paired("recv-2", "send-2"),
            core.offer(RelayHello.Subscribe(fpB, "s3cret"), "recv-2"),
        )
    }

    @Test
    fun `an open relay ignores whatever token arrived`() {
        val core = RelayCore(token = null)
        assertEquals(RelayCore.Decision.Parked("send-1"), core.offer(RelayHello.Request(fpB, "anything"), "send-1"))
        assertEquals(
            RelayCore.Decision.Paired("recv-1", "send-1"),
            core.offer(RelayHello.Subscribe(fpB), "recv-1"),
        )
        assertTrue(RelayHello.parse("FLITRLY1 req $fpB") != null)
    }

    // ---- bounded hello grammar -------------------------------------------

    @Test
    fun `control characters do not sneak into a hello`() {
        assertNull(RelayHello.parse("FLITRLY1 req $fpA tok\u0001"))
        assertNull(RelayHello.parse("FLITRLY1 sub ${fpA.dropLast(1)}\u0001"))
        assertNull(RelayHello.parse("FLITRLY1 req $fpA\tok"))
        // An ordinary v2.2 hello still parses unchanged.
        assertTrue(RelayHello.parse("FLITRLY1 req $fpA s3cret") != null)
    }

    // ---- global cap / flood ----------------------------------------------

    @Test
    fun `a global cap bounds parked subscriptions and queued requests`() {
        val core = RelayCore(maxParked = 3)
        assertEquals(RelayCore.Decision.Parked("req-1"), core.offer(RelayHello.Request(fpA), "req-1"))
        assertEquals(RelayCore.Decision.Parked("req-2"), core.offer(RelayHello.Request(fpB), "req-2"))
        assertEquals(RelayCore.Decision.Parked("sub-1"), core.offer(RelayHello.Subscribe(fpC), "sub-1"))
        assertEquals(3, core.parkedCount())
        // The budget is shared: requests and subscriptions cannot each take three.
        assertEquals(RelayCore.Decision.Refused("cap"), core.offer(RelayHello.Request(fpD), "req-3"))
        assertEquals(RelayCore.Decision.Refused("cap"), core.offer(RelayHello.Subscribe(fpD), "sub-2"))
        // A pair frees a slot without a withdraw.
        assertEquals(RelayCore.Decision.Paired("sub-live", "req-1"), core.offer(RelayHello.Subscribe(fpA), "sub-live"))
        assertEquals(2, core.parkedCount())
        // A retired park frees one too.
        core.withdraw("req-2")
        assertEquals(1, core.parkedCount())
        assertEquals(RelayCore.Decision.Parked("sub-live2"), core.offer(RelayHello.Subscribe(fpB), "sub-live2"))
    }

    @Test
    fun `a subscription flood cannot grow the registry past the cap`() {
        val core = RelayCore(maxParked = 8)
        repeat(8) { i ->
            val fp = "F".repeat(56) + i.toString().padStart(8, '0')
            assertEquals(RelayCore.Decision.Parked("flood-$i"), core.offer(RelayHello.Subscribe(fp), "flood-$i"))
        }
        repeat(200) { i ->
            assertEquals(RelayCore.Decision.Refused("cap"), core.offer(RelayHello.Subscribe(fpA), "extra-$i"))
        }
        assertEquals(8, core.parkedCount())
        assertEquals(8, core.parkedSubscriptionCount())
        // The oldest parked subscription is still reachable by a real sender.
        val first = "F".repeat(56) + "00000000"
        assertEquals(RelayCore.Decision.Paired("send-fresh", "flood-0"), core.offer(RelayHello.Request(first), "send-fresh"))
    }

    // ---- per-IP wrong-token backoff --------------------------------------

    @Test
    fun `repeated wrong tokens throttle that source IP only`() {
        var now = 1_000L
        val core = RelayCore(
            token = "s3cret",
            clock = { now },
            failureBackoffMs = 1_000,
            maxFailures = 3,
        )
        val wrong = RelayHello.Request(fpB, "wrong")
        assertEquals(RelayCore.Decision.Refused("bad-token"), core.offer(wrong, "a", "198.51.100.7"))
        assertEquals(RelayCore.Decision.Refused("bad-token"), core.offer(wrong, "b", "198.51.100.7"))
        assertEquals(RelayCore.Decision.Refused("bad-token"), core.offer(wrong, "c", "198.51.100.7"))
        // The window is open: even a correct token from that address is refused.
        val right = RelayHello.Request(fpB, "s3cret")
        assertEquals(RelayCore.Decision.Refused("throttled"), core.offer(right, "d", "198.51.100.7"))
        assertTrue(core.isThrottled("198.51.100.7"))
        // Another source is untouched by the first one's guesses.
        assertEquals(RelayCore.Decision.Parked("e"), core.offer(right, "e", "198.51.100.8"))
        // The window closes on the injected clock; no sleeping is involved.
        now += 1_001
        assertTrue(!core.isThrottled("198.51.100.7"))
        assertEquals(
            RelayCore.Decision.Paired("f", "e"),
            core.offer(RelayHello.Subscribe(fpB, "s3cret"), "f", "198.51.100.7"),
        )
    }

    @Test
    fun `a good hello clears a source IP backoff state`() {
        var now = 500L
        val core = RelayCore(token = "s3cret", clock = { now }, failureBackoffMs = 2_000, maxFailures = 2)
        val ip = "203.0.113.9"
        core.offer(RelayHello.Request(fpA, "nope"), "a", ip)
        core.offer(RelayHello.Request(fpA, "s3cret"), "b", ip) // good: clears the slate
        assertEquals(RelayCore.Decision.Parked("c"), core.offer(RelayHello.Request(fpA, "s3cret"), "c", ip))
        core.offer(RelayHello.Request(fpA, "nope"), "d", ip)
        assertEquals(RelayCore.Decision.Parked("e"), core.offer(RelayHello.Request(fpA, "s3cret"), "e", ip))
    }

    @Test
    fun `wrong-token state is bounded across many source IPs`() {
        val core = RelayCore(token = "s3cret")
        repeat(RelayCore.DEFAULT_MAX_PARKED * 5) { i ->
            core.offer(RelayHello.Request(fpA, "wrong"), "guess-$i", "10.0.$i.1")
        }
        assertTrue(core.trackedIpCount() <= 4_096, "tracked ${core.trackedIpCount()} addresses")
    }

    // ---- finite park lifetime --------------------------------------------

    @Test
    fun `an expired park is retired instead of paired with a late arrival`() {
        var now = 100L
        val core = RelayCore(clock = { now }, parkTtlMs = 500)
        core.offer(RelayHello.Request(fpA), "stale")
        assertEquals(1, core.parkedCount())
        now += 501
        // The stale ticket is purged on the next offer, never paired with it.
        assertEquals(RelayCore.Decision.Parked("sub"), core.offer(RelayHello.Subscribe(fpA), "sub"))
        assertEquals(0, core.parkedRequestCount())
        assertEquals(RelayCore.Decision.Paired("fresh", "sub"), core.offer(RelayHello.Request(fpA), "fresh"))
    }

    // ---- token policy for non-loopback binds -----------------------------

    @Test
    fun `a weak token is refused when the strong-token policy is on`() {
        val core = RelayCore(token = "s3cret", requireStrongToken = true)
        assertEquals(
            RelayCore.Decision.Refused("weak-token"),
            core.offer(RelayHello.Request(fpA, "s3cret"), "send-1"),
        )
        assertEquals(
            RelayCore.Decision.Refused("weak-token"),
            core.offer(RelayHello.Subscribe(fpA, "s3cret"), "recv-1"),
        )
        assertEquals(0, core.parkedCount())
    }

    @Test
    fun `weak and missing tokens are refused for a non-loopback bind`() {
        assertTrue(!RelayCore.isStrongToken(null))
        assertTrue(!RelayCore.isStrongToken("s3cret"))
        assertTrue(!RelayCore.isStrongToken("12345678901"))
        assertTrue(RelayCore.isStrongToken("Kx7!pQ2#vL9zRt4"))
        assertEquals("missing-token", RelayCore.bindTokenProblem(null, loopback = false))
        assertEquals("weak-token", RelayCore.bindTokenProblem("s3cret", loopback = false))
        assertNull(RelayCore.bindTokenProblem(null, loopback = true)) // local development unchanged
        assertNull(RelayCore.bindTokenProblem("Kx7!pQ2#vL9zRt4", loopback = false))

        val core = RelayCore(token = "s3cret", requireStrongToken = true)
        assertEquals(RelayCore.Decision.Refused("weak-token"), core.offer(RelayHello.Request(fpA, "s3cret"), "a"))
    }
}
