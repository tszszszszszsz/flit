package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine truth seams that must hold without a device: the persisted-history
 * payload roundtrip (pure, no context), the roster's self-ghost / manual-stub
 * display filter, and the per-drop keyed file gate (body fields, wrap caps,
 * unwrap/open/digest refusals).
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
    fun `direct radio rows label their address without a phantom port`() {
        // Wi-Fi rows keep ip:port; a direct-radio row (no address-bearing
        // socket, port 0) speaks only its radio address — a port without a
        // network would be a lie, not a label.
        val wifi = peerEntry("WIFI_FP", "host", ip = "192.168.100.9")
        val direct = FlitEngine.PeerEntry(
            ip = "AA:BB:CC:DD:EE:FF",
            info = PeerInfo(alias = "", version = "2.2", fingerprint = "DIRECT_FP", port = 0),
            lastSeenMs = 0L,
        )
        assertEquals("192.168.100.9:53317", wifi.second.address)
        assertEquals("AA:BB:CC:DD:EE:FF", direct.address)
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

    // ==== per-drop keyed file drops =============================================
    //
    // The engine builds one signed `file` body per drop and, on the receive
    // side, gates a landed sealed file through unwrap → open → digest check
    // before any plaintext reaches the received-file ledger. These cases drive
    // that gate with real mint/wrap/seal material and the app's own body gate
    // (circleBodyValid), so no Android context, socket or clock is involved.
    // Every refusal leaves no plaintext — the same outcome as a drop-refused
    // engine log line with no ledger entry.

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun flip(bytes: ByteArray, index: Int): ByteArray = bytes.clone().also {
        it[index] = (it[index].toInt() xor 0x01).toByte()
    }

    private data class DropMaterial(
        val keyed: DropKeyCore.KeyedFile,
        val sealed: ByteArray,
        val plaintext: ByteArray,
    )

    /** One real mint/wrap/seal drop; [announcedCipherSha] can forge a bad digest. */
    private fun dropMaterial(
        name: String = "notes.gflit",
        plaintext: ByteArray = "sealed drop bytes".toByteArray(Charsets.UTF_8),
        recipients: Map<String, X509Certificate> = emptyMap(),
        announcedPlainSha: String = sha256Hex(plaintext),
        announcedCipherSha: String? = null,
        key: DropKeyCore.Key = DropKeyCore.mint(),
    ): DropMaterial {
        val sealed = DropKeyCore.seal(key, plaintext)
        val keyed = DropKeyCore.KeyedFile(
            name = name,
            size = plaintext.size.toLong(),
            sha256 = announcedPlainSha,
            keyGen = 1,
            cipherSha = announcedCipherSha ?: sha256Hex(sealed),
            iv = Base64.getEncoder().encodeToString(key.iv),
            wraps = DropKeyCore.wrap(key.bytes, recipients),
        )
        return DropMaterial(keyed, sealed, plaintext)
    }

    private fun bodyOf(d: DropMaterial): String = DropKeyCore.keyedFileBody(d.keyed)

    /** A signed `file` event, the shape the engine indexes at boot and on sync. */
    private fun keyedEvent(d: DropMaterial, author: TlsIdentity = TlsIdentity.generate()): CircleEvent =
        CircleCore.create(
            "c1", author.fingerprint, author, CircleEvent.FILE, bodyOf(d), 1L, 1_800_000_000_000L,
        )

    /**
     * The receive-side gate: check the sealed digest announced in the signed
     * body, unwrap with the local identity, open the GCM bytes, then check the
     * announced plaintext digest. Null is a refusal — no plaintext, no ledger.
     */
    private fun landDrop(d: DropMaterial, identity: TlsIdentity): ByteArray? {
        if (sha256Hex(d.sealed) != d.keyed.cipherSha) return null
        val keyBytes = DropKeyCore.unwrap(d.keyed.wraps, identity) ?: return null
        val plain = DropKeyCore.open(keyBytes, d.sealed) ?: return null
        if (sha256Hex(plain) != d.keyed.sha256) return null
        return plain
    }

    /** The same gate driven by the engine's own parsed [FlitEngine.DropKeyMaterial]. */
    private fun landDrop(m: FlitEngine.DropKeyMaterial, sealed: ByteArray, identity: TlsIdentity): ByteArray? {
        if (sha256Hex(sealed) != m.cipherSha) return null
        val keyBytes = DropKeyCore.unwrap(m.wraps, identity) ?: return null
        val plain = DropKeyCore.open(keyBytes, sealed) ?: return null
        if (sha256Hex(plain) != m.sha256) return null
        return plain
    }

    @Test
    fun `keyed drop refuses a roster with no usable recipient certificate`() {
        val d = dropMaterial(recipients = emptyMap())
        assertTrue(d.keyed.wraps.isEmpty(), "no cert means no wrap, never a group-key fallback")
        val body = bodyOf(d)
        assertFalse(circleBodyValid(CircleEvent.FILE, body), "the app gate must refuse a zero-wrap keyed body")
        assertFalse(CircleCore.validFileBody(body))
        assertNull(DropKeyCore.parseFileKeyBody(body))
        // Refusing a keyed drop must not disturb the legacy three-key path.
        assertTrue(circleBodyValid(CircleEvent.FILE, circleFileBody("notes.gflit", 12L, "legacy-sha")))
    }

    @Test
    fun `keyed drop refuses a roster above the wrap cap without trimming it`() {
        val cert = TlsIdentity.generate().certificate
        val atCap = (0 until DropKeyCore.MAX_WRAPS).associate {
            "FP" + it.toString(16).uppercase().padStart(4, '0') to cert
        }
        val d = dropMaterial(recipients = atCap)
        assertEquals(DropKeyCore.MAX_WRAPS, d.keyed.wraps.size)
        assertTrue(circleBodyValid(CircleEvent.FILE, bodyOf(d)), "a full-cap roster stays a valid drop")

        val overCap = LinkedHashMap(atCap)
        overCap["OVERCAP"] = cert
        assertEquals(DropKeyCore.MAX_WRAPS + 1, overCap.size)
        assertTrue(runCatching { DropKeyCore.wrap(DropKeyCore.mint().bytes, overCap) }.isFailure)

        // A hand-built over-cap body is gate-invalid too: no silent trimming.
        val overCapBody = DropKeyCore.keyedFileBody(
            d.keyed.copy(wraps = d.keyed.wraps + ("OVERCAP" to d.keyed.wraps.values.first())),
        )
        assertFalse(circleBodyValid(CircleEvent.FILE, overCapBody), "the roster cap is never trimmed")
        assertNull(DropKeyCore.parseFileKeyBody(overCapBody))
    }

    @Test
    fun `keyed drop body carries the exact announced fields and binds the sealed bytes`() {
        val recipient = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(recipient.fingerprint to recipient.certificate))
        val body = bodyOf(d)
        val parsed = assertNotNull(DropKeyCore.parseFileKeyBody(body))

        assertEquals(CircleCore.KEYED_FILE_BODY_KEYS, Json.parseToJsonElement(body).jsonObject.keys)
        assertTrue(circleBodyValid(CircleEvent.FILE, body), "the engine-built body must pass the app gate")
        assertEquals(d.keyed.name, parsed.name)
        assertEquals(d.plaintext.size.toLong(), parsed.size)
        assertEquals(sha256Hex(d.plaintext), parsed.sha256, "sha256 is the plaintext digest")
        assertEquals(sha256Hex(d.sealed), parsed.cipherSha, "cipherSha is the sealed-byte digest")
        assertEquals(d.keyed.iv, parsed.iv)
        assertEquals(setOf(recipient.fingerprint), parsed.wraps.keys, "wrap keys are uppercase fingerprints")
        val opened = DropKeyCore.open(DropKeyCore.unwrap(parsed.wraps, recipient)!!, d.sealed)
        assertContentEquals(d.plaintext, opened)

        // The signed body binds the key material: an edited cipherSha fails the
        // author's signature even though the edited body is still gate-valid.
        val author = TlsIdentity.generate()
        val event = CircleCore.create(
            "c1", author.fingerprint, author, CircleEvent.FILE, body, 1L, 1_800_000_000_000L,
        )
        assertTrue(CircleCore.verify(event))
        val edited = JsonObject(
            Json.parseToJsonElement(body).jsonObject.toMap() + ("cipherSha" to JsonPrimitive("f".repeat(64))),
        ).toString()
        assertTrue(CircleCore.validFileBody(edited))
        assertFalse(CircleCore.verify(event.copy(body = edited)), "cipherSha is inside the signed bytes")
    }

    @Test
    fun `keyedDropMaterial maps a signed keyed event to the indexed drop material`() {
        val me = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(me.fingerprint to me.certificate))
        val event = keyedEvent(d)
        assertTrue(CircleCore.verify(event), "only verified events feed the engine's drop index")

        val material = assertNotNull(FlitEngine.keyedDropMaterial(event))
        assertEquals(d.keyed.name, material.name)
        assertEquals(sha256Hex(d.plaintext), material.sha256)
        assertEquals(sha256Hex(d.sealed), material.cipherSha)
        assertEquals(d.keyed.wraps, material.wraps)
        // The index key is the sealed digest; the gate driven by engine material
        // still lands the exact announced plaintext.
        assertContentEquals(d.plaintext, landDrop(material, d.sealed, me))
    }

    @Test
    fun `keyedDropMaterial normalises digests and ignores legacy and non-file events`() {
        val me = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(me.fingerprint to me.certificate))
        // Uppercase announced digests are gate-valid but must be indexed lowercase.
        val upper = d.copy(
            keyed = d.keyed.copy(
                sha256 = d.keyed.sha256.uppercase(),
                cipherSha = d.keyed.cipherSha.uppercase(),
            ),
        )
        val material = assertNotNull(FlitEngine.keyedDropMaterial(keyedEvent(upper)))
        assertEquals(d.keyed.sha256, material.sha256, "plain sha indexed lowercase")
        assertEquals(d.keyed.cipherSha, material.cipherSha, "cipher sha indexed lowercase")
        assertContentEquals(d.plaintext, landDrop(material, upper.sealed, me))

        val legacy = CircleCore.create(
            "c1", me.fingerprint, me, CircleEvent.FILE,
            circleFileBody("legacy.gflit", 12L, "legacy-sha"), 1L, 1_800_000_000_000L,
        )
        assertNull(FlitEngine.keyedDropMaterial(legacy), "legacy has no key material to index")
        val message = CircleCore.create(
            "c1", me.fingerprint, me, CircleEvent.MESSAGE,
            """{"text":"hi"}""", 1L, 1_800_000_000_000L,
        )
        assertNull(FlitEngine.keyedDropMaterial(message), "only file events carry drops")
    }

    @Test
    fun `receive gate opens a valid keyed drop to the announced plaintext`() {
        val me = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(me.fingerprint to me.certificate))
        assertContentEquals(d.plaintext, landDrop(d, me))
        assertEquals(sha256Hex(d.sealed), d.keyed.cipherSha)
    }

    @Test
    fun `receive gate refuses a drop with no wrap for the local identity`() {
        val me = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(stranger.fingerprint to stranger.certificate))
        assertNull(DropKeyCore.unwrap(d.keyed.wraps, me), "a stranger's private key cannot open the wrap")
        assertNull(landDrop(d, me), "no wrap means no plaintext and no ledger entry")
    }

    @Test
    fun `receive gate refuses a tag tampered sealed drop`() {
        val me = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(me.fingerprint to me.certificate))
        val tampered = d.copy(sealed = flip(d.sealed, d.sealed.lastIndex)) // last TAG_BYTES are the GCM tag
        val keyBytes = DropKeyCore.unwrap(d.keyed.wraps, me)
        assertNotNull(keyBytes)
        assertNull(DropKeyCore.open(keyBytes, tampered.sealed), "a flipped tag fails authentication")
        assertNull(landDrop(tampered, me), "a tampered drop leaves no plaintext")
        assertContentEquals(d.plaintext, landDrop(d, me), "the intact drop still lands")
    }

    @Test
    fun `receive gate refuses a plaintext digest mismatch`() {
        val me = TlsIdentity.generate()
        val d = dropMaterial(
            recipients = mapOf(me.fingerprint to me.certificate),
            announcedPlainSha = "0".repeat(64),
        )
        val keyBytes = DropKeyCore.unwrap(d.keyed.wraps, me)
        assertNotNull(keyBytes)
        assertNotNull(DropKeyCore.open(keyBytes, d.sealed), "the bytes open, so the gate must catch the digest")
        assertTrue(circleBodyValid(CircleEvent.FILE, bodyOf(d)), "the forged digest is still gate-valid")
        assertNull(landDrop(d, me), "a wrong plaintext sha refuses the drop")
    }

    @Test
    fun `receive gate refuses a sealed digest mismatch before unwrapping`() {
        val me = TlsIdentity.generate()
        val d = dropMaterial(
            recipients = mapOf(me.fingerprint to me.certificate),
            announcedCipherSha = "0".repeat(64),
        )
        assertNull(landDrop(d, me), "the landed bytes must hash to the announced cipherSha")
    }

    @Test
    fun `a legacy file body stays on the plaintext receive path`() {
        val legacy = circleFileBody("notes.gflit", 12L, "legacy-sha")
        assertTrue(circleBodyValid(CircleEvent.FILE, legacy))
        assertNull(DropKeyCore.parseFileKeyBody(legacy), "legacy has no key material to unwrap")
        // The legacy gate still refuses path/token fields; the keyed form adds
        // no new field that could smuggle the local spool onto the wire.
        assertFalse(
            circleBodyValid(CircleEvent.FILE, """{"name":"notes.gflit","size":12,"sha256":"sha","wraps":{}}"""),
        )
    }
}
