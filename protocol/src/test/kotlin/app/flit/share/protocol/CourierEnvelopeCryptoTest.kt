package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * PURE crypto path of the courier envelope: fresh content key wrapped to the
 * named recipient, a signature bound to the sender's own certificate, and the
 * replay/skew gate in `open`. No Android, socket, file or Robolectric.
 *
 * Identities come from [TlsIdentity.generate]. Events are signed directly with
 * [TlsIdentity.sign] (not `CircleCore.create`) so a test never writes a pin as
 * a side effect of building its fixture.
 */
class CourierEnvelopeCryptoTest {

    private val sender = TlsIdentity.generate()
    private val recipient = TlsIdentity.generate()

    private fun signedEvent(text: String, createdMs: Long = NOW): CircleEvent {
        val unsigned = CircleEvent(
            id = UUID.randomUUID().toString(),
            circleId = CIRCLE,
            authorFp = sender.fingerprint,
            lamport = 1L,
            type = CircleEvent.MESSAGE,
            body = """{"text":"$text"}""",
            sig = "",
            createdMs = createdMs,
            cert = Base64.getEncoder().encodeToString(sender.certificate.encoded),
        )
        return unsigned.copy(sig = sender.sign(CircleCore.canonical(unsigned)))
    }

    private fun sealTo(
        events: List<CircleEvent>,
        to: TlsIdentity = recipient,
        nowMs: Long = NOW,
    ): CourierEnvelope = CourierEnvelope.seal(
        events, CIRCLE, sender.fingerprint, to.fingerprint, to.certificate, sender, nowMs,
    ) ?: error("seal refused a well-formed recipient certificate")

    /** Decrypts the OAEP block directly, independently of `CourierEnvelope.open`. */
    private fun unwrap(wrapped: ByteArray, identity: TlsIdentity): ByteArray {
        val privateKey: PrivateKey =
            identity.keyStore.getKey(identity.alias, identity.secretChars()) as PrivateKey
        val specs = listOf(
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
        )
        for (spec in specs) {
            runCatching {
                Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    init(Cipher.DECRYPT_MODE, privateKey, spec)
                }.doFinal(wrapped)
            }.getOrNull()?.let { return it }
        }
        error("wrapped key is not RSA-OAEP/SHA-256 to the named certificate")
    }

    private fun flipped(b64: String): String {
        val bytes = Base64.getDecoder().decode(b64)
        val at = bytes.size / 2
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `seal and open round-trip signed events byte-exactly`() {
        val events = listOf(signedEvent("first"), signedEvent("second"))
        val sealed = sealTo(events)

        assertTrue(sealed.id.isNotBlank())
        assertEquals("AESGCM+RSAOAEP256", sealed.alg)
        assertTrue(CourierEnvelope.verify(sealed))
        val opened = CourierEnvelope.open(sealed, recipient, NOW)
        assertNotNull(opened)
        assertEquals(events, opened)
        assertEquals(WireJson.encode(events), WireJson.encode(opened))
    }

    @Test
    fun `seal wraps a fresh 32-byte key to the recipient certificate`() {
        val events = listOf(signedEvent("wrap"))
        val first = sealTo(events)
        val second = sealTo(events)

        val iv = Base64.getDecoder().decode(first.ivB64)
        val wrapped = Base64.getDecoder().decode(first.keyWrapB64)
        assertEquals(12, iv.size, "12-byte AES-GCM IV")
        assertEquals(256, wrapped.size, "one 2048-bit RSA-OAEP block")
        assertEquals(32, unwrap(wrapped, recipient).size, "AES-256 content key")

        assertNotEquals(first.ivB64, second.ivB64)
        assertNotEquals(first.keyWrapB64, second.keyWrapB64)
        assertNotEquals(first.cipherB64, second.cipherB64)
        assertNotNull(CourierEnvelope.open(first, recipient, NOW))
        assertNotNull(CourierEnvelope.open(second, recipient, NOW))
    }

    @Test
    fun `seal refuses when the recipient certificate is absent`() {
        val events = listOf(signedEvent("no cert"))
        val attempted = runCatching {
            CourierEnvelope.seal(
                events, CIRCLE, sender.fingerprint, recipient.fingerprint,
                null, sender, NOW,
            )
        }
        assertTrue(
            attempted.isFailure || attempted.getOrNull() == null,
            "no recipient certificate means no envelope",
        )
    }

    @Test
    fun `open refuses an envelope sealed to a different certificate`() {
        val stranger = TlsIdentity.generate()
        val events = listOf(signedEvent("for recipient"))
        val sealed = sealTo(events)

        assertNull(
            CourierEnvelope.open(sealed, stranger, NOW),
            "a stranger's private key cannot unwrap the recipient's block",
        )

        val misaddressed = CourierEnvelope.seal(
            events, CIRCLE, sender.fingerprint, recipient.fingerprint,
            stranger.certificate, sender, NOW,
        )
        if (misaddressed != null) {
            assertNull(
                CourierEnvelope.open(misaddressed, recipient, NOW),
                "the named recipient cannot unwrap a stranger's block",
            )
        }
    }

    @Test
    fun `verify refuses a certificate that does not hash to fromFp and writes no pin`() {
        val claimed = "C".repeat(64)
        val sealed = sealTo(listOf(signedEvent("binding")))
        TlsIdentity.forgetPin(claimed)
        TlsIdentity.forgetPin(sender.fingerprint)

        assertFalse(CourierEnvelope.verify(sealed.copy(fromFp = claimed)))
        assertNull(TlsIdentity.pinned(claimed), "the claimed fingerprint stays unpinned")
        assertNull(TlsIdentity.pinned(sender.fingerprint), "no pin before the check succeeds")
    }

    @Test
    fun `verify refuses a forged signature and writes no pin`() {
        val sealed = sealTo(listOf(signedEvent("forgery")))
        TlsIdentity.forgetPin(sender.fingerprint)
        val forged = sealed.copy(
            sig = Base64.getEncoder().encodeToString(ByteArray(256) { 7 }),
        )

        assertFalse(CourierEnvelope.verify(forged))
        assertNull(TlsIdentity.pinned(sender.fingerprint))
    }

    @Test
    fun `verify and open refuse tampered ciphertext IV and wrapped key`() {
        val sealed = sealTo(listOf(signedEvent("tamper")))
        val tampered = listOf(
            "cipherB64" to sealed.copy(cipherB64 = flipped(sealed.cipherB64)),
            "ivB64" to sealed.copy(ivB64 = flipped(sealed.ivB64)),
            "keyWrapB64" to sealed.copy(keyWrapB64 = flipped(sealed.keyWrapB64)),
        )
        for ((field, envelope) in tampered) {
            assertFalse(CourierEnvelope.verify(envelope), "$field tamper fails verify")
            assertNull(CourierEnvelope.open(envelope, recipient, NOW), "$field tamper fails open")
        }
    }

    @Test
    fun `open refuses an envelope redirected to another circle or recipient`() {
        val stranger = TlsIdentity.generate()
        val events = listOf(signedEvent("redirect"))
        val sealed = sealTo(events)

        assertNull(CourierEnvelope.open(sealed.copy(circleId = "another-circle"), recipient, NOW))
        assertNull(CourierEnvelope.open(sealed.copy(toFp = stranger.fingerprint), recipient, NOW))
    }

    @Test
    fun `open accepts an envelope at the replay window boundary`() {
        val events = listOf(signedEvent("window edge"))
        val sealed = sealTo(events, nowMs = NOW - CircleCore.REPLAY_WINDOW_MS)
        assertEquals(events, CourierEnvelope.open(sealed, recipient, NOW))
    }

    @Test
    fun `open refuses an envelope older than the replay window`() {
        val events = listOf(signedEvent("stale"))
        val sealed = sealTo(events, nowMs = NOW - CircleCore.REPLAY_WINDOW_MS - 1L)
        assertNull(CourierEnvelope.open(sealed, recipient, NOW))
    }

    @Test
    fun `open accepts an envelope at the clock skew boundary`() {
        val events = listOf(signedEvent("skew edge"))
        val sealed = sealTo(events, nowMs = NOW + CircleCore.CLOCK_SKEW_MS)
        assertEquals(events, CourierEnvelope.open(sealed, recipient, NOW))
    }

    @Test
    fun `open refuses an envelope ahead of the clock skew`() {
        val events = listOf(signedEvent("future"))
        val sealed = sealTo(events, nowMs = NOW + CircleCore.CLOCK_SKEW_MS + 1L)
        assertNull(CourierEnvelope.open(sealed, recipient, NOW))
    }

    @Test
    fun `open owns no replay set and opens the same envelope twice`() {
        val events = listOf(signedEvent("repeat"))
        val sealed = sealTo(events)

        assertEquals(events, CourierEnvelope.open(sealed, recipient, NOW))
        assertEquals(events, CourierEnvelope.open(sealed, recipient, NOW))
    }

    companion object {
        const val CIRCLE = "circle-crypto"
        const val NOW = 1_800_000_000_000L
    }
}
