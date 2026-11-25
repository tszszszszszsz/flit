package app.flit.share.protocol

import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.HttpExchange
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import app.flit.share.protocol.transport.PipePair
import app.flit.share.protocol.transport.SimLinkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Circle's honest guarantees: only a key matching the claimed fingerprint may
 * sign, ids are remembered once inside a replay window, every peer converges
 * on the same order from scrambled delivery, the latest vote wins, and none
 * of the v2.2 routes change shape.
 */
class CircleCoreTest {

    private class CircleDelegate : ServerDelegate {
        val requests = mutableListOf<CircleRequest>()
        var answer: (CircleRequest) -> CircleResponse = {
            CircleResponse(ok = true, key = "group-key", members = listOf("AAAA"))
        }

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
            PrepareDecision.Accept(request.files.keys)

        override fun onCircle(request: CircleRequest, ip: String): CircleResponse {
            requests += request
            return answer(request)
        }
    }

    private class FakeExchange(
        override val method: String,
        override val path: String,
        override val origin: String = "192.0.2.10",
        private val body: String = "",
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpExchange {
        var status: Int = 0
        var response: String = ""
        private var at = 0

        override fun query(name: String): String? = null
        override fun header(name: String): String? = headers[name.lowercase()]
        override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int {
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (at >= bytes.size) return 0
            val n = minOf(len, bytes.size - at)
            System.arraycopy(bytes, at, buf, offset, n)
            at += n
            return n
        }
        override suspend fun respondText(status: Int, contentType: String, text: String) {
            this.status = status
            response = text
        }
        override suspend fun respondStatus(status: Int) {
            this.status = status
        }
    }

    private fun core(identity: TlsIdentity, delegate: ServerDelegate) = ServerCore(
        identity,
        ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-dest")),
        delegate,
    )

    private fun wire(request: CircleRequest, identity: TlsIdentity? = null): FakeExchange = FakeExchange(
        method = "POST",
        path = Protocol.PATH_CIRCLE_SYNC,
        body = WireJson.encode(request),
        headers = identity?.let {
            mapOf(Protocol.HEADER_CIRCLE_CERT.lowercase() to
                java.util.Base64.getEncoder().encodeToString(it.certificate.encoded))
        } ?: emptyMap(),
    )

    private fun event(
        identity: TlsIdentity,
        type: String,
        body: String,
        lamport: Long = 0L,
        createdMs: Long = System.currentTimeMillis(),
        circle: String = "circle-1",
        authorFp: String = identity.fingerprint,
    ): CircleEvent = CircleCore.create(circle, authorFp, identity, type, body, lamport, createdMs)

    @BeforeTest
    fun resetDedup() {
        CircleCore.clear()
    }

    // ---- signature --------------------------------------------------------

    @Test
    fun `created events verify and any edit breaks the signature`() {
        val identity = TlsIdentity.generate()
        val e = event(identity, CircleEvent.MESSAGE, """{"text":"hello"}""", lamport = 3)

        assertEquals(identity.fingerprint, e.authorFp)
        assertEquals("circle-1", e.circleId)
        assertTrue(e.sig.isNotBlank())
        assertTrue(CircleCore.verify(e))
        assertContentEquals(CircleCore.canonical(e), CircleCore.canonical(e.copy()))

        assertFalse(CircleCore.verify(e.copy(body = """{"text":"tampered"}""")))
        assertFalse(CircleCore.verify(e.copy(type = CircleEvent.LEAVE)))
        assertFalse(CircleCore.verify(e.copy(lamport = e.lamport + 1)))
        assertFalse(CircleCore.verify(e.copy(createdMs = e.createdMs + 1)))
        assertFalse(CircleCore.verify(e.copy(circleId = "other")))
        assertFalse(CircleCore.verify(e.copy(id = "other")))
        assertFalse(CircleCore.verify(e.copy(sig = "not base64 !!")))
        assertFalse(CircleCore.verify(e.copy(sig = "")))
    }

    @Test
    fun `forged author fingerprint is refused by verify and by the route`() {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        TlsIdentity.pin(victim.certificate)

        val signed = event(
            attacker, CircleEvent.MESSAGE, """{"text":"pretend to be the victim"}""",
            authorFp = victim.fingerprint,
        )
        assertFalse(CircleCore.verify(signed), "attacker's signature verified as the victim")

        val delegate = CircleDelegate().apply {
            answer = { req ->
                val kept = req.events.filter { CircleCore.verify(it) }
                CircleResponse(ok = false, events = kept, reason = "forged events dropped")
            }
        }
        val server = core(victim, delegate)
        val x = wire(CircleRequest(CircleRequest.ACTION_SYNC, "circle-1", events = listOf(signed)), attacker)
        runBlocking { server.handle(x) }
        assertEquals(200, x.status)
        val response = WireJson.decode<CircleResponse>(x.response)
        assertFalse(response.ok)
        assertTrue(response.events.isEmpty(), "forgery reached the delegate's event list")
    }

    // ---- dedup / replay / skew --------------------------------------------

    @Test
    fun `fresh ids pass once and stale or future events leave the window`() {
        val identity = TlsIdentity.generate()
        val now = 1_700_000_000_000L
        val fresh = event(identity, CircleEvent.MESSAGE, """{"text":"now"}""", createdMs = now)

        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(fresh, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.evaluate(fresh, now + 1))

        val old = event(
            identity, CircleEvent.MESSAGE, """{"text":"old"}""",
            createdMs = now - CircleCore.REPLAY_WINDOW_MS - 1,
        )
        assertEquals(CircleCore.Decision.OutsideWindow, CircleCore.evaluate(old, now))

        val farFuture = event(
            identity, CircleEvent.MESSAGE, """{"text":"future"}""",
            createdMs = now + CircleCore.CLOCK_SKEW_MS + 1,
        )
        assertEquals(CircleCore.Decision.OutsideWindow, CircleCore.evaluate(farFuture, now))

        val withinSkew = event(
            identity, CircleEvent.MESSAGE, """{"text":"skew"}""",
            createdMs = now + CircleCore.CLOCK_SKEW_MS,
        )
        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(withinSkew, now))
    }

    @Test
    fun `structurally broken events are invalid and the seen set is bounded`() {
        val identity = TlsIdentity.generate()
        val now = 1_700_000_000_000L
        val good = event(identity, CircleEvent.MESSAGE, """{"text":"ok"}""", createdMs = now)

        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(id = ""), now))
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(circleId = ""), now))
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(authorFp = " "), now))
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(lamport = -1), now))
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(createdMs = 0), now))
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(body = ""), now))
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(good.copy(type = "nope"), now))

        CircleCore.clear()
        assertTrue(CircleCore.fresh("id-1", now))
        assertFalse(CircleCore.fresh("id-1", now + 1))
        assertTrue(CircleCore.fresh("id-2", now + 2))
    }

    // ---- ordering / convergence -------------------------------------------

    @Test
    fun `scrambled delivery converges on one order and replays collapse`() {
        val a = TlsIdentity.generate()
        val b = TlsIdentity.generate()
        val now = System.currentTimeMillis()
        val events = listOf(
            event(a, CircleEvent.MESSAGE, """{"text":"a1"}""", lamport = 1, createdMs = now),
            event(b, CircleEvent.MESSAGE, """{"text":"b1"}""", lamport = 1, createdMs = now),
            event(a, CircleEvent.VOTE, """{"pollId":"p","option":0}""", lamport = 2, createdMs = now),
            event(b, CircleEvent.FILE, """{"name":"x","size":1,"sha256":"s","token":"t"}""", lamport = 3, createdMs = now),
            event(a, CircleEvent.POLL, """{"pollId":"p","question":"q","options":["a","b"]}""", lamport = 4, createdMs = now),
            event(b, CircleEvent.MESSAGE, """{"text":"b5"}""", lamport = 5, createdMs = now),
        )
        val scrambled = listOf(events[4], events[0], events[3], events[1], events[4], events[5], events[2], events[0])
        val otherOrder = listOf(events[5], events[2], events[1], events[0], events[3], events[4], events[5], events[3])

        assertEquals(8, CircleCore.order(scrambled).size, "order() sorts; it does not drop")
        val first = CircleCore.order(CircleCore.dedup(scrambled))
        val second = CircleCore.order(CircleCore.dedup(otherOrder))
        assertEquals(events.size, first.size)
        assertEquals(events.map { it.id }.toSet(), first.map { it.id }.toSet())
        assertEquals(first.map { it.id }, second.map { it.id }, "peers diverged on event order")
        assertTrue(first.zipWithNext().all { (x, y) -> x.lamport <= y.lamport })
        assertEquals(
            listOf(events[0], events[1]).sortedBy { it.authorFp }.first().id,
            first.first().id,
            "equal lamports must break by authorFp",
        )
    }

    // ---- vote change / kick tally -----------------------------------------

    @Test
    fun `latest vote per member wins and departed votes stop counting`() {
        val owner = TlsIdentity.generate()
        val m1 = TlsIdentity.generate()
        val m2 = TlsIdentity.generate()
        val outsider = TlsIdentity.generate()
        val members = listOf(owner.fingerprint, m1.fingerprint, m2.fingerprint)
        val poll = """{"pollId":"p1","question":"where","options":["a","b"]}"""
        val events = mutableListOf(
            event(owner, CircleEvent.POLL, poll, lamport = 1),
            event(m1, CircleEvent.VOTE, """{"pollId":"p1","option":0}""", lamport = 2),
            event(m2, CircleEvent.VOTE, """{"pollId":"p1","option":0}""", lamport = 3),
        )

        assertEquals(mapOf("0" to 2), CircleCore.tally(events, "p1", members))

        events += event(m1, CircleEvent.VOTE, """{"pollId":"p1","option":1}""", lamport = 4)
        assertEquals(mapOf("0" to 1, "1" to 1), CircleCore.tally(events, "p1", members), "old vote still counted")

        events += event(outsider, CircleEvent.VOTE, """{"pollId":"p1","option":1}""", lamport = 5)
        assertEquals(mapOf("0" to 1, "1" to 1), CircleCore.tally(events, "p1", members), "non-member vote counted")

        assertEquals(emptyMap<String, Int>(), CircleCore.tally(events, "p2", members))
        assertEquals(
            mapOf("0" to 1),
            CircleCore.tally(events, "p1", listOf(owner.fingerprint, m2.fingerprint)),
            "departed member's vote still counted",
        )
    }

    @Test
    fun `kick tally follows each member's latest yes or no`() {
        val owner = TlsIdentity.generate()
        val m1 = TlsIdentity.generate()
        val target = TlsIdentity.generate()
        val members = listOf(owner.fingerprint, m1.fingerprint, target.fingerprint)
        val kicks = mutableListOf(
            event(owner, CircleEvent.KICK, """{"kickId":"k1","targetFp":"${target.fingerprint}","approve":true}""", lamport = 10),
            event(m1, CircleEvent.KICK, """{"kickId":"k1","targetFp":"${target.fingerprint}","approve":true}""", lamport = 11),
        )

        assertEquals(2 to 0, CircleCore.kickTally(kicks, target.fingerprint, members))

        kicks += event(m1, CircleEvent.KICK, """{"kickId":"k1","targetFp":"${target.fingerprint}","approve":false}""", lamport = 12)
        assertEquals(1 to 1, CircleCore.kickTally(kicks, target.fingerprint, members), "old kick vote still counted")

        assertEquals(0 to 0, CircleCore.kickTally(kicks, owner.fingerprint, members))
        assertEquals(1 to 0, CircleCore.kickTally(kicks, target.fingerprint, listOf(owner.fingerprint, target.fingerprint)))
    }

    @Test
    fun `kick votes keyed by kickId inherit the opener target and admin kicks are not votes`() {
        val owner = TlsIdentity.generate()
        val m1 = TlsIdentity.generate()
        val target = TlsIdentity.generate()
        val members = listOf(owner.fingerprint, m1.fingerprint, target.fingerprint)
        val votes = listOf(
            event(owner, CircleEvent.KICK, """{"kickId":"k1","targetFp":"${target.fingerprint}","mode":"vote"}""", lamport = 10),
            event(m1, CircleEvent.VOTE, """{"kickId":"k1","approve":true}""", lamport = 11),
            event(m1, CircleEvent.VOTE, """{"kickId":"k1","approve":false}""", lamport = 12),
        )
        assertEquals(1 to 1, CircleCore.kickTally(votes, target.fingerprint, members))

        val adminKick = listOf(
            event(owner, CircleEvent.KICK, """{"kickId":"k2","targetFp":"${target.fingerprint}","mode":"admin"}""", lamport = 1),
        )
        assertEquals(0 to 0, CircleCore.kickTally(adminKick, target.fingerprint, members))
    }

    // ---- additive route ---------------------------------------------------

    @Test
    fun `circle routes answer while v2-shaped paths stay 404 and info stays untouched`() {
        val identity = TlsIdentity.generate()
        val delegate = CircleDelegate()
        val server = core(identity, delegate)
        val request = CircleRequest(
            action = CircleRequest.ACTION_JOIN,
            circleId = "circle-1",
            invite = "bearer-token",
            memberFp = identity.fingerprint,
        )
        val join = FakeExchange("POST", Protocol.PATH_CIRCLE_JOIN, body = WireJson.encode(request))
        val sync = FakeExchange("POST", Protocol.PATH_CIRCLE_SYNC, body = WireJson.encode(request))
        val bogus = FakeExchange("POST", "${Protocol.API_ROOT}/circle/sync", body = "{}")
        val malformed = FakeExchange("POST", Protocol.PATH_CIRCLE_SYNC, body = "{")
        val info = FakeExchange("GET", Protocol.PATH_INFO)

        runBlocking {
            server.handle(join)
            server.handle(sync)
            server.handle(bogus)
            server.handle(malformed)
            server.handle(info)
        }
        assertEquals(200, join.status)
        assertEquals(200, sync.status)
        assertEquals(404, bogus.status)
        assertEquals(400, malformed.status)
        assertEquals(200, info.status)

        val answered = WireJson.decode<CircleResponse>(sync.response)
        assertTrue(answered.ok)
        assertEquals("group-key", answered.key)
        assertEquals(2, delegate.requests.size)
        assertEquals("bearer-token", delegate.requests.first().invite)
    }

    @Test
    fun `default delegate refuses a circle request without a circle implementation`() {
        val bare = object : ServerDelegate {
            override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
                PrepareDecision.Accept(request.files.keys)
        }
        val refused = bare.onCircle(CircleRequest(CircleRequest.ACTION_JOIN, "c"))
        assertFalse(refused.ok)
        assertTrue(refused.reason.isNotBlank())
    }

    // ---- end to end over a second carrier ---------------------------------

    @Test
    fun `circleJoin and circleSync reach the delegate through the link server`() = runBlocking {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val delegate = CircleDelegate()
        val seed = event(receiver, CircleEvent.MESSAGE, """{"text":"welcome"}""", lamport = 1)
        delegate.answer = { CircleResponse(ok = true, key = "k", members = listOf(receiver.fingerprint), events = listOf(seed)) }

        val server = ProtocolServer(
            receiver,
            delegate,
            ServerConfig(port = 0, tls = false, destDir = Files.createTempDirectory("circle-link")),
        )
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val client = ProtocolClient(sender, links = SimLinkProvider(sender, tls = false, inbox))
        try {
            val serve = (1..2).map {
                launch(Dispatchers.IO) { server.serve(inbox.receive().serverLink("192.0.2.31")) }
            }
            val target = SendTarget("127.0.0.1", 53317, tls = false)
            val joined = client.circleJoin(
                target,
                CircleRequest(CircleRequest.ACTION_JOIN, "circle-1", "invite", sender.fingerprint),
                timeoutMs = 10_000,
            )
            assertNotNull(joined)
            assertTrue(joined.ok)
            assertEquals("k", joined.key)
            assertEquals(listOf(receiver.fingerprint), joined.members)
            assertEquals(seed.id, joined.events.first().id)

            val outbound = event(sender, CircleEvent.MESSAGE, """{"text":"hi"}""", lamport = 2)
            val synced = client.circleSync(
                target, "circle-1", sender.fingerprint, listOf(outbound), timeoutMs = 10_000,
            )
            assertNotNull(synced)
            assertTrue(synced.ok)

            serve.joinAll()
            assertEquals(2, delegate.requests.size)
            assertEquals(CircleRequest.ACTION_JOIN, delegate.requests[0].action)
            assertEquals(CircleRequest.ACTION_SYNC, delegate.requests[1].action)
            assertEquals(outbound.id, delegate.requests[1].events.first().id)
            assertTrue(CircleCore.verify(delegate.requests[1].events.first()), "pinned sender could not be verified by receiver")
        } finally {
            client.close()
        }
    }
}
