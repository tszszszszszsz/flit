package app.flit.share

import app.flit.share.protocol.model.PeerInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine truth seams that must hold without a device: the persisted-history
 * payload roundtrip (pure, no context) and the roster's self-ghost /
 * manual-stub display filter.
 */
class FlitEngineTest {

    private fun peerEntry(fp: String, alias: String, ip: String = "192.168.100.9", key: String = fp) =
        key to FlitEngine.PeerEntry(
            ip = ip,
            info = PeerInfo(
                alias = alias,
                version = "2.2",
                deviceModel = "test",
                deviceType = "device",
                fingerprint = fp,
                port = 53317,
                protocol = "https",
            ),
            lastSeenMs = 0L,
        )

    @Test
    fun `received history payload roundtrips`() {
        val list = listOf(
            FlitEngine.ReceivedFile("a.bin", 123L, 456L, File("/data/app/received/a.bin"), null),
            FlitEngine.ReceivedFile("n.png", 2L, 3L, null, "content://x/y"),
        )
        assertEquals(list, FlitEngine.decodeReceived(FlitEngine.encodeReceived(list)))
    }

    @Test
    fun `empty and corrupt history payloads decode to nothing`() {
        assertTrue(FlitEngine.decodeReceived("").isEmpty())
        assertTrue(FlitEngine.decodeReceived("\"not\":{\"an array\":\"just strings\"}").isEmpty())
    }

    @Test
    fun `deduped peers hides self ghosts`() {
        val self = peerEntry("SELF_FP", "ghost-self", ip = "127.0.0.1", key = "ghost")
        val other = peerEntry("OTHER_FP", "Flit (szszszs)")
        val out = FlitEngine.dedupedPeers(mapOf(self, other), selfFp = "SELF_FP")
        assertEquals(setOf("OTHER_FP"), out.keys)
    }

    @Test
    fun `deduped peers keeps self when identity not yet loaded`() {
        // Boot race the filter can't solve (null identity → no-op): rows must
        // never crash or hide legit peers; ingest still owns the real fix.
        val ghost = peerEntry("SELF_FP", "ghost-self", ip = "127.0.0.1", key = "ghost")
        val other = peerEntry("OTHER_FP", "Flit (szszszs)")
        val out = FlitEngine.dedupedPeers(mapOf(ghost, other), selfFp = null)
        assertEquals(setOf("ghost", "OTHER_FP"), out.keys)
    }

    @Test
    fun `deduped peers drops loopback manual junk`() {
        // The pre-fix self-ghost bug recorded loopback rows as manual peers;
        // those ships in wild prefs and must hide at the UI seam.
        val junk = FlitEngine.PeerEntry(
            ip = "127.0.0.1",
            info = PeerInfo(
                alias = "",
                version = "2.2",
                fingerprint = "manual@127.0.0.1:47317",
            ),
            lastSeenMs = 0L,
        )
        val other = peerEntry("OTHER_FP", "Flit (szszszs)")
        val out = FlitEngine.dedupedPeers(mapOf("manual@junk" to junk, other), selfFp = "SELF_FP")
        assertEquals(setOf("OTHER_FP"), out.keys)
    }

    @Test
    fun `deduped peers hides manual stubs once the real device covers them`() {
        val real = peerEntry("REAL_FP", "host", ip = "192.168.100.12", key = "REAL_FP")
        val stubCovered = FlitEngine.PeerEntry(
            ip = "192.168.100.12",
            info = real.second.info.copy(alias = "manual-onion"),
            lastSeenMs = 0L,
        )
        val stubAlone = FlitEngine.PeerEntry(
            ip = "10.0.0.1",
            info = real.second.info.copy(alias = "", fingerprint = "STUB_FP", port = 53317),
            lastSeenMs = 0L,
        )
        val out = FlitEngine.dedupedPeers(
            mapOf(
                "REAL_FP" to real.second,
                "manual@deadbeef" to stubCovered,
                "manual@onion" to stubAlone,
            ),
        )
        assertEquals(setOf("REAL_FP", "manual@onion"), out.keys)
    }
}
