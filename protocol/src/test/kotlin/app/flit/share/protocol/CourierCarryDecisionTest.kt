package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierParcel
import app.flit.share.protocol.courier.Decision
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure decision tests for the courier bag. Every rule here is decided from
 * caller-supplied values: no clock read, no socket, no file and no Android.
 *
 * The local constants mirror the frozen 2026-11-27 protocol values; the tests
 * deliberately do not import the main-code constants, so a rename of their
 * owner cannot silently change what is being pinned here.
 */
class CourierCarryDecisionTest {

    private companion object {
        const val CIRCLE = "circle-1"
        const val TARGET = "AAAA1111"
        const val OTHER = "BBBB2222"
        const val ENVELOPE = "AQIDBAUGBwg="
        const val NOW = 1_700_000_000_000L
        const val MAX_PARCELS = 256
        const val MAX_SEEN = 4096
        const val MAX_PARCEL_TTL_MS = 24L * 60 * 60 * 1000
    }

    private val signer = TlsIdentity.generate()

    private fun parcelBody(toFp: String = TARGET, envelope: String = ENVELOPE): String =
        """{"toFp":"$toFp","envelope":"$envelope"}"""

    private fun parcelEvent(
        toFp: String = TARGET,
        envelope: String = ENVELOPE,
        lamport: Long = 0L,
        createdMs: Long = NOW,
    ): CircleEvent = CircleCore.create(
        circleId = CIRCLE,
        authorFp = signer.fingerprint,
        identity = signer,
        type = CircleEvent.PARCEL,
        body = parcelBody(toFp, envelope),
        lamport = lamport,
        createdMs = createdMs,
    )

    private fun parcel(
        id: String? = null,
        toFp: String = TARGET,
        lamport: Long = 0L,
        createdMs: Long = NOW,
        expiresMs: Long = createdMs + MAX_PARCEL_TTL_MS,
    ): CourierParcel {
        val minted = parcelEvent(toFp = toFp, lamport = lamport, createdMs = createdMs)
        val event = id?.let { minted.copy(id = it) } ?: minted
        return CourierParcel(
            id = event.id,
            toFp = toFp.trim().uppercase(),
            fromFp = event.authorFp,
            createdMs = createdMs,
            expiresMs = expiresMs,
            event = event,
        )
    }

    /** order() is (createdMs, fromFp, id, …); distinct createdMs fixes that order. */
    private fun orderedParcels(count: Int, toFp: String = TARGET): List<CourierParcel> =
        (0 until count).map { parcel(toFp = toFp, lamport = it.toLong(), createdMs = NOW + it.toLong()) }

    @BeforeTest
    fun resetReservations() {
        CourierCarryCore.clear()
    }

    // ---- evaluate precedence ---------------------------------------------

    @Test
    fun `evaluate follows the six-way precedence and reserves ids only on Accept`() {
        val good = parcel(lamport = 1)

        // Invalid outranks the clock, expiry, dedup and capacity checks.
        val blankId = good.copy(id = "", event = good.event.copy(id = ""))
        assertEquals(Decision.Invalid, CourierCarryCore.evaluate(blankId, 0, NOW))
        val extraKey = good.copy(
            event = good.event.copy(body = """{"toFp":"AAAA1111","envelope":"AQID","token":"x"}"""),
        )
        assertEquals(Decision.Invalid, CourierCarryCore.evaluate(extraKey, 0, NOW))

        // A TTL-elapsed parcel is Expired at the createdMs + TTL boundary.
        // A six-hour-old but TTL-live parcel is inside the full-TTL past window
        // and is Accept; only a future-dated parcel is OutsideWindow here.
        val expired = parcel(lamport = 2, createdMs = NOW - MAX_PARCEL_TTL_MS)
        assertEquals(Decision.Expired, CourierCarryCore.evaluate(expired, 0, NOW))
        val stillLive = parcel(lamport = 3, createdMs = NOW - CircleCore.REPLAY_WINDOW_MS - 1)
        assertEquals(Decision.Accept, CourierCarryCore.evaluate(stillLive, 0, NOW))
        val future = parcel(lamport = 4, createdMs = NOW + CircleCore.CLOCK_SKEW_MS + 1)
        assertEquals(Decision.OutsideWindow, CourierCarryCore.evaluate(future, 0, NOW))

        // A hand-edited expiry is Invalid, not Expired: the TTL is the only
        // expiry source, so a shortened or extended row is refused outright.
        val editedExpiry = parcel(lamport = 5, createdMs = NOW, expiresMs = NOW + 1_000L)
        assertEquals(Decision.Invalid, CourierCarryCore.evaluate(editedExpiry, 0, NOW + 2_000L))

        // Refused ids above reserve nothing; the distinct fresh id still passes,
        // then the same id is Duplicate even when the bag is reported full.
        assertEquals(Decision.Accept, CourierCarryCore.evaluate(good, 0, NOW))
        assertEquals(Decision.Duplicate, CourierCarryCore.evaluate(good, MAX_PARCELS, NOW))
    }

    @Test
    fun `evaluate window accepts TTL-live past parcels and rejects far-future and overflowing clocks`() {
        val p = parcel(lamport = 5)
        assertEquals(
            Decision.Accept,
            CourierCarryCore.evaluate(parcel(lamport = 6, createdMs = NOW), 0, NOW + CircleCore.REPLAY_WINDOW_MS + 1),
        )
        assertEquals(Decision.Expired, CourierCarryCore.evaluate(p, 0, Long.MAX_VALUE))
        assertEquals(Decision.OutsideWindow, CourierCarryCore.evaluate(parcel(lamport = 7), 0, Long.MIN_VALUE))
        assertEquals(Decision.OutsideWindow, CourierCarryCore.evaluate(parcel(lamport = 8), 0, 0L))
        assertEquals(Decision.OutsideWindow, CourierCarryCore.evaluate(parcel(lamport = 9), 0, -1L))

        // At the TTL boundary the Expired check wins before the window check:
        // nowMs == createdMs + MAX_PARCEL_TTL_MS is not live.
        assertEquals(
            Decision.Expired,
            CourierCarryCore.evaluate(
                parcel(lamport = 41, createdMs = NOW),
                0,
                NOW + MAX_PARCEL_TTL_MS,
            ),
        )
        assertEquals(
            Decision.Accept,
            CourierCarryCore.evaluate(parcel(lamport = 10, createdMs = NOW + CircleCore.CLOCK_SKEW_MS), 0, NOW),
        )
    }

    @Test
    fun `evaluate refuses an expiry outside the TTL bound and reserves nothing`() {
        val p = parcel(lamport = 11)
        val tooLate = p.copy(expiresMs = p.createdMs + MAX_PARCEL_TTL_MS + 1)
        assertEquals(Decision.Invalid, CourierCarryCore.evaluate(tooLate, 0, NOW))
        val tooEarly = p.copy(expiresMs = p.createdMs - 1L)
        assertEquals(Decision.Invalid, CourierCarryCore.evaluate(tooEarly, 0, NOW))
        assertEquals(Decision.Accept, CourierCarryCore.evaluate(p, 0, NOW))
    }

    // ---- quota and reservations -------------------------------------------

    @Test
    fun `a full bag refuses the offer without burning its id`() {
        val p = parcel(lamport = 12)
        assertEquals(Decision.Full, CourierCarryCore.evaluate(p, MAX_PARCELS, NOW))
        assertEquals(Decision.Full, CourierCarryCore.evaluate(p, MAX_PARCELS + 5, NOW))
        assertEquals(Decision.Accept, CourierCarryCore.evaluate(p, MAX_PARCELS - 1, NOW))
        assertEquals(Decision.Duplicate, CourierCarryCore.evaluate(p, 0, NOW))
    }

    @Test
    fun `fresh and evaluate share one bounded reservation set`() {
        val p = parcel(lamport = 13)
        assertTrue(CourierCarryCore.fresh(p.id, NOW))
        assertFalse(CourierCarryCore.fresh(p.id, NOW + 1))
        assertEquals(Decision.Duplicate, CourierCarryCore.evaluate(p, 0, NOW))

        CourierCarryCore.clear()
        assertEquals(Decision.Accept, CourierCarryCore.evaluate(p, 0, NOW))
        assertFalse(CourierCarryCore.fresh(p.id, NOW))
    }

    @Test
    fun `the 4097th fresh reservation evicts the oldest id`() {
        val oldest = "oldest-reservation"
        assertTrue(CourierCarryCore.fresh(oldest, NOW))
        for (i in 1 until MAX_SEEN) {
            assertTrue(CourierCarryCore.fresh("reservation-$i", NOW), "unexpected refusal at $i")
        }
        assertTrue(CourierCarryCore.fresh("reservation-$MAX_SEEN", NOW))

        assertEquals(
            Decision.Accept,
            CourierCarryCore.evaluate(parcel(id = oldest, lamport = 14), 0, NOW),
            "evicted id should be reservable again",
        )
    }

    // ---- TTL derivation ----------------------------------------------------

    @Test
    fun `parcelOf derives the TTL expiry and normalises the addressee`() {
        val lower = "a1b2c3"
        val event = parcelEvent(toFp = lower, lamport = 15)
        val p = CourierCarryCore.parcelOf(event)
        assertNotNull(p)
        assertEquals(event.id, p.id)
        assertEquals(lower.uppercase(), p.toFp)
        assertEquals(event.authorFp, p.fromFp)
        assertEquals(NOW, p.createdMs)
        assertEquals(NOW + MAX_PARCEL_TTL_MS, p.expiresMs)
        assertEquals(event, p.event)

        assertNull(CourierCarryCore.parcelOf(event.copy(type = CircleEvent.MESSAGE)))
        assertNull(CourierCarryCore.parcelOf(event.copy(cert = "")))
        assertNull(CourierCarryCore.parcelOf(event.copy(body = """{"toFp":"A","envelope":"AQID","extra":"x"}""")))
        assertNull(CourierCarryCore.parcelOf(parcelEvent(createdMs = Long.MAX_VALUE - 1_000L)))
    }

    // ---- fold --------------------------------------------------------------

    @Test
    fun `fold dedups by id and trims the earliest parcels by deterministic order`() {
        val all = orderedParcels(MAX_PARCELS + 1)
        var reversed = CarryState()
        for (p in all.asReversed()) reversed = CourierCarryCore.fold(reversed, p)
        assertEquals(MAX_PARCELS, reversed.parcels.size)
        assertFalse(all.first().id in reversed.parcels.map { it.id }, "earliest by order must be trimmed")
        assertTrue(all.last().id in reversed.parcels.map { it.id })

        var ascending = CarryState()
        for (p in all) ascending = CourierCarryCore.fold(ascending, p)
        assertEquals(reversed.parcels.map { it.id }.toSet(), ascending.parcels.map { it.id }.toSet())

        val again = CourierCarryCore.fold(reversed, all.last())
        assertEquals(reversed.parcels.map { it.id }, again.parcels.map { it.id })
    }

    // ---- select ------------------------------------------------------------

    @Test
    fun `select returns only live parcels for the normalised addressee in order`() {
        val early = parcel(lamport = 16, toFp = TARGET, createdMs = NOW + 1)
        val late = parcel(lamport = 17, toFp = TARGET, createdMs = NOW + 2)
        val other = parcel(lamport = 18, toFp = OTHER, createdMs = NOW + 3)
        val expired = parcel(lamport = 19, toFp = TARGET, createdMs = NOW + 4, expiresMs = NOW + 500L)
        val state = CarryState(listOf(late, expired, other, early))

        val forTarget = CourierCarryCore.select(state, TARGET.lowercase(), NOW + 1_000L)
        assertEquals(listOf(early.id, late.id), forTarget.map { it.id })
        assertEquals(listOf(other.id), CourierCarryCore.select(state, OTHER, NOW + 1_000L).map { it.id })
        assertEquals(emptyList<CourierParcel>(), CourierCarryCore.select(state, "CCCC3333", NOW + 1_000L))

        val atBoundary = CourierCarryCore.select(state, TARGET, expired.expiresMs)
        assertFalse(expired.id in atBoundary.map { it.id }, "nowMs == expiresMs is not live")
        assertEquals(emptyList<CourierParcel>(), CourierCarryCore.expire(CarryState(listOf(expired)), expired.expiresMs).parcels)
    }

    @Test
    fun `select is capped by limit after ordering`() {
        val all = (0 until 5).map { parcel(lamport = 20L + it.toLong(), createdMs = NOW + 1 + it.toLong()) }
        val state = CarryState(all)
        val firstTwo = CourierCarryCore.select(state, TARGET, NOW + 1L, limit = 2)
        assertEquals(listOf(all[0].id, all[1].id), firstTwo.map { it.id })
        assertEquals(5, CourierCarryCore.select(state, TARGET, NOW + 1L).size)
        assertEquals(emptyList<CourierParcel>(), CourierCarryCore.select(state, TARGET, NOW + 1L, limit = 0))
    }

    // ---- ack / expire ------------------------------------------------------

    @Test
    fun `ack removes named ids and leaves unknown ids and other parcels byte-equal`() {
        val a = parcel(lamport = 25)
        val b = parcel(lamport = 26)
        val state = CarryState(listOf(a, b))

        val acked = CourierCarryCore.ack(state, listOf(a.id, "not-in-bag"))
        assertEquals(listOf(b), acked.parcels)
        assertEquals(setOf(a.id, b.id), state.parcels.map { it.id }.toSet(), "ack must not mutate its input")
        assertEquals(state, CourierCarryCore.ack(state, emptyList()))
        assertEquals(CarryState(), CourierCarryCore.ack(state, listOf(a.id, b.id)))
    }

    @Test
    fun `expire drops reached expiries and leaves future parcels byte-equal`() {
        val live = parcel(lamport = 27)
        val atBoundary = parcel(lamport = 28, expiresMs = NOW + 1_000L)
        val old = parcel(lamport = 29, expiresMs = NOW - 1L)
        val state = CarryState(listOf(live, atBoundary, old))

        val kept = CourierCarryCore.expire(state, NOW + 1_000L)
        assertEquals(listOf(live), kept.parcels)
        assertEquals(3, state.parcels.size, "expire must not mutate its input")
        assertEquals(state, CourierCarryCore.expire(state, NOW - 2L))
    }

    // ---- merge -------------------------------------------------------------

    @Test
    fun `merge is commutative idempotent and keeps the bounded deterministic union`() {
        val aParcels = orderedParcels(4)
        val shared = aParcels[0]
        val bParcels = (4..6).map { parcel(lamport = 30L + it.toLong(), createdMs = NOW + 1 + it.toLong()) }
        val a = CarryState(aParcels)
        val b = CarryState(listOf(shared) + bParcels)

        val ab = CourierCarryCore.merge(a, b, NOW + 1L)
        val ba = CourierCarryCore.merge(b, a, NOW + 1L)
        assertEquals(ab, ba, "two carriers must converge byte-for-byte")
        assertEquals(7, ab.parcels.size, "the shared id is kept once")
        assertEquals(
            ab.parcels.map { it.id }.toSet(),
            CourierCarryCore.merge(ab, CarryState(), NOW + 1L).parcels.map { it.id }.toSet(),
        )
    }

    @Test
    fun `merge dedups duplicate ids by CircleCore order drops expired rows and trims`() {
        val winnerEvent = parcelEvent(lamport = 40)
        val loserEvent = winnerEvent.copy(lamport = 45, body = parcelBody(envelope = "AQIDBA=="))
        val winner = CourierCarryCore.parcelOf(winnerEvent)!!
        val loser = CourierCarryCore.parcelOf(loserEvent)!!
        val expired = parcel(lamport = 46, expiresMs = NOW + 100L)

        val merged = CourierCarryCore.merge(
            CarryState(listOf(loser, expired)),
            CarryState(listOf(winner)),
            NOW + 1_000L,
        )
        assertEquals(listOf(winner), merged.parcels)
        assertEquals(winnerEvent.body, merged.parcels.single().event.body)

        val many = orderedParcels(MAX_PARCELS + 1)
        val trimmed = CourierCarryCore.merge(CarryState(many), CarryState(), NOW + 1L)
        assertEquals(MAX_PARCELS, trimmed.parcels.size)
        assertFalse(many.first().id in trimmed.parcels.map { it.id })
        assertTrue(many.last().id in trimmed.parcels.map { it.id })
    }
}
