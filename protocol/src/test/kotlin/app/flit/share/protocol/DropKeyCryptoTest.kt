package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PURE crypto regressions for the frozen `DropKeyCore` seam. Every case drives
 * byte arrays and in-process identities only: no socket, file, clock or Android.
 * The GCM/AAD builder below is written independently of `DropKeyCore.seal`, so a
 * refusal of a wrong-AAD blob proves the domain is authenticated rather than a
 * shared-code accident.
 *
 * Identities come from [TlsIdentity.generate]; no fixture here pins a
 * certificate (the pin store is deliberately left clean and asserted on).
 */
class DropKeyCryptoTest {

    private val recipient = TlsIdentity.generate()
    private val stranger = TlsIdentity.generate()

    // Independent wire/AAD helpers — mirror the documented layout, share no code
    // with DropKeyCore: field = 4-byte BE UTF-8 length then bytes; AAD = one
    // field("FLITDROP1"); sealed = IV || ciphertext || tag.

    private fun field(out: DataOutputStream, value: String) {
        val raw = value.toByteArray(Charsets.UTF_8)
        out.writeInt(raw.size)
        out.write(raw)
    }

    private fun aad(domain: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        val out = DataOutputStream(bytes)
        field(out, domain)
        out.flush()
    }.toByteArray()

    /** Seals with a caller-chosen AAD and a fresh IV, without calling DropKeyCore.seal. */
    private fun sealWithAad(keyBytes: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(DropKeyCore.IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(DropKeyCore.TAG_BYTES * 8, iv),
        )
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    private fun flip(bytes: ByteArray, index: Int): ByteArray = bytes.clone().also {
        it[index] = (it[index].toInt() xor 0x01).toByte()
    }

    private fun flippedB64(b64: String): String {
        val decoded = Base64.getDecoder().decode(b64)
        return Base64.getEncoder().encodeToString(flip(decoded, decoded.size / 2))
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
        text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    // ---- AES-GCM: intact control, then tag / IV / ciphertext / AAD tamper ----

    @Test
    fun `seal and open round-trip the exact IV ciphertext tag layout`() {
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, PLAINTEXT)

        assertEquals(
            DropKeyCore.IV_BYTES + PLAINTEXT.size + DropKeyCore.TAG_BYTES,
            sealed.size,
            "sealed bytes are exactly IV || ciphertext || tag",
        )
        assertContentEquals(PLAINTEXT, DropKeyCore.open(key.bytes, sealed), "intact control must open")
    }

    @Test
    fun `flipping one tag byte makes open return null`() {
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, PLAINTEXT)
        val tampered = flip(sealed, sealed.lastIndex) // last TAG_BYTES are the GCM tag

        assertNull(DropKeyCore.open(key.bytes, tampered))
        assertContentEquals(PLAINTEXT, DropKeyCore.open(key.bytes, sealed), "control still opens")
    }

    @Test
    fun `flipping one IV byte makes open return null`() {
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, PLAINTEXT)
        val tampered = flip(sealed, 0) // first IV_BYTES are the nonce

        assertNull(DropKeyCore.open(key.bytes, tampered))
        assertContentEquals(PLAINTEXT, DropKeyCore.open(key.bytes, sealed), "control still opens")
    }

    @Test
    fun `flipping one ciphertext byte makes open return null`() {
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, PLAINTEXT)
        val middle = DropKeyCore.IV_BYTES + (sealed.size - DropKeyCore.IV_BYTES - DropKeyCore.TAG_BYTES) / 2
        val tampered = flip(sealed, middle)

        assertNull(DropKeyCore.open(key.bytes, tampered))
        assertContentEquals(PLAINTEXT, DropKeyCore.open(key.bytes, sealed), "control still opens")
    }

    @Test
    fun `a sealed blob shorter than IV plus tag is refused`() {
        val key = DropKeyCore.mint()
        val tooShort = ByteArray(DropKeyCore.IV_BYTES + DropKeyCore.TAG_BYTES - 1)

        assertNull(DropKeyCore.open(key.bytes, tooShort))
        assertNull(DropKeyCore.open(key.bytes, ByteArray(0)))
    }

    @Test
    fun `a blob sealed under a different AAD domain is refused`() {
        val key = DropKeyCore.mint()

        // Positive control: our independent builder produces a blob open accepts.
        val correct = sealWithAad(key.bytes, PLAINTEXT, aad(DropKeyCore.DOMAIN))
        assertContentEquals(PLAINTEXT, DropKeyCore.open(key.bytes, correct))

        // Same key, IV prefix form, ciphertext and tag layout — only AAD differs.
        val otherDomain = sealWithAad(key.bytes, PLAINTEXT, aad(DropKeyCore.DOMAIN + "2"))
        assertNull(DropKeyCore.open(key.bytes, otherDomain), "a changed domain tag fails authentication")

        val noLengthPrefix = sealWithAad(key.bytes, PLAINTEXT, DropKeyCore.DOMAIN.toByteArray(Charsets.UTF_8))
        assertNull(DropKeyCore.open(key.bytes, noLengthPrefix), "the 4-byte length prefix is part of the AAD")

        val noAad = sealWithAad(key.bytes, PLAINTEXT, ByteArray(0))
        assertNull(DropKeyCore.open(key.bytes, noAad), "an empty AAD is not the domain field")
    }

    // ---- RSA-OAEP wrap / unwrap failures ----

    @Test
    fun `unwrap with a wrong identity or a wrap crossed to another key returns null`() {
        val key = DropKeyCore.mint()
        val wraps = DropKeyCore.wrap(key.bytes, mapOf(recipient.fingerprint to recipient.certificate))
        val wrapped = wraps[recipient.fingerprint] ?: error("recipient must get one wrap")

        assertContentEquals(key.bytes, DropKeyCore.unwrap(wraps, recipient), "right identity opens its wrap")
        assertNull(DropKeyCore.unwrap(wraps, stranger), "a stranger's private key cannot open it")
        assertNull(
            DropKeyCore.unwrap(mapOf(stranger.fingerprint to wrapped), stranger),
            "a wrap stored under the caller's fingerprint but encrypted to another key fails",
        )
    }

    @Test
    fun `unwrap returns null when the identity has no entry in the wrap map`() {
        val key = DropKeyCore.mint()
        val wraps = DropKeyCore.wrap(key.bytes, mapOf(recipient.fingerprint to recipient.certificate))

        assertNull(DropKeyCore.unwrap(wraps, stranger), "no entry for the caller's fingerprint")
        assertNull(DropKeyCore.unwrap(emptyMap(), recipient), "an empty map has no wrap")
        assertNull(
            DropKeyCore.unwrap(mapOf(" ".repeat(64) to wraps.getValue(recipient.fingerprint)), recipient),
            "a blank fingerprint key is not the caller's entry",
        )
    }

    @Test
    fun `unwrap returns null for malformed base64 short blobs and bad OAEP padding`() {
        val fp = recipient.fingerprint
        val junk = listOf(
            "",
            "!!!not-base64!!!",
            Base64.getEncoder().encodeToString("short".toByteArray()),
            Base64.getEncoder().encodeToString(ByteArray(256)), // right size, wrong padding
            "AA===",
        )
        for (value in junk) {
            assertNull(DropKeyCore.unwrap(mapOf(fp to value), recipient), "malformed wrap: '$value'")
        }
    }

    @Test
    fun `a flipped wrapped-key byte cannot be unwrapped`() {
        val key = DropKeyCore.mint()
        val wrap = DropKeyCore
            .wrap(key.bytes, mapOf(recipient.fingerprint to recipient.certificate))
            .getValue(recipient.fingerprint)
        val tampered = mapOf(recipient.fingerprint to flippedB64(wrap))

        assertNotNull(DropKeyCore.unwrap(mapOf(recipient.fingerprint to wrap), recipient), "control unwrap")
        assertNull(DropKeyCore.unwrap(tampered, recipient), "OAEP padding cannot survive a flipped byte")
    }

    // ---- no pin writes on any crypto path ----

    @Test
    fun `mint wrap unwrap seal and open never touch the pin store`() {
        val bystander = TlsIdentity.generate()
        listOf(recipient, stranger, bystander).forEach { TlsIdentity.forgetPin(it.fingerprint) }
        listOf(recipient, stranger, bystander).forEach {
            assertNull(TlsIdentity.pinned(it.fingerprint), "fixture identity starts unpinned")
        }

        val key = DropKeyCore.mint()
        val wraps = DropKeyCore.wrap(key.bytes, mapOf(recipient.fingerprint to recipient.certificate))
        assertContentEquals(key.bytes, DropKeyCore.unwrap(wraps, recipient))
        val sealed = DropKeyCore.seal(key, PLAINTEXT)
        assertContentEquals(PLAINTEXT, DropKeyCore.open(key.bytes, sealed))

        // Failure paths must not pin either.
        DropKeyCore.unwrap(wraps, stranger)
        DropKeyCore.unwrap(mapOf(recipient.fingerprint to "!!!"), recipient)
        DropKeyCore.open(key.bytes, flip(sealed, sealed.lastIndex))

        listOf(recipient, stranger, bystander).forEach {
            assertNull(TlsIdentity.pinned(it.fingerprint), "no crypto call may write a pin")
        }
    }

    // ---- canonical bytes of every pre-existing verb are unchanged ----

    @Test
    fun `canonical vectors for every existing verb are byte-identical`() {
        assertEquals(CircleEvent.TYPES - CircleEvent.PAD, GOLDEN.keys, "goldens cover exactly the eleven pre-pad verbs")
        for ((type, expectedHex) in GOLDEN) {
            val event = CircleEvent(
                id = "id-1",
                circleId = "circle-1",
                authorFp = "ABCD",
                lamport = 7L,
                type = type,
                body = BODIES.getValue(type),
                sig = "",
                createdMs = 1_800_000_000_000L,
            )
            assertContentEquals(
                hex(expectedHex),
                CircleCore.canonical(event),
                "$type canonical bytes changed",
            )
        }
    }

    companion object {
        private val PLAINTEXT = ByteArray(8192) { (it * 31).toByte() }

        private val BODIES = mapOf(
            CircleEvent.MESSAGE to """{"text":"hi"}""",
            CircleEvent.FILE to """{"name":"a.txt","size":12,"sha256":"abc"}""",
            CircleEvent.POLL to """{"pollId":"p1","question":"q","options":["a","b"]}""",
            CircleEvent.VOTE to """{"pollId":"p1","option":1}""",
            CircleEvent.JOIN to """{"alias":"alice"}""",
            CircleEvent.LEAVE to "{}",
            CircleEvent.KICK to """{"kickId":"k1","targetFp":"ABCD","mode":"admin","approve":true}""",
            CircleEvent.ROLE to """{"targetFp":"ABCD","role":"ADMIN"}""",
            CircleEvent.REKEY to """{"keyB64":"a2V5","gen":1}""",
            CircleEvent.PARCEL to """{"toFp":"ABCD","envelope":"ZW52"}""",
            CircleEvent.RECEIPT to """{"dropId":"d1","sha256":"abc","keyId":"d1:1"}""",
        )

        /** Golden canonical bytes, generated independently from the documented field rule. */
        private val GOLDEN = mapOf(
            CircleEvent.MESSAGE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "00000004414243440000000000000007000000076d6573736167650000000d7b2274657874223a226869227d" +
                "000001a3185c5000",
            CircleEvent.FILE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "000000044142434400000000000000070000000466696c65000000297b226e616d65223a22612e74787422" +
                "2c2273697a65223a31322c22736861323536223a22616263227d000001a3185c5000",
            CircleEvent.POLL to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "0000000441424344000000000000000700000004706f6c6c000000327b22706f6c6c4964223a22703122" +
                "2c227175657374696f6e223a2271222c226f7074696f6e73223a5b2261222c2262225d7d000001a3185c5000",
            CircleEvent.VOTE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "0000000441424344000000000000000700000004766f74650000001a7b22706f6c6c4964223a22703122" +
                "2c226f7074696f6e223a317d000001a3185c5000",
            CircleEvent.JOIN to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "00000004414243440000000000000007000000046a6f696e000000117b22616c696173223a22616c696365227d" +
                "000001a3185c5000",
            CircleEvent.LEAVE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "00000004414243440000000000000007000000056c65617665000000027b7d000001a3185c5000",
            CircleEvent.KICK to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "00000004414243440000000000000007000000046b69636b0000003f7b226b69636b4964223a226b3122" +
                "2c227461726765744670223a2241424344222c226d6f6465223a2261646d696e222c22617070726f766522" +
                "3a747275657d000001a3185c5000",
            CircleEvent.ROLE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "0000000441424344000000000000000700000004726f6c65000000227b227461726765744670223a224142" +
                "4344222c22726f6c65223a2241444d494e227d000001a3185c5000",
            CircleEvent.REKEY to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "000000044142434400000000000000070000000572656b6579000000197b226b6579423634223a22613256" +
                "35222c2267656e223a317d000001a3185c5000",
            CircleEvent.PARCEL to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "000000044142434400000000000000070000000670617263656c000000217b22746f4670223a2241424344" +
                "222c22656e76656c6f7065223a225a573532227d000001a3185c5000",
            CircleEvent.RECEIPT to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c652d31" +
                "0000000441424344000000000000000700000007726563656970740000002d7b2264726f704964223a" +
                "226431222c22736861323536223a22616263222c226b65794964223a2264313a31227d000001a3185c5000",
        )
    }
}
