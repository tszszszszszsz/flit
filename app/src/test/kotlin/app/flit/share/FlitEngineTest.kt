package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `relay hunt last resort needs an explicit relayFor and an allowing policy`() {
        fun device(relayFor: Boolean, policy: FlitKnown.RoutePolicy) = FlitKnown.Device(
            fingerprint = "A".repeat(64),
            alias = "peer",
            relayFor = relayFor,
            routePolicy = policy,
        )
        // No Known entry, an opted-out device, or a policy that excludes relay
        // never gets a register through the blind relay (fail closed).
        assertFalse(FlitEngine.relayHuntAllowed(null))
        assertFalse(FlitEngine.relayHuntAllowed(device(false, FlitKnown.RoutePolicy.AUTO)))
        assertFalse(FlitEngine.relayHuntAllowed(device(true, FlitKnown.RoutePolicy.WIFI_ONLY)))
        assertFalse(FlitEngine.relayHuntAllowed(device(true, FlitKnown.RoutePolicy.BLUETOOTH_ONLY)))
        // Explicit opt-in with AUTO or RELAY_ONLY is the same gate that allows
        // file sends, Circle dials and panic fan-out to use the relay.
        assertTrue(FlitEngine.relayHuntAllowed(device(true, FlitKnown.RoutePolicy.AUTO)))
        assertTrue(FlitEngine.relayHuntAllowed(device(true, FlitKnown.RoutePolicy.RELAY_ONLY)))
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

    // ==== delivery receipts: mint and confirmation seams =======================
    //
    // Mirrors of the app's receipt seams. They depend only on the frozen
    // protocol API and the engine's existing pure keyed-drop helpers, so the
    // cases stay runnable while the mint/count wiring lands in the engine and
    // the UI. Each case names the exact step it pins: the landed keyed `file`
    // event, the closed body, one signed event, and the pure confirmation fold.

    /** The closed `{dropId,sha256,keyId}` body — no other field is signed. */
    private fun receiptBody(dropId: String, sha256: String, keyId: String): String = buildJsonObject {
        put("dropId", JsonPrimitive(dropId))
        put("sha256", JsonPrimitive(sha256))
        put("keyId", JsonPrimitive(keyId))
    }.toString()

    private fun bodyObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** The drop index the engine rebuilds from its bounded, verified event log. */
    private fun keyedIndex(event: CircleEvent): Map<String, FlitEngine.DropKeyMaterial> {
        val material = FlitEngine.keyedDropMaterial(event) ?: return emptyMap()
        return mapOf(material.cipherSha to material)
    }

    /**
     * Mirror of `onUploadFinish` → `openKeyedDropIfAny`: index the landed sealed
     * bytes by their digest, open them with the receiving identity, require the
     * announced plaintext digest to match, then mint ONE signed receipt from
     * the landed keyed `file` event's id, plaintext digest and keyGen-derived
     * key id. Every refusal (no index hit, NO_KEY/no wrap, wrong identity,
     * mismatch or legacy) returns null — the engine publishes nothing.
     */
    private fun mintReceiptOnLanding(
        fileEvent: CircleEvent,
        sealed: ByteArray,
        receiver: TlsIdentity,
        index: Map<String, FlitEngine.DropKeyMaterial>,
    ): CircleEvent? {
        val material = index[sha256Hex(sealed)] ?: return null
        val opened = FlitEngine.openKeyedDrop(sealed, material, receiver)
        if (opened !is FlitEngine.KeyedDropOpen.Opened) return null
        val keyed = DropKeyCore.parseFileKeyBody(fileEvent.body) ?: return null
        return CircleCore.create(
            circleId = fileEvent.circleId,
            authorFp = receiver.fingerprint,
            identity = receiver,
            type = CircleEvent.RECEIPT,
            body = receiptBody(
                fileEvent.id,
                opened.sha256,
                DropKeyCore.keyId(fileEvent.id, keyed.keyGen),
            ),
            lamport = fileEvent.lamport + 1L,
            createdMs = fileEvent.createdMs + 1_000L,
        )
    }

    /** A signed receipt, as the engine's `post` path publishes it. */
    private fun signedReceipt(
        signer: TlsIdentity,
        circleId: String,
        dropId: String,
        sha: String,
        keyGen: Int,
        lamport: Long,
    ): CircleEvent = CircleCore.create(
        circleId, signer.fingerprint, signer, CircleEvent.RECEIPT,
        receiptBody(dropId, sha, DropKeyCore.keyId(dropId, keyGen)), lamport,
        1_800_000_100_000L + lamport,
    )

    /**
     * Mirror of the file card's confirmed fold: collect the ids of `file`
     * events whose body sha matches the delivery, then union
     * `CircleCore.receiptAuthors` over those ids with the current roster.
     */
    private fun confirmedAuthors(
        events: List<CircleEvent>,
        members: List<String>,
        delivery: FlitCircleStore.CircleDelivery,
    ): Set<String> {
        val dropIds = events.mapNotNull { event ->
            if (event.type != CircleEvent.FILE) return@mapNotNull null
            val sha = bodyObject(event.body)?.let { text(it, "sha256") } ?: return@mapNotNull null
            if (sha.equals(delivery.sha256, ignoreCase = true)) event.id else null
        }
        return dropIds.flatMap { CircleCore.receiptAuthors(events, it, delivery.sha256, members) }.toSet()
    }

    @Test
    fun `a valid keyed landing mints one receipt with the exact closed body`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(receiver.fingerprint to receiver.certificate))
        val fileEvent = keyedEvent(d, author = sender)
        assertTrue(CircleCore.verify(fileEvent), "only a verified file event feeds the drop index")
        val index = keyedIndex(fileEvent)
        assertEquals(setOf(sha256Hex(d.sealed)), index.keys, "the engine indexes the landed sealed digest")

        val receipt = assertNotNull(mintReceiptOnLanding(fileEvent, d.sealed, receiver, index))

        assertEquals(CircleEvent.RECEIPT, receipt.type)
        assertEquals(fileEvent.circleId, receipt.circleId)
        assertEquals(receiver.fingerprint, receipt.authorFp, "the lander, not the sender, acknowledges")
        assertTrue(CircleCore.verify(receipt), "the minted receipt verifies with its own cert")
        assertTrue(CircleCore.validReceiptBody(receipt.body))
        val fields = assertNotNull(bodyObject(receipt.body))
        assertEquals(CircleCore.RECEIPT_BODY_KEYS, fields.keys, "the body is exactly dropId sha256 keyId")
        assertEquals(fileEvent.id, text(fields, "dropId"))
        assertEquals(sha256Hex(d.plaintext), text(fields, "sha256"), "the plaintext digest from the file event")
        assertEquals(DropKeyCore.keyId(fileEvent.id, 1), text(fields, "keyId"))
        assertFalse(fields.keys.any { it in setOf("landedMs", "path", "status", "key", "cipherSha", "spoolPath") })
        assertFalse(receipt.body.contains(".flitdrop"), "no local spool path on the wire")
    }

    @Test
    fun `a plaintext digest mismatch mints no receipt`() {
        val receiver = TlsIdentity.generate()
        val d = dropMaterial(
            recipients = mapOf(receiver.fingerprint to receiver.certificate),
            announcedPlainSha = "0".repeat(64),
        )
        val fileEvent = keyedEvent(d, author = TlsIdentity.generate())
        val index = keyedIndex(fileEvent)
        val material = assertNotNull(index[sha256Hex(d.sealed)])
        assertTrue(circleBodyValid(CircleEvent.FILE, fileEvent.body), "the forged digest is still gate-valid")

        val refused = assertNotNull(FlitEngine.openKeyedDrop(d.sealed, material, receiver) as? FlitEngine.KeyedDropOpen.Refused)
        assertEquals("plaintext digest mismatch", refused.reason, "the gate catches it after the bytes open")
        assertNull(mintReceiptOnLanding(fileEvent, d.sealed, receiver, index), "no receipt for bytes that fail the digest")
    }

    @Test
    fun `a sealed digest mismatch mints no receipt before the key is opened`() {
        val receiver = TlsIdentity.generate()
        val d = dropMaterial(
            recipients = mapOf(receiver.fingerprint to receiver.certificate),
            announcedCipherSha = "0".repeat(64),
        )
        val fileEvent = keyedEvent(d, author = TlsIdentity.generate())
        val index = keyedIndex(fileEvent)

        assertEquals(setOf("0".repeat(64)), index.keys, "the engine indexes the announced cipherSha")
        assertNull(index[sha256Hex(d.sealed)], "the landed bytes do not hash to the announced digest")
        assertNull(mintReceiptOnLanding(fileEvent, d.sealed, receiver, index), "not hash-exact — nothing minted")
    }

    @Test
    fun `a NO_KEY landing with no wrap for the local identity mints no receipt`() {
        // NO_KEY is the terminal local outcome for a member the drop's key was
        // not wrapped to; it must never become a signed acknowledgement.
        val receiver = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(stranger.fingerprint to stranger.certificate))
        val fileEvent = keyedEvent(d, author = TlsIdentity.generate())
        val index = keyedIndex(fileEvent)
        val material = assertNotNull(index[sha256Hex(d.sealed)])
        assertEquals(setOf(stranger.fingerprint), material.wraps.keys)
        assertFalse(receiver.fingerprint in material.wraps.keys)

        val refused = assertNotNull(FlitEngine.openKeyedDrop(d.sealed, material, receiver) as? FlitEngine.KeyedDropOpen.Refused)
        assertEquals("no wrap for this device", refused.reason)
        assertNull(mintReceiptOnLanding(fileEvent, d.sealed, receiver, index))
    }

    @Test
    fun `a legacy plaintext drop mints no receipt`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val legacyBody = circleFileBody("notes.gflit", 12L, "legacy-sha")
        assertTrue(circleBodyValid(CircleEvent.FILE, legacyBody))
        val legacy = CircleCore.create(
            "c1", sender.fingerprint, sender, CircleEvent.FILE, legacyBody, 1L, 1_800_000_000_000L,
        )
        assertTrue(CircleCore.verify(legacy))
        assertNull(DropKeyCore.parseFileKeyBody(legacy.body), "legacy has no keyGen or wraps")
        assertTrue(keyedIndex(legacy).isEmpty(), "legacy is never indexed as a keyed drop")

        assertNull(
            mintReceiptOnLanding(legacy, "plain bytes".toByteArray(), receiver, keyedIndex(legacy)),
            "a plaintext drop keeps its send-report status and gets no receipt",
        )
    }

    @Test
    fun `receiptAuthors folds into the file card confirmation count`() {
        val sender = TlsIdentity.generate()
        val alice = TlsIdentity.generate()
        val bob = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val d = dropMaterial(recipients = mapOf(
            sender.fingerprint to sender.certificate,
            alice.fingerprint to alice.certificate,
            bob.fingerprint to bob.certificate,
        ))
        val fileEvent = keyedEvent(d, author = sender)
        val sha = sha256Hex(d.plaintext)
        val dropId = fileEvent.id
        val a1 = signedReceipt(alice, fileEvent.circleId, dropId, sha, 1, 2L)
        val b1 = signedReceipt(bob, fileEvent.circleId, dropId, sha, 1, 3L)
        // A later rekey changes only provenance: keyId is never a selection key.
        val a2 = signedReceipt(alice, fileEvent.circleId, dropId, sha, 9, 4L)
        val outsider = signedReceipt(stranger, fileEvent.circleId, dropId, sha, 1, 5L)
        val otherDrop = signedReceipt(alice, fileEvent.circleId, "another-drop", sha, 1, 6L)
        val forged = CircleCore.create(
            fileEvent.circleId, alice.fingerprint, attacker, CircleEvent.RECEIPT,
            receiptBody(dropId, sha, DropKeyCore.keyId(dropId, 1)), 7L, 1_800_000_100_007L,
        )
        assertFalse(CircleCore.verify(forged), "a cert that does not match authorFp is refused")

        val events = listOf(fileEvent, a1, b1, a2, outsider, otherDrop, forged)
        val members = listOf(sender.fingerprint, alice.fingerprint, bob.fingerprint)
        val delivery = FlitCircleStore.CircleDelivery(
            id = "delivery-1", name = d.keyed.name, size = d.plaintext.size.toLong(),
            sha256 = sha, spoolPath = "/local/notes.gflit.flitdrop",
            statuses = mapOf(
                alice.fingerprint to FlitCircleStore.CircleDelivery.REFUSED,
                bob.fingerprint to FlitCircleStore.CircleDelivery.PENDING,
            ),
        )
        val state = FlitCircleStore.CircleState(
            id = fileEvent.circleId, keyB64 = "circle-key", role = FlitCircleStore.Role.OWNER,
            members = members.map { FlitCircleStore.Member(it, "alias") },
            events = events, deliveries = mapOf(sha to delivery),
        )

        val confirmed = confirmedAuthors(state.events, members, delivery)

        assertEquals(setOf(alice.fingerprint, bob.fingerprint), confirmed)
        assertEquals(2, confirmed.size, "one entry per author, not per receipt")
        assertEquals(
            confirmed,
            confirmedAuthors(listOf(a2, forged, outsider, b1, otherDrop, a1, fileEvent), members, delivery),
            "arrival order must not change the confirmation count",
        )
        assertEquals(
            setOf(alice.fingerprint),
            CircleCore.receiptAuthors(listOf(a1, a2), dropId, sha, members),
            "a rekeyed keyId does not invalidate the earlier receipt",
        )
        // The count is a pure read: local sender-side statuses do not move.
        assertEquals(
            FlitCircleStore.CircleDelivery.REFUSED,
            state.deliveries.getValue(sha).statuses[alice.fingerprint],
        )
        var updated = state
        for (fp in confirmed) {
            updated = FlitCircleStore.setDeliveryStatus(updated, sha, fp, FlitCircleStore.CircleDelivery.SENT)
        }
        assertEquals(1, updated.deliveries.size, "no phantom delivery row")
        assertEquals(FlitCircleStore.CircleDelivery.SENT, updated.deliveries.getValue(sha).statuses[alice.fingerprint])
        assertEquals(FlitCircleStore.CircleDelivery.SENT, updated.deliveries.getValue(sha).statuses[bob.fingerprint])
        assertEquals(
            updated.deliveries,
            FlitCircleStore.setDeliveryStatus(updated, "unknown-sha", stranger.fingerprint, FlitCircleStore.CircleDelivery.SENT).deliveries,
            "an unknown sha creates no confirmation row",
        )
    }

    // ==== circle pad: view rebuild and exact publish bodies =====================
    //
    // The engine keeps no second copy of the pad: its `padView` StateFlow is
    // rebuilt from the signed log through PadCore.view on every store fold.
    // A put/del is a signed `pad` event posted through FlitCircleSync's existing
    // /circle/sync fan-out — no new route, header, DTO field or store field.
    // These cases drive the real publish seam with injected fakes (no socket,
    // no Android context) and the same pure fold the engine uses for the view.

    private fun padPutEvent(
        signer: TlsIdentity,
        key: String,
        text: String,
        id: String,
        lamport: Long,
        circleId: String = "c1",
    ): CircleEvent = CircleCore.create(
        circleId, signer.fingerprint, signer, CircleEvent.PAD,
        PadCore.padPutBody(key, text), lamport, 1_800_000_000_000L + lamport,
    )

    private fun padDelEvent(
        signer: TlsIdentity,
        key: String,
        id: String,
        lamport: Long,
        circleId: String = "c1",
    ): CircleEvent = CircleCore.create(
        circleId, signer.fingerprint, signer, CircleEvent.PAD,
        PadCore.padDelBody(key), lamport, 1_800_000_000_000L + lamport,
    )

    private class PadSeam {
        val upserted = mutableListOf<CircleEvent>()
        val sent = mutableListOf<CircleRequest>()
        var current: CircleState = CircleState(
            id = "c1",
            keyB64 = "key",
            role = Role.OWNER,
            members = listOf(Member("ME", "", Role.OWNER), Member("AABB", "", Role.MEMBER)),
        )
    }

    /** Real sync-worker seams with a fake carrier: nothing here opens a socket. */
    private fun installPadSeam(signer: TlsIdentity): PadSeam {
        FlitCircleSync.resetForTest()
        val seam = PadSeam()
        FlitCircleSync.stateHook = { seam.current }
        FlitCircleSync.meHook = { "ME" }
        FlitCircleSync.identityHook = { signer }
        FlitCircleSync.clockHook = { 1_000L }
        FlitCircleSync.logHook = { }
        FlitCircleSync.upsertHook = { events ->
            seam.upserted += events
            seam.current = seam.current.copy(events = seam.current.events + events)
        }
        FlitCircleSync.dialHook = { fp ->
            FlitCircleSync.Route(
                fp,
                sync = { request -> seam.sent += request; CircleResponse(true) },
                join = { CircleResponse(false, reason = "unused") },
            )
        }
        return seam
    }

    @Test
    fun `pad view rebuilds from signed events with dedup and conflict resolution`() {
        val alice = TlsIdentity.generate()
        val bob = TlsIdentity.generate()
        val events = listOf(
            padPutEvent(alice, "alpha", "first", "e1", 5L),
            padPutEvent(bob, "alpha", "stale", "e2", 1L), // older put, inert
            padDelEvent(bob, "alpha", "e3", 2L),          // older del, inert
            padPutEvent(alice, "beta", "gone", "e4", 3L),
            padDelEvent(bob, "beta", "e5", 6L),           // greater del tombstones beta
            padPutEvent(alice, "gamma", "three", "e6", 7L),
            padPutEvent(alice, "gamma", "three", "e6", 7L), // replayed id folds once
            padPutEvent(bob, "delta", "foreign", "e7", 9L, circleId = "other"),
            CircleCore.create(
                "c1", alice.fingerprint, alice, CircleEvent.MESSAGE,
                PadCore.padPutBody("epsilon", "wrong verb"), 8L, 1_800_000_008_000L,
            ),
            CircleCore.create(
                "c1", alice.fingerprint, alice, CircleEvent.PAD,
                """{"op":"put","key":"zeta"}""", 9L, 1_800_000_009_000L,
            ),
        )

        val view = PadCore.view(events, "c1")

        assertEquals(
            listOf("alpha" to "first", "gamma" to "three"),
            view.lines.map { it.key to it.text },
            "live keys are lexicographic; tombstoned, foreign, malformed and stale keys are absent",
        )
        assertTrue(view.winners.getValue("beta").deleted, "the greater del is kept as a tombstone")
        assertEquals(
            view,
            PadCore.view(events.shuffled(), "c1"),
            "a rebuild from the same log is byte-identical regardless of arrival order",
        )
        assertEquals(
            view,
            PadCore.view(events + events.take(3), "c1"),
            "re-folding events already present leaves the view unchanged",
        )
    }

    @Test
    fun `engine padView flow rebuilds from a folded store snapshot`() {
        val alice = TlsIdentity.generate()
        val snapshot = CircleState(
            id = "c1",
            keyB64 = "key",
            role = Role.OWNER,
            members = emptyList(),
            events = listOf(
                padPutEvent(alice, "alpha", "one", "e1", 2L),
                padDelEvent(alice, "beta", "e2", 3L),
                padPutEvent(alice, "gamma", "three", "e3", 4L),
            ),
        )
        // foldPad is the engine's private projection from the signed log into
        // the StateFlow; driving it here avoids an Android context while still
        // asserting the flow itself, not just the pure fold it delegates to.
        val fold = assertNotNull(
            runCatching { FlitEngine.javaClass.getDeclaredMethod("foldPad", CircleState::class.java) }.getOrNull(),
            "the engine must project pad state through foldPad",
        )
        fold.isAccessible = true
        fold.invoke(FlitEngine, snapshot)

        assertEquals(
            listOf("alpha" to "one", "gamma" to "three"),
            FlitEngine.padView.value.lines.map { it.key to it.text },
            "the engine flow is rebuilt from the store snapshot, never a second copy",
        )

        fold.invoke(FlitEngine, snapshot.copy(events = emptyList()))
        assertTrue(FlitEngine.padView.value.lines.isEmpty(), "a store with no pad events yields an empty flow")
    }

    @Test
    fun `postPadPut signs and fans out the exact PadCore put body`() = runBlocking {
        val signer = TlsIdentity.generate()
        val seam = installPadSeam(signer)
        try {
            FlitCircleSync.postPadPut("topic", "hello pad")

            val event = assertNotNull(seam.upserted.singleOrNull(), "one signed event is committed")
            assertEquals(CircleEvent.PAD, event.type)
            assertEquals("c1", event.circleId)
            assertTrue(event.authorFp.equals(signer.fingerprint, ignoreCase = true))
            assertTrue(CircleCore.verify(event), "the pad event verifies with its own cert")
            assertEquals(PadCore.padPutBody("topic", "hello pad"), event.body, "body is the pure builder's bytes")
            assertTrue(PadCore.validPadBody(event.body))
            val fields = assertNotNull(runCatching { Json.parseToJsonElement(event.body).jsonObject }.getOrNull())
            assertEquals(PadCore.PAD_PUT_KEYS, fields.keys, "the put body has exactly three keys")
            assertEquals("put", fields.getValue("op").jsonPrimitive.content)
            assertEquals("topic", fields.getValue("key").jsonPrimitive.content)
            assertEquals("hello pad", fields.getValue("text").jsonPrimitive.content)

            val request = assertNotNull(seam.sent.singleOrNull(), "the event goes out on the existing sync route")
            assertEquals(CircleRequest.ACTION_SYNC, request.action)
            assertEquals(listOf(event), request.events, "peers are offered the newly emitted pad event")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `postPadDel signs and fans out the exact PadCore del body`() = runBlocking {
        val signer = TlsIdentity.generate()
        val seam = installPadSeam(signer)
        try {
            FlitCircleSync.postPadDel("topic")

            val event = assertNotNull(seam.upserted.singleOrNull(), "one signed event is committed")
            assertEquals(CircleEvent.PAD, event.type)
            assertEquals("c1", event.circleId)
            assertTrue(CircleCore.verify(event))
            assertEquals(PadCore.padDelBody("topic"), event.body, "body is the pure builder's bytes")
            val fields = assertNotNull(runCatching { Json.parseToJsonElement(event.body).jsonObject }.getOrNull())
            assertEquals(PadCore.PAD_DEL_KEYS, fields.keys, "the del body has exactly two keys")
            assertEquals("del", fields.getValue("op").jsonPrimitive.content)
            assertEquals("topic", fields.getValue("key").jsonPrimitive.content)
            assertFalse("text" in fields, "a del carries no text")

            val request = assertNotNull(seam.sent.singleOrNull())
            assertEquals(CircleRequest.ACTION_SYNC, request.action)
            assertEquals(listOf(event), request.events)
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `pad publish refuses blank and oversized fields before signing or fanning out`() = runBlocking {
        val signer = TlsIdentity.generate()
        val seam = installPadSeam(signer)
        try {
            FlitCircleSync.postPadPut("  ", "text")
            FlitCircleSync.postPadPut("key", "  ")
            FlitCircleSync.postPadPut("x".repeat(PadCore.MAX_KEY_CHARS + 1), "text")
            FlitCircleSync.postPadPut("key", "x".repeat(PadCore.MAX_TEXT_CHARS + 1))
            FlitCircleSync.postPadDel("  ")
            FlitCircleSync.postPadDel("x".repeat(PadCore.MAX_KEY_CHARS + 1))

            assertTrue(seam.upserted.isEmpty(), "a refused body signs no event")
            assertTrue(seam.sent.isEmpty(), "a refused body never reaches a peer")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `published pad bodies rebuild into the exact live view`() = runBlocking {
        val signer = TlsIdentity.generate()
        val seam = installPadSeam(signer)
        try {
            FlitCircleSync.postPadPut("alpha", "one")
            FlitCircleSync.postPadDel("beta")
            FlitCircleSync.postPadPut("beta", "later")
            FlitCircleSync.postPadPut("alpha", "final")

            assertEquals(4, seam.upserted.size)
            assertTrue(seam.upserted.all { it.type == CircleEvent.PAD && CircleCore.verify(it) })
            assertEquals(
                listOf("alpha" to "final", "beta" to "later"),
                PadCore.view(seam.upserted, seam.current.id).lines.map { it.key to it.text },
                "the view folds the published log: a put after a del wins by order",
            )
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }
}
