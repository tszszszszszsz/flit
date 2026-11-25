package app.flit.share.protocol

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TransferException
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.HttpExchange
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.server.ServerDelegate
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
import java.util.Collections
import kotlin.test.Test
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
    ) : HttpExchange {
        override val origin: String = "192.0.2.60"
        var status: Int = 0
        var response: String = ""
        var responseHeaders: Map<String, String> = emptyMap()
        /** Set after prepare to emulate a carrier dying mid-body. */
        var failReadWith: Throwable? = null
        private var at = 0

        override fun query(name: String): String? = params[name] ?: path.substringAfter('?', "")
            .split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }

        override fun header(name: String): String? = requestHeaders[name.lowercase()]

        override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int {
            failReadWith?.let { throw it }
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (at >= bytes.size) return 0
            val n = minOf(len, bytes.size - at)
            System.arraycopy(bytes, at, buf, offset, n)
            at += n
            return n
        }

        override suspend fun respondText(status: Int, contentType: String, body: String) {
            this.status = status
            this.response = body
        }

        override suspend fun respondText(status: Int, contentType: String, body: String, headers: Map<String, String>) {
            this.status = status
            this.response = body
            // Real carriers lowercase response header names on the wire.
            this.responseHeaders = headers.entries.associate { it.key.lowercase() to it.value }
        }

        override suspend fun respondStatus(status: Int) {
            this.status = status
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

    private fun prepareBody(sender: TlsIdentity): String = WireJson.encode(
        PrepareUploadRequest(peer(sender.fingerprint), mapOf("f1" to info())),
    )

    private fun core(
        identity: TlsIdentity,
        delegate: ServerDelegate,
        dest: Path = Files.createTempDirectory("refusal-dest"),
    ) = ServerCore(identity, ServerConfig(tls = false, destDir = dest), delegate)

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
}
