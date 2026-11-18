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
}
