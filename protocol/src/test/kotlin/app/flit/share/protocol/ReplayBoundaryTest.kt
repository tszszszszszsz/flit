package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial replay-boundary tests for [CircleCore]. The failure being pinned
 * down is eviction of a LIVE replay record: a flood of fresh ids used to drop
 * the oldest id from the bounded map even while that id's signed event could
 * still pass the window, after which a captured event was accepted again.
 * Every clock here is fixed; no route, Gradle task, socket or Android class is
 * involved.
 */
class ReplayBoundaryTest {

    private val identities = mutableListOf<TlsIdentity>()
    private val retainMs = CircleCore.REPLAY_WINDOW_MS + CircleCore.CLOCK_SKEW_MS

    @BeforeTest
    fun resetState() {
        CircleCore.clear()
        identities.clear()
    }

    @AfterTest
    fun forgetGeneratedPins() {
        identities.forEach { TlsIdentity.forgetPin(it.fingerprint) }
        identities.clear()
        CircleCore.clear()
    }

    private fun identity(): TlsIdentity = TlsIdentity.generate().also { identities += it }

    private fun message(signer: TlsIdentity, createdMs: Long): CircleEvent = CircleCore.create(
        CIRCLE, signer.fingerprint, signer, CircleEvent.MESSAGE,
        """{"text":"boundary"}""", 0L, createdMs,
    )

    private fun proofBody(signer: TlsIdentity, invite: String, createdMs: Long = NOW): String =
        CircleCore.joinProofBody(
            HOST, CIRCLE, invite, signer.fingerprint, CircleCore.joinProofBucket(createdMs),
        )

    private fun proofEvent(signer: TlsIdentity, body: String, createdMs: Long = NOW): CircleEvent =
        CircleCore.create(CIRCLE, signer.fingerprint, signer, CircleEvent.JOIN, body, 0L, createdMs)

    private fun joinRequest(
        signer: TlsIdentity,
        event: CircleEvent,
        invite: String = INVITE,
    ): CircleRequest = CircleRequest(CircleRequest.ACTION_JOIN, CIRCLE, invite, signer.fingerprint, listOf(event))

    // ---- live reservations are never evicted by a flood --------------------

    @Test
    fun `a flood of live ids does not evict an in-window replay record`() {
        val signer = identity()
        val live = message(signer, NOW)
        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(live, NOW))

        // One slot is taken by `live`; fill the remaining MAX_SEEN - 1 slots.
        for (i in 0 until CircleCore.MAX_SEEN - 1) {
            assertEquals(
                CircleCore.Decision.Accept,
                CircleCore.evaluate(live.copy(id = "flood-$i"), NOW),
                "flood id $i was refused before the set was full",
            )
        }
        assertEquals(
            CircleCore.Decision.Full,
            CircleCore.evaluate(live.copy(id = "overflow"), NOW),
            "the set admitted a newcomer instead of protecting live ids",
        )
        assertFalse(CircleCore.fresh("another", NOW), "a saturated set admitted a new key")

        assertEquals(
            CircleCore.Decision.Duplicate,
            CircleCore.evaluate(live, NOW),
            "the in-window replay record was evicted by the flood",
        )
        assertEquals(
            CircleCore.Decision.Duplicate,
            CircleCore.evaluate(live.copy(id = "flood-0"), NOW),
            "a flooded in-window id lost its replay record",
        )
    }

    @Test
    fun `expired reservations are reclaimed only past the retain deadline`() {
        repeat(CircleCore.MAX_SEEN) { i ->
            assertTrue(CircleCore.fresh("hold-$i", NOW))
        }
        val atDeadline = NOW + retainMs
        assertFalse(
            CircleCore.fresh("edge", atDeadline),
            "a reservation expired while its signed event was still in-window",
        )
        assertTrue(
            CircleCore.fresh("edge", atDeadline + 1),
            "expired slots were not reclaimed after the retain deadline",
        )
        assertEquals(
            CircleCore.Decision.Accept,
            CircleCore.evaluate(message(identity(), atDeadline + 1).copy(id = "hold-0"), atDeadline + 1),
            "a reclaimed id should be reservable again",
        )
    }

    @Test
    fun `a future-skew event stays reserved for the whole retain window`() {
        val signer = identity()
        // createdMs at the maximum accepted future skew stays in-window until
        // now - createdMs exceeds REPLAY_WINDOW_MS, i.e. at now + RETAIN_MS.
        val future = message(signer, NOW + CircleCore.CLOCK_SKEW_MS)
        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(future, NOW))
        assertEquals(
            CircleCore.Decision.Duplicate,
            CircleCore.evaluate(future, NOW + CircleCore.REPLAY_WINDOW_MS),
        )
        assertEquals(
            CircleCore.Decision.Duplicate,
            CircleCore.evaluate(future, NOW + retainMs),
            "future-skew id expired while its signed event could still pass the window",
        )
        assertFalse(CircleCore.fresh(future.id, NOW + retainMs), "duplicate key admitted at the deadline")
        assertTrue(CircleCore.fresh(future.id, NOW + retainMs + 1), "expired future-skew key not reclaimed")
    }

    // ---- JOIN proof single-use is keyed on the digest ----------------------

    @Test
    fun `a re-signed JOIN proof with a new id is still refused as replay`() {
        val signer = identity()
        val body = proofBody(signer, INVITE)
        // create() mints a fresh UUID and a fresh signature each time; the
        // proof body, and therefore its 32-byte digest, is identical.
        val first = proofEvent(signer, body)
        val reSigned = proofEvent(signer, body)

        assertNotEquals(first.id, reSigned.id, "fixture did not re-sign under a new id")
        assertEquals(first.body, reSigned.body)
        assertTrue(CircleCore.verify(first))
        assertTrue(CircleCore.verify(reSigned), "the re-signed proof must be cryptographically valid")

        assertNull(CircleCore.joinProofFailure(joinRequest(signer, first), HOST, NOW))
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(joinRequest(signer, reSigned), HOST, NOW),
            "re-signing the same proof under a new id replayed the JOIN",
        )
    }

    @Test
    fun `a captured JOIN proof stays replay-refused after a live-id flood`() {
        val signer = identity()
        val captured = proofEvent(signer, proofBody(signer, INVITE))
        assertNull(CircleCore.joinProofFailure(joinRequest(signer, captured), HOST, NOW))

        repeat(CircleCore.MAX_SEEN - 1) { i ->
            assertTrue(CircleCore.fresh("flood-$i", NOW))
        }
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(joinRequest(signer, captured), HOST, NOW),
            "the captured proof survived a flood of live ids",
        )

        // A never-seen digest must also fail closed while every slot is live.
        val unseen = proofEvent(signer, proofBody(signer, "invite-2"))
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(joinRequest(signer, unseen, "invite-2"), HOST, NOW),
            "a saturated replay set admitted a new proof",
        )
    }

    private companion object {
        const val HOST = "AAAA"
        const val CIRCLE = "circle-1"
        const val INVITE = "invite-1"
        const val NOW = 1_700_000_000_000L
    }
}
