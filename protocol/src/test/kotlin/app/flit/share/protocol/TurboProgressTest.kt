package app.flit.share.protocol

import app.flit.share.protocol.client.OutgoingFile
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The 2026-11-24 speed-readout complaint: "KB/s, then gigabits, then download
 * finished". One contributor was the multi-socket progress ledger: each of
 * the four streams reported its own *absolute* byte offset into the same
 * per-file slot, so the value seen by the UI jumped forward and backward as
 * faster and slower streams took their turn.
 *
 * This drives the production [ProtocolClient.sendFiles] multi=true path over
 * a real TCP [ProtocolServer] and records every progress callback. A 64 MiB
 * body splits into exactly four 16 MiB ranges (TURBO_MAX_STREAMS = 4,
 * SPLIT_TARGET_BYTES = 16 MiB), so the old overwrite-one-slot behaviour would
 * necessarily produce a decreasing value. The new code sums per-stream
 * bytes, which must be non-decreasing, never exceed the file size, and land
 * exactly on it once all ranges are confirmed.
 */
class TurboProgressTest {

    private class AcceptAll : ServerDelegate {
        override fun onPrepare(request: PrepareUploadRequest, peerIp: String) =
            PrepareDecision.Accept(request.files.keys)
    }

    @Test
    fun `multi socket progress is non decreasing bounded by total and ends exactly at total`() = runBlocking {
        val dest = Files.createTempDirectory("turbo-progress")
        // 64 MiB / 16 MiB split target = 4 concurrent ranges, i.e. exactly the
        // case where four absolute offsets used to fight over one ledger slot.
        val size = 64L * 1024L * 1024L
        val body = ByteArray(size.toInt()) { (it % 251).toByte() }
        val server = ProtocolServer(
            TlsIdentity.generate(),
            AcceptAll(),
            ServerConfig(alias = "turbo-progress-node", port = 0, tls = false, destDir = dest),
        )
        val client = ProtocolClient(TlsIdentity.generate())
        // Callbacks arrive on the four upload coroutines: collect thread-safely.
        val seen = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        try {
            server.start()
            val source = object : OutgoingFile {
                override val wireName = "turbo-progress.bin"
                override val size = body.size.toLong()
                override val mime = "application/octet-stream"
                override fun openStream(): InputStream = ByteArrayInputStream(body)
            }
            val report = client.sendFiles(
                SendTarget("127.0.0.1", server.port, tls = false),
                PeerInfo(
                    alias = "turbo-progress-sender",
                    version = Protocol.VERSION,
                    deviceType = "headless",
                    fingerprint = "TURBO_TEST_FP_0000000000000000000000000000000000",
                    port = 53317,
                    protocol = "https",
                ),
                listOf(source),
                multi = true,
            ) { _ /* fileName */, bytesSent, totalBytes ->
                seen += bytesSent to totalBytes
            }

            assertTrue(report.failed.isEmpty(), "multi-socket send failed: ${report.failed}")
            assertEquals(1, report.sent.size)
            assertContentEquals(body, Files.readAllBytes(dest.resolve("turbo-progress.bin")))

            assertTrue(seen.isNotEmpty(), "progress callback was never invoked")
            assertTrue(seen.all { it.second == size }, "callback total changed: ${seen.map { it.second }.toSet()}")
            assertTrue(
                seen.all { it.first in 0..size },
                "callback reported bytes outside 0..$size: ${seen.filter { it.first !in 0..size }}",
            )
            var previous = 0L
            for ((bytes, _) in seen) {
                assertTrue(
                    bytes >= previous,
                    "progress went backwards: $previous -> $bytes (samples: ${seen.map { it.first }})",
                )
                previous = bytes
            }
            assertEquals(
                size, seen.last().first,
                "the final callback must land exactly on the total, not on a partial stream offset",
            )
            assertTrue(
                seen.any { it.first in 1 until size },
                "incremental progress must be reported before the terminal callback",
            )
        } finally {
            client.close()
            server.close()
        }
    }
}
