package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Random
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure per-drop content-key tests: fresh mint, RSA-OAEP wrap/unwrap, AES-GCM
 * seal/open byte-exactness, tamper refusal, the declared caps and `keyId`.
 * No socket, no Android, no persistence and no pin write — only the JVM-pure
 * surface of `DropKeyCore`.
 */
class DropKeyCoreTest {

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Deterministic filler so every size/round-trip vector is reproducible. */
    private fun seeded(size: Int, seed: Long): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    /** Changes one byte without ever leaving the input unchanged. */
    private fun flipped(data: ByteArray, index: Int): ByteArray =
        data.clone().also { it[index] = (it[index] + 1).toByte() }

    /** An independent JCE seal, used to prove the AAD domain is authenticated. */
    private fun sealWithDomain(domain: String, key: DropKeyCore.Key, plaintext: ByteArray): ByteArray {
        val aad = ByteArrayOutputStream().apply {
            val out = DataOutputStream(this)
            val raw = domain.toByteArray(Charsets.UTF_8)
            out.writeInt(raw.size) // CircleCore's 4-byte big-endian UTF-8 field rule
            out.write(raw)
            out.flush()
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.bytes, "AES"),
            GCMParameterSpec(DropKeyCore.TAG_BYTES * 8, key.iv),
        )
        cipher.updateAAD(aad)
        return key.iv + cipher.doFinal(plaintext)
    }

    // ---- mint -------------------------------------------------------------

    @Test
    fun `mint draws a fresh 32-byte key and 12-byte IV every call`() {
        assertEquals(32, DropKeyCore.KEY_BYTES)
        assertEquals(12, DropKeyCore.IV_BYTES)
        assertEquals(16, DropKeyCore.TAG_BYTES)
        val keys = HashSet<String>()
        val ivs = HashSet<String>()
        repeat(64) {
            val key = DropKeyCore.mint()
            assertEquals(DropKeyCore.KEY_BYTES, key.bytes.size)
            assertEquals(DropKeyCore.IV_BYTES, key.iv.size)
            assertTrue(key.bytes.any { it != 0.toByte() }, "a minted key is not all zero")
            assertTrue(key.iv.any { it != 0.toByte() }, "a minted IV is not all zero")
            val keyHex = key.bytes.joinToString("") { "%02X".format(it) }
            val ivHex = key.iv.joinToString("") { "%02X".format(it) }
            assertTrue(keys.add(keyHex), "mint repeated a key")
            assertTrue(ivs.add(ivHex), "mint repeated an IV")
        }
    }

    @Test
    fun `mint does not derive its key or IV from a group key or invite`() {
        val groupSha = sha256("group-key".toByteArray(Charsets.UTF_8))
        val inviteSha = sha256("invite-code".toByteArray(Charsets.UTF_8))
        val seededKey = seeded(DropKeyCore.KEY_BYTES, 7L)
        repeat(8) {
            val key = DropKeyCore.mint()
            assertFalse(key.bytes.contentEquals(groupSha))
            assertFalse(key.bytes.contentEquals(inviteSha))
            assertFalse(key.bytes.contentEquals(seededKey))
            assertFalse(key.iv.contentEquals(groupSha.copyOf(DropKeyCore.IV_BYTES)))
            assertFalse(key.iv.contentEquals(inviteSha.copyOf(DropKeyCore.IV_BYTES)))
        }
    }

    // ---- seal / open ------------------------------------------------------

    @Test
    fun `seal emits an IV prefix plus ciphertext plus a 16-byte tag`() {
        val key = DropKeyCore.mint()
        val empty = DropKeyCore.seal(key, ByteArray(0))
        assertEquals(DropKeyCore.IV_BYTES + DropKeyCore.TAG_BYTES, empty.size)
        assertContentEquals(key.iv, empty.copyOfRange(0, DropKeyCore.IV_BYTES))

        val plain = seeded(5, 1L)
        val sealed = DropKeyCore.seal(key, plain)
        assertEquals(DropKeyCore.IV_BYTES + plain.size + DropKeyCore.TAG_BYTES, sealed.size)
        assertContentEquals(key.iv, sealed.copyOfRange(0, DropKeyCore.IV_BYTES))
        val opened = DropKeyCore.open(key.bytes, sealed)
        assertNotNull(opened)
        assertContentEquals(plain, opened)
    }

    @Test
    fun `seal and open round-trip byte-exact at 0 1 8192 and 3 MiB seeded sizes`() {
        for (size in listOf(0, 1, 8 * 1024, 3 * 1024 * 1024)) {
            val key = DropKeyCore.mint()
            val plain = seeded(size, 1234L + size.toLong())
            val sealed = DropKeyCore.seal(key, plain)
            assertEquals(DropKeyCore.IV_BYTES + size + DropKeyCore.TAG_BYTES, sealed.size)
            val opened = DropKeyCore.open(key.bytes, sealed)
            assertNotNull(opened, "open failed at $size bytes")
            assertContentEquals(plain, opened)
        }
    }

    @Test
    fun `two mints sealing identical bytes produce different IVs and sealed bytes`() {
        val plain = seeded(256, 99L)
        val first = DropKeyCore.mint()
        val second = DropKeyCore.mint()
        val sealedFirst = DropKeyCore.seal(first, plain)
        val sealedSecond = DropKeyCore.seal(second, plain)
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(sealedFirst.contentEquals(sealedSecond))
        val openedFirst = DropKeyCore.open(first.bytes, sealedFirst)
        val openedSecond = DropKeyCore.open(second.bytes, sealedSecond)
        assertNotNull(openedFirst)
        assertNotNull(openedSecond)
        assertContentEquals(plain, openedFirst)
        assertContentEquals(plain, openedSecond)
    }

    // ---- tamper refusal ---------------------------------------------------

    @Test
    fun `open refuses a ciphertext sealed under another key`() {
        val first = DropKeyCore.mint()
        val second = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(first, seeded(64, 3L))
        assertNull(DropKeyCore.open(second.bytes, sealed))
        assertNull(DropKeyCore.open(ByteArray(DropKeyCore.KEY_BYTES), sealed))
    }

    @Test
    fun `open refuses a flipped IV ciphertext or tag byte`() {
        val key = DropKeyCore.mint()
        val plain = seeded(200, 11L)
        val sealed = DropKeyCore.seal(key, plain)
        assertNotNull(DropKeyCore.open(key.bytes, sealed))

        val cipherMid = DropKeyCore.IV_BYTES + (plain.size / 2)
        val ivFlip = flipped(sealed, 0)
        val cipherStartFlip = flipped(sealed, DropKeyCore.IV_BYTES)
        val cipherMidFlip = flipped(sealed, cipherMid)
        val tagFlip = flipped(sealed, sealed.lastIndex)
        assertFalse(ivFlip.contentEquals(sealed))
        assertNull(DropKeyCore.open(key.bytes, ivFlip))
        assertNull(DropKeyCore.open(key.bytes, cipherStartFlip))
        assertNull(DropKeyCore.open(key.bytes, cipherMidFlip))
        assertNull(DropKeyCore.open(key.bytes, tagFlip))

        val untouched = DropKeyCore.open(key.bytes, sealed)
        assertNotNull(untouched)
        assertContentEquals(plain, untouched)
    }

    @Test
    fun `open refuses any blob shorter than an IV plus tag`() {
        val key = DropKeyCore.mint()
        for (length in 0 until DropKeyCore.IV_BYTES + DropKeyCore.TAG_BYTES) {
            assertNull(DropKeyCore.open(key.bytes, ByteArray(length)), "accepted a ${length}-byte blob")
        }
    }

    @Test
    fun `open refuses a ciphertext sealed under a different domain AAD`() {
        assertEquals("FLITDROP1", DropKeyCore.DOMAIN)
        val key = DropKeyCore.mint()
        val plain = seeded(128, 42L)
        val foreign = sealWithDomain("FLITDROP2", key, plain)
        assertEquals(DropKeyCore.IV_BYTES + plain.size + DropKeyCore.TAG_BYTES, foreign.size)
        assertNull(DropKeyCore.open(key.bytes, foreign))

        // The same JCE recipe with the frozen domain still opens, so the
        // refusal above is caused by the AAD change and not by a bad helper.
        val control = sealWithDomain(DropKeyCore.DOMAIN, key, plain)
        val opened = DropKeyCore.open(key.bytes, control)
        assertNotNull(opened)
        assertContentEquals(plain, opened)
    }

    // ---- wrap / unwrap ----------------------------------------------------

    @Test
    fun `wrap emits one uppercase fingerprint entry within MAX_WRAPPED_CHARS`() {
        assertEquals(32, DropKeyCore.MAX_WRAPS)
        assertEquals(512, DropKeyCore.MAX_WRAPPED_CHARS)
        assertTrue(DropKeyCore.MAX_WRAPPED_CHARS >= 344, "a 2048-bit OAEP wrap is ~344 base64 chars")
        assertTrue(DropKeyCore.MAX_WRAPPED_CHARS > CircleCore.MAX_FIELD_CHARS)

        val first = TlsIdentity.generate()
        val second = TlsIdentity.generate()
        val wrapped = DropKeyCore.wrap(
            DropKeyCore.mint().bytes,
            mapOf(first.fingerprint to first.certificate, second.fingerprint to second.certificate),
        )
        assertEquals(setOf(first.fingerprint, second.fingerprint), wrapped.keys)
        for ((fingerprint, value) in wrapped) {
            assertEquals(fingerprint.uppercase(), fingerprint)
            assertTrue(value.isNotBlank())
            assertTrue(value.length <= DropKeyCore.MAX_WRAPPED_CHARS, "wrap value exceeds its cap")
            assertTrue(Base64.getDecoder().decode(value).isNotEmpty())
        }
    }

    @Test
    fun `unwrap returns the exact wrapped key for each recipient identity`() {
        val first = TlsIdentity.generate()
        val second = TlsIdentity.generate()
        val key = DropKeyCore.mint().bytes
        val wrapped = DropKeyCore.wrap(
            key,
            mapOf(first.fingerprint to first.certificate, second.fingerprint to second.certificate),
        )
        val openedFirst = DropKeyCore.unwrap(wrapped, first)
        val openedSecond = DropKeyCore.unwrap(wrapped, second)
        assertNotNull(openedFirst)
        assertNotNull(openedSecond)
        assertContentEquals(key, openedFirst)
        assertContentEquals(key, openedSecond)
    }

    @Test
    fun `unwrap refuses a wrong identity absent fingerprint junk and tampered wrap`() {
        val recipient = TlsIdentity.generate()
        val outsider = TlsIdentity.generate()
        val misaddressed = TlsIdentity.generate()
        val key = DropKeyCore.mint().bytes
        val wrapped = DropKeyCore.wrap(key, mapOf(recipient.fingerprint to recipient.certificate))

        assertNull(DropKeyCore.unwrap(wrapped, outsider)) // no entry for this identity
        assertNull(DropKeyCore.unwrap(emptyMap(), recipient)) // absent fingerprint
        assertNull(DropKeyCore.unwrap(mapOf(recipient.fingerprint to "!!!not base64!!!"), recipient))
        assertNull(DropKeyCore.unwrap(mapOf(recipient.fingerprint to ""), recipient))

        val cipher = Base64.getDecoder().decode(wrapped.getValue(recipient.fingerprint))
        val tampered = flipped(cipher, cipher.lastIndex)
        val badPadding = Base64.getEncoder().encodeToString(tampered)
        assertNull(DropKeyCore.unwrap(mapOf(recipient.fingerprint to badPadding), recipient))

        // A wrap made for A and relabelled as B's must not open for B.
        assertNull(
            DropKeyCore.unwrap(
                mapOf(misaddressed.fingerprint to wrapped.getValue(recipient.fingerprint)),
                misaddressed,
            ),
        )
    }

    @Test
    fun `wrap unwrap seal and open leave the pin map untouched`() {
        val first = TlsIdentity.generate()
        val second = TlsIdentity.generate()
        assertNull(TlsIdentity.pinned(first.fingerprint))
        assertNull(TlsIdentity.pinned(second.fingerprint))

        val key = DropKeyCore.mint()
        val wrapped = DropKeyCore.wrap(key.bytes, mapOf(first.fingerprint to first.certificate))
        val sealed = DropKeyCore.seal(key, seeded(32, 5L))
        assertNotNull(DropKeyCore.unwrap(wrapped, first))
        assertNull(DropKeyCore.unwrap(wrapped, second))
        assertNotNull(DropKeyCore.open(key.bytes, sealed))

        assertNull(TlsIdentity.pinned(first.fingerprint))
        assertNull(TlsIdentity.pinned(second.fingerprint))
    }

    @Test
    fun `wrap refuses a roster above MAX_WRAPS without trimming`() {
        val rosterCert: X509Certificate = TlsIdentity.generate().certificate
        val key = DropKeyCore.mint().bytes
        val atCap = LinkedHashMap<String, X509Certificate>()
        repeat(DropKeyCore.MAX_WRAPS) {
            atCap["%064X".format(it)] = rosterCert
        }
        val accepted = DropKeyCore.wrap(key, atCap)
        assertTrue(accepted.size <= DropKeyCore.MAX_WRAPS)
        assertTrue(accepted.values.all { it.length <= DropKeyCore.MAX_WRAPPED_CHARS })

        val overCap = LinkedHashMap(atCap)
        overCap["%064X".format(DropKeyCore.MAX_WRAPS)] = rosterCert
        val outcome = runCatching { DropKeyCore.wrap(key, overCap) }
        val result = outcome.getOrNull()
        assertTrue(
            outcome.isFailure || result!!.isEmpty(),
            "a roster above ${DropKeyCore.MAX_WRAPS} must be refused, not trimmed",
        )
    }

    // ---- explicit fingerprint/certificate binding --------------------------

    @Test
    fun `wrapVerified binds each wrap to the certificate's own fingerprint`() {
        val first = TlsIdentity.generate()
        val second = TlsIdentity.generate()
        val key = DropKeyCore.mint().bytes
        val wrapped = DropKeyCore.wrapVerified(
            key,
            mapOf(
                first.fingerprint to first.certificate,
                second.fingerprint to second.certificate,
            ),
        )
        assertEquals(setOf(first.fingerprint, second.fingerprint), wrapped.keys)
        assertContentEquals(key, DropKeyCore.unwrap(wrapped, first))
        assertContentEquals(key, DropKeyCore.unwrap(wrapped, second))
        // A lowercase claim still binds: normalisation is the same as wrap's.
        val lower = DropKeyCore.wrapVerified(
            key,
            mapOf(first.fingerprint.lowercase() to first.certificate),
        )
        assertEquals(setOf(first.fingerprint), lower.keys)
    }

    @Test
    fun `wrapVerified refuses a fingerprint misbound to a foreign certificate`() {
        val recipient = TlsIdentity.generate()
        val claim = "F".repeat(64) // not the certificate's SHA-256 DER fingerprint
        val key = DropKeyCore.mint().bytes
        val wrapped = DropKeyCore.wrapVerified(key, mapOf(claim to recipient.certificate))

        // Total refusal: no wrap is produced and the call never throws, so the
        // mislabelled key cannot be handed to this certificate's private key.
        assertTrue(wrapped.isEmpty(), "a misbound roster must yield no wrap")
        assertNull(DropKeyCore.unwrap(wrapped, recipient))
    }

    @Test
    fun `wrapVerified of a mixed roster keeps only correctly bound entries`() {
        val good = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val key = DropKeyCore.mint().bytes
        val wrapped = DropKeyCore.wrapVerified(
            key,
            mapOf(
                good.fingerprint to good.certificate,
                "B".repeat(64) to stranger.certificate,
            ),
        )
        assertEquals(setOf(good.fingerprint), wrapped.keys)
        assertContentEquals(key, DropKeyCore.unwrap(wrapped, good))
        assertNull(DropKeyCore.unwrap(wrapped, stranger))
        assertTrue(wrapped.values.all { it.length <= DropKeyCore.MAX_WRAPPED_CHARS })

        // Bounded: a roster above the cap is still refused outright, not trimmed.
        val overCap = LinkedHashMap<String, X509Certificate>()
        repeat(DropKeyCore.MAX_WRAPS + 1) { overCap["%064X".format(it)] = good.certificate }
        assertTrue(runCatching { DropKeyCore.wrapVerified(key, overCap) }.isFailure)
    }

    // ---- keyId ------------------------------------------------------------

    @Test
    fun `keyId is dropId colon keyGen stable and bounded`() {
        val dropId = "event-42"
        assertEquals("event-42:1", DropKeyCore.keyId(dropId, 1))
        assertEquals(DropKeyCore.keyId(dropId, 1), DropKeyCore.keyId(dropId, 1))
        assertNotEquals(DropKeyCore.keyId(dropId, 1), DropKeyCore.keyId(dropId, 2))
        assertNotEquals(DropKeyCore.keyId(dropId, 1), DropKeyCore.keyId("other-drop", 1))

        val signedEventId = UUID.randomUUID().toString()
        assertTrue(DropKeyCore.keyId(signedEventId, 1).length <= CircleCore.MAX_FIELD_CHARS)
        assertTrue(DropKeyCore.keyId(signedEventId, Int.MAX_VALUE).length <= CircleCore.MAX_FIELD_CHARS)
    }
}
