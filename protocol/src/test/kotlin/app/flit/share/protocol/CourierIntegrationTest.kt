package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.CourierParcel
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One JVM-only path through the courier seam: a sender seals signed Circle
 * events into an envelope, the envelope rides in a signed `parcel` event into a
 * carrier's bounded bag, and the addressee opens it and verifies the inner
 * events with [CircleCore.verify]. No Android, socket or persisted file is
 * involved; the carrier's bag is held in memory and only its encoded form is
 * used to stand in for a carrier restart.
 */
class CourierIntegrationTest {

    private val circleId = "circle-courier"

    /** Derived once by `parcelOf`; kept local so the test does not restate a production constant. */
    private val parcelTtlMs = 24L * 60 * 60 * 1000L

    @BeforeTest
    fun resetSharedGuards() {
        CircleCore.clear()
        CourierCarryCore.clear()
    }

    private fun parcelBody(toFp: String, envelope: String): String =
        """{"toFp":"$toFp","envelope":"$envelope"}"""

    private fun innerEvents(sender: TlsIdentity, now: Long): List<CircleEvent> = listOf(
        CircleCore.create(circleId, sender.fingerprint, sender, CircleEvent.MESSAGE,
            """{"text":"courier hello"}""", 0L, now),
        CircleCore.create(circleId, sender.fingerprint, sender, CircleEvent.MESSAGE,
            """{"text":"courier second"}""", 1L, now),
    )

    private fun sealParcel(
        sender: TlsIdentity,
        recipient: TlsIdentity,
        events: List<CircleEvent>,
        now: Long,
    ): SealedParcel {
        val envelope = CourierEnvelope.seal(
            events = events,
            circleId = circleId,
            fromFp = sender.fingerprint,
            toFp = recipient.fingerprint,
            recipientCert = recipient.certificate,
            identity = sender,
            nowMs = now,
        ) ?: error("seal refused a well-formed recipient certificate")
        val encoded = CourierEnvelope.encode(envelope)
        val body = parcelBody(recipient.fingerprint, encoded)
        val event = CircleCore.create(
            circleId, sender.fingerprint, sender, CircleEvent.PARCEL, body, 2L, now,
        )
        val parcel = CourierCarryCore.parcelOf(event)
            ?: error("parcelOf refused a well-formed signed parcel event")
        return SealedParcel(envelope, encoded, body, event, parcel)
    }

    private data class SealedParcel(
        val envelope: CourierEnvelope,
        val encoded: String,
        val body: String,
        val event: CircleEvent,
        val parcel: CourierParcel,
    )

    @Test
    fun `parcel verb is registered as the tenth Circle event type`() {
        assertEquals("parcel", CircleEvent.PARCEL)
        assertTrue(CircleEvent.PARCEL in CircleEvent.TYPES)
        assertEquals(10, CircleEvent.TYPES.size)
    }

    @Test
    fun `seal parcel carry open and verify round trip`() {
        val now = System.currentTimeMillis()
        val sender = TlsIdentity.generate("courier-sender")
        val recipient = TlsIdentity.generate("courier-recipient")
        val inner = innerEvents(sender, now)
        val sealed = sealParcel(sender, recipient, inner, now)

        assertEquals(circleId, sealed.envelope.circleId)
        assertEquals(sender.fingerprint, sealed.envelope.fromFp)
        assertEquals(recipient.fingerprint, sealed.envelope.toFp)

        assertTrue(CourierCarryCore.validParcelBody(sealed.body))
        assertTrue(CircleCore.verify(sealed.event))
        assertEquals("Accept", CourierCarryCore.evaluate(sealed.parcel, 0, now)::class.simpleName)

        val bag = CourierCarryCore.fold(CarryState(), sealed.parcel)
        assertEquals(1, bag.parcels.size)
        val selected = CourierCarryCore.select(bag, recipient.fingerprint, now)
        assertEquals(1, selected.size)

        val decoded = CourierEnvelope.decode(sealed.encoded) ?: error("decode refused its own encode")
        assertTrue(CourierEnvelope.verify(decoded))
        val opened = CourierEnvelope.open(decoded, recipient, now)
        assertNotNull(opened)
        assertEquals(inner, opened)
        inner.zip(opened).forEach { (before, after) ->
            assertContentEquals(CircleCore.canonical(before), CircleCore.canonical(after))
            assertTrue(CircleCore.verify(after))
        }

        val delivered = CourierCarryCore.ack(bag, setOf(sealed.event.id))
        assertTrue(delivered.parcels.isEmpty())
    }

    @Test
    fun `carrier can verify the envelope but cannot open it`() {
        val now = System.currentTimeMillis()
        val sender = TlsIdentity.generate("courier-sender")
        val recipient = TlsIdentity.generate("courier-recipient")
        val carrier = TlsIdentity.generate("courier-carrier")
        val sealed = sealParcel(sender, recipient, innerEvents(sender, now), now)

        val bag = CourierCarryCore.fold(CarryState(), sealed.parcel)
        assertEquals(1, CourierCarryCore.select(bag, recipient.fingerprint, now).size)
        assertTrue(CourierCarryCore.select(bag, carrier.fingerprint, now).isEmpty())

        val decoded = CourierEnvelope.decode(sealed.encoded) ?: error("decode refused its own encode")
        assertTrue(CourierEnvelope.verify(decoded))
        assertNull(CourierEnvelope.open(decoded, carrier, now))
    }

    @Test
    fun `open and select refuse clocks and parcels outside their windows`() {
        val now = System.currentTimeMillis()
        val sender = TlsIdentity.generate("courier-sender")
        val recipient = TlsIdentity.generate("courier-recipient")
        val sealed = sealParcel(sender, recipient, innerEvents(sender, now), now)
        val decoded = CourierEnvelope.decode(sealed.encoded) ?: error("decode refused its own encode")
        val bag = CourierCarryCore.fold(CarryState(), sealed.parcel)

        assertNull(CourierEnvelope.open(decoded, recipient, now + 7L * 60 * 60 * 1000L))
        assertNull(CourierEnvelope.open(decoded, recipient, now - CircleCore.CLOCK_SKEW_MS - 1000L))
        assertTrue(CourierCarryCore.select(bag, recipient.fingerprint, now + parcelTtlMs).isEmpty())
        assertEquals(1, CourierCarryCore.select(bag, recipient.fingerprint, now).size)
    }

    @Test
    fun `encoded carry state survives a carrier restart and ack empties the bag`() {
        val now = System.currentTimeMillis()
        val sender = TlsIdentity.generate("courier-sender")
        val recipient = TlsIdentity.generate("courier-recipient")
        val sealed = sealParcel(sender, recipient, innerEvents(sender, now), now)

        val encodedBag = CourierCarryCore.encode(CourierCarryCore.fold(CarryState(), sealed.parcel))
        val restored = CourierCarryCore.decode(encodedBag) ?: error("encoded bag did not decode")
        assertEquals(1, restored.parcels.size)
        assertEquals(sealed.event.id, restored.parcels.single().id)
        assertEquals(1, CourierCarryCore.select(restored, recipient.fingerprint, now).size)

        val acked = CourierCarryCore.ack(restored, setOf(sealed.event.id))
        assertTrue(acked.parcels.isEmpty())
        assertTrue(CourierCarryCore.select(acked, recipient.fingerprint, now).isEmpty())
    }

    @Test
    fun `reoffered parcel id is refused once and fold keeps a single row`() {
        val now = System.currentTimeMillis()
        val sender = TlsIdentity.generate("courier-sender")
        val recipient = TlsIdentity.generate("courier-recipient")
        val sealed = sealParcel(sender, recipient, innerEvents(sender, now), now)

        assertEquals("Accept", CourierCarryCore.evaluate(sealed.parcel, 0, now)::class.simpleName)
        assertEquals("Duplicate", CourierCarryCore.evaluate(sealed.parcel, 1, now)::class.simpleName)
        val bag = CourierCarryCore.fold(CourierCarryCore.fold(CarryState(), sealed.parcel), sealed.parcel)
        assertEquals(1, bag.parcels.size)
        assertEquals(1, CourierCarryCore.select(bag, recipient.fingerprint, now).size)
    }
}
