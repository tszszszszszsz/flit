package app.flit.share.protocol

import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.model.PanicAlert
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.panic.PanicCore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panic route's honest guarantees: only a key matching the claimed
 * fingerprint may sign, delivery happens once per id, relays stop at the
 * hop limit, and no v2.2 route changes shape.
 */
class PanicTest {

    private class RecordingDelegate : ServerDelegate {
        val alerts = mutableListOf<PanicAlert>()
        val origins = mutableListOf<String>()

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
            PrepareDecision.Accept(request.files.keys)

        override fun onPanic(alert: PanicAlert, ip: String) {
            alerts += alert
            origins += ip
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
        ServerConfig(tls = false, destDir = Files.createTempDirectory("panic-dest")),
        delegate,
    )

    private fun wire(alert: PanicAlert, cert: TlsIdentity): FakeExchange = FakeExchange(
        method = "POST",
        path = Protocol.PATH_PANIC,
        body = WireJson.encode(alert),
        headers = mapOf(
            Protocol.HEADER_PANIC_CERT.lowercase() to
                Base64.getEncoder().encodeToString(cert.certificate.encoded),
        ),
    )

    @BeforeTest
    fun resetDedup() {
        PanicCore.clear()
    }

    // ---- signature --------------------------------------------------------

    @Test
    fun `created alerts verify and any edit breaks the signature`() {
        val identity = TlsIdentity.generate()
        val alert = PanicCore.create(identity.fingerprint, "need a ride", identity)
        assertEquals(identity.fingerprint, alert.senderFp)
        assertTrue(PanicCore.verify(alert))

        assertFalse(PanicCore.verify(alert.copy(text = "all clear")), "edited note verified")
        assertFalse(PanicCore.verify(alert.copy(sentMs = alert.sentMs + 1)), "edited time verified")
        assertFalse(PanicCore.verify(alert.copy(hops = alert.hops + 1)), "edited hops verified")
        assertFalse(PanicCore.verify(alert.copy(sig = "not base64 !!")), "garbage sig verified")
    }

    @Test
    fun `forged sender fingerprint is refused by verify and by the route`() {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val signed = PanicCore.create(attacker.fingerprint, "pretend to be the victim", attacker)
        val forged = signed.copy(senderFp = victim.fingerprint)

        assertFalse(PanicCore.verify(forged), "attacker's cert verified as the victim")

        val delegate = RecordingDelegate()
        val server = core(victim, delegate)
        val x = wire(forged, attacker)
        runBlocking { server.handle(x) }
        assertEquals(403, x.status)
        assertTrue(delegate.alerts.isEmpty(), "forgery reached the delegate")
    }

    // ---- dedup ------------------------------------------------------------

    @Test
    fun `fresh ids pass once and the route delivers an alert once`() {
        assertTrue(PanicCore.fresh("one", 1_000L))
        assertFalse(PanicCore.fresh("one", 1_001L))
        assertTrue(PanicCore.fresh("two", 1_002L))

        val identity = TlsIdentity.generate()
        val alert = PanicCore.create(identity.fingerprint, "help", identity)
        val delegate = RecordingDelegate()
        val server = core(identity, delegate)
        val first = wire(alert, identity)
        val replay = wire(alert, identity)
        runBlocking {
            server.handle(first)
            server.handle(replay)
        }
        assertEquals(200, first.status)
        assertEquals(200, replay.status)
        assertEquals(1, delegate.alerts.size)
        assertEquals(alert.id, delegate.alerts.first().id)
        assertEquals("192.0.2.10", delegate.origins.first())
    }

    // ---- hops and replay --------------------------------------------------

    @Test
    fun `hop limit stops forwarding and replay window rejects stale alerts`() {
        val identity = TlsIdentity.generate()
        val alert = PanicCore.create(identity.fingerprint, "relay me", identity)
        val now = System.currentTimeMillis()

        assertEquals(PanicCore.Decision.Accept, PanicCore.evaluate(alert, now))
        assertEquals(
            PanicCore.Decision.HopLimit,
            PanicCore.evaluate(alert.copy(hops = PanicCore.DEFAULT_MAX_HOPS), now),
        )
        assertEquals(
            PanicCore.Decision.OutsideWindow,
            PanicCore.evaluate(alert, now + PanicCore.REPLAY_WINDOW_MS + 1),
        )
        assertNull(
            PanicCore.forward(alert.copy(hops = PanicCore.DEFAULT_MAX_HOPS - 1), identity.fingerprint, identity),
        )

        val next = PanicCore.forward(alert, identity.fingerprint, identity)
        assertNotNull(next)
        assertEquals(1, next.hops)
        assertEquals(identity.fingerprint, next.senderFp)
        assertTrue(PanicCore.verify(next), "relay's re-signed hop did not verify")

        val delegate = RecordingDelegate()
        val server = core(identity, delegate)
        val spent = alert.copy(hops = PanicCore.DEFAULT_MAX_HOPS)
        val x = wire(spent.copy(sig = identity.signPanic(PanicCore.canonical(spent))), identity)
        runBlocking { server.handle(x) }
        assertEquals(200, x.status)
        assertTrue(delegate.alerts.isEmpty(), "spent-hop alert reached the delegate")
    }

    // ---- additive route ---------------------------------------------------

    @Test
    fun `v2-shaped panic path stays 404 and info stays untouched`() {
        val identity = TlsIdentity.generate()
        val delegate = RecordingDelegate()
        val server = core(identity, delegate)

        val fake = FakeExchange("POST", "${Protocol.API_ROOT}/panic", body = "{}")
        val info = FakeExchange("GET", Protocol.PATH_INFO)
        runBlocking {
            server.handle(fake)
            server.handle(info)
        }
        assertEquals(404, fake.status)
        assertEquals(200, info.status)
        assertTrue(delegate.alerts.isEmpty())
    }

    // ---- end-to-end over a second carrier ---------------------------------

    @Test
    fun `sendPanic reaches the delegate through the link server`() = runBlocking {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val delegate = RecordingDelegate()
        val server = ProtocolServer(
            receiver,
            delegate,
            ServerConfig(port = 0, tls = false, destDir = Files.createTempDirectory("panic-link")),
        )
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val client = ProtocolClient(sender, links = SimLinkProvider(sender, tls = false, inbox))
        try {
            val serve = launch(Dispatchers.IO) { server.serve(inbox.receive().serverLink("192.0.2.31")) }
            val alert = PanicCore.create(sender.fingerprint, "please check on me", sender)
            val accepted = client.sendPanic(
                SendTarget("127.0.0.1", 53317, tls = false),
                alert,
                timeoutMs = 10_000,
            )
            assertTrue(accepted, "receiver never answered 200")
            serve.join()
            assertEquals(1, delegate.alerts.size)
            assertEquals(alert.id, delegate.alerts.first().id)
            assertEquals(sender.fingerprint, delegate.alerts.first().senderFp)
            assertEquals("please check on me", delegate.alerts.first().text)
            assertEquals("192.0.2.31", delegate.origins.first())
        } finally {
            client.close()
        }
    }
}
