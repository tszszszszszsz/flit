package app.flit.share.harness

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TransferUnreachable
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.server.LinkServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.tls.TlsIdentity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Milestone E, end-to-end on the JVM: a real protocol send rides the
 * blind pairer. The relay never parses a byte of the exchange — the
 * sender's ProtocolClient and the receiver's LinkServer own every
 * protocol decision inside the relayed pipe (plain carrier here; TLS is
 * the same bytes to a relay and is covered by the protocol module's own
 * runs). Plus the two refusal shapes a self-hosted relay must answer
 * cleanly: a wrong token and a pair that never comes.
 */
class RelayServerTest {

    private fun dialRelay(
        relayPort: Int,
        hello: String,
        expectOk: Boolean = true,
    ): Link {
        val sock = Socket("127.0.0.1", relayPort)
        sock.getOutputStream().write((hello + "\n").toByteArray(Charsets.US_ASCII))
        sock.getOutputStream().flush()
        val reply = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII)).readLine()
        if (reply == "OK") return object : Link {
            override val input = sock.getInputStream()
            override val output = sock.getOutputStream()
            override val origin = "relay"
            override fun close() = sock.close()
        }
        sock.close()
        throw IOException("relay said: $reply")
    }

    /**
     * The receiver's subscription worker, as the app will run it: hold a
     * `sub` for this fingerprint, serve the paired link through the real
     * [LinkServer], and immediately re-subscribe for the next exchange
     * (prepare, probe and upload each arrive on their own paired pipe).
     * [stopped] counts down once the relay closes and the loop ends.
     */
    private fun subscribeLoop(
        relayPort: Int,
        fingerprint: String,
        identity: TlsIdentity,
        destDir: java.nio.file.Path,
    ): Pair<CountDownLatch, CoroutineScope> {
        val stopped = CountDownLatch(1)
        val core = ServerCore(
            identity,
            ServerConfig(destDir = destDir, alias = "relay-recv", tls = false),
            ConsoleDelegate(),
        )
        val linkServer = LinkServer(core)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                while (true) {
                    val link = try {
                        dialRelay(relayPort, "$RelayHello_MAGIC sub $fingerprint")
                    } catch (_: Exception) {
                        break // relay closed or refused: the worker stops
                    }
                    try {
                        linkServer.serve(link)
                    } catch (_: Exception) {
                        break
                    }
                }
            } finally {
                stopped.countDown()
            }
        }
        return stopped to scope
    }

    // The magic lives in the protocol's RelayHello; spelled once here so the
    // test also pins the wire line itself.
    private val RelayHello_MAGIC = "FLITRLY1"

    @Test
    fun `a real send lands hash-exact through the blind pairer`() = runBlocking {
        val relay = RelayServer().start(0)
        try {
            val dest = Files.createTempDirectory("relay-dest")
            val recvId = TlsIdentity.generate()
            val (stopped, recvScope) = subscribeLoop(relay.port, recvId.fingerprint, recvId, dest)
            val senderId = TlsIdentity.generate()
            val sender = PeerInfo(
                alias = "relay-sender", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = senderId.fingerprint,
                port = Protocol.DEFAULT_PORT, protocol = "https",
            )
            val payload = ByteArray(3 * 1024 * 1024).also { java.util.Random(42).nextBytes(it) }
            val file = Files.createTempFile("relay-send", ".bin").also { Files.write(it, payload) }

            // The dialer an app-side relay provider would be: ignore host/port,
            // dial the relay, ask for the receiver's fingerprint, hand back the
            // generic paired link.
            val links = object : LinkProvider {
                override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                    dialRelay(relay.port, "$RelayHello_MAGIC req ${recvId.fingerprint}")
            }
            ProtocolClient(senderId, links = links).use { cli ->
                val report = cli.sendFiles(
                    SendTarget("relay", relay.port, tls = false),
                    sender,
                    listOf(PathOutgoing(file, "through-relay.bin")),
                )
                assertTrue(report.failed.isEmpty(), "relay send failed: ${report.failed}")
                assertEquals(listOf("through-relay.bin"), report.sent)
            }
            val landed = dest.resolve("through-relay.bin")
            val deadline = System.currentTimeMillis() + 20_000
            while (!Files.exists(landed) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(Files.exists(landed), "file did not land: $dest")
            assertContentEquals(payload, landed.readBytes())
            // The worker parks on its next subscription; closing the relay is
            // what unblocks the read and lets it stop — the same shape an app
            // shutdown takes.
            recvScope.cancel()
            relay.close()
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "subscription worker never stopped")
        } finally {
            relay.close()
        }
    }

    @Test
    fun `a wrong token is refused on the wire`() = runBlocking {
        val relay = RelayServer(token = "s3cret").start(0)
        try {
            val refused = runCatching {
                dialRelay(relay.port, "$RelayHello_MAGIC req ${"A".repeat(64)} wrong-token")
            }
            assertTrue(refused.isFailure, "wrong token must be refused")
            assertTrue(
                refused.exceptionOrNull()?.message?.contains("bad-token") == true,
                "refusal said: ${refused.exceptionOrNull()?.message}",
            )
        } finally {
            relay.close()
        }
    }

    @Test
    fun `a pair that never comes times out cleanly`() = runBlocking {
        val relay = RelayServer(parkTimeoutMs = 400).start(0)
        try {
            val alone = runCatching {
                dialRelay(relay.port, "$RelayHello_MAGIC sub ${"B".repeat(64)}")
            }
            assertTrue(alone.isFailure, "an unpaired subscription must not hang forever")
            assertTrue(
                alone.exceptionOrNull()?.message?.contains("timeout") == true,
                "timeout said: ${alone.exceptionOrNull()?.message}",
            )
        } finally {
            relay.close()
        }
    }

    @Test
    fun `a relay dial through ProtocolClient reports unreachable, not a send report`() = runBlocking {
        val relay = RelayServer(parkTimeoutMs = 300).start(0)
        try {
            val senderId = TlsIdentity.generate()
            val links = object : LinkProvider {
                override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                    dialRelay(relay.port, "$RelayHello_MAGIC req ${"C".repeat(64)}")
            }
            val sender = PeerInfo(
                alias = "relay-sender", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = senderId.fingerprint,
                port = Protocol.DEFAULT_PORT, protocol = "https",
            )
            val file = Files.createTempFile("relay-none", ".bin").also { Files.write(it, ByteArray(1024)) }
            val out = runCatching {
                ProtocolClient(senderId, links = links).use { cli ->
                    cli.sendFiles(
                        SendTarget("relay", relay.port, tls = false),
                        sender,
                        listOf(PathOutgoing(file, "nobody-home.bin")),
                    )
                }
            }
            val err = out.exceptionOrNull()
            assertTrue(out.isFailure, "a dead relay dial must throw, got: ${out.getOrNull()}")
            assertTrue(
                err is TransferUnreachable,
                "relay death surfaced as ${err?.javaClass?.simpleName}, not TransferUnreachable",
            )
        } finally {
            relay.close()
        }
    }
}
