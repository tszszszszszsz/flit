package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.HttpExchange
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Adversarial edges the v2.2-shaped happy-path suite never touched: bounded
 * bodies/fields, malformed certs and JSON, an overflow-safe replay window,
 * cert↔fingerprint binding without pin poisoning, total order independent of
 * delivery order, and honest 400/403 route answers. No v2.2 route changes.
 */
class CircleHardeningTest {

    private class FakeExchange(
        override val method: String,
        override val path: String,
        private val bodyBytes: ByteArray,
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpExchange {
        var status: Int = 0
        var contentType: String = ""
        var response: String = ""
        private var at = 0

        override val origin: String = "192.0.2.10"
        override fun query(name: String): String? = null
        override fun header(name: String): String? = headers[name.lowercase()]

        override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int {
            if (at >= bodyBytes.size) return 0
            val n = minOf(len, bodyBytes.size - at)
            System.arraycopy(bodyBytes, at, buf, offset, n)
            at += n
            return n
        }

        override suspend fun respondText(status: Int, contentType: String, text: String) {
            this.status = status
            this.contentType = contentType
            this.response = text
        }

        override suspend fun respondStatus(status: Int) {
            this.status = status
        }
    }

    private val delegate = object : ServerDelegate {
        var circleCalls: Int = 0

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
            PrepareDecision.Accept(request.files.keys)

        override fun onCircle(request: CircleRequest, ip: String): CircleResponse {
            circleCalls++
            return CircleResponse(ok = true, key = "group-key")
        }
    }

    private fun event(
        identity: TlsIdentity,
        body: String = """{"text":"ok"}""",
        lamport: Long = 1L,
        createdMs: Long = 1_700_000_000_000L,
        circleId: String = "circle-1",
        authorFp: String = identity.fingerprint,
    ): CircleEvent = CircleCore.create(circleId, authorFp, identity, CircleEvent.MESSAGE, body, lamport, createdMs)

    private fun exchange(
        method: String = "POST",
        path: String = Protocol.PATH_CIRCLE_SYNC,
        raw: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): FakeExchange = FakeExchange(method, path, raw, headers)

    private fun call(server: ServerCore, x: FakeExchange): FakeExchange {
        runBlocking { server.handle(x) }
        return x
    }

    @BeforeTest
    fun reset() = CircleCore.clear()

    @Test
    fun `declared circle bounds are sized for real wire values and enforced`() {
        assertTrue(CircleCore.MAX_FIELD_CHARS >= 64, "a SHA-256 fingerprint is 64 hex chars")
        assertTrue(CircleCore.MAX_SIG_CHARS >= 344, "a 2048-bit RSA signature is ~344 base64 chars")
        assertTrue(CircleCore.MAX_CERT_CHARS >= 1_500, "a 2048-bit self-signed cert is ~1.2 KB of DER")
        assertTrue(CircleCore.MAX_BODY_CHARS >= 8 * 1024, "chat and metadata JSON must fit")
        assertTrue(CircleCore.MAX_EVENTS_PER_REQUEST >= 500, "the store caps a log at 500 events")
        assertTrue(CircleCore.MAX_REQUEST_BYTES > CircleCore.MAX_BODY_CHARS)
        assertTrue(CircleCore.MAX_SEEN >= CircleCore.MAX_EVENTS_PER_REQUEST)

        val identity = TlsIdentity.generate()
        assertTrue(CircleCore.validShape(event(identity)))
        assertFalse(CircleCore.validShape(event(identity).copy(id = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1))))
        assertFalse(CircleCore.fresh("x".repeat(CircleCore.MAX_FIELD_CHARS + 1)))
    }

    @Test
    fun `evaluate accept and fresh share one bounded replay set with exact precedence`() {
        val identity = TlsIdentity.generate()
        val now = 1_700_000_000_000L
        val e = event(identity, createdMs = now)

        // Invalid (shape) beats window and dedup.
        assertEquals(
            CircleCore.Decision.Invalid,
            CircleCore.evaluate(e.copy(id = ""), now + CircleCore.REPLAY_WINDOW_MS + CircleCore.CLOCK_SKEW_MS + 1L),
        )
        // Reserving the id with fresh makes later events Duplicate, but a
        // broken clock still reports OutsideWindow first.
        assertTrue(CircleCore.fresh(e.id, now))
        assertEquals(
            CircleCore.Decision.OutsideWindow,
            CircleCore.evaluate(e.copy(createdMs = now - CircleCore.REPLAY_WINDOW_MS - 1L), now),
        )
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.evaluate(e, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.accept(e, now))
        assertFalse(CircleCore.fresh(e.id, now), "fresh and evaluate must share the same set")

        val other = e.copy(id = "other-id")
        assertEquals(CircleCore.Decision.Accept, CircleCore.accept(other, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.evaluate(other, now))
    }

    // ---- size bounds -------------------------------------------------------

    @Test
    fun `oversized fields and bodies are invalid and never consume the id`() {
        val identity = TlsIdentity.generate()
        val now = 1_700_000_000_000L
        val good = event(identity, createdMs = now)
        assertTrue(CircleCore.validShape(good))

        val oversized = listOf(
            good.copy(id = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)),
            good.copy(circleId = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)),
            good.copy(authorFp = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)),
            good.copy(body = "x".repeat(CircleCore.MAX_BODY_CHARS + 1)),
            good.copy(cert = "x".repeat(CircleCore.MAX_CERT_CHARS + 1)),
        )
        for (e in oversized) {
            assertFalse(CircleCore.validShape(e))
            assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(e, now))
            assertEquals(CircleCore.Decision.Invalid, CircleCore.accept(e, now))
            assertFalse(CircleCore.verify(e))
        }
        assertFalse(CircleCore.verify(good.copy(sig = "x".repeat(CircleCore.MAX_SIG_CHARS + 1))))

        // Invalid events do not reserve the id: the honest copy still passes.
        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(good, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.accept(good, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.evaluate(good, now))

        assertFailsWith<IllegalArgumentException> {
            CircleCore.create(
                "circle-1", identity.fingerprint, identity, CircleEvent.MESSAGE,
                "x".repeat(CircleCore.MAX_BODY_CHARS + 1),
            )
        }
    }

    // ---- cert / fingerprint binding ---------------------------------------

    @Test
    fun `embedded cert is bound to authorFp and a rejected signature never poisons pins`() {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        TlsIdentity.pin(victim.certificate)

        val forged = event(attacker, authorFp = victim.fingerprint)
        assertFalse(CircleCore.verify(forged), "attacker signed as the victim")
        assertContentEquals(victim.certificate.encoded, TlsIdentity.pinned(victim.fingerprint)?.encoded)

        val stranger = TlsIdentity.generate()
        val bad = CircleEvent(
            id = "bad-1", circleId = "circle-1", authorFp = stranger.fingerprint,
            lamport = 1L, type = CircleEvent.MESSAGE, body = """{"text":"x"}""",
            sig = Base64.getEncoder().encodeToString(ByteArray(16)),
            createdMs = 1_700_000_000_000L,
            cert = Base64.getEncoder().encodeToString(stranger.certificate.encoded),
        )
        assertFalse(CircleCore.verify(bad))
        assertNull(TlsIdentity.pinned(stranger.fingerprint), "forged cert was pinned before signature check")
        assertFalse(CircleCore.verify(bad.copy(cert = "not base64 !!")))
        assertFalse(CircleCore.verify(bad.copy(cert = Base64.getEncoder().encodeToString("junk".toByteArray()))))
        assertNull(TlsIdentity.pinned(stranger.fingerprint))

        // The cert-in-hand helper verifies without touching the global map.
        val helper = TlsIdentity.generate()
        val unsigned = bad.copy(
            id = "helper-1", authorFp = helper.fingerprint, sig = "",
            cert = Base64.getEncoder().encodeToString(helper.certificate.encoded),
        )
        val canonical = CircleCore.canonical(unsigned)
        val sig = helper.sign(canonical)
        assertTrue(TlsIdentity.verifyWithCertificate(helper.certificate, canonical, sig))
        assertNull(TlsIdentity.pinned(helper.fingerprint))
        val signed = CircleCore.sign(unsigned, helper)
        assertTrue(CircleCore.verify(signed))
        assertFailsWith<IllegalArgumentException> { CircleCore.sign(signed, helper) }
    }

    // ---- replay window -----------------------------------------------------

    @Test
    fun `extreme clocks cannot overflow the replay window`() {
        val identity = TlsIdentity.generate()
        val far = event(identity, body = """{"text":"far"}""", createdMs = Long.MAX_VALUE)
        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(far, Long.MAX_VALUE))

        val old = event(identity, body = """{"text":"old"}""", createdMs = 1L)
        assertEquals(CircleCore.Decision.OutsideWindow, CircleCore.evaluate(old, Long.MAX_VALUE))

        val future = event(identity, body = """{"text":"future"}""", createdMs = Long.MAX_VALUE)
        assertEquals(CircleCore.Decision.OutsideWindow, CircleCore.evaluate(future, 1L))
        assertEquals(
            CircleCore.Decision.Invalid,
            CircleCore.evaluate(old.copy(createdMs = Long.MIN_VALUE), 1L),
            "a non-positive clock is structural, not a window miss",
        )
    }

    // ---- malformed JSON bodies --------------------------------------------

    @Test
    fun `malformed and oversized JSON bodies are ignored by tallies`() {
        val owner = TlsIdentity.generate()
        val member = TlsIdentity.generate()
        val members = listOf(owner.fingerprint, member.fingerprint)
        fun vote(id: String, body: String) = CircleEvent(
            id = id, circleId = "circle-1", authorFp = owner.fingerprint,
            lamport = 1L, type = CircleEvent.VOTE, body = body, sig = "s",
            createdMs = 1_700_000_000_000L,
        )
        val votes = listOf(
            vote("v1", "{"),
            vote("v2", "[]"),
            vote("v3", "null"),
            vote("v4", """{"pollId":"p1","option":-1}"""),
            vote("v5", """{"pollId":"p1","option":"x"}"""),
            vote("v6", "x".repeat(CircleCore.MAX_BODY_CHARS + 1)),
            vote("v7", """{"pollId":"p1","option":0}"""),
        )
        assertEquals(mapOf("0" to 1), CircleCore.tally(votes, "p1", members))

        val kicks = listOf(
            vote("k1", "{"),
            vote("k2", """{"kickId":"k","targetFp":"${member.fingerprint}","approve":"maybe"}"""),
        )
        assertEquals(0 to 0, CircleCore.kickTally(kicks, member.fingerprint, members))
    }

    // ---- file announcement body -------------------------------------------

    @Test
    fun `file body gate refuses extra keys wrong types and oversized metadata`() {
        assertTrue(CircleCore.validFileBody("""{"name":"a.gflit","size":12,"sha256":"deadbeef"}"""))
        assertTrue(CircleCore.validFileBody("""{"name":"a","size":0,"sha256":"s"}"""))

        val longName = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)
        val longSha = "y".repeat(CircleCore.MAX_FIELD_CHARS + 1)
        val rejected = listOf(
            "", " ", "{", "[]", "null",
            "x".repeat(CircleCore.MAX_BODY_CHARS + 1),
            """{"name":"a","size":12}""",
            """{"name":"a","sha256":"s"}""",
            """{"size":12,"sha256":"s"}""",
            """{"name":"","size":12,"sha256":"s"}""",
            """{"name":"a","size":12,"sha256":""}""",
            """{"name":"a","size":-1,"sha256":"s"}""",
            """{"name":"a","size":"12","sha256":"s"}""",
            """{"name":"a","size":1.5,"sha256":"s"}""",
            """{"name":"a","size":null,"sha256":"s"}""",
            """{"name":"a","size":12,"sha256":"s","token":"t"}""",
            """{"name":"a","size":12,"sha256":"s","path":"/cache/circle/a"}""",
            """{"name":"a","size":12,"sha256":"s","spoolPath":"/cache/circle/a"}""",
            """{"name":"a","size":12,"sha256":"s","extra":1}""",
            """{"name":"$longName","size":12,"sha256":"s"}""",
            """{"name":"a","size":12,"sha256":"$longSha"}""",
        )
        for (body in rejected) {
            assertFalse(CircleCore.validFileBody(body), "accepted a bad file body: ${body.take(80)}")
        }
    }

    // ---- deterministic order ----------------------------------------------

    @Test
    fun `dedup winner for two different events sharing one id is delivery-order independent`() {
        val a = TlsIdentity.generate()
        val b = TlsIdentity.generate()
        val e1 = event(a, body = """{"text":"a"}""")
        val e2 = event(b, body = """{"text":"b"}""")
        val divergent = e1.copy(body = """{"text":"divergent"}""") // SAME id, different content

        val left = listOf(e1, divergent, e2)
        val right = listOf(e2, divergent, e1)
        val winnerLeft = CircleCore.dedup(left).single { it.id == e1.id }
        val winnerRight = CircleCore.dedup(right).single { it.id == e1.id }
        assertEquals(e1.id, winnerLeft.id)
        assertEquals(e1.body, winnerLeft.body, "tie-break must pick the smaller body")
        assertEquals(winnerLeft, winnerRight, "peers kept different copies of one id")
        assertEquals(2, CircleCore.dedup(left).size)
        assertEquals(
            CircleCore.order(CircleCore.dedup(left)).map { it.id },
            CircleCore.order(CircleCore.dedup(right)).map { it.id },
        )
        assertEquals(CircleCore.order(left).map { it.id }, CircleCore.order(right).map { it.id })
    }

    @Test
    fun `kick opener target resolution is independent of delivery order`() {
        val owner = TlsIdentity.generate()
        val voter = TlsIdentity.generate()
        val target = TlsIdentity.generate()
        val members = listOf(owner.fingerprint, voter.fingerprint, target.fingerprint)
        fun event(id: String, authorFp: String, lamport: Long, type: String, body: String) = CircleEvent(
            id = id, circleId = "circle-1", authorFp = authorFp, lamport = lamport,
            type = type, body = body, sig = "s", createdMs = 1_700_000_000_000L,
        )
        val openerA = event(
            "kick-a", owner.fingerprint, 1L, CircleEvent.KICK,
            """{"kickId":"k","targetFp":"${owner.fingerprint}"}""",
        )
        val openerB = event(
            "kick-b", owner.fingerprint, 2L, CircleEvent.KICK,
            """{"kickId":"k","targetFp":"${target.fingerprint}"}""",
        )
        val vote = event(
            "vote-k", voter.fingerprint, 3L, CircleEvent.VOTE,
            """{"kickId":"k","approve":true}""",
        )
        assertEquals(
            CircleCore.kickTally(listOf(openerA, openerB, vote), target.fingerprint, members),
            CircleCore.kickTally(listOf(vote, openerB, openerA), target.fingerprint, members),
        )
        // The later opener (lamport 2) determines the target, so both the
        // opener's own yes and the voter's yes count for that target.
        assertEquals(2 to 0, CircleCore.kickTally(listOf(vote, openerB, openerA), target.fingerprint, members))
    }

    // ---- route status codes ------------------------------------------------

    @Test
    fun `circle route gives exact status and reason for every refusal branch`() {
        val identity = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val server = ServerCore(
            identity,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-hardening")),
            delegate,
        )
        val request = CircleRequest(CircleRequest.ACTION_SYNC, "circle-1", memberFp = identity.fingerprint)
        val validBody = WireJson.encode(request).toByteArray(Charsets.UTF_8)
        val certHeader = Protocol.HEADER_CIRCLE_CERT.lowercase()

        fun assertAnswer(x: FakeExchange, status: Int, reason: String) {
            assertEquals(status, x.status, "status for: ${x.response}")
            assertEquals(reason, x.response, "reason")
        }

        assertAnswer(
            call(server, exchange(raw = ByteArray(CircleCore.MAX_REQUEST_BYTES + 1))),
            400, "Circle request too large",
        )
        assertAnswer(call(server, exchange(raw = "{".toByteArray(Charsets.UTF_8))), 400, "Invalid circle request")
        assertAnswer(
            call(server, exchange(raw = WireJson.encode(request.copy(action = "nope")).toByteArray(Charsets.UTF_8))),
            400, "Invalid circle request",
        )

        val tooMany = request.copy(events = List(CircleCore.MAX_EVENTS_PER_REQUEST + 1) {
            CircleEvent(
                id = "e$it", circleId = "circle-1", authorFp = identity.fingerprint,
                lamport = it.toLong(), type = CircleEvent.MESSAGE, body = "{}",
                sig = "s", createdMs = 1_700_000_000_000L,
            )
        })
        assertAnswer(
            call(server, exchange(raw = WireJson.encode(tooMany).toByteArray(Charsets.UTF_8))),
            400, "Invalid circle request",
        )

        val badEvent = request.copy(events = listOf(event(identity).copy(id = "")))
        assertAnswer(
            call(server, exchange(raw = WireJson.encode(badEvent).toByteArray(Charsets.UTF_8))),
            400, "Invalid circle request",
        )

        assertAnswer(
            call(server, exchange(raw = validBody, headers = mapOf(certHeader to "not base64 !!"))),
            403, "Malformed sender certificate",
        )
        val attackerCert = Base64.getEncoder().encodeToString(attacker.certificate.encoded)
        assertAnswer(
            call(server, exchange(raw = validBody, headers = mapOf(certHeader to attackerCert))),
            403, "Certificate does not match member fingerprint",
        )

        val ownCert = Base64.getEncoder().encodeToString(identity.certificate.encoded)
        val accepted = call(server, exchange(raw = validBody, headers = mapOf(certHeader to ownCert)))
        assertEquals(200, accepted.status)
        assertEquals("group-key", WireJson.decode<CircleResponse>(accepted.response).key)
        val relayed = call(server, exchange(raw = validBody))
        assertAnswer(relayed, 403, "Missing sender certificate")

        val throwing = object : ServerDelegate {
            override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
                PrepareDecision.Accept(request.files.keys)

            override fun onCircle(request: CircleRequest, ip: String): CircleResponse =
                throw IllegalStateException("delegate exploded")
        }
        val brokenServer = ServerCore(
            identity,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-hardening-throw")),
            throwing,
        )
        assertAnswer(
            call(brokenServer, exchange(raw = validBody, headers = mapOf(certHeader to ownCert))),
            500, "Circle delegate error",
        )
    }

    // ---- route-level JOIN proof gate ---------------------------------------

    private fun joinProofEvent(
        hostFp: String,
        circleId: String,
        invite: String,
        member: TlsIdentity,
    ): CircleEvent {
        val bucket = CircleCore.joinProofBucket(System.currentTimeMillis())
        // Centre the proof inside its bucket: the route reads the clock a
        // moment later, and a test must not flake on a bucket rollover.
        val createdMs = bucket * CircleCore.JOIN_PROOF_BUCKET_MS + CircleCore.JOIN_PROOF_BUCKET_MS / 2
        val body = CircleCore.joinProofBody(hostFp, circleId, invite, member.fingerprint, bucket)
        return CircleCore.create(circleId, member.fingerprint, member, CircleEvent.JOIN, body, 0L, createdMs)
    }

    @Test
    fun `JOIN without a proof is a signed refusal and never reaches the delegate`() {
        val host = TlsIdentity.generate()
        val member = TlsIdentity.generate()
        val server = ServerCore(
            host,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-join-empty")),
            delegate,
        )
        val request = CircleRequest(
            CircleRequest.ACTION_JOIN, "circle-1", invite = "invite-token", memberFp = member.fingerprint,
        )
        val certHeader = Protocol.HEADER_CIRCLE_CERT.lowercase() to
            Base64.getEncoder().encodeToString(member.certificate.encoded)
        val answered = call(
            server,
            exchange(
                path = Protocol.PATH_CIRCLE_JOIN,
                raw = WireJson.encode(request).toByteArray(Charsets.UTF_8),
                headers = mapOf(certHeader),
            ),
        )
        assertEquals(200, answered.status)
        assertEquals("application/json", answered.contentType)
        val refusal = WireJson.decode<CircleResponse>(answered.response)
        assertFalse(refusal.ok)
        assertEquals("join proof required", refusal.reason)
        assertEquals(0, delegate.circleCalls, "the delegate must never see an unproven JOIN")
        assertTrue(CircleResponse.verify(refusal, host.fingerprint))
    }

    @Test
    fun `a valid signed JOIN proof reaches the delegate`() {
        val host = TlsIdentity.generate()
        val member = TlsIdentity.generate()
        val server = ServerCore(
            host,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-join-valid")),
            delegate,
        )
        val request = CircleRequest(
            CircleRequest.ACTION_JOIN, "circle-1", invite = "invite-token",
            memberFp = member.fingerprint,
            events = listOf(joinProofEvent(host.fingerprint, "circle-1", "invite-token", member)),
        )
        val certHeader = Protocol.HEADER_CIRCLE_CERT.lowercase() to
            Base64.getEncoder().encodeToString(member.certificate.encoded)
        val answered = call(
            server,
            exchange(
                path = Protocol.PATH_CIRCLE_JOIN,
                raw = WireJson.encode(request).toByteArray(Charsets.UTF_8),
                headers = mapOf(certHeader),
            ),
        )
        assertEquals(200, answered.status)
        val response = WireJson.decode<CircleResponse>(answered.response)
        assertTrue(response.ok)
        assertEquals("group-key", response.key)
        assertEquals(1, delegate.circleCalls)
    }

    @Test
    fun `a forged JOIN proof is refused with a verifiable signature`() {
        val host = TlsIdentity.generate()
        val member = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val server = ServerCore(
            host,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-join-forged")),
            delegate,
        )
        val proof = joinProofEvent(host.fingerprint, "circle-1", "invite-token", member)
        // Correct host/circle/invite/bucket digest, but signed by a stranger
        // who claims the member's fingerprint and presents its own cert.
        val forged = CircleCore.sign(
            proof.copy(
                id = "forged-${proof.id}",
                cert = Base64.getEncoder().encodeToString(attacker.certificate.encoded),
                sig = "",
            ),
            attacker,
        )
        val request = CircleRequest(
            CircleRequest.ACTION_JOIN, "circle-1", invite = "invite-token",
            memberFp = member.fingerprint, events = listOf(forged),
        )
        val certHeader = Protocol.HEADER_CIRCLE_CERT.lowercase() to
            Base64.getEncoder().encodeToString(member.certificate.encoded)
        val answered = call(
            server,
            exchange(
                path = Protocol.PATH_CIRCLE_JOIN,
                raw = WireJson.encode(request).toByteArray(Charsets.UTF_8),
                headers = mapOf(certHeader),
            ),
        )
        assertEquals(200, answered.status)
        assertEquals("application/json", answered.contentType)
        val refusal = WireJson.decode<CircleResponse>(answered.response)
        assertFalse(refusal.ok)
        assertEquals("invalid join proof", refusal.reason)
        assertEquals(0, delegate.circleCalls)
        assertTrue(CircleResponse.verify(refusal, host.fingerprint))
    }

    @Test
    fun `v2 2 routes keep their exact status behavior`() {
        val identity = TlsIdentity.generate()
        val server = ServerCore(
            identity,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("circle-hardening-v22")),
            delegate,
        )
        val info = call(server, exchange("GET", Protocol.PATH_INFO, ByteArray(0)))
        assertEquals(200, info.status)
        val keys = (WireJson.json.parseToJsonElement(info.response) as JsonObject).keys
        assertEquals(
            setOf("alias", "version", "deviceModel", "deviceType", "fingerprint", "port", "protocol", "download"),
            keys,
            "the v2.2 info payload must not gain or lose a key",
        )
        assertEquals(
            404,
            call(server, exchange("POST", "${Protocol.API_ROOT}/circle/sync", "{}".toByteArray(Charsets.UTF_8))).status,
        )
        assertEquals(
            400,
            call(server, exchange("POST", Protocol.PATH_PANIC, "{".toByteArray(Charsets.UTF_8))).status,
        )
    }
}
