package app.flit.share.harness

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure seams of the CLI's per-drop key path. No socket, no route and no device
 * claim is touched: every case drives the same production [DropKeyCore],
 * [CircleCore] and [TlsIdentity] functions the `circle file` command and the
 * receiving `ConsoleDelegate.onUploadFinish` call.
 *
 * The two-node wire walk (real `sendFiles`, real landing and the printed
 * `drop-open` line) belongs to `CircleHarnessTest`; this class pins the body
 * shape, the wrap roster, the refusal gates and the unwrap/open/digest chain in
 * isolation so a regression names the exact step that broke.
 */
class DropKeyCliTest {

    // ---- local helpers (the same digest and Base64 rules the CLI uses) ------

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * The CLI's keyed announcement builder: `Harness.circleFileAnnouncement`
     * keyed overload. Kept in one place so a signature change is a one-line fix.
     */
    private fun cliKeyedAnnouncement(v: DropKeyCore.KeyedFile): String = circleFileAnnouncement(
        name = v.name,
        size = v.size,
        sha256 = v.sha256,
        keyGen = v.keyGen,
        cipherSha = v.cipherSha,
        iv = v.iv,
        wraps = v.wraps,
    )

    /** Replace top-level body fields while leaving the signed envelope alone. */
    private fun patchBody(body: String, vararg patches: Pair<String, JsonElement>): String {
        val fields = Json.parseToJsonElement(body).jsonObject.toMap().toMutableMap()
        patches.forEach { (key, value) -> fields[key] = value }
        return buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
    }

    /** Build a current-member certificate map exactly as `circle file` gathers it. */
    private fun certsOf(vararg identities: TlsIdentity): Map<String, java.security.cert.X509Certificate> =
        identities.associate { it.fingerprint to it.certificate }

    /** One complete keyed drop fixture: key, sealed bytes and signed body. */
    private fun makeDrop(
        signer: TlsIdentity,
        name: String,
        plaintext: ByteArray,
        recipients: Map<String, java.security.cert.X509Certificate>,
    ): Triple<DropKeyCore.Key, ByteArray, DropKeyCore.KeyedFile> {
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, plaintext)
        val keyed = DropKeyCore.KeyedFile(
            name = name,
            size = plaintext.size.toLong(),
            sha256 = sha256Hex(plaintext),
            keyGen = 1,
            cipherSha = sha256Hex(sealed),
            iv = base64(key.iv),
            wraps = DropKeyCore.wrap(key.bytes, recipients),
        )
        return Triple(key, sealed, keyed)
    }

    // ---- the two announcement builders stay distinct ------------------------

    @Test
    fun `circle post --file keeps the legacy three-key plaintext announcement`() {
        val payload = ByteArray(64).also { it[0] = 7 }
        val sha = sha256Hex(payload)
        val body = circleFileAnnouncement("plain.bin", payload.size.toLong(), sha)

        assertTrue(CircleCore.validFileBody(body))
        val fields = Json.parseToJsonElement(body).jsonObject
        assertEquals(CircleCore.FILE_BODY_KEYS, fields.keys)
        assertEquals(sha, (fields.getValue("sha256") as JsonPrimitive).content)
        assertFalse(fields.containsKey("wraps"), "a legacy announcement carries no key material")
        assertFalse(fields.containsKey("cipherSha"))
        assertFalse(fields.containsKey("keyGen"))
    }

    @Test
    fun `circle file keyed announcement mints the exact keyed body shape`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val receiverCerts = certsOf(receiver)
        val (_, _, keyed) = makeDrop(sender, "sealed.bin", ByteArray(128) { (it * 3).toByte() }, receiverCerts)

        val body = cliKeyedAnnouncement(keyed)

        assertEquals(DropKeyCore.keyedFileBody(keyed), body, "CLI builder must emit the protocol body verbatim")
        assertTrue(CircleCore.validFileBody(body))
        val fields = Json.parseToJsonElement(body).jsonObject
        assertEquals(CircleCore.KEYED_FILE_BODY_KEYS, fields.keys)
        assertEquals(keyed.sha256, (fields.getValue("sha256") as JsonPrimitive).content)
        assertEquals(keyed.cipherSha, (fields.getValue("cipherSha") as JsonPrimitive).content)
        assertEquals(keyed.iv, (fields.getValue("iv") as JsonPrimitive).content)
        assertEquals(1, (fields.getValue("keyGen") as JsonPrimitive).content.toInt())

        val parsed = DropKeyCore.parseFileKeyBody(body)
        assertNotNull(parsed)
        assertEquals(keyed, parsed, "the keyed body round-trips through the gate")
    }

    // ---- wraps to the current members ---------------------------------------

    @Test
    fun `keyed drop wraps once per current member and omits a departed one`() {
        val alice = TlsIdentity.generate()
        val bob = TlsIdentity.generate()
        val departed = TlsIdentity.generate()
        val key = DropKeyCore.mint()

        val before = DropKeyCore.wrap(key.bytes, certsOf(alice, bob, departed))
        assertEquals(setOf(alice.fingerprint, bob.fingerprint, departed.fingerprint), before.keys)
        assertTrue(before.keys.all { it == it.uppercase() }, "wraps are keyed by uppercase fingerprints")

        // A member who left before the drop gets no wrap and cannot open it.
        val current = DropKeyCore.wrap(key.bytes, certsOf(alice, bob))
        assertEquals(setOf(alice.fingerprint, bob.fingerprint), current.keys)
        assertFalse(current.containsKey(departed.fingerprint))
        assertNull(DropKeyCore.unwrap(current, departed), "no wrap means no plaintext")

        assertContentEquals(key.bytes, DropKeyCore.unwrap(current, alice)!!)
        assertContentEquals(key.bytes, DropKeyCore.unwrap(current, bob)!!)
    }

    @Test
    fun `a member with no pinned certificate gets no wrap and no keyed body`() {
        val sender = TlsIdentity.generate()
        val pinned = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val (_, _, keyed) = makeDrop(sender, "partial.bin", ByteArray(32) { it.toByte() }, certsOf(pinned, stranger) - stranger.fingerprint)

        assertEquals(setOf(pinned.fingerprint), keyed.wraps.keys)
        assertNull(DropKeyCore.unwrap(keyed.wraps, stranger), "a missing wrap cannot be opened")

        // An empty wrap map never passes the gate: no signed event is minted.
        val body = DropKeyCore.keyedFileBody(keyed.copy(wraps = emptyMap()))
        assertFalse(CircleCore.validFileBody(body))
        assertNull(DropKeyCore.parseFileKeyBody(body))
    }

    @Test
    fun `a roster above MAX_WRAPS is refused instead of being trimmed`() {
        val key = DropKeyCore.mint()
        val roster = (0..DropKeyCore.MAX_WRAPS).map { TlsIdentity.generate() }
        assertEquals(DropKeyCore.MAX_WRAPS + 1, roster.size)

        assertFailsWith<IllegalArgumentException> {
            DropKeyCore.wrap(key.bytes, certsOf(*roster.toTypedArray()))
        }

        // The CLI gate refuses before it builds or posts a body: no wrap set,
        // so no keyed `file` event can pass the body gate.
        val body = DropKeyCore.keyedFileBody(
            DropKeyCore.KeyedFile("cap.bin", 1L, "a".repeat(64), 1, "b".repeat(64), base64(key.iv), emptyMap()),
        )
        assertFalse(CircleCore.validFileBody(body))
    }

    // ---- a fresh key and IV for identical bytes -----------------------------

    @Test
    fun `two drops of identical bytes mint different keys IVs and cipherSha`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val plaintext = ByteArray(4096).also { java.util.Random(19).nextBytes(it) }
        val first = makeDrop(sender, "same.bin", plaintext, certsOf(receiver))
        val second = makeDrop(sender, "same.bin", plaintext, certsOf(receiver))

        assertFalse(first.first.bytes.contentEquals(second.first.bytes), "each drop has a fresh key")
        assertFalse(first.first.iv.contentEquals(second.first.iv), "each drop has a fresh IV")
        assertFalse(first.second.contentEquals(second.second), "the sealed bytes differ")
        assertTrue(first.third.cipherSha != second.third.cipherSha, "cipherSha is over the sealed bytes")
        assertEquals(first.third.sha256, second.third.sha256, "the plaintext digest is the same")
        assertContentEquals(plaintext, DropKeyCore.open(second.first.bytes, second.second)!!)
    }

    // ---- receive: unwrap, open, then check the announced plaintext digest ---

    @Test
    fun `receiver unwraps with its own identity and lands plaintext hash-exact`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val plaintext = ByteArray(8192).also { java.util.Random(23).nextBytes(it) }
        val (key, sealed, keyed) = makeDrop(sender, "landed.bin", plaintext, certsOf(receiver))

        // The signed body is what the receiver reads from its circle.state log.
        val body = cliKeyedAnnouncement(keyed)
        val event = CircleCore.create(
            "cli-drop", sender.fingerprint, sender, CircleEvent.FILE, body, lamport = 1L,
        )
        assertTrue(CircleCore.verify(event))
        val parsed = DropKeyCore.parseFileKeyBody(event.body)
        assertNotNull(parsed)

        // The landed ciphertext is gated on cipherSha before it is opened.
        assertEquals(parsed.cipherSha, sha256Hex(sealed), "the wire bytes hash to the announced cipherSha")
        val keyBytes = DropKeyCore.unwrap(parsed.wraps, receiver)
        assertNotNull(keyBytes)
        assertContentEquals(key.bytes, keyBytes)
        val opened = DropKeyCore.open(keyBytes, sealed)
        assertNotNull(opened)
        assertContentEquals(plaintext, opened)
        assertEquals(parsed.sha256, sha256Hex(opened), "the landed plaintext hashes to the announced sha256")
    }

    @Test
    fun `a wrong identity or an absent fingerprint unwraps to null and yields no plaintext`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val (_, sealed, keyed) = makeDrop(sender, "private.bin", ByteArray(64) { (it + 1).toByte() }, certsOf(receiver))

        assertNull(DropKeyCore.unwrap(keyed.wraps, stranger), "a wrong private key cannot unwrap")
        assertNull(DropKeyCore.unwrap(emptyMap(), receiver), "no wrap entry cannot unwrap")
        assertNull(DropKeyCore.unwrap(keyed.wraps - receiver.fingerprint, receiver))

        // No key means open is never attempted and no plaintext is released.
        val keyBytes = DropKeyCore.unwrap(keyed.wraps, stranger)
        assertNull(keyBytes?.let { DropKeyCore.open(it, sealed) })
    }

    @Test
    fun `a tampered tag or a wrong plaintext digest refuses the drop`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val plaintext = ByteArray(256).also { java.util.Random(29).nextBytes(it) }
        val (key, sealed, keyed) = makeDrop(sender, "tamper.bin", plaintext, certsOf(receiver))
        val keyBytes = DropKeyCore.unwrap(keyed.wraps, receiver)
        assertNotNull(keyBytes)

        val flipped = sealed.copyOf()
        flipped[flipped.lastIndex] = (flipped[flipped.lastIndex].toInt() xor 1).toByte()
        assertNull(DropKeyCore.open(keyBytes, flipped), "a flipped GCM tag releases no plaintext")

        val opened = DropKeyCore.open(keyBytes, sealed)
        assertNotNull(opened)
        // The receiver's gate: opened bytes must hash to the announced sha256.
        assertEquals(keyed.sha256, sha256Hex(opened))
        // A changed announcement can never match those same opened bytes.
        val wrongSha = if (keyed.sha256.first() == '0') "1" + keyed.sha256.drop(1)
        else "0" + keyed.sha256.drop(1)
        assertFalse(wrongSha == sha256Hex(opened), "a changed announced digest refuses the drop")
    }

    @Test
    fun `changing cipherSha iv or a wrap breaks the signed keyed event before unwrap`() {
        val sender = TlsIdentity.generate()
        val receiver = TlsIdentity.generate()
        val (_, _, keyed) = makeDrop(sender, "signed.bin", ByteArray(96) { it.toByte() }, certsOf(receiver))
        val body = cliKeyedAnnouncement(keyed)
        val event = CircleCore.create("cli-sign", sender.fingerprint, sender, CircleEvent.FILE, body, lamport = 1L)
        assertTrue(CircleCore.verify(event), "the sender's own keyed event verifies")

        val wrongCipher = patchBody(body, "cipherSha" to JsonPrimitive("f".repeat(64)))
        assertFalse(CircleCore.verify(event.copy(body = wrongCipher)))

        val wrongIv = patchBody(body, "iv" to JsonPrimitive(base64(ByteArray(12) { 9 })))
        assertFalse(CircleCore.verify(event.copy(body = wrongIv)))

        val wraps = Json.parseToJsonElement(body).jsonObject.getValue("wraps").jsonObject
        val firstFp = wraps.keys.first()
        val badWraps = buildJsonObject {
            wraps.forEach { (fp, value) -> put(fp, if (fp == firstFp) JsonPrimitive("QQ==") else value) }
        }
        val wrongWrap = patchBody(body, "wraps" to badWraps)
        assertFalse(CircleCore.verify(event.copy(body = wrongWrap)))

        // A signature-verified body still parses; a tampered one is never unwrapped.
        assertNotNull(DropKeyCore.parseFileKeyBody(event.body))
    }
}
