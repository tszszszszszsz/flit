package app.flit.share.protocol

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TransferException
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PanicAlert
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.panic.PanicCore
import app.flit.share.protocol.server.HttpExchange
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.server.UploadResult
import app.flit.share.protocol.tls.TlsIdentity
import app.flit.share.protocol.transport.PipePair
import app.flit.share.protocol.transport.SimLinkProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Honest refusals vs dead carriers. A deliberate prepare-level decline must
 * conclude the batch with a report (`SendReport.declined`); a 409/5xx or a
 * dropped link must still throw, so the engine keeps the transfer owed. An
 * aborted upload must end the receiver session as cancelled, never saved.
 */
class ProtocolRefusalTest {

    private class FakeExchange(
        override val method: String,
        override val path: String,
        private val body: String = "",
        private val requestHeaders: Map<String, String> = emptyMap(),
        private val params: Map<String, String> = emptyMap(),
        override val origin: String = "192.0.2.60",
    ) : HttpExchange {
        var status: Int = 0
        var response: String = ""
        var responseHeaders: Map<String, String> = emptyMap()
        /** Number of respond* calls; one exchange must answer exactly once. */
        var responses: Int = 0
            private set
        /** Set after prepare to emulate a carrier dying mid-body. */
        var failReadWith: Throwable? = null
        private var at = 0
        private val bodyBytes: ByteArray by lazy { body.toByteArray(Charsets.UTF_8) }

        override fun query(name: String): String? = params[name] ?: path.substringAfter('?', "")
            .split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }

        override fun header(name: String): String? = requestHeaders[name.lowercase()]

        override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int {
            failReadWith?.let { throw it }
            val bytes = bodyBytes
            if (at >= bytes.size) return 0
            val n = minOf(len, bytes.size - at)
            System.arraycopy(bytes, at, buf, offset, n)
            at += n
            return n
        }

        override suspend fun respondText(status: Int, contentType: String, body: String) {
            this.status = status
            this.response = body
            responses++
        }

        override suspend fun respondText(status: Int, contentType: String, body: String, headers: Map<String, String>) {
            this.status = status
            this.response = body
            // Real carriers lowercase response header names on the wire.
            this.responseHeaders = headers.entries.associate { it.key.lowercase() to it.value }
            responses++
        }

        override suspend fun respondStatus(status: Int) {
            this.status = status
            responses++
        }
    }

    /** A canned HTTP answer: no real carrier, just a status on the wire. */
    private class CannedLink(status: Int, body: String, headers: Map<String, String> = emptyMap()) : Link {
        private val bytes = ByteArrayOutputStream().apply {
            write("HTTP/1.1 $status Canned\r\n".toByteArray(Charsets.US_ASCII))
            headers.forEach { (k, v) -> write("$k: $v\r\n".toByteArray(Charsets.US_ASCII)) }
            val raw = body.toByteArray(Charsets.UTF_8)
            write("Content-Length: ${raw.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(raw)
        }.toByteArray()

        override val input: InputStream = ByteArrayInputStream(bytes)
        override val output: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }

    private fun info(name: String = "a.bin"): FileInfo = FileInfo(
        id = "f1", fileName = name, size = 3L, fileType = "application/octet-stream", sha256 = "sha",
    )

    private fun peer(fp: String): PeerInfo = PeerInfo(
        alias = "sender", version = Protocol.VERSION, fingerprint = fp, protocol = "http",
    ).sansAnnounce()

    private fun prepareBody(
        sender: TlsIdentity,
        declaredFp: String = sender.fingerprint,
    ): String = WireJson.encode(
        PrepareUploadRequest(peer(declaredFp), mapOf("f1" to info())),
    )

    /** The headers a Flit prepare sender attaches: cert plus a body signature. */
    private fun bindingHeaders(id: TlsIdentity, body: String): Map<String, String> = mapOf(
        Protocol.HEADER_PREPARE_CERT.lowercase() to
            Base64.getEncoder().encodeToString(id.certificate.encoded),
        Protocol.HEADER_PREPARE_SIG.lowercase() to
            id.sign(body.toByteArray(Charsets.UTF_8)),
    )

    private fun core(
        identity: TlsIdentity,
        delegate: ServerDelegate,
        dest: Path = Files.createTempDirectory("refusal-dest"),
        requirePrepareCert: () -> Boolean = { false },
    ) = ServerCore(
        identity,
        ServerConfig(tls = false, destDir = dest, requirePrepareCert = requirePrepareCert),
        delegate,
    )

    /**
     * The trust decision stays with the delegate; the route only proves key
     * possession. [allow] plays the role of the persisted pin / known-device
     * check fed by the bound fingerprint.
     */
    private class PrepareSpy(
        private val allow: (String?) -> Boolean = { true },
    ) : ServerDelegate {
        var calls = 0
            private set
        val presented = mutableListOf<String?>()

        override fun onPrepare(
            request: PrepareUploadRequest,
            peerIp: String,
            presentedFp: String?,
        ): PrepareDecision {
            calls++
            presented += presentedFp
            return if (allow(presentedFp)) PrepareDecision.Accept(request.files.keys)
            else PrepareDecision.Decline
        }
    }

    private val declining = object : ServerDelegate {
        override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
            PrepareDecision.Decline
    }

    @Test
    fun `a marked prepare decline carries the refusal header and an unmarked one does not`() = runBlocking {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val server = core(receiver, declining)
        val body = prepareBody(sender)

        val marked = FakeExchange(
            "POST", Protocol.PATH_PREPARE_UPLOAD, body,
            mapOf(Protocol.HEADER_FLIT_CLIENT.lowercase() to Protocol.FLIT_CLIENT_VALUE),
        )
        server.handle(marked)
        assertEquals(403, marked.status)
        assertEquals("Rejected", marked.response)
        assertEquals(Protocol.REFUSAL_DECLINED, marked.responseHeaders[Protocol.HEADER_FLIT_REFUSAL.lowercase()])

        val unmarked = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body)
        server.handle(unmarked)
        assertEquals(403, unmarked.status)
        assertEquals("Rejected", unmarked.response)
        assertTrue(unmarked.responseHeaders.isEmpty(), "v2.2 answer gained a Flit header")
    }

    @Test
    fun `sendFiles turns a marked decline into a concluded report not a throw`() = runBlocking {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val dest = Files.createTempDirectory("decline-report")
        val server = app.flit.share.protocol.server.ProtocolServer(
            receiver, declining, ServerConfig(port = 0, tls = false, destDir = dest),
        )
        val inbox = Channel<PipePair>(Channel.UNLIMITED)
        val client = ProtocolClient(sender, links = SimLinkProvider(sender, tls = false, inbox))
        val payload = Files.createTempFile("decline", ".bin").also { Files.write(it, "abc".toByteArray()) }
        val serve = launch(Dispatchers.IO) { server.serve(inbox.receive().serverLink("192.0.2.61")) }
        try {
            val report = client.sendFiles(
                SendTarget("127.0.0.1", 53317, tls = false),
                peer(sender.fingerprint),
                listOf(PathOutgoing(payload, "a.bin")),
            )
            assertTrue(report.declined, "a deliberate refusal must be a returned, concluded report")
            assertTrue(report.sent.isEmpty())
            assertEquals(1, report.failed.size)
            assertEquals("a.bin", report.failed.single().fileName)
            assertNull(report.sessionId, "no session is armed by a declined prepare")
        } finally {
            client.close()
            serve.cancel()
        }
    }

    @Test
    fun `transient prepare statuses still throw TransferException`() = runBlocking {
        val sender = TlsIdentity.generate()
        val payload = Files.createTempFile("transient", ".bin").also { Files.write(it, "abc".toByteArray()) }
        for (status in listOf(403, 409, 500)) {
            val client = ProtocolClient(sender, links = LinkProvider { _, _, _, _ -> CannedLink(status, "busy") })
            // An unmarked 403 is a v2.2 refusal we cannot interpret; it stays a
            // throw just like 409/5xx. Only the marked 403 returns a report.
            val thrown = assertFailsWith<TransferException> {
                client.sendFiles(
                    SendTarget("127.0.0.1", 53317, tls = false),
                    peer(sender.fingerprint),
                    listOf(PathOutgoing(payload, "a.bin")),
                )
            }
            assertEquals(status, thrown.status)
            client.close()
        }
    }

    @Test
    fun `an aborted upload ends the receiver session as cancelled not saved`() = runBlocking {
        val identity = TlsIdentity.generate()
        val ends = Collections.synchronizedList(mutableListOf<String>())
        val delegate = object : ServerDelegate {
            override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
                PrepareDecision.Accept(request.files.keys)

            override fun onSessionEnd(sessionId: String, cancelled: Boolean) {
                ends.add("end:cancelled=$cancelled")
            }
        }
        val server = core(identity, delegate)
        val prepared = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, prepareBody(identity))
        server.handle(prepared)
        assertEquals(200, prepared.status)
        val answer = WireJson.decode<PrepareUploadResponse>(prepared.response)

        val upload = FakeExchange(
            "POST",
            Protocol.PATH_UPLOAD,
            "abc",
            params = mapOf(
                "sessionId" to answer.sessionId,
                "fileId" to "f1",
                "token" to answer.files.getValue("f1"),
            ),
        )
        upload.failReadWith = CancellationException("carrier died mid-body")
        val aborted = runCatching { server.handle(upload) }
        assertTrue(aborted.isFailure, "the aborted pump must propagate cancellation")
        assertTrue(
            ends.any { it == "end:cancelled=true" },
            "a killed transfer was reported as a successful receive: $ends",
        )
        assertTrue(ends.none { it == "end:cancelled=false" }, "a cancelled session also ended as saved: $ends")
    }

    // ---- prepare certificate binding ---------------------------------------

    @Test
    fun `a gated prepare without certificate headers is refused before the delegate`() = runBlocking {
        val sender = TlsIdentity.generate()
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val body = prepareBody(sender)

        val unmarked = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body)
        server.handle(unmarked)
        assertEquals(403, unmarked.status)
        assertEquals("Rejected", unmarked.response)
        assertTrue(unmarked.responseHeaders.isEmpty(), "a v2.2 answer gained a Flit header")
        assertEquals(0, spy.calls, "the delegate saw an unbound prepare")

        val marked = FakeExchange(
            "POST", Protocol.PATH_PREPARE_UPLOAD, body,
            mapOf(Protocol.HEADER_FLIT_CLIENT.lowercase() to Protocol.FLIT_CLIENT_VALUE),
        )
        server.handle(marked)
        assertEquals(403, marked.status)
        assertEquals(
            Protocol.REFUSAL_DECLINED,
            marked.responseHeaders[Protocol.HEADER_FLIT_REFUSAL.lowercase()],
        )
        assertEquals(0, spy.calls)
    }

    @Test
    fun `a replayed certificate without a body signature is refused`() = runBlocking {
        val sender = TlsIdentity.generate()
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val body = prepareBody(sender)
        val certOnly = mapOf(
            Protocol.HEADER_PREPARE_CERT.lowercase() to
                Base64.getEncoder().encodeToString(sender.certificate.encoded),
        )
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, certOnly)
        server.handle(x)
        assertEquals(403, x.status, "a public certificate alone authenticated the request")
        assertEquals(0, spy.calls)
    }

    @Test
    fun `a declared fingerprint differing from the certificate digest is refused`() = runBlocking {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        // Correct signature by the victim over a body claiming the attacker.
        val body = prepareBody(victim, declaredFp = attacker.fingerprint)
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, bindingHeaders(victim, body))
        server.handle(x)
        assertEquals(403, x.status)
        assertEquals(0, spy.calls)
    }

    @Test
    fun `a signature made by another key is refused`() = runBlocking {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val body = prepareBody(victim)
        val headers = mapOf(
            Protocol.HEADER_PREPARE_CERT.lowercase() to
                Base64.getEncoder().encodeToString(victim.certificate.encoded),
            Protocol.HEADER_PREPARE_SIG.lowercase() to
                attacker.sign(body.toByteArray(Charsets.UTF_8)),
        )
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, headers)
        server.handle(x)
        assertEquals(403, x.status)
        assertEquals(0, spy.calls)
    }

    @Test
    fun `a valid certificate binding passes the verified digest to the delegate`() = runBlocking {
        val sender = TlsIdentity.generate()
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val body = prepareBody(sender)
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, bindingHeaders(sender, body))
        server.handle(x)
        assertEquals(200, x.status)
        assertEquals(1, spy.calls)
        assertEquals(listOf<String?>(sender.fingerprint), spy.presented)
        assertEquals("f1", WireJson.decode<PrepareUploadResponse>(x.response).files.keys.single())
    }

    @Test
    fun `with the gate off prepare headers are ignored and no fingerprint is passed`() = runBlocking {
        val sender = TlsIdentity.generate()
        val body = prepareBody(sender)
        for (headers in listOf(emptyMap(), bindingHeaders(sender, body))) {
            val spy = PrepareSpy()
            val server = core(TlsIdentity.generate(), spy)
            val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, headers)
            server.handle(x)
            assertEquals(200, x.status)
            assertEquals(1, spy.calls)
            assertEquals(listOf<String?>(null), spy.presented)
        }
    }

    @Test
    fun `malformed and oversized prepare binding headers are refused`() = runBlocking {
        val sender = TlsIdentity.generate()
        val body = prepareBody(sender)
        val cert = Base64.getEncoder().encodeToString(sender.certificate.encoded)
        val sig = sender.sign(body.toByteArray(Charsets.UTF_8))
        val cases = listOf(
            "bad cert base64" to mapOf(
                Protocol.HEADER_PREPARE_CERT.lowercase() to "not base64 !!",
                Protocol.HEADER_PREPARE_SIG.lowercase() to sig,
            ),
            "cert is not a certificate" to mapOf(
                Protocol.HEADER_PREPARE_CERT.lowercase() to
                    Base64.getEncoder().encodeToString("junk".toByteArray(Charsets.UTF_8)),
                Protocol.HEADER_PREPARE_SIG.lowercase() to sig,
            ),
            "bad signature base64" to mapOf(
                Protocol.HEADER_PREPARE_CERT.lowercase() to cert,
                Protocol.HEADER_PREPARE_SIG.lowercase() to "not base64 !!",
            ),
            "signature over another body" to mapOf(
                Protocol.HEADER_PREPARE_CERT.lowercase() to cert,
                Protocol.HEADER_PREPARE_SIG.lowercase() to
                    sender.sign(prepareBody(sender, declaredFp = "OTHER").toByteArray(Charsets.UTF_8)),
            ),
            "oversized cert" to mapOf(
                Protocol.HEADER_PREPARE_CERT.lowercase() to "A".repeat(ServerCore.MAX_PREPARE_CERT_CHARS + 1),
                Protocol.HEADER_PREPARE_SIG.lowercase() to sig,
            ),
            "oversized signature" to mapOf(
                Protocol.HEADER_PREPARE_CERT.lowercase() to cert,
                Protocol.HEADER_PREPARE_SIG.lowercase() to "B".repeat(ServerCore.MAX_PREPARE_SIG_CHARS + 1),
            ),
        )
        for ((label, headers) in cases) {
            val spy = PrepareSpy()
            val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
            val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, headers)
            server.handle(x)
            assertEquals(403, x.status, label)
            assertEquals(0, spy.calls, label)
        }
    }

    @Test
    fun `a self-consistent attacker identity is still refused by the delegate trust gate`() = runBlocking {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        // Binding proves key possession, not trust: the delegate applies the
        // persisted-list check to the digest the route verified.
        val spy = PrepareSpy(allow = { it == victim.fingerprint })
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val body = prepareBody(attacker)
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, bindingHeaders(attacker, body))
        server.handle(x)
        assertEquals(403, x.status)
        assertEquals(1, spy.calls, "the verified digest never reached the trust check")
        assertEquals(listOf<String?>(attacker.fingerprint), spy.presented)
    }

    @Test
    fun `a two argument delegate still receives prepares through the default bridge`() = runBlocking {
        val sender = TlsIdentity.generate()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val delegate = object : ServerDelegate {
            override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision {
                seen += request.info.fingerprint
                return PrepareDecision.Accept(request.files.keys)
            }
        }
        val server = core(TlsIdentity.generate(), delegate)
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, prepareBody(sender))
        server.handle(x)
        assertEquals(200, x.status)
        assertEquals(listOf(sender.fingerprint), seen)
    }

    // ---- pin-store discipline and body caps --------------------------------

    @Test
    fun `refused panic and circle requests leave the pin store unchanged`() = runBlocking {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val signer = TlsIdentity.generate()
        TlsIdentity.pin(victim.certificate)
        val victimBefore = TlsIdentity.pinned(victim.fingerprint)!!

        val server = core(receiver, object : ServerDelegate {})
        val forged = PanicAlert(
            id = "forged-panic", senderFp = victim.fingerprint,
            sentMs = System.currentTimeMillis(), text = "help", hops = 0,
            originFp = victim.fingerprint,
        ).let { it.copy(sig = attacker.sign(PanicCore.canonical(it))) }
        val certHeader = Protocol.HEADER_PANIC_CERT.lowercase() to
            Base64.getEncoder().encodeToString(attacker.certificate.encoded)
        val panic = FakeExchange(
            "POST", Protocol.PATH_PANIC, WireJson.encode(forged), mapOf(certHeader),
        )
        server.handle(panic)
        assertEquals(403, panic.status)
        assertNull(TlsIdentity.pinned(attacker.fingerprint), "a refused panic pinned its header cert")

        val circle = CircleRequest(
            action = CircleRequest.ACTION_SYNC, circleId = "circle-1", memberFp = victim.fingerprint,
        )
        val cx = FakeExchange(
            "POST", Protocol.PATH_CIRCLE_SYNC, WireJson.encode(circle),
            mapOf(Protocol.HEADER_CIRCLE_CERT.lowercase() to certHeader.second),
        )
        server.handle(cx)
        assertEquals(403, cx.status)
        assertNull(TlsIdentity.pinned(attacker.fingerprint), "a refused circle pinned its header cert")
        assertContentEquals(
            victimBefore.encoded,
            TlsIdentity.pinned(victim.fingerprint)!!.encoded,
            "a refused request evicted the recipient pin",
        )

        // A request that passes both checks may pin, and only then.
        val honest = PanicCore.create(signer.fingerprint, "help", signer)
        val hx = FakeExchange(
            "POST", Protocol.PATH_PANIC, WireJson.encode(honest),
            mapOf(Protocol.HEADER_PANIC_CERT.lowercase() to
                Base64.getEncoder().encodeToString(signer.certificate.encoded)),
        )
        server.handle(hx)
        assertEquals(200, hx.status)
        assertTrue(TlsIdentity.pinned(signer.fingerprint) != null, "a verified alert did not pin")
    }

    @Test
    fun `an attacker signed alert claiming a victim origin is refused before the delegate`() = runBlocking {
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        var deliveries = 0
        val delegate = object : ServerDelegate {
            override fun onPanic(alert: PanicAlert, ip: String) {
                deliveries++
            }
        }
        val server = core(TlsIdentity.generate(), delegate)

        // The attacker holds its own key and signs both the hop and the
        // origin fields, but claims the victim as origin. Its certificate
        // cannot bind to the victim's fingerprint, so verifyOrigin fails.
        val authored = PanicCore.create(victim.fingerprint, "help", victim)
        val claimed = authored.copy(
            id = "claimed-${authored.id}",
            senderFp = attacker.fingerprint,
            originSig = attacker.sign(PanicCore.canonical(authored)),
            originCert = Base64.getEncoder().encodeToString(attacker.certificate.encoded),
        ).let { it.copy(sig = attacker.sign(PanicCore.canonical(it))) }
        val x = FakeExchange(
            "POST", Protocol.PATH_PANIC, WireJson.encode(claimed),
            mapOf(Protocol.HEADER_PANIC_CERT.lowercase() to
                Base64.getEncoder().encodeToString(attacker.certificate.encoded)),
        )
        server.handle(x)
        assertTrue(x.status == 403 || x.status == 400, "status was ${x.status}")
        assertEquals(0, deliveries, "the delegate saw an unbound origin claim")
        assertNull(TlsIdentity.pinned(attacker.fingerprint), "a refused alert pinned the attacker cert")
    }

    @Test
    fun `oversized control bodies are answered 413 before route work`() = runBlocking {
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val big = "x".repeat(ServerCore.MAX_CONTROL_BODY_BYTES + 1)

        val register = FakeExchange("POST", Protocol.PATH_REGISTER, big)
        server.handle(register)
        assertEquals(413, register.status)

        val prepare = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, big)
        server.handle(prepare)
        assertEquals(413, prepare.status)
        assertEquals(0, spy.calls, "an over-cap prepare reached the delegate")

        val panic = FakeExchange("POST", Protocol.PATH_PANIC, big)
        server.handle(panic)
        assertEquals(413, panic.status)
    }

    @Test
    fun `a plain upload body longer than the declared size is refused without storing the excess`() = runBlocking {
        val dest = Files.createTempDirectory("oversize-upload")
        val finishes = Collections.synchronizedList(mutableListOf<UploadResult>())
        val delegate = object : ServerDelegate {
            override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
                PrepareDecision.Accept(request.files.keys)

            override fun onUploadFinish(
                sessionId: String,
                fileId: String,
                file: FileInfo,
                result: UploadResult,
            ) {
                finishes += result
            }
        }
        val server = core(TlsIdentity.generate(), delegate, dest = dest)
        val prepared = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, prepareBody(TlsIdentity.generate()))
        server.handle(prepared)
        assertEquals(200, prepared.status)
        val answer = WireJson.decode<PrepareUploadResponse>(prepared.response)

        val upload = FakeExchange(
            "POST", Protocol.PATH_UPLOAD, "abcdef", // info() declares 3 bytes
            params = mapOf(
                "sessionId" to answer.sessionId,
                "fileId" to "f1",
                "token" to answer.files.getValue("f1"),
            ),
        )
        server.handle(upload)
        assertEquals(413, upload.status)
        assertEquals("Body exceeds declared size", upload.response)
        assertTrue(finishes.any { it is UploadResult.Mismatch })
        Files.list(dest).use { stream ->
            assertTrue(stream.noneMatch { Files.isRegularFile(it) }, "an oversized body left bytes on disk")
        }
    }

    // ---- QA P1 regressions -------------------------------------------------

    @Test
    fun `quiet panic refusals leave both pin tiers unchanged`() = runBlocking {
        val sender = TlsIdentity.generate()
        PanicCore.clear()
        var deliveries = 0
        val delegate = object : ServerDelegate {
            override fun onPanic(alert: PanicAlert, ip: String) {
                deliveries++
            }
        }
        val server = core(TlsIdentity.generate(), delegate)
        val certB64 = Base64.getEncoder().encodeToString(sender.certificate.encoded)

        fun signed(
            id: String,
            hops: Int = 0,
            sentMs: Long = System.currentTimeMillis(),
        ): PanicAlert {
            val base = PanicAlert(
                id = id, senderFp = sender.fingerprint, sentMs = sentMs,
                text = "help", hops = hops, originFp = sender.fingerprint,
            )
            val sig = sender.sign(PanicCore.canonical(base))
            val originSig = sender.sign(
                PanicCore.canonical(base.copy(senderFp = sender.fingerprint, hops = 0)),
            )
            return base.copy(sig = sig, originSig = originSig, originCert = certB64)
        }

        val cases = listOf(
            "duplicate" to signed("dup-id").also { PanicCore.fresh(it.id, System.currentTimeMillis()) },
            "outside window" to signed(
                "old-id", sentMs = System.currentTimeMillis() - PanicCore.REPLAY_WINDOW_MS - 1L,
            ),
            "hop limit" to signed("hop-id", hops = PanicCore.DEFAULT_MAX_HOPS),
        )
        for ((label, alert) in cases) {
            // PanicCore.create would pin locally; the route must not pin on a
            // quiet refusal, so start each case with an empty store.
            TlsIdentity.forgetPin(sender.fingerprint)
            val x = FakeExchange(
                "POST", Protocol.PATH_PANIC, WireJson.encode(alert),
                mapOf(Protocol.HEADER_PANIC_CERT.lowercase() to certB64),
            )
            server.handle(x)
            assertEquals(200, x.status, label)
            assertEquals(1, x.responses, label)
            assertNull(TlsIdentity.pinned(sender.fingerprint), "$label wrote a pin")
        }
        assertEquals(0, deliveries, "a quiet refusal reached the delegate")

        val delivered = signed("delivered-id")
        val hx = FakeExchange(
            "POST", Protocol.PATH_PANIC, WireJson.encode(delivered),
            mapOf(Protocol.HEADER_PANIC_CERT.lowercase() to certB64),
        )
        server.handle(hx)
        assertEquals(200, hx.status)
        assertEquals(1, deliveries)
        assertTrue(TlsIdentity.pinned(sender.fingerprint) != null, "a delivered alert did not pin")
    }

    @Test
    fun `a prepare with mixed valid and invalid file names produces exactly one response`() = runBlocking {
        val sender = TlsIdentity.generate()
        val dest = Files.createTempDirectory("mixed-names")
        val request = PrepareUploadRequest(
            peer(sender.fingerprint),
            mapOf("f1" to info("ok.bin"), "f2" to info("../bad.bin")),
        )
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, WireJson.encode(request))
        val server = core(TlsIdentity.generate(), PrepareSpy(), dest = dest)
        server.handle(x)
        assertEquals(1, x.responses, "one prepare produced ${x.responses} responses")
        assertEquals(204, x.status)
        Files.list(dest).use { stream ->
            assertTrue(stream.noneMatch { Files.isRegularFile(it) }, "a refused file name left bytes on disk")
        }
    }

    @Test
    fun `a colon formatted declared fingerprint binds to the certificate digest`() = runBlocking {
        val sender = TlsIdentity.generate()
        val spy = PrepareSpy()
        val server = core(TlsIdentity.generate(), spy, requirePrepareCert = { true })
        val body = prepareBody(sender, declaredFp = sender.fingerprint.chunked(2).joinToString(":"))
        val x = FakeExchange("POST", Protocol.PATH_PREPARE_UPLOAD, body, bindingHeaders(sender, body))
        server.handle(x)
        assertEquals(200, x.status)
        assertEquals(1, spy.calls)
        assertEquals(listOf<String?>(sender.fingerprint), spy.presented)
    }

    // ---- register boundary -------------------------------------------------

    @Test
    fun `spoofed and malformed register offers are refused before the delegate`() = runBlocking {
        val learned = Collections.synchronizedList(mutableListOf<String>())
        val delegate = object : ServerDelegate {
            override fun onRegister(info: PeerInfo, ip: String) {
                learned += info.fingerprint
            }
        }
        val server = core(TlsIdentity.generate(), delegate)
        val hex = "A".repeat(64)
        val cases = listOf(
            "short fingerprint" to
                PeerInfo(alias = "sender", version = "2.2", fingerprint = "short") to "127.0.0.1",
            "manual fingerprint" to
                PeerInfo(alias = "sender", version = "2.2", fingerprint = "manual@my-device") to "127.0.0.1",
            "blank alias" to
                PeerInfo(alias = "  ", version = "2.2", fingerprint = hex) to "127.0.0.1",
            "global source IP" to
                PeerInfo(alias = "sender", version = "2.2", fingerprint = hex) to "8.8.8.8",
        )
        for (case in cases) {
            val (label, offer) = case.first
            val origin = case.second
            val x = FakeExchange(
                "POST", Protocol.PATH_REGISTER, WireJson.encode(offer), origin = origin,
            )
            server.handle(x)
            assertEquals(403, x.status, label)
            assertEquals("Rejected", x.response, label)
            assertEquals(1, x.responses, label)
        }
        assertTrue(learned.isEmpty(), "a refused register reached the delegate: $learned")
    }

    @Test
    fun `a legitimate register fingerprint still reaches the delegate`() = runBlocking {
        val learned = Collections.synchronizedList(mutableListOf<String>())
        val delegate = object : ServerDelegate {
            override fun onRegister(info: PeerInfo, ip: String) {
                learned += info.fingerprint
            }
        }
        val server = core(TlsIdentity.generate(), delegate)
        val sender = TlsIdentity.generate()
        for (declared in listOf(sender.fingerprint, sender.fingerprint.lowercase())) {
            val x = FakeExchange(
                "POST", Protocol.PATH_REGISTER, WireJson.encode(peer(declared)), origin = "127.0.0.1",
            )
            server.handle(x)
            assertEquals(200, x.status)
            assertEquals(1, x.responses)
        }
        assertEquals(listOf(sender.fingerprint, sender.fingerprint.lowercase()), learned)
    }
}
