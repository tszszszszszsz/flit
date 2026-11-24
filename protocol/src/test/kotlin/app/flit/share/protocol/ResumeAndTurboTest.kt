package app.flit.share.protocol

import app.flit.share.protocol.client.OutgoingFile
import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PartialInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.server.UploadResult
import app.flit.share.protocol.server.parseContentRange
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The 2026-11-18 hardware-walk defect, pinned layer by layer.
 *
 * The walk killed the receiver mid-upload, restarted it, and watched the
 * sender burn 711 MB into a fresh part instead of appending the missing
 * tail to the 127 MB prefix:
 *
 *  1. `Content-Range` was parsed only as `bytes=…`, while the wire value is
 *     the RFC form `bytes …` — every turbo chunk was answered 400, so the
 *     multi-socket lane silently fell back to single-stream on every run.
 *  2. The plain retry's claim trimmed the kept part to the (empty) chunk
 *     ledger's base of zero, deleting the prefix; the resume header then
 *     found no base and the tail was written as a fresh file.
 *
 * These tests keep both doors closed: the header parser accepts the real
 * wire syntax, a part left by a dead run is probed and resumed hash-exact,
 * a failed chunk claim never destroys the kept part, a resume header whose
 * base is gone is refused with 409 instead of written blind, and the
 * production `sendFiles(multi = true)` path actually exercises the turbo
 * lane end-to-end and starts once.
 */
class ResumeAndTurboTest {

    // ---- fixtures ---------------------------------------------------------

    private class EventDelegate : ServerDelegate {
        val events = CopyOnWriteArrayList<String>()

        override fun onPrepare(request: PrepareUploadRequest, peerIp: String) =
            PrepareDecision.Accept(request.files.keys)

        override fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {
            events += "start:$fileId"
        }

        override fun onResumeStart(sessionId: String, fileId: String, file: FileInfo, prefixBytes: Long) {
            events += "resume:$prefixBytes"
        }

        override fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {
            events += "finish:${result.javaClass.simpleName}"
        }
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun shaOf(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun payload(bytes: Int, seed: Long): ByteArray =
        ByteArray(bytes).also { Random(seed).nextBytes(it) }

    private fun info(id: String, name: String, payload: ByteArray) = FileInfo(
        id = id,
        fileName = name,
        size = payload.size.toLong(),
        fileType = "application/octet-stream",
        sha256 = shaOf(payload),
    )

    private fun sender() = PeerInfo(
        alias = "resume-sender",
        version = Protocol.VERSION,
        deviceType = "headless",
        fingerprint = "RESUME_TEST_FP_0000000000000000000000000000000000",
        port = 53317,
        protocol = "https",
    )

    private fun startServer(dest: Path, delegate: ServerDelegate, alias: String): ProtocolServer {
        val server = ProtocolServer(
            TlsIdentity.generate(),
            delegate,
            ServerConfig(alias = alias, port = 0, tls = false, destDir = dest),
        )
        server.start()
        return server
    }

    /** A part as it survives a SIGKILL: bytes on disk + its discoverability. */
    private fun seedPart(dest: Path, name: String, fi: FileInfo, prefix: ByteArray) {
        Files.write(dest.resolve(".$name.part"), prefix)
        Files.writeString(dest.resolve(".$name.part.token"), Protocol.resumeToken(fi))
    }

    private suspend fun prepare(
        cli: PeerHttpClient,
        port: Int,
        fi: FileInfo,
        multi: Boolean = false,
    ): PrepareUploadResponse {
        val req = WireJson.encode(
            PrepareUploadRequest(sender().sansAnnounce(), mapOf(fi.id to fi)),
        ).toByteArray()
        val res = cli.exchange(
            "POST", "127.0.0.1", port, tls = false, Protocol.PATH_PREPARE_UPLOAD,
            headers = buildMap {
                put("Content-Type", "application/json")
                if (multi) put("X-Flit-Multi", "1")
            },
            bodyProvider = { ByteArrayInputStream(req) },
            contentLength = req.size.toLong(),
            timeoutMs = 20_000,
        )
        assertEquals(200, res.status, "prepare answered ${res.status}: ${res.text().take(200)}")
        return WireJson.decode(res.text())
    }

    private fun uploadPath(pr: PrepareUploadResponse, fileId: String): String =
        "${Protocol.PATH_UPLOAD}?sessionId=${enc(pr.sessionId)}&fileId=${enc(fileId)}" +
            "&token=${enc(pr.files.getValue(fileId))}"

    private suspend fun probe(cli: PeerHttpClient, port: Int, token: String): Int {
        val r = cli.exchange(
            "GET", "127.0.0.1", port, tls = false,
            "${Protocol.PATH_PARTIAL}?token=${enc(token)}",
            timeoutMs = 2_000,
        )
        return r.status
    }

    private suspend fun probeSize(cli: PeerHttpClient, port: Int, token: String): Long {
        val r = cli.exchange(
            "GET", "127.0.0.1", port, tls = false,
            "${Protocol.PATH_PARTIAL}?token=${enc(token)}",
            timeoutMs = 2_000,
        )
        assertEquals(200, r.status, "probe answered ${r.status}: ${r.text().take(200)}")
        return WireJson.decode<PartialInfo>(r.text()).size
    }

    // ---- 1. the wire syntax -------------------------------------------------

    @Test
    fun `content range accepts the rfc space form and the equals form`() {
        assertEquals(Triple(10L, 19L, 100L), parseContentRange("bytes 10-19/100"))
        assertEquals(Triple(0L, 0L, 1L), parseContentRange("bytes 0-0/1"))
        assertEquals(Triple(10L, 19L, 100L), parseContentRange("bytes=10-19/100"))
        assertEquals(Triple(10L, 19L, 100L), parseContentRange("BYTES 10-19/100"))
        assertNull(parseContentRange("items 0-1/2"))
        assertNull(parseContentRange("bytes 10-19/*"))
        assertNull(parseContentRange("bytes 19-10/100"))
        assertNull(parseContentRange("bytes 10-/100"))
    }

    // ---- 2. restart: probe and attach share the disk base ------------------

    @Test
    fun `a part left by a dead run is probed and resumed hash-exact`() = runBlocking {
        val dest = Files.createTempDirectory("resume-restart")
        val body = payload(2 * 1024 * 1024, seed = 11)
        val fi = info("f1", "restart.bin", body)
        val token = Protocol.resumeToken(fi)
        val prefix = body.size / 2
        seedPart(dest, "restart.bin", fi, body.copyOf(prefix))

        val delegate = EventDelegate()
        val server = startServer(dest, delegate, "restart-node")
        val cli = PeerHttpClient(TlsIdentity.generate())
        try {
            // A fresh process answers the probe from the part's own sidecar.
            assertEquals(prefix.toLong(), probeSize(cli, server.port, token))

            val pr = prepare(cli, server.port, fi)
            val tail = body.copyOfRange(prefix, body.size)
            val resumed = cli.exchange(
                "POST", "127.0.0.1", server.port, tls = false, uploadPath(pr, "f1"),
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "X-Flit-Resume" to token,
                ),
                bodyProvider = { ByteArrayInputStream(tail) },
                contentLength = tail.size.toLong(),
                timeoutMs = 20_000,
            )
            assertEquals(200, resumed.status, "resume answered ${resumed.status}: ${resumed.text().take(200)}")
            assertContentEquals(body, Files.readAllBytes(dest.resolve("restart.bin")))

            val ev = delegate.events
            assertEquals(1, ev.count { it.startsWith("start:") }, "start must fire once: $ev")
            assertEquals(1, ev.count { it == "resume:$prefix" }, "resume event at the probed base: $ev")
            assertTrue(ev.any { it == "finish:Saved" }, "finish event missing: $ev")
            // The story is over: part + sidecar consumed by the landing.
            assertFalse(Files.exists(dest.resolve(".restart.bin.part")))
            assertFalse(Files.exists(dest.resolve(".restart.bin.part.token")))
        } finally {
            cli.close()
            server.close()
        }
    }

    // ---- 3. the walk's exact sequence: failed chunk claim, then resume -----

    @Test
    fun `a failed chunk claim leaves the kept part intact for a plain resume`() = runBlocking {
        val dest = Files.createTempDirectory("resume-chunk-fail")
        val body = payload(2 * 1024 * 1024, seed = 23)
        val fi = info("f1", "walk.bin", body)
        val token = Protocol.resumeToken(fi)
        val prefix = body.size / 3
        seedPart(dest, "walk.bin", fi, body.copyOf(prefix))

        val delegate = EventDelegate()
        val server = startServer(dest, delegate, "walk-node")
        val cli = PeerHttpClient(TlsIdentity.generate())
        try {
            assertEquals(prefix.toLong(), probeSize(cli, server.port, token))
            val pr = prepare(cli, server.port, fi, multi = true)

            // The turbo lane claims first, then the route refuses the range
            // (a total that disagrees with the offer). The claim already put
            // an empty ledger on the file — the state that used to delete
            // the prefix when the plain retry re-claimed.
            val bad = cli.exchange(
                "POST", "127.0.0.1", server.port, tls = false, uploadPath(pr, "f1"),
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Range" to "bytes 0-0/${body.size + 1}",
                    "X-Flit-Base" to "0",
                ),
                contentLength = 0,
                timeoutMs = 20_000,
            )
            assertEquals(400, bad.status, "a bad chunk must be refused: ${bad.status}")
            assertEquals(
                prefix.toLong(), Files.size(dest.resolve(".walk.bin.part")),
                "the kept part must survive a refused chunk claim",
            )

            // The plain retry re-claims and attaches: the base now comes from
            // the registry, not from the empty ledger.
            val tail = body.copyOfRange(prefix, body.size)
            val resumed = cli.exchange(
                "POST", "127.0.0.1", server.port, tls = false, uploadPath(pr, "f1"),
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "X-Flit-Resume" to token,
                ),
                bodyProvider = { ByteArrayInputStream(tail) },
                contentLength = tail.size.toLong(),
                timeoutMs = 20_000,
            )
            assertEquals(200, resumed.status, "plain resume answered ${resumed.status}: ${resumed.text().take(200)}")
            assertContentEquals(body, Files.readAllBytes(dest.resolve("walk.bin")))

            val ev = delegate.events
            assertEquals(1, ev.count { it.startsWith("start:") }, "start must fire once: $ev")
            assertEquals(1, ev.count { it == "resume:$prefix" }, "resume event at the kept base: $ev")
            assertTrue(ev.any { it == "finish:Saved" }, "finish event missing: $ev")
        } finally {
            cli.close()
            server.close()
        }
    }

    // ---- 4. a resume header with no base is refused, never written blind ---

    @Test
    fun `a resume header with no held base is refused with 409 and writes nothing`() = runBlocking {
        val dest = Files.createTempDirectory("resume-nobase")
        val body = payload(64 * 1024, seed = 31)
        val fi = info("f1", "nobase.bin", body)
        val token = Protocol.resumeToken(fi)

        val delegate = EventDelegate()
        val server = startServer(dest, delegate, "nobase-node")
        val cli = PeerHttpClient(TlsIdentity.generate())
        try {
            assertEquals(404, probe(cli, server.port, token), "nothing is held; the probe must say so")
            val pr = prepare(cli, server.port, fi)
            val refused = cli.exchange(
                "POST", "127.0.0.1", server.port, tls = false, uploadPath(pr, "f1"),
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "X-Flit-Resume" to token,
                ),
                bodyProvider = { ByteArrayInputStream(body) },
                contentLength = body.size.toLong(),
                timeoutMs = 20_000,
            )
            assertEquals(
                409, refused.status,
                "a tail with no held base must be refused, not written as a fresh file: ${refused.status}",
            )
            assertFalse(Files.exists(dest.resolve("nobase.bin")), "the tail must not land as a file")
            assertFalse(Files.exists(dest.resolve(".nobase.bin.part")), "the tail must not land as a part")
            assertTrue(
                delegate.events.none { it.startsWith("start:") || it.startsWith("resume:") },
                "a refused resume is not an upload start: ${delegate.events}",
            )
        } finally {
            cli.close()
            server.close()
        }
    }

    // ---- 5. the production multi-socket path, resume-aware -----------------

    @Test
    fun `multi-socket send resumes the receiver's prefix, starts once and lands hash-exact`() = runBlocking {
        val dest = Files.createTempDirectory("turbo-resume")
        val body = payload(40 * 1024 * 1024, seed = 47)
        val fi = info("f1", "turbo.bin", body)
        val prefix = 5 * 1024 * 1024
        seedPart(dest, "turbo.bin", fi, body.copyOf(prefix))

        val delegate = EventDelegate()
        val server = startServer(dest, delegate, "turbo-node")
        val client = ProtocolClient(TlsIdentity.generate())
        try {
            val report = client.sendFiles(
                SendTarget("127.0.0.1", server.port, tls = false),
                sender(),
                listOf(object : OutgoingFile {
                    override val wireName = "turbo.bin"
                    override val size = body.size.toLong()
                    override val mime = "application/octet-stream"
                    override fun openStream(): InputStream = ByteArrayInputStream(body)
                }),
                multi = true,
            )
            assertTrue(report.failed.isEmpty(), "multi-socket send failed: ${report.failed}")
            assertEquals(1, report.sent.size)
            assertContentEquals(body, Files.readAllBytes(dest.resolve("turbo.bin")))

            val ev = delegate.events
            assertEquals(
                1, ev.count { it.startsWith("start:") },
                "a multi-stream run must announce the upload exactly once: $ev",
            )
            assertEquals(
                1, ev.count { it == "resume:$prefix" },
                "the turbo lane must seed its ledger from the advertised prefix: $ev",
            )
            assertTrue(ev.any { it == "finish:Saved" }, "finish event missing: $ev")
        } finally {
            client.close()
            server.close()
        }
    }
}
