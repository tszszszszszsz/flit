package app.flit.share.protocol

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.OutgoingFile
import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.client.TransferUnreachable
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PartialInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.LinkServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import app.flit.share.protocol.transport.PipePair
import app.flit.share.protocol.transport.SimLinkProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The B1 keystone proof: the EXACT protocol route semantics of [ServerCore]
 * must survive a non-TCP pipe. Here the whole flow — info, register,
 * prepare-upload, big upload body, dead-sender abort, /partial probe,
 * resume to a byte-exact file, cancel — runs from [PeerHttpClient] (the real
 * client) into [LinkServer] (the binder Bluetooth rides) over in-memory
 * conduits, once plain and once with TlsLink wrapping both ends via
 * StreamTls. The carrier ([PipePair], [SimLinkProvider]) now lives in the
 * protocol's own testables (app.flit.share.protocol.transport) so the
 * harness's bt-sim rig proves exactly what this test proves.
 */
class LinkServerParityTest {

    // ---- recording delegate ------------------------------------------------

    private class ParityDelegate : ServerDelegate {
        val events = SynchronizedList()
        override fun onPrepare(request: app.flit.share.protocol.model.PrepareUploadRequest, peerIp: String)
            : app.flit.share.protocol.server.PrepareDecision = app.flit.share.protocol.server.PrepareDecision.Accept(request.files.keys)
        override fun onRegister(info: PeerInfo, ip: String) {
            events.add("register:${info.alias}@$ip")
        }

        override fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {
            events.add("start:$fileId")
        }

        override fun onResumeStart(sessionId: String, fileId: String, file: FileInfo, prefixBytes: Long) {
            events.add("resume:$prefixBytes")
        }

        override fun onUploadProgress(sessionId: String, fileId: String, file: FileInfo, bytes: Long, totalBytes: Long) {
            events.add("progress:$bytes")
        }

        override fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: app.flit.share.protocol.server.UploadResult) {
            events.add("finish:$fileId:${result.javaClass.simpleName}")
        }

        override fun onSessionEnd(sessionId: String, cancelled: Boolean) {
            events.add("end:cancelled=$cancelled")
        }
    }

    private class SynchronizedList {
        private val raw = ArrayList<String>()
        fun add(s: String) = synchronized(raw) { raw.add(s) }
        fun snapshot(): List<String> = synchronized(raw) { raw.toList() }
    }

    // ---- test ---------------------------------------------------------------

    private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    private fun shaOf(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun parityRun(tls: Boolean) {
        val harnessWd = thread(isDaemon = true, name = "parity-watchdog-$tls") {
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                return@thread
            }
            System.err.println("[watchdog] stuck run (tls=$tls) — dumping all threads")
            Thread.getAllStackTraces().forEach { (t, st) ->
                if (st.isNotEmpty()) {
                    System.err.println("[watchdog] ${t.name}:")
                    st.take(14).forEach { System.err.println("[watchdog]   $it") }
                }
            }
            Runtime.getRuntime().halt(97)
        }
        try {
            runBlocking {
                withTimeoutOrNull(90_000) { drive(tls) } ?: fail("parity run (tls=$tls) timed out")
            }
        } finally {
            harnessWd.interrupt() // healthy run: stand down before halt
        }
    }

    private suspend fun drive(tls: Boolean) {
        val identity = TlsIdentity.generate()
        val dest = Files.createTempDirectory("flit-parity")
        val delim = ParityDelegate()
        val core = ServerCore(identity, ServerConfig(destDir = dest, alias = "parity-node"), delim)
        val server = LinkServer(core)
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val connections = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val p = inbox.receive()
                launch(Dispatchers.IO) {
                    val raw = p.serverLink("127.0.0.1")
                    try {
                        server.serve(if (tls) TlsLink(raw, identity, clientMode = false) else raw)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        System.err.println("serve crashed: $t")
                    }
                }
            }
        }
        val cli = PeerHttpClient(identity, links = SimLinkProvider(identity, tls, inbox))

        // 1. info — the same JSON the TCP binder answers
        val info = cli.exchange("GET", "127.0.0.1", 53317, tls, Protocol.PATH_INFO, timeoutMs = 20_000)
        assertEquals(200, info.status)
        val self = WireJson.decode<PeerInfo>(info.text())
        assertEquals("parity-node", self.alias)
        assertEquals(identity.fingerprint, self.fingerprint)
        assertEquals(Protocol.VERSION, self.version)

        // 2. register POST — delegate.learning works over the label
        val sender = PeerInfo(
            alias = "parity-sender", version = "2.2",
            fingerprint = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )
        val reg = cli.exchange(
            "POST", "127.0.0.1", 53317, tls, Protocol.PATH_REGISTER,
            headers = mapOf("Content-Type" to "application/json"),
            bodyProvider = { ByteArrayInputStream(WireJson.encode(sender).toByteArray()) },
            contentLength = WireJson.encode(sender).length.toLong(),
            timeoutMs = 20_000,
        )
        assertEquals(200, reg.status, "register tls=$tls answered ${reg.status}: ${reg.text().take(200)}")
        assertTrue(
            delim.events.snapshot().any { it == "register:parity-sender@127.0.0.1" },
            "register event missing: ${delim.events.snapshot().takeLast(8)}",
        )

        // 3. prepare — one 1.5MB file with a real digest
        val payload = ByteArray(1536 * 1024).also { Random(4242).nextBytes(it) }
        val fi = FileInfo(
            id = "f1", fileName = "parity.bin",
            size = payload.size.toLong(), fileType = "application/octet-stream",
            sha256 = shaOf(payload),
        )
        val req = PrepareUploadRequest(sender.sansAnnounce(), mapOf("f1" to fi))
        val reqBytes = WireJson.encode(req).toByteArray()
        val prepared = cli.exchange(
            "POST", "127.0.0.1", 53317, tls, Protocol.PATH_PREPARE_UPLOAD,
            headers = mapOf("Content-Type" to "application/json"),
            bodyProvider = { ByteArrayInputStream(reqBytes) },
            contentLength = reqBytes.size.toLong(),
            timeoutMs = 20_000,
        )
        assertEquals(200, prepared.status)
        val pr = WireJson.decode<PrepareUploadResponse>(prepared.text())
        assertTrue(pr.files.containsKey("f1"))

        // 4. dead mid-body: write half, kill the link (the carrier's real death)
        PeerHttpClient.testAbortBytes = payload.size / 2L
        val rt = Protocol.resumeToken(fi)
        val aborted = runCatching {
            cli.exchange(
                "POST", "127.0.0.1", 53317, tls,
                "${Protocol.PATH_UPLOAD}?sessionId=${enc(pr.sessionId)}&fileId=f1&token=${pr.files["f1"]}",
                headers = mapOf("Content-Type" to "application/octet-stream"),
                bodyProvider = { ByteArrayInputStream(payload) },
                contentLength = payload.size.toLong(),
                bodyProgress = { }, // quiet
                timeoutMs = 45_000,
            )
        }
        PeerHttpClient.testAbortBytes = 0L
        assertTrue(aborted.isFailure, "upload body must have aborted mid-flight")

        // 5. /partial probe confirms the receiver kept a salvageable prefix.
        // One exchange, no single shot: the receiver registers its kept part
        // only after the upload pump winds the dead carrier down, so a
        // one-shot probe races that — production senders poll briefly for
        // exactly this reason (the resume lane in ProtocolClient), and the
        // test rides the same contract.
        var probe: PeerHttpClient.Response? = null
        var missed = 0
        while (missed < 6) {
            val p = cli.exchange(
                "GET", "127.0.0.1", 53317, tls,
                "${Protocol.PATH_PARTIAL}?token=${rt}",
                timeoutMs = 2_000,
            )
            probe = p
            if (p.status == 200) break
            missed++
            kotlinx.coroutines.delay(50)
        }
        val pr2 = probe!!
        assertEquals(200, pr2.status, "probe tls=$tls answered ${pr2.status} after $missed retries: ${pr2.text().take(200)}")
        val partial = WireJson.decode<PartialInfo>(pr2.text())
        val prefix = partial.size
        assertTrue(prefix > 0 && prefix < payload.size, "probe says $prefix of ${payload.size}")

        // 6. resume single-stream: skip the confirmed prefix, declare the rest
        val resumed = cli.exchange(
            "POST", "127.0.0.1", 53317, tls,
            "${Protocol.PATH_UPLOAD}?sessionId=${enc(pr.sessionId)}&fileId=f1&token=${pr.files["f1"]}",
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "X-Flit-Resume" to rt,
            ),
            bodyProvider = { ByteArrayInputStream(payload).let { it.skip(prefix); it } },
            contentLength = payload.size - prefix,
            timeoutMs = 45_000,
        )
        assertEquals(200, resumed.status, "resume tls=$tls answered ${resumed.status}: ${resumed.text().take(200)}")
        assertContentEquals(payload, Files.readAllBytes(dest.resolve("parity.bin")))
        val ev = delim.events.snapshot()
        assertTrue(ev.any { it == "resume:$prefix" }, "resume event missing: ${ev.takeLast(8)}")
        assertTrue(ev.any { it == "finish:f1:Saved" }, "finish event missing: ${ev.takeLast(8)}")
        assertTrue(ev.any { it == "end:cancelled=false" }, "end event missing: ${ev.takeLast(8)}")

        // 7. cancel — answers 200 whatever the state, end event only for real hits
        val cancel = cli.exchange(
            "POST", "127.0.0.1", 53317, tls,
            "${Protocol.PATH_CANCEL}?sessionId=${enc(pr.sessionId)}",
            timeoutMs = 20_000,
        )
        assertEquals(200, cancel.status)

        cli.close()
        connections.cancel()
    }

    @Test
    fun `full flow rides plain pipes`() = parityRun(tls = false)

    @Test
    fun `full flow rides pipes wrapped in TLS both ends`() = parityRun(tls = true)

    /**
     * Milestone C, in one exchange chain: the receiver DIES mid-upload (the
     * whole core is discarded — session, registry, everything in-memory) and
     * a fresh core over the same dest directory comes back. The kept part
     * must carry its own discoverability: a token sidecar beside the `.part`,
     * read back by the start sweep, so the sender's /partial probe still
     * answers with the prefix and the re-attach lands hash-exact. TLS only —
     * the sidecar/sweep machinery is carrier-independent and the plain
     * carrier is already covered by the parity runs above.
     */
    private fun deathRun() {
        val harnessWd = thread(isDaemon = true, name = "death-watchdog") {
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                return@thread
            }
            System.err.println("[watchdog] stuck death run — dumping all threads")
            Thread.getAllStackTraces().forEach { (t, st) ->
                if (st.isNotEmpty()) {
                    System.err.println("[watchdog] ${t.name}:")
                    st.take(14).forEach { System.err.println("[watchdog]   $it") }
                }
            }
            Runtime.getRuntime().halt(97)
        }
        try {
            runBlocking {
                withTimeoutOrNull(90_000) { driveDeath() } ?: fail("death run timed out")
            }
        } finally {
            harnessWd.interrupt()
        }
    }

    private suspend fun driveDeath() {
        val identity = TlsIdentity.generate()
        val dest = Files.createTempDirectory("flit-death")
        val tls = true
        val serverRef = java.util.concurrent.atomic.AtomicReference<app.flit.share.protocol.server.LinkServer>()
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val connections = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val p = inbox.receive()
                val s = serverRef.get()
                launch(Dispatchers.IO) {
                    val raw = p.serverLink("127.0.0.1")
                    try {
                        s.serve(TlsLink(raw, identity, clientMode = false))
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        System.err.println("serve crashed: $t")
                    }
                }
            }
        }
        val cli = PeerHttpClient(identity, links = SimLinkProvider(identity, tls, inbox))
        val sender = PeerInfo(
            alias = "death-sender", version = "2.2",
            fingerprint = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )

        // 1. live core takes half a body and loses its process to the abort.
        val payload = ByteArray(1536 * 1024).also { Random(777).nextBytes(it) }
        val fi = FileInfo(
            id = "f1", fileName = "parity-death.bin",
            size = payload.size.toLong(), fileType = "application/octet-stream",
            sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        )
        val core1 = ServerCore(identity, ServerConfig(destDir = dest, alias = "parity-node"), ParityDelegate())
        serverRef.set(LinkServer(core1))
        val reqBytes = WireJson.encode(PrepareUploadRequest(sender.sansAnnounce(), mapOf("f1" to fi))).toByteArray()
        val prepared = cli.exchange(
            "POST", "127.0.0.1", 53317, tls, Protocol.PATH_PREPARE_UPLOAD,
            headers = mapOf("Content-Type" to "application/json"),
            bodyProvider = { ByteArrayInputStream(reqBytes) },
            contentLength = reqBytes.size.toLong(),
            timeoutMs = 20_000,
        )
        assertEquals(200, prepared.status)
        val pr = WireJson.decode<PrepareUploadResponse>(prepared.text())
        val rt = Protocol.resumeToken(fi)

        PeerHttpClient.testAbortBytes = payload.size / 2L
        val aborted = runCatching {
            cli.exchange(
                "POST", "127.0.0.1", 53317, tls,
                "${Protocol.PATH_UPLOAD}?sessionId=${enc(pr.sessionId)}&fileId=f1&token=${pr.files["f1"]}",
                headers = mapOf("Content-Type" to "application/octet-stream"),
                bodyProvider = { ByteArrayInputStream(payload) },
                contentLength = payload.size.toLong(),
                bodyProgress = { },
                timeoutMs = 45_000,
            )
        }
        PeerHttpClient.testAbortBytes = 0L
        assertTrue(aborted.isFailure, "upload body must have aborted mid-flight")

        // 2. the death leaves a part AND its token sidecar on disk — the
        // sidecar is the whole trick: discoverability that outlives the
        // process, written at keep time.
        val part = dest.resolve(".parity-death.bin.part")
        val sidecar = app.flit.share.protocol.server.tokenSidecar(part)
        var sidecarSeen = false
        for (attempt in 0 until 100) {
            if (Files.exists(sidecar)) {
                sidecarSeen = true
                break
            }
            delay(50)
        }
        assertTrue(sidecarSeen, "token sidecar never appeared beside the kept part")
        assertEquals(rt, Files.readString(sidecar).trim(), "sidecar must carry the sender's probe token")

        // 3. the process is gone. A fresh core over the same dest sweeps the
        // part back into its registry — the probe answers across death.
        val delegate2 = ParityDelegate()
        val core2 = ServerCore(identity, ServerConfig(destDir = dest, alias = "parity-node"), delegate2)
        core2.sweepOnStart()
        serverRef.set(LinkServer(core2))
        val probed = cli.exchange(
            "GET", "127.0.0.1", 53317, tls,
            "${Protocol.PATH_PARTIAL}?token=${rt}",
            timeoutMs = 20_000,
        )
        assertEquals(200, probed.status, "probe after death answered ${probed.status}: ${probed.text().take(200)}")
        val prefix = WireJson.decode<PartialInfo>(probed.text()).size
        assertTrue(prefix > 0 && prefix < payload.size, "probe says $prefix of ${payload.size}")

        // 4. the real re-entry: the production client's sendFiles — attempt 1
        // probes /partial (the swept prefix answers from the receiver's
        // disk), attaches with X-Flit-Resume on its own, and lands
        // hash-exact. No manual headers: the fresh sender re-learns the
        // prefix, which is the whole point of milestone C.
        val sent = ProtocolClient(identity, links = SimLinkProvider(identity, tls, inbox)).let { full ->
            full.sendFiles(
                SendTarget("127.0.0.1", 53317, tls),
                sender,
                listOf(object : OutgoingFile {
                    override val wireName = "parity-death.bin"
                    override val size = payload.size.toLong()
                    override val mime = "application/octet-stream"
                    override fun openStream(): InputStream = ByteArrayInputStream(payload)
                }),
                multi = false,
            ).also { full.close() }
        }
        assertTrue(sent.failed.isEmpty(), "sendFiles after death failed: ${sent.failed}")
        assertEquals(1, sent.sent.size)
        assertContentEquals(payload, Files.readAllBytes(dest.resolve("parity-death.bin")))
        val ev = delegate2.events.snapshot()
        assertTrue(ev.any { it == "resume:$prefix" }, "resume event missing: ${ev.takeLast(8)}")
        assertTrue(ev.any { it.endsWith(":Saved") }, "finish event missing: ${ev.takeLast(8)}")

        // 5. the story is over: part and sidecar are gone from the disk.
        assertTrue(!Files.exists(part), "part file must be consumed by the landing")
        assertTrue(!Files.exists(sidecar), "token sidecar must go with the part")

        cli.close()
        connections.cancel()
    }

    @Test
    fun `a kept part survives the receiver's death and resumes hash-exact`() = deathRun()

    @Test
    fun `a carrier death throws instead of reporting`() = unreachableRun()

    /**
     * Milestone A's contract, sim-side: a carrier death is a THROW, never a
     * returned report. Two shapes:
     *  1. nobody is there at all — the batch prepare cannot even connect,
     *     and sendFiles throws TransferUnreachable;
     *  2. a receiver takes half a body, then the carrier dies for good —
     *     the salvage probes fail, the attempts exhaust, and the sender
     *     throws instead of returning a report the caller could misread as
     *     "the far side spoke". The receiver's keep (part + token sidecar)
     *     must survive the throw untouched: that on-disk prefix is exactly
     *     what the next flush's resume rides.
     * The receiver-alive half of this story — death, sweep, hash-exact
     * resume — is `deathRun` above.
     */
    private fun unreachableRun() {
        runBlocking {
            withTimeoutOrNull(90_000) { driveUnreachable() } ?: fail("unreachable run timed out")
        }
    }

    private suspend fun driveUnreachable() {
        val identity = TlsIdentity.generate()
        val dest = Files.createTempDirectory("flit-dead")
        val sender = PeerInfo(
            alias = "unreach-sender", version = "2.2",
            fingerprint = "UNREACH_FP_00000000000000000000000000000000000",
        )

        // 1. nothing listens at all: the prepare handshake cannot connect.
        val dead = ProtocolClient(identity, links = object : LinkProvider {
            override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                throw IOException("connect refused (sim: nobody is there)")
        })
        val alone = runCatching {
            dead.sendFiles(
                SendTarget("127.0.0.1", 53317, tls = true),
                sender,
                listOf(plainOutgoing("unreachable.bin", 4096)),
            )
        }
        dead.close()
        assertTrue(alone.isFailure, "a prepare against a dead carrier must throw")
        val aloneErr = alone.exceptionOrNull()
        assertTrue(
            aloneErr is TransferUnreachable,
            "prepare death surfaced as ${aloneErr?.javaClass?.simpleName}, not TransferUnreachable",
        )

        // 2. a live receiver takes half a body, then the carrier dies for
        // good: dials 1-3 serve the prepare, the first /partial probe and
        // the aborted body; every later dial is refused.
        val tls = true
        val serverRef = java.util.concurrent.atomic.AtomicReference<LinkServer>()
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val connections = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val p = inbox.receive()
                val s = serverRef.get() ?: continue
                launch(Dispatchers.IO) {
                    val raw = p.serverLink("127.0.0.1")
                    try {
                        s.serve(TlsLink(raw, identity, clientMode = false))
                    } catch (t: Throwable) {
                        System.err.println("serve crashed: $t")
                    }
                }
            }
        }
        var dials = 0
        val carrier = object : LinkProvider {
            override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link {
                dials++
                if (dials > 3) throw IOException("carrier gone (dial $dials)")
                return SimLinkProvider(identity, tls, inbox).connect(host, port, tls, timeoutMs)
            }
        }
        val payload = ByteArray(1536 * 1024).also { Random(909).nextBytes(it) }
        val outgoing = plainOutgoing("unreachable.bin", payload.size.toLong()) { ByteArrayInputStream(payload) }
        val core = ServerCore(identity, ServerConfig(destDir = dest, alias = "parity-node"), ParityDelegate())
        serverRef.set(LinkServer(core))
        val cli = ProtocolClient(identity, links = carrier)
        PeerHttpClient.testAbortBytes = payload.size / 2L
        val cut = runCatching {
            cli.sendFiles(SendTarget("127.0.0.1", 53317, tls), sender, listOf(outgoing))
        }
        PeerHttpClient.testAbortBytes = 0L
        assertTrue(dials > 2, "expected dials past the carrier's death, saw $dials")
        val cutErr = cut.exceptionOrNull()
        assertTrue(cut.isFailure, "mid-body carrier death must throw, got report: ${cut.getOrNull()}")
        assertTrue(
            cutErr is TransferUnreachable,
            "mid-body death surfaced as ${cutErr?.javaClass?.simpleName}, not TransferUnreachable",
        )

        // The throw left the receiver's checkpoint untouched — the part and
        // its token sidecar sit on disk, waiting for the next flush.
        val part = dest.resolve(".unreachable.bin.part")
        val sidecar = app.flit.share.protocol.server.tokenSidecar(part)
        var keptSeen = false
        for (attempt in 0 until 100) {
            if (Files.exists(part) && Files.exists(sidecar)) {
                keptSeen = true
                break
            }
            delay(50)
        }
        assertTrue(keptSeen, "part/sidecar must survive the sender's throw (part=${Files.exists(part)} sidecar=${Files.exists(sidecar)})")

        cli.close()
        connections.cancel()
    }

    private fun plainOutgoing(
        name: String,
        size: Long,
        stream: () -> InputStream = { ByteArrayInputStream(ByteArray(size.toInt())) },
    ): OutgoingFile = object : OutgoingFile {
        override val wireName = name
        override val size = size
        override val mime = "application/octet-stream"
        override fun openStream(): InputStream = stream()
    }
}
