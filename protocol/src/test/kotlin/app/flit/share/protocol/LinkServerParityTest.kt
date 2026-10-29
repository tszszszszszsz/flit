package app.flit.share.protocol

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.client.TlsLink
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
                    val raw = p.serverLink("192.0.2.77")
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
            fingerprint = "SENDER_FP_0000000000000000000000000000000000000",
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
            delim.events.snapshot().any { it == "register:parity-sender@192.0.2.77" },
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
}
