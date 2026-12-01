package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.tls.TlsIdentity
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PURE authorization edges of the courier path — no socket, file, Android or
 * Gradle. Identities come from [TlsIdentity.generate]; events are signed
 * directly with [TlsIdentity.sign] so building a fixture never folds an event
 * into Circle's replay set.
 *
 * Two distinct layers are under test:
 *  - [CourierEnvelope.seal] binds the wrapping certificate to `toFp` itself;
 *  - the opened event array is NOT membership-gated by the codec (by design),
 *    so the app/CLI fold gate is modelled below as an explicit pure predicate.
 */
class CourierAuthorizationTest {

    private val sender = TlsIdentity.generate()
    private val recipient = TlsIdentity.generate()

    private fun signedEvent(
        identity: TlsIdentity,
        type: String,
        body: String,
        lamport: Long = 1L,
        createdMs: Long = NOW,
    ): CircleEvent {
        val unsigned = CircleEvent(
            id = UUID.randomUUID().toString(),
            circleId = CIRCLE,
            authorFp = identity.fingerprint,
            lamport = lamport,
            type = type,
            body = body,
            sig = "",
            createdMs = createdMs,
            cert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
        )
        return unsigned.copy(sig = identity.sign(CircleCore.canonical(unsigned)))
    }

    /**
     * Pure model of the app/CLI courier fold gate (`FlitCircleSync.courierReceive`
     * and `CourierCli.receive`): an opened event may join the signed log only if
     * it belongs to this circle, its author is on the CURRENT local roster, and
     * its verb is not JOIN or REKEY — those two are governance events that a
     * carrier never folds, no matter how valid the signature is. Kept here, in
     * the protocol test, because this module cannot depend on app/harness.
     */
    private fun courierFoldGateAdmits(
        event: CircleEvent,
        circleId: String,
        memberFps: Set<String>,
    ): Boolean {
        val author = event.authorFp.trim().uppercase()
        val governanceVerb = event.type.equals(CircleEvent.JOIN, ignoreCase = true) ||
            event.type.equals(CircleEvent.REKEY, ignoreCase = true)
        return event.circleId == circleId && author in memberFps && !governanceVerb
    }

    @Test
    fun `seal refuses a recipient certificate whose fingerprint is not toFp`() {
        val stranger = TlsIdentity.generate()
        val events = listOf(signedEvent(sender, CircleEvent.MESSAGE, """{"text":"for the named recipient"}"""))

        // Control: a certificate that really is toFp still seals.
        val good = CourierEnvelope.seal(
            events, CIRCLE, sender.fingerprint, recipient.fingerprint,
            recipient.certificate, sender, NOW,
        )
        assertNotNull(good)
        assertEquals(recipient.fingerprint, good.toFp)

        // Lowercase toFp is the same fingerprint, so it must not be refused.
        val lowercaseTarget = CourierEnvelope.seal(
            events, CIRCLE, sender.fingerprint, recipient.fingerprint.lowercase(),
            recipient.certificate, sender, NOW,
        )
        assertNotNull(lowercaseTarget, "fingerprint comparison is case-insensitive")

        // toFp names the recipient, but the certificate in hand is a stranger's:
        // no envelope may be created for that mismatch.
        val mismatched = CourierEnvelope.seal(
            events, CIRCLE, sender.fingerprint, recipient.fingerprint,
            stranger.certificate, sender, NOW,
        )
        assertNull(mismatched, "the wrapping certificate must hash to toFp")

        // And the mirror image: toFp names the stranger, cert is the recipient's.
        val reversed = CourierEnvelope.seal(
            events, CIRCLE, sender.fingerprint, stranger.fingerprint,
            recipient.certificate, sender, NOW,
        )
        assertNull(reversed, "a confusingly named recipient is not the certificate")
    }

    @Test
    fun `open yields signature-valid non-member JOIN and REKEY events the app and CLI gate refuses`() {
        val outsider = TlsIdentity.generate()
        val message = signedEvent(sender, CircleEvent.MESSAGE, """{"text":"ordinary member event"}""", lamport = 1L)
        val join = signedEvent(outsider, CircleEvent.JOIN, """{"alias":"Mallory"}""", lamport = 2L)
        val rekey = signedEvent(outsider, CircleEvent.REKEY, """{"keyB64":"deadbeef","gen":1}""", lamport = 3L)
        val inner = listOf(message, join, rekey)

        val sealed = CourierEnvelope.seal(
            inner, CIRCLE, sender.fingerprint, recipient.fingerprint,
            recipient.certificate, sender, NOW,
        )
        assertNotNull(sealed)

        // Signature validity is real: each privileged event is signed by the
        // very identity its embedded cert hashes to, and normal Circle verify
        // accepts it. Verification proves authorship, never membership.
        for (event in listOf(join, rekey)) {
            assertTrue(
                TlsIdentity.verifyWithCertificate(outsider.certificate, CircleCore.canonical(event), event.sig),
                "the outsider really signed this event",
            )
            assertTrue(CircleCore.verify(event), "Circle's normal gate accepts the signature")
        }

        // The codec opens the whole array unchanged — it owns no roster and
        // asserts no authorship, so both privileged events come out of open().
        val opened = CourierEnvelope.open(sealed, recipient, NOW)
        assertNotNull(opened)
        assertEquals(inner, opened)
        assertTrue(join in opened)
        assertTrue(rekey in opened)

        val members = setOf(sender.fingerprint, recipient.fingerprint)
        assertFalse(outsider.fingerprint in members, "the signer of JOIN/REKEY is not a member")

        // The app/CLI fold gate must refuse those two, while an ordinary event
        // from a current member is still allowed through.
        assertFalse(courierFoldGateAdmits(join, CIRCLE, members), "non-member JOIN must be refused")
        assertFalse(courierFoldGateAdmits(rekey, CIRCLE, members), "non-member REKEY must be refused")
        assertTrue(courierFoldGateAdmits(message, CIRCLE, members))
        assertEquals(listOf(message), opened.filter { courierFoldGateAdmits(it, CIRCLE, members) })

        // Governance verbs are refused even when a CURRENT member signed them.
        val memberRekey = signedEvent(sender, CircleEvent.REKEY, """{"keyB64":"cafe","gen":2}""", lamport = 4L)
        assertFalse(courierFoldGateAdmits(memberRekey, CIRCLE, members))
    }

    companion object {
        const val CIRCLE = "circle-authorization"
        const val NOW = 1_800_000_000_000L
    }
}
