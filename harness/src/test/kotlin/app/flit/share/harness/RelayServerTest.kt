package app.flit.share.harness

import app.flit.share.TrustGate
import app.flit.share.protocol.Protocol
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TransferUnreachable
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.relay.RelayCore
import app.flit.share.protocol.relay.RelayHello
import app.flit.share.protocol.relay.RelayLinkProvider
import app.flit.share.protocol.relay.RelaySubscriber
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.tls.TlsIdentity
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        delegate: ConsoleDelegate = ConsoleDelegate(destDir = destDir),
        requirePrepareCert: Boolean = false,
    ): Pair<CountDownLatch, CoroutineScope> {
        val stopped = CountDownLatch(1)
        val server = ProtocolServer(
            identity,
            delegate,
            ServerConfig(
                destDir = destDir, alias = "relay-recv", port = 0, tls = false,
                requirePrepareCert = { requirePrepareCert },
            ),
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
        val relay = app.flit.share.protocol.relay.RelayServer().start(0)
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
        val relay = app.flit.share.protocol.relay.RelayServer().start(0)
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
        val relay = app.flit.share.protocol.relay.RelayServer(token = "s3cret").start(0)
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
        val relay = app.flit.share.protocol.relay.RelayServer(parkTimeoutMs = 400).start(0)
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

    /**
     * The reply is exactly one line; every byte after "OK\n" belongs to the
     * peer's TLS ClientHello and must stay on the raw stream handed onward.
     * A buffering reader here would read ahead into those bytes and discard
     * them when it is dropped, so the handshake would hang or reset.
     */
    @Test
    fun `the OK reply leaves following bytes readable on the paired stream`() = runBlocking {
        val relay = ServerSocket(0)
        // A plausible TLS record prefix written in the same packet as "OK\n".
        val extra = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x04, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val fakeRelay = launch(Dispatchers.IO) {
            val peer = relay.accept()
            try {
                val input = peer.getInputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break // consume the hello line only
                }
                val out = peer.getOutputStream()
                out.write("OK\n".toByteArray(Charsets.US_ASCII) + extra)
                out.flush()
            } finally {
                runCatching { peer.close() } // EOF makes a stuck reader fail fast, not hang
            }
        }
        try {
            val link = RelayLinkProvider(TlsIdentity.generate()).connect(
                "127.0.0.1", relay.localPort, tls = false, timeoutMs = 2_000,
                relayFor = "D".repeat(64),
            )
            link.use {
                val got = ByteArray(extra.size + 1)
                var n = 0
                while (n < got.size) {
                    val r = it.input.read(got, n, got.size - n)
                    if (r < 0) break
                    n += r
                }
                assertContentEquals(extra, got.copyOf(n))
            }
        } finally {
            fakeRelay.cancel()
            relay.close()
        }
    }

    @Test
    fun `a relay dial through ProtocolClient reports unreachable, not a send report`() = runBlocking {
        val relay = app.flit.share.protocol.relay.RelayServer(parkTimeoutMs = 300).start(0)
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

    // ==== prepare-certificate gate + keyed-drop landing hardening ================

    private fun gatedDelegate(
        destDir: Path,
        onlyTrusted: Boolean = true,
        qrOnly: Boolean = false,
        pinned: Set<String> = emptySet(),
        known: Set<String> = emptySet(),
    ) = ConsoleDelegate(
        destDir = destDir,
        trustConfig = TrustGate.Config(onlyTrusted = onlyTrusted, qrOnly = qrOnly),
        pinnedFps = pinned,
        knownFps = known,
    )

    private fun directServer(
        identity: TlsIdentity,
        destDir: Path,
        delegate: ConsoleDelegate,
        requirePrepareCert: Boolean,
    ): ProtocolServer = ProtocolServer(
        identity, delegate,
        ServerConfig(
            destDir = destDir, alias = "gate-recv", port = 0, tls = true,
            requirePrepareCert = { requirePrepareCert },
        ),
    ).also { it.start() }

    private fun peer(fp: String, alias: String = "sender"): PeerInfo = PeerInfo(
        alias = alias, version = Protocol.VERSION, deviceType = "headless",
        fingerprint = fp, port = Protocol.DEFAULT_PORT, protocol = "https",
    )

    private fun payload(prefix: String, text: String = "abc"): Path =
        Files.createTempFile(prefix, ".bin").also { Files.write(it, text.toByteArray()) }

    private fun prepareBody(fp: String): String = WireJson.encode(
        PrepareUploadRequest(
            PeerInfo(alias = "raw-sender", version = Protocol.VERSION, fingerprint = fp, protocol = "https"),
            mapOf(
                "f1" to FileInfo(
                    id = "f1", fileName = "raw.bin", size = 3L,
                    fileType = "application/octet-stream", sha256 = "abc",
                ),
            ),
        ),
    )

    /** The refusal shape must stay exactly the v2.2 403 plus the Flit marker. */
    private fun assertMarkedRefusal(resp: PeerHttpClient.Response) {
        assertEquals(403, resp.status)
        assertEquals("Rejected", resp.text())
        assertEquals(Protocol.REFUSAL_DECLINED, resp.headers[Protocol.HEADER_FLIT_REFUSAL.lowercase()])
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `a gated prepare over the relay binds the cert and concludes refusals`() = runBlocking {
        val relay = app.flit.share.protocol.relay.RelayServer().start(0)
        try {
            val dest = Files.createTempDirectory("relay-gate")
            val recvId = TlsIdentity.generate()
            val senderId = TlsIdentity.generate()
            val delegate = gatedDelegate(dest, pinned = setOf(senderId.fingerprint))
            val (_, recvScope) = subscribeLoop(
                "127.0.0.1", relay.port, recvId.fingerprint, recvId, dest,
                delegate = delegate, requirePrepareCert = true,
            )
            try {
                val target = SendTarget("127.0.0.1", relay.port, tls = true, relayFor = recvId.fingerprint)

                val bound = payload("relay-gate-bound")
                ProtocolClient(senderId, links = RelayLinkProvider(senderId)).use { cli ->
                    val report = cli.sendFiles(target, peer(senderId.fingerprint), listOf(PathOutgoing(bound, "bound.bin")))
                    assertTrue(report.failed.isEmpty(), "bound+pinned sender refused: ${report.failed}")
                    assertEquals(listOf("bound.bin"), report.sent)
                    assertFalse(report.declined)
                }

                // A different key claiming the pinned fingerprint: the declared
                // value passes the store, but the presented cert digest cannot.
                val impostorId = TlsIdentity.generate()
                val bad = payload("relay-gate-bad")
                ProtocolClient(impostorId, links = RelayLinkProvider(impostorId)).use { cli ->
                    val report = cli.sendFiles(
                        target, peer(senderId.fingerprint, "impostor"), listOf(PathOutgoing(bad, "impostor.bin")),
                    )
                    assertTrue(report.declined, "mismatched cert must conclude with a marked decline")
                    assertTrue(report.sent.isEmpty())
                    assertTrue(report.failed.isNotEmpty())
                    assertNull(report.sessionId)
                }

                // A v2.2-style prepare with no certificate header at all.
                PeerHttpClient(senderId, links = RelayLinkProvider(senderId)).use { http ->
                    val body = prepareBody(senderId.fingerprint)
                    val resp = http.exchange(
                        method = "POST", host = "127.0.0.1", port = relay.port, tls = true,
                        pathAndQuery = Protocol.PATH_PREPARE_UPLOAD,
                        headers = mapOf(Protocol.HEADER_FLIT_CLIENT to Protocol.FLIT_CLIENT_VALUE),
                        bodyProvider = { body.byteInputStream() },
                        contentLength = body.toByteArray(Charsets.UTF_8).size.toLong(),
                        timeoutMs = 5_000, relayFor = recvId.fingerprint,
                    )
                    assertMarkedRefusal(resp)
                }

                val landed = dest.resolve("bound.bin")
                val deadline = System.currentTimeMillis() + 20_000
                while (!Files.exists(landed) && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertTrue(Files.exists(landed), "bound file did not land: $dest")
                assertFalse(Files.exists(dest.resolve("impostor.bin")), "mismatched sender's bytes landed")
            } finally {
                recvScope.cancel()
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun `the same cert gate binds fingerprints on the direct TCP TLS path`() = runBlocking {
        val dest = Files.createTempDirectory("direct-gate")
        val recvId = TlsIdentity.generate()
        val senderId = TlsIdentity.generate()
        val server = directServer(
            recvId, dest,
            gatedDelegate(dest, pinned = setOf(senderId.fingerprint), known = setOf(senderId.fingerprint)),
            requirePrepareCert = true,
        )
        try {
            val target = SendTarget("127.0.0.1", server.port, tls = true)
            val bound = payload("direct-gate-bound")
            ProtocolClient(senderId).use { cli ->
                val report = cli.sendFiles(target, peer(senderId.fingerprint), listOf(PathOutgoing(bound, "bound.bin")))
                assertTrue(report.failed.isEmpty(), "bound+pinned sender refused: ${report.failed}")
                assertEquals(listOf("bound.bin"), report.sent)
            }

            val impostorId = TlsIdentity.generate()
            val bad = payload("direct-gate-bad")
            ProtocolClient(impostorId).use { cli ->
                val report = cli.sendFiles(
                    target, peer(senderId.fingerprint, "impostor"), listOf(PathOutgoing(bad, "impostor.bin")),
                )
                assertTrue(report.declined, "mismatched cert must conclude with a marked decline")
                assertTrue(report.sent.isEmpty())
            }

            PeerHttpClient(senderId).use { http ->
                val body = prepareBody(senderId.fingerprint)
                val resp = http.exchange(
                    method = "POST", host = "127.0.0.1", port = server.port, tls = true,
                    pathAndQuery = Protocol.PATH_PREPARE_UPLOAD,
                    headers = mapOf(Protocol.HEADER_FLIT_CLIENT to Protocol.FLIT_CLIENT_VALUE),
                    bodyProvider = { body.byteInputStream() },
                    contentLength = body.toByteArray(Charsets.UTF_8).size.toLong(),
                )
                assertMarkedRefusal(resp)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `qrOnly accepts a known unpinned sender and refuses an unknown one`() = runBlocking {
        val dest = Files.createTempDirectory("qr-gate")
        val recvId = TlsIdentity.generate()
        val knownId = TlsIdentity.generate()
        val strangerId = TlsIdentity.generate()
        val server = directServer(
            recvId, dest,
            gatedDelegate(dest, onlyTrusted = false, qrOnly = true, known = setOf(knownId.fingerprint)),
            requirePrepareCert = true,
        )
        try {
            val target = SendTarget("127.0.0.1", server.port, tls = true)
            val file = payload("qr-gate-known")
            ProtocolClient(knownId).use { cli ->
                val report = cli.sendFiles(target, peer(knownId.fingerprint), listOf(PathOutgoing(file, "known.bin")))
                assertTrue(report.failed.isEmpty(), "known sender refused: ${report.failed}")
                assertEquals(listOf("known.bin"), report.sent)
            }
            val bad = payload("qr-gate-unknown")
            ProtocolClient(strangerId).use { cli ->
                val report = cli.sendFiles(target, peer(strangerId.fingerprint), listOf(PathOutgoing(bad, "unknown.bin")))
                assertTrue(report.declined, "an unrecognised QR fingerprint must be declined")
                assertTrue(report.sent.isEmpty())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `with both gates off an unpinned sender keeps the legacy auto-accept`() = runBlocking {
        val dest = Files.createTempDirectory("gate-off")
        val recvId = TlsIdentity.generate()
        val senderId = TlsIdentity.generate()
        val server = directServer(recvId, dest, ConsoleDelegate(destDir = dest), requirePrepareCert = false)
        try {
            val file = payload("gate-off")
            ProtocolClient(senderId).use { cli ->
                val report = cli.sendFiles(
                    SendTarget("127.0.0.1", server.port, tls = true), peer(senderId.fingerprint),
                    listOf(PathOutgoing(file, "legacy.bin")),
                )
                assertTrue(report.failed.isEmpty(), "gate-off sender refused: ${report.failed}")
                assertEquals(listOf("legacy.bin"), report.sent)
                assertFalse(report.declined)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `keyed landing names cannot traverse or escape destDir`() = runBlocking {
        val root = Files.createTempDirectory("gate-landing")
        val dest = root.resolve("dest").also { Files.createDirectories(it) }
        try {
            for (hostile in listOf("../escaped.txt", "..\\escaped.txt", "sub/escaped.txt", "/tmp/escaped.txt", ".", "..", "")) {
                assertNull(safeLandingPath(dest, hostile), "hostile name accepted: $hostile")
            }
            val good = safeLandingPath(dest, "good.txt")
            assertNotNull(good)
            assertEquals(dest.toRealPath().resolve("good.txt"), good)

            // A pre-planted sibling symlink must not redirect the write out.
            val outside = root.resolve("outside").also { Files.createDirectories(it) }
            Files.createSymbolicLink(dest.resolve("linked.txt"), outside.resolve("escaped.txt"))
            assertNull(safeLandingPath(dest, "linked.txt"))
            assertFalse(Files.exists(outside.resolve("escaped.txt")))
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `a forged unsigned event in circle state is dropped on load`() = runBlocking {
        assumeTrue(
            System.getenv("FLIT_HARNESS_HOME").isNullOrBlank(),
            "FLIT_HARNESS_HOME pins the real harness home; refusing to overwrite it",
        )
        val root = Files.createTempDirectory("gate-state")
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", root.toString())
            val identity = TlsIdentity.generate()
            val signed = CircleCore.create(
                "circle-gate", identity.fingerprint, identity, CircleEvent.MESSAGE, "{\"text\":\"ok\"}",
            )
            val forged = signed.copy(id = "forged-id", sig = "", body = "{\"text\":\"forged\"}")
            assertFalse(CircleCore.verify(forged))

            saveCircleState(
                CircleCliState(
                    circleId = "circle-gate", hostFp = identity.fingerprint,
                    members = mutableListOf(identity.fingerprint),
                    events = mutableListOf(signed, forged),
                ),
            )
            val loaded = loadCircleState()
            assertNotNull(loaded)
            assertEquals(listOf(signed.id), loaded.events.map { it.id })

            val home = harnessHome()
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(home.resolve("circle.state")))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(home))
        } finally {
            if (previous == null) System.clearProperty("user.home")
            else System.setProperty("user.home", previous)
            deleteRecursively(root)
        }
    }

    // ==== relay bind policy + per-IP wrong-token backoff =======================

    /** One raw hello on a fresh socket, and its single reply line. */
    private fun rawRelayLine(port: Int, line: String): String? = Socket("127.0.0.1", port).use { sock ->
        sock.soTimeout = 3_000
        val out = sock.getOutputStream()
        out.write((line + "\n").toByteArray(Charsets.US_ASCII))
        out.flush()
        val input = sock.getInputStream()
        val answer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) answer.append(b.toChar())
        }
        answer.toString().takeIf { it.isNotBlank() }
    }

    @Test
    fun `a weak token on a non-loopback relay bind is refused before listening`() {
        assertEquals("missing-token", RelayCore.bindTokenProblem(null, loopback = false))
        assertEquals("weak-token", RelayCore.bindTokenProblem("short", loopback = false))
        assertNull(RelayCore.bindTokenProblem(null, loopback = true))
        assertNull(RelayCore.bindTokenProblem("s3cret-strong-token-12345", loopback = false))

        val server = app.flit.share.protocol.relay.RelayServer(token = "short")
        val refused = runCatching { server.start(0, "0.0.0.0") }
        assertTrue(refused.isFailure, "a non-loopback bind with a weak token must be refused")
        assertTrue(
            refused.exceptionOrNull()?.message?.contains("weak-token") == true,
            "refusal said: ${refused.exceptionOrNull()?.message}",
        )
        server.close()
    }

    @Test
    fun `wrong tokens from one address are throttled on the wire`() = runBlocking {
        val token = "s3cret-strong-token-12345"
        val relay = app.flit.share.protocol.relay.RelayServer(
            token = token, parkTimeoutMs = 500L,
        ).start(0, "127.0.0.1")
        try {
            val wrong = RelayHello.Request("A".repeat(64), "wrong-token").encode()
            val answers = (1..RelayCore.DEFAULT_MAX_FAILURES).map { rawRelayLine(relay.port, wrong) }
            assertTrue(answers.all { it == "ERR bad-token" }, "wrong-token answers: $answers")
            assertEquals("ERR throttled", rawRelayLine(relay.port, wrong))
            // The backoff gates the source address, not just the bad secret.
            val right = RelayHello.Request("A".repeat(64), token).encode()
            assertEquals("ERR throttled", rawRelayLine(relay.port, right))
        } finally {
            relay.close()
        }
    }

    @Test
    fun `circleFreshEvents drops member-signed ROLE and admin KICK but folds an ordinary event`() {
        val circleId = "circle-privileged"
        val owner = TlsIdentity.generate()
        val member = TlsIdentity.generate()
        val st = CircleCliState(
            circleId = circleId,
            hostFp = owner.fingerprint,
            members = mutableListOf(owner.fingerprint, member.fingerprint),
        )
        val roles = circleRoleMap(st)
        assertEquals("OWNER", roles[owner.fingerprint])
        assertEquals("MEMBER", roles[member.fingerprint])

        val role = CircleCore.create(
            circleId, member.fingerprint, member, CircleEvent.ROLE,
            """{"targetFp":"${member.fingerprint}","role":"ADMIN"}""",
        )
        val kick = CircleCore.create(
            circleId, member.fingerprint, member, CircleEvent.KICK,
            """{"kickId":"kick-1","targetFp":"${owner.fingerprint}","mode":"admin","approve":true}""",
        )
        val ordinary = CircleCore.create(
            circleId, member.fingerprint, member, CircleEvent.MESSAGE,
            """{"text":"hello"}""",
        )

        val folded = circleFreshEvents(st, listOf(role, kick, ordinary), roles)
        assertEquals(listOf(ordinary.id), folded.map { it.id })
        assertTrue(circleFreshEvents(st, listOf(role), roles).isEmpty(), "a member-signed ROLE must not fold")
        assertTrue(circleFreshEvents(st, listOf(kick), roles).isEmpty(), "a member admin-mode KICK must not fold")
    }

    @Test
    fun `an oversized park timeout is rejected instead of overflowing`() {
        assertEquals(RelayCore.DEFAULT_PARK_TIMEOUT_MS, parsedParkTimeoutMs(null))
        assertEquals(0L, parsedParkTimeoutMs("0")) // still the explicit wait-forever option
        assertEquals(5_000L, parsedParkTimeoutMs("5"))
        assertNull(parsedParkTimeoutMs("9223372036854775807")) // Long.MAX_VALUE seconds would overflow
        assertNull(parsedParkTimeoutMs("604801")) // one week plus one second
        assertNull(parsedParkTimeoutMs("-1"))
        assertNull(parsedParkTimeoutMs("abc"))
    }

    @Test
    fun `keyed-drop plaintext lands with owner-only permissions`() = runBlocking {
        assumeTrue(
            System.getenv("FLIT_HARNESS_HOME").isNullOrBlank(),
            "FLIT_HARNESS_HOME pins the real harness home; refusing to overwrite it",
        )
        val root = Files.createTempDirectory("drop-perms")
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", root.toString())
            val dest = root.resolve("dest").also { Files.createDirectories(it) }
            val recipient = TlsIdentity.generate()
            val sender = TlsIdentity.generate()
            val key = DropKeyCore.mint()
            val plain = "private bytes".toByteArray(Charsets.UTF_8)
            val sealed = DropKeyCore.seal(key, plain)
            val cipherSha = ProtocolClient.sha256Hex { sealed.inputStream() }
            val plainSha = ProtocolClient.sha256Hex { plain.inputStream() }
            val wraps = DropKeyCore.wrap(key.bytes, mapOf(recipient.fingerprint to recipient.certificate))
            val body = circleFileAnnouncement(
                "secret.txt", plain.size.toLong(), plainSha, 1, cipherSha,
                Base64.getEncoder().encodeToString(key.iv), wraps,
            )
            val event = CircleCore.create("circle-drop", sender.fingerprint, sender, CircleEvent.FILE, body)
            saveCircleState(
                CircleCliState(
                    circleId = "circle-drop", hostFp = sender.fingerprint,
                    members = mutableListOf(sender.fingerprint, recipient.fingerprint),
                    events = mutableListOf(event),
                ),
            )

            val delegate = ConsoleDelegate(cliIdentity = recipient, destDir = dest)
            val server = directServer(recipient, dest, delegate, requirePrepareCert = false)
            try {
                val sealedFile = payload("drop-sealed").also { Files.write(it, sealed) }
                ProtocolClient(sender).use { cli ->
                    val report = cli.sendFiles(
                        SendTarget("127.0.0.1", server.port, tls = true), peer(sender.fingerprint),
                        listOf(PathOutgoing(sealedFile, "sealed.bin")),
                    )
                    assertTrue(report.failed.isEmpty(), "drop send failed: ${report.failed}")
                }
                val landed = dest.resolve("secret.txt")
                val deadline = System.currentTimeMillis() + 20_000
                while (!Files.exists(landed) && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertTrue(Files.exists(landed), "keyed plaintext did not land: $dest")
                assertContentEquals(plain, Files.readAllBytes(landed))
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(landed),
                )
            } finally {
                server.close()
            }
        } finally {
            if (previous == null) System.clearProperty("user.home")
            else System.setProperty("user.home", previous)
            deleteRecursively(root)
        }
    }
}
