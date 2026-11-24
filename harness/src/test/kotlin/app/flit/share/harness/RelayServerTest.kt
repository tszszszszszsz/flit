package app.flit.share.harness

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TransferUnreachable
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.relay.RelayLinkProvider
import app.flit.share.protocol.relay.RelaySubscriber
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.tls.TlsIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Milestone E, end-to-end on the JVM with the *same endpoints the app uses*:
 * [RelayLinkProvider] dials and [RelaySubscriber] holds the subscription. The
 * relay never parses a byte — TLS, register, prepare and upload run inside
 * the paired pipe through [ProtocolServer.serve], the same door B rides.
 * Plus the two refusal shapes a self-hosted relay must answer cleanly.
 */
class RelayServerTest {

    /**
     * The app's subscription worker, in miniature: hold a `sub` for this
     * fingerprint, hand each paired pipe to the real server door, and
     * immediately re-subscribe — prepare and upload arrive on their own
     * paired pipes. TLS is server-mode inside the pipe.
     */
    private fun subscribeLoop(
        relayAddress: String,
        relayPort: Int,
        fingerprint: String,
        identity: TlsIdentity,
        destDir: Path,
    ): Pair<CountDownLatch, CoroutineScope> {
        val stopped = CountDownLatch(1)
        val server = ProtocolServer(
            identity,
            ConsoleDelegate(),
            ServerConfig(destDir = destDir, alias = "relay-recv", port = 0, tls = false),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                while (true) {
                    val link = try {
                        RelaySubscriber.subscribe(relayAddress, relayPort, fingerprint)
                    } catch (_: Exception) {
                        break // relay closed or refused: the worker stops
                    }
                    try {
                        server.serve(TlsLink(link, identity, clientMode = false))
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

    @Test
    fun `a real TLS send lands hash-exact through the blind pairer`() = runBlocking {
        val relay = RelayServer().start(0)
        try {
            val dest = Files.createTempDirectory("relay-dest")
            val recvId = TlsIdentity.generate()
            val (stopped, recvScope) = subscribeLoop("127.0.0.1", relay.port, recvId.fingerprint, recvId, dest)
            val senderId = TlsIdentity.generate()
            val sender = PeerInfo(
                alias = "relay-sender", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = senderId.fingerprint,
                port = Protocol.DEFAULT_PORT, protocol = "https",
            )
            val payload = ByteArray(3 * 1024 * 1024).also { java.util.Random(42).nextBytes(it) }
            val file = Files.createTempFile("relay-send", ".bin").also { Files.write(it, payload) }

            ProtocolClient(senderId, links = RelayLinkProvider(senderId)).use { cli ->
                val report = cli.sendFiles(
                    SendTarget("127.0.0.1", relay.port, tls = true, relayFor = recvId.fingerprint),
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

            recvScope.cancel()
            relay.close()
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "subscription worker never stopped")
        } finally {
            relay.close()
        }
    }

    /**
     * E must be transparent to C: a mid-body relay death throws (never a
     * report), keeps the receiver's prefix, and the next send probes and
     * resumes through the same relay — hash-exact. This is the wire-level
     * version of the hardware walk owed for the relay.
     */
    @Test
    fun `a carrier death through the relay keeps the prefix and resumes hash-exact`() = runBlocking {
        val relay = RelayServer().start(0)
        try {
            val dest = Files.createTempDirectory("relay-resume")
            val recvId = TlsIdentity.generate()
            val (_, recvScope) = subscribeLoop("127.0.0.1", relay.port, recvId.fingerprint, recvId, dest)
            val senderId = TlsIdentity.generate()
            val sender = PeerInfo(
                alias = "relay-resume-sender", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = senderId.fingerprint,
                port = Protocol.DEFAULT_PORT, protocol = "https",
            )
            val payload = ByteArray(8 * 1024 * 1024).also { java.util.Random(99).nextBytes(it) }
            val file = Files.createTempFile("relay-resume", ".bin").also { Files.write(it, payload) }
            val outgoing = PathOutgoing(file, "resume-relay.bin")
            val target = SendTarget("127.0.0.1", relay.port, tls = true, relayFor = recvId.fingerprint)

            // The abort cuts the upload body; a carrier that stays alive
            // would let the client's own retry resume within this one call.
            // The first send dials three times (prepare, /partial probe,
            // upload); refuse from the fourth on so the death is real.
            val relayLinks = RelayLinkProvider(senderId)
            var connects = 0
            val dyingCarrier = object : LinkProvider {
                private suspend fun take(host: String, port: Int, tls: Boolean, timeoutMs: Int, relayFor: String?): Link {
                    if (++connects > 3) throw IOException("relay carrier gone")
                    return relayLinks.connect(host, port, tls, timeoutMs, relayFor)
                }
                override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                    take(host, port, tls, timeoutMs, null)
                override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int, relayFor: String?): Link =
                    take(host, port, tls, timeoutMs, relayFor)
            }
            PeerHttpClient.testAbortBytes = 2L * 1024 * 1024
            val died = runCatching {
                ProtocolClient(senderId, links = dyingCarrier).use { cli ->
                    cli.sendFiles(target, sender, listOf(outgoing))
                }
            }
            PeerHttpClient.testAbortBytes = 0L
            assertTrue(died.isFailure, "relay carrier death must throw, got: ${died.getOrNull()}")
            assertTrue(
                died.exceptionOrNull() is TransferUnreachable,
                "relay death surfaced as ${died.exceptionOrNull()?.javaClass?.simpleName}",
            )

            val part = dest.resolve(".resume-relay.bin.part")
            val partDeadline = System.currentTimeMillis() + 20_000
            while (!Files.exists(part) && System.currentTimeMillis() < partDeadline) Thread.sleep(50)
            assertTrue(Files.exists(part), "receiver kept no prefix: $dest")
            val prefix = Files.size(part)
            assertTrue(prefix > 0 && prefix < payload.size, "probe base would be $prefix of ${payload.size}")

            // A new sender session may only replace the dead one after its
            // stale window — the real hunter's backoff covers the same gap.
            delay(11_000)
            ProtocolClient(senderId, links = RelayLinkProvider(senderId)).use { cli ->
                val report = cli.sendFiles(target, sender, listOf(outgoing))
                assertTrue(report.failed.isEmpty(), "relay resume failed: ${report.failed}")
                assertEquals(1, report.sent.size)
            }
            val landed = dest.resolve("resume-relay.bin")
            val landedDeadline = System.currentTimeMillis() + 20_000
            while (!Files.exists(landed) && System.currentTimeMillis() < landedDeadline) Thread.sleep(50)
            assertTrue(Files.exists(landed), "resumed file did not land: $dest")
            assertContentEquals(payload, landed.readBytes())

            recvScope.cancel()
        } finally {
            PeerHttpClient.testAbortBytes = 0L
            relay.close()
        }
    }

    @Test
    fun `a wrong token is refused on the wire by the production dialer`() = runBlocking {
        val relay = RelayServer(token = "s3cret").start(0)
        try {
            val refused = runCatching {
                RelayLinkProvider(TlsIdentity.generate(), token = "wrong-token").connect(
                    "127.0.0.1", relay.port, tls = false, timeoutMs = 2_000,
                    relayFor = "A".repeat(64),
                )
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
    fun `a pair that never comes times out cleanly for the production subscriber`() = runBlocking {
        val relay = RelayServer(parkTimeoutMs = 400).start(0)
        try {
            val alone = runCatching {
                RelaySubscriber.subscribe(
                    "127.0.0.1", relay.port, "B".repeat(64),
                    parkTimeoutMs = 5_000,
                )
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
            val sender = PeerInfo(
                alias = "relay-sender", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = senderId.fingerprint,
                port = Protocol.DEFAULT_PORT, protocol = "https",
            )
            val file = Files.createTempFile("relay-none", ".bin").also { Files.write(it, ByteArray(1024)) }
            val out = runCatching {
                ProtocolClient(senderId, links = RelayLinkProvider(senderId)).use { cli ->
                    cli.sendFiles(
                        SendTarget("127.0.0.1", relay.port, tls = false, relayFor = "C".repeat(64)),
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
