package app.flit.share.protocol

import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Milestone C's process-death promise, at its earliest point: a receiver
 * that is SIGKILLed never runs a catch block, so the part's `.token` sidecar
 * cannot wait for `keepPart`. It must already be on disk before the first
 * body byte — that is what lets the restarted server answer the sender's
 * `/partial` probe and resume instead of starting from zero (found on the
 * 2026-11-18 hardware walk, where a killed box restarted and logged
 * `recv-start` over a 24 MB part).
 */
class PartialSidecarTest {

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun shaOf(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `the part token sidecar exists before the first body byte`() = runBlocking {
        val dest = Files.createTempDirectory("sidecar-dest")
        val identity = TlsIdentity.generate()
        val server = ProtocolServer(
            identity,
            object : ServerDelegate {
                override fun onPrepare(request: PrepareUploadRequest, peerIp: String) =
                    PrepareDecision.Accept(request.files.keys)
            },
            ServerConfig(alias = "sidecar-node", port = 0, tls = false, destDir = dest),
        )
        val cli = PeerHttpClient(identity)
        val gate = CountDownLatch(1)
        try {
            server.start()
            val senderId = TlsIdentity.generate()
            val sender = PeerInfo(
                alias = "sidecar-sender", version = Protocol.VERSION,
                deviceType = "headless", fingerprint = senderId.fingerprint,
                port = 53317, protocol = "https",
            )
            // The size/sha describe a file that never gets sent here; only the
            // part's birth matters.
            val payload = ByteArray(777_777).also { java.util.Random(7).nextBytes(it) }
            val fi = FileInfo(
                id = "f1", fileName = "sidecar.bin", size = payload.size.toLong(),
                fileType = "application/octet-stream", sha256 = shaOf(payload),
            )
            val reqBytes = WireJson.encode(
                PrepareUploadRequest(sender.sansAnnounce(), mapOf("f1" to fi)),
            ).toByteArray()
            val prepared = cli.exchange(
                "POST", "127.0.0.1", server.port, tls = false, Protocol.PATH_PREPARE_UPLOAD,
                headers = mapOf("Content-Type" to "application/json"),
                bodyProvider = { ByteArrayInputStream(reqBytes) },
                contentLength = reqBytes.size.toLong(),
                timeoutMs = 20_000,
            )
            assertEquals(200, prepared.status)
            val pr = WireJson.decode<PrepareUploadResponse>(prepared.text())

            // A body that yields no byte until the gate opens: the upload is
            // in flight, the claim has been made, and nothing has been sent.
            val blocked = object : InputStream() {
                override fun read(): Int {
                    gate.await()
                    return -1
                }
            }
            val upload = launch(Dispatchers.IO) {
                runCatching {
                    cli.exchange(
                        "POST", "127.0.0.1", server.port, tls = false,
                        "${Protocol.PATH_UPLOAD}?sessionId=${enc(pr.sessionId)}" +
                            "&fileId=f1&token=${enc(pr.files.getValue("f1"))}",
                        headers = mapOf("Content-Type" to "application/octet-stream"),
                        bodyProvider = { blocked },
                        contentLength = payload.size.toLong(),
                        timeoutMs = 20_000,
                    )
                }
            }
            val part = dest.resolve(".sidecar.bin.part")
            val sidecar = dest.resolve(".sidecar.bin.part.token")
            val deadline = System.currentTimeMillis() + 5_000
            while (!Files.exists(sidecar) && System.currentTimeMillis() < deadline) delay(50)
            assertTrue(
                Files.exists(sidecar),
                "part sidecar never appeared — a SIGKILLed receiver would restart from zero",
            )
            assertEquals(Protocol.resumeToken(fi), Files.readString(sidecar).trim())
            assertEquals(0L, runCatching { Files.size(part) }.getOrDefault(0L), "body bytes must not have landed")
            gate.countDown()
            upload.join()
        } finally {
            gate.countDown()
            cli.close()
            server.close()
        }
    }
}
