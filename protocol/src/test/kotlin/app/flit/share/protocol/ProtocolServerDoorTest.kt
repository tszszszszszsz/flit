package app.flit.share.protocol

import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import app.flit.share.protocol.transport.PipePair
import app.flit.share.protocol.transport.SimLinkProvider
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The [ProtocolServer.serve] door (milestones E and B): a link that did
 * not arrive over the TCP listener — a relayed pipe, a radio carrier —
 * must walk exactly the same route semantics as one that did. One link,
 * one exchange: here a register, answered by the same core that serves
 * the listener.
 */
class ProtocolServerDoorTest {

    @Test
    fun `an externally served link answers with the listener's route semantics`() = runBlocking {
        val identity = TlsIdentity.generate()
        val registered = AtomicReference<Pair<PeerInfo, String>>()
        val delegate = object : ServerDelegate {
            override fun onRegister(info: PeerInfo, ip: String) {
                registered.set(info to ip)
            }

            // This door test never uploads; accept-all keeps the delegate total.
            override fun onPrepare(request: app.flit.share.protocol.model.PrepareUploadRequest, peerIp: String) =
                app.flit.share.protocol.server.PrepareDecision.Accept(request.files.keys)
        }
        val server = ProtocolServer(
            identity,
            delegate,
            ServerConfig(
                alias = "door-node",
                port = 0,
                tls = false,
                destDir = Files.createTempDirectory("door-dest"),
            ),
        )
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val cli = ProtocolClient(identity, links = SimLinkProvider(identity, tls = false, inbox))
        try {
            val serveJob = launch(Dispatchers.IO) {
                server.serve(inbox.receive().serverLink("127.0.0.1"))
            }
            val self = PeerInfo(
                alias = "door-walker", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                port = 53317, protocol = "https",
            )
            val answered = cli.register(SendTarget("127.0.0.1", 53317, tls = false), self, timeoutMs = 10_000)
            assertNotNull(answered, "the served door never answered the register")
            // The answer is the server's own identity, on the same wire shape
            // the TCP listener serves — proof the door rides the real core.
            assertEquals("door-node", answered.alias)
            assertEquals(identity.fingerprint, answered.fingerprint)
            serveJob.join()
            val seen = registered.get()
            assertNotNull(seen, "the delegate never saw the register")
            assertEquals("door-walker", seen.first.alias)
            assertEquals(self.fingerprint, seen.first.fingerprint)
            assertEquals("127.0.0.1", seen.second)
        } finally {
            cli.close()
        }
        assertTrue(true) // the exchange concluded without a throw
    }
}
