package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.circle.PadCore.PadLine
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM pins for [PadCore.view]. Every event is minted with
 * [CircleCore.create] or re-signed with [CircleCore.sign] using a real
 * [TlsIdentity], but the fold under test neither verifies signatures nor pins
 * certificates: it is a deterministic reduction over the events supplied.
 * No socket, Android class, store, clock sleep or device is involved.
 *
 * Coverage: the `pad`/circle/body filter; [CircleCore.dedup] without
 * [CircleCore.verify]; the greatest `(lamport, authorFp, id)` conflict rule
 * (put, tombstoning del, inert older del and tie-breaks); lexicographic
 * [PadCore.PadView.lines] capped at [PadCore.MAX_LINES]; delivery-order
 * independence and re-fold idempotence; and tombstone/order metadata carried in
 * [PadCore.PadView.winners].
 */
class PadViewTest {

    private val identities = mutableListOf<TlsIdentity>()

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

    /** A fresh signed put, with a generated id and the signer's own fingerprint. */
    private fun mintedPut(
        identity: TlsIdentity,
        key: String,
        text: String,
        lamport: Long = 1L,
        circleId: String = CIRCLE,
        createdMs: Long = FIXED_MS,
    ): CircleEvent = CircleCore.create(
        circleId = circleId,
        authorFp = identity.fingerprint,
        identity = identity,
        type = CircleEvent.PAD,
        body = PadCore.padPutBody(key, text),
        lamport = lamport,
        createdMs = createdMs,
    )

    /** A caller-owned signed event, so id and authorFp can drive the tie-breaks. */
    private fun signed(
        identity: TlsIdentity,
        body: String,
        id: String,
        circleId: String = CIRCLE,
        authorFp: String = identity.fingerprint,
        lamport: Long = 1L,
        type: String = CircleEvent.PAD,
        createdMs: Long = FIXED_MS,
    ): CircleEvent = CircleCore.sign(
        CircleEvent(
            id = id,
            circleId = circleId,
            authorFp = authorFp,
            lamport = lamport,
            type = type,
            body = body,
            sig = "",
            createdMs = createdMs,
        ),
        identity,
    )

    private fun signedPut(
        identity: TlsIdentity,
        id: String,
        key: String,
        text: String,
        lamport: Long = 1L,
        authorFp: String = identity.fingerprint,
        circleId: String = CIRCLE,
    ): CircleEvent = signed(
        identity = identity,
        body = PadCore.padPutBody(key, text),
        id = id,
        circleId = circleId,
        authorFp = authorFp,
        lamport = lamport,
    )

    private fun signedDel(
        identity: TlsIdentity,
        id: String,
        key: String,
        lamport: Long = 1L,
        authorFp: String = identity.fingerprint,
        circleId: String = CIRCLE,
    ): CircleEvent = signed(
        identity = identity,
        body = PadCore.padDelBody(key),
        id = id,
        circleId = circleId,
        authorFp = authorFp,
        lamport = lamport,
    )

    // ---- filter ------------------------------------------------------------

    @Test
    fun `view keeps only pad events for the target circle`() {
        val signer = identity()
        val kept = mintedPut(signer, "kept", "in this circle", lamport = 2L)

        // A non-pad verb carrying a pad-shaped body must still be ignored.
        val otherVerb = CircleCore.create(
            circleId = CIRCLE,
            authorFp = signer.fingerprint,
            identity = signer,
            type = CircleEvent.MESSAGE,
            body = PadCore.padPutBody("sneaky", "not a pad line"),
            lamport = 3L,
            createdMs = FIXED_MS,
        )
        val foreign = mintedPut(signer, "foreign", "another circle", circleId = "elsewhere")

        assertTrue(CircleCore.verify(kept))
        val view = PadCore.view(listOf(otherVerb, foreign, kept), CIRCLE)

        assertEquals(listOf("kept"), view.lines.map { it.key })
        assertEquals("in this circle", view.lines.single().text)
        assertEquals(setOf("kept"), view.winners.keys)
        assertEquals(kept.id, view.winners.getValue("kept").id)
    }

    @Test
    fun `view ignores bodies the pad gate refuses without throwing`() {
        val signer = identity()
        val template = mintedPut(signer, "ignored", "valid")

        val badBodies = listOf(
            """{"op":"put","key":"x","text":"","extra":"y"}""", // blank text + extra key
            """{"op":"set","key":"x","text":"y"}""", // unknown verb
            """{"op":"put","key":"x"}""", // missing text
            """{"op":"put","key":"x","text":7}""", // non-string text
            """{"op":"put","key":"","text":"y"}""", // blank key
            """{"op":"del","key":"x","text":"y"}""", // extra key on a del
            """{"op":"del"}""", // missing key
            "{not json",
            "   ",
            """{"op":"put","key":"x","text":"${"t".repeat(PadCore.MAX_TEXT_CHARS + 1)}"}""",
            """{"op":"put","key":"${"k".repeat(PadCore.MAX_KEY_CHARS + 1)}","text":"y"}""",
        )
        val events = badBodies.mapIndexed { i, body ->
            template.copy(id = "bad-$i", body = body)
        }

        assertTrue(PadCore.view(events, CIRCLE).lines.isEmpty())
        assertTrue(PadCore.view(events, CIRCLE).winners.isEmpty())
        assertEquals(
            PadCore.view(emptyList(), CIRCLE),
            PadCore.view(events, CIRCLE),
        )
    }

    // ---- dedup -------------------------------------------------------------

    @Test
    fun `view applies dedup so a repeated id counts once without verifying`() {
        val signer = identity()
        // Same id, different body: dedup keeps the smaller by CircleCore.ORDER
        // (lamport 1), so the later lamport-9 replay cannot overwrite the text.
        val first = signedPut(signer, "dup", "k", "first", lamport = 1L)
        val replay = signedPut(signer, "dup", "k", "replayed", lamport = 9L)

        // A signed event claiming another author's fingerprint fails verify,
        // yet the fold still includes it: view never calls CircleCore.verify.
        val forged = signedPut(signer, "forged", "m", "forged text", authorFp = "DEADBEEF")
        assertTrue(CircleCore.verify(first))
        assertFalse(CircleCore.verify(forged))

        val view = PadCore.view(listOf(first, replay, forged), CIRCLE)
        assertEquals(listOf("k", "m"), view.lines.map { it.key })
        assertEquals("first", view.winners.getValue("k").text)
        assertEquals(first.id, view.winners.getValue("k").id)
        assertEquals("forged text", view.winners.getValue("m").text)

        assertEquals(
            view,
            PadCore.view(listOf(forged, replay, first, first.copy(), replay.copy()), CIRCLE),
        )
        assertEquals(
            PadCore.view(listOf(first), CIRCLE),
            PadCore.view(listOf(first, first.copy(), first.copy()), CIRCLE),
        )
    }

    // ---- conflict rule -----------------------------------------------------

    @Test
    fun `greatest lamport wins and an older del is inert`() {
        val signer = identity()
        val put = signedPut(signer, "p5", "k", "current", lamport = 5L)
        val staleDel = signedDel(signer, "d3", "k", lamport = 3L)

        assertEquals(
            "current",
            PadCore.view(listOf(put, staleDel), CIRCLE).lines.single().text,
            "a del older than the winning put must be inert",
        )

        val winningDel = signedDel(signer, "d6", "k", lamport = 6L)
        val tombstoned = PadCore.view(listOf(put, staleDel, winningDel), CIRCLE)
        assertTrue(tombstoned.lines.isEmpty(), "a greater del must tombstone the key")
        assertTrue(tombstoned.winners.getValue("k").deleted)
        assertEquals("", tombstoned.winners.getValue("k").text)

        val resurrect = signedPut(signer, "p7", "k", "back", lamport = 7L)
        val live = PadCore.view(listOf(put, staleDel, winningDel, resurrect), CIRCLE)
        assertEquals("back", live.lines.single().text)
        assertFalse(live.winners.getValue("k").deleted)
        assertEquals("p7", live.winners.getValue("k").id)
    }

    @Test
    fun `authorFp and id break ties for the greatest order key`() {
        val writers = listOf(identity(), identity()).sortedBy { it.fingerprint }
        val low = writers[0]
        val high = writers[1]

        // Equal lamport: the greater authorFp wins the put.
        val lowPut = signedPut(low, "fp-low", "kFp", "low fp", lamport = 4L)
        val highPut = signedPut(high, "fp-high", "kFp", "high fp", lamport = 4L)
        assertEquals("high fp", PadCore.view(listOf(lowPut, highPut), CIRCLE).lines.single().text)
        assertEquals(
            "high fp",
            PadCore.view(listOf(highPut, lowPut), CIRCLE).lines.single().text,
        )

        // Equal lamport and authorFp: the greater id wins.
        val lowId = signedPut(high, "a-id", "kId", "low id", lamport = 4L)
        val highId = signedPut(high, "z-id", "kId", "high id", lamport = 4L)
        assertEquals("high id", PadCore.view(listOf(lowId, highId), CIRCLE).lines.single().text)
        assertEquals("z-id", PadCore.view(listOf(highId, lowId), CIRCLE).lines.single().id)

        // The same tie-break decides del against put: higher authorFp del wins,
        // lower authorFp del is inert at the same lamport.
        val putLow = signedPut(low, "d-put-low", "kDel", "kept", lamport = 8L)
        val delHigh = signedDel(high, "d-del-high", "kDel", lamport = 8L)
        assertTrue(PadCore.view(listOf(putLow, delHigh), CIRCLE).lines.isEmpty())

        val putHigh = signedPut(high, "d-put-high", "kDel2", "kept", lamport = 8L)
        val delLow = signedDel(low, "d-del-low", "kDel2", lamport = 8L)
        assertEquals("kept", PadCore.view(listOf(delLow, putHigh), CIRCLE).lines.single().text)
    }

    // ---- view shape --------------------------------------------------------

    @Test
    fun `lines are lexicographic by key and capped at MAX_LINES`() {
        assertEquals(256, PadCore.MAX_LINES)
        val signer = identity()
        val keys = (0..PadCore.MAX_LINES + 4).map { "k%03d".format(it) }
        val shuffled = keys.shuffled(Random(2026L))
        val events = shuffled.mapIndexed { i, key ->
            signedPut(signer, "e-$i", key, "text-$key", lamport = 1L)
        }

        val view = PadCore.view(events, CIRCLE)
        assertEquals(PadCore.MAX_LINES, view.lines.size)
        assertEquals(keys.sorted().take(PadCore.MAX_LINES), view.lines.map { it.key })
        assertEquals(
            keys.map { "text-$it" }.sorted().take(PadCore.MAX_LINES),
            view.lines.map { it.text },
        )
        // The cap trims lines only; every winner stays in the map for merge.
        assertEquals(keys.size, view.winners.size)
    }

    @Test
    fun `opposite delivery orders and a re-fold give byte-identical views`() {
        val signer = identity()
        val events = listOf(
            mintedPut(signer, "b", "first", lamport = 1L),
            signedDel(signer, "d-c", "c", lamport = 2L),
            signedPut(signer, "p-b", "b", "final", lamport = 3L),
            mintedPut(signer, "a", "alpha", lamport = 1L),
            signedDel(signer, "d-a", "a", lamport = 0L), // older del is inert
        )

        val forward = PadCore.view(events, CIRCLE)
        // The del for "a" has lamport 0, older than its put, so it is inert.
        assertEquals(listOf("a", "b"), forward.lines.map { it.key })
        assertEquals("alpha", forward.lines.first { it.key == "a" }.text)
        assertEquals("final", forward.lines.first { it.key == "b" }.text)

        assertEquals(forward, PadCore.view(events.reversed(), CIRCLE))
        assertEquals(forward, PadCore.view(events.shuffled(Random(15L)), CIRCLE))
        assertEquals(forward, PadCore.view(events + events, CIRCLE))
        assertEquals(forward, PadCore.view(events + events.reversed(), CIRCLE))
        assertEquals(forward, PadCore.view(events, CIRCLE))
    }

    @Test
    fun `winners carry tombstones and the winning lamport authorFp and id`() {
        val signer = identity()
        val put = signedPut(signer, "put-1", "live", "visible", lamport = 7L)
        val del = signedDel(signer, "del-1", "gone", lamport = 9L)

        val view = PadCore.view(listOf(del, put), CIRCLE)
        assertEquals(setOf("live", "gone"), view.winners.keys)
        assertEquals(
            PadLine("live", "visible", 7L, signer.fingerprint, "put-1", false),
            view.winners.getValue("live"),
        )
        assertEquals(
            PadLine("gone", "", 9L, signer.fingerprint, "del-1", true),
            view.winners.getValue("gone"),
        )
        assertEquals(listOf("live"), view.lines.map { it.key })
        assertEquals(view.winners.getValue("live"), view.lines.single())
    }

    @Test
    fun `view is stateless and total on empty and hostile inputs`() {
        val signer = identity()
        val put = mintedPut(signer, "k", "value")

        assertEquals(EMPTY_VIEW, PadCore.view(emptyList(), CIRCLE))
        val once = PadCore.view(listOf(put), CIRCLE)

        // Folds for other circles and empty snapshots cannot colour a later fold.
        PadCore.view(listOf(put.copy(circleId = "elsewhere")), CIRCLE)
        PadCore.view(emptyList(), "elsewhere")
        PadCore.view(listOf(put.copy(body = "{not json")), CIRCLE)
        assertEquals(once, PadCore.view(listOf(put), CIRCLE))
        assertEquals("value", once.lines.single().text)
    }

    companion object {
        private const val CIRCLE = "circle-1"
        private const val FIXED_MS = 1_800_000_000_000L
        private val EMPTY_VIEW = PadCore.PadView(emptyList(), emptyMap())
    }
}
