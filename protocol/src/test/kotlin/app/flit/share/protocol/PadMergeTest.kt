package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.circle.PadCore.PadLine
import app.flit.share.protocol.circle.PadCore.PadView
import app.flit.share.protocol.tls.TlsIdentity
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM pins for the `PadCore.merge` seam: the per-key greatest-order-key
 * rule re-applied to views already derived from separate signed event sets, the
 * `view(a + b) == merge(view(a), view(b))` equivalence for disjoint and
 * overlapping sets in either merge order, tombstone precedence, idempotence,
 * the `MAX_LINES` cap, and hand-built views that carry winner metadata.
 *
 * Events are minted by `CircleCore.sign` over a fixed `CircleEvent` whose id is
 * chosen by the test, so the order key `(lamport, authorFp, id)` is deterministic
 * and the bytes really are signed by a `TlsIdentity`. Nothing here starts a
 * socket, an Android class, a store or a clock sleep; all timestamps are fixed.
 */
class PadMergeTest {

    companion object {
        private const val CIRCLE = "circle-merge"
        private const val FIXED_MS = 1_700_000_000_000L

        // Shared across tests: two authors make the authorFp tie-break observable
        // without paying for an RSA key generation in every case.
        private val ALICE: TlsIdentity by lazy { TlsIdentity.generate() }
        private val BOB: TlsIdentity by lazy { TlsIdentity.generate() }
    }

    private fun put(key: String, text: String): String = PadCore.padPutBody(key, text)
    private fun del(key: String): String = PadCore.padDelBody(key)

    /** A real signed `pad` event with a caller-chosen id for a stable order key. */
    private fun padEvent(
        identity: TlsIdentity,
        id: String,
        body: String,
        lamport: Long,
        circleId: String = CIRCLE,
        createdMs: Long = FIXED_MS,
    ): CircleEvent = CircleCore.sign(
        CircleEvent(
            id = id,
            circleId = circleId,
            authorFp = identity.fingerprint,
            lamport = lamport,
            type = CircleEvent.PAD,
            body = body,
            sig = "",
            createdMs = createdMs,
            cert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
        ),
        identity,
    )

    private fun viewOf(events: Iterable<CircleEvent>): PadView = PadCore.view(events, CIRCLE)

    // ---- per-key rule across separate event sets ---------------------------

    @Test
    fun `merge picks the per-key greatest-order operation across separate event sets`() {
        val a = listOf(
            padEvent(ALICE, "a-1", put("alpha", "from-A-low"), 3L),
            padEvent(ALICE, "a-2", put("beta", "A-beta"), 2L),
            padEvent(ALICE, "a-3", put("gamma", "A-gamma"), 8L),
        )
        val b = listOf(
            padEvent(BOB, "b-1", put("alpha", "from-B-high"), 9L),
            padEvent(BOB, "b-2", put("beta", "B-beta-older"), 1L),
            padEvent(BOB, "b-3", put("delta", "B-delta"), 5L),
        )
        assertTrue(CircleCore.verify(a.first()), "the fold input is a genuinely signed pad event")
        assertTrue(CircleCore.verify(b.first()), "the fold input is a genuinely signed pad event")

        val merged = PadCore.merge(listOf(viewOf(a), viewOf(b)))

        assertEquals(listOf("alpha", "beta", "delta", "gamma"), merged.lines.map { it.key })
        assertEquals(4, merged.winners.size)
        assertEquals("from-B-high", merged.winners.getValue("alpha").text)
        assertEquals(9L, merged.winners.getValue("alpha").lamport)
        assertEquals(BOB.fingerprint, merged.winners.getValue("alpha").authorFp)
        assertEquals("b-1", merged.winners.getValue("alpha").id)
        assertEquals("A-beta", merged.winners.getValue("beta").text, "A's newer op beats B's older one")
        assertEquals(2L, merged.winners.getValue("beta").lamport)
        assertEquals("A-gamma", merged.winners.getValue("gamma").text)
        assertEquals("B-delta", merged.winners.getValue("delta").text)
        assertEquals(viewOf(a + b), merged)
    }

    @Test
    fun `view of disjoint event sets equals merge of their views in either order`() {
        val a = listOf(
            padEvent(ALICE, "d-1", put("k1", "v1"), 4L),
            padEvent(ALICE, "d-2", put("k3", "v3"), 6L),
        )
        val b = listOf(
            padEvent(BOB, "d-3", put("k2", "v2"), 5L),
            padEvent(BOB, "d-4", put("k4", "v4"), 7L),
        )
        val union = viewOf(a + b)
        val ab = PadCore.merge(listOf(viewOf(a), viewOf(b)))
        val ba = PadCore.merge(listOf(viewOf(b), viewOf(a)))

        assertEquals(listOf("k1", "k2", "k3", "k4"), union.lines.map { it.key })
        assertEquals(union, viewOf(b + a), "delivery order cannot change the union view")
        assertEquals(union, ab)
        assertEquals(union, ba)
        assertEquals(ab, ba)
    }

    @Test
    fun `view of overlapping event sets equals merge of their views in either order`() {
        val shared = padEvent(ALICE, "o-shared", put("shared", "common"), 10L)
        val a = listOf(
            shared,
            padEvent(ALICE, "o-1", put("keyA", "a-low"), 2L),
            padEvent(ALICE, "o-2", put("dupKey", "a-old"), 4L),
        )
        val b = listOf(
            shared,
            padEvent(BOB, "o-3", put("keyB", "b-low"), 3L),
            padEvent(BOB, "o-4", put("dupKey", "b-new"), 11L),
        )
        val union = viewOf(a + b)

        assertEquals(listOf("dupKey", "keyA", "keyB", "shared"), union.lines.map { it.key })
        assertEquals("b-new", union.winners.getValue("dupKey").text)
        assertEquals("common", union.winners.getValue("shared").text)
        assertEquals(union, viewOf(b + a))
        assertEquals(union, PadCore.merge(listOf(viewOf(a), viewOf(b))))
        assertEquals(union, PadCore.merge(listOf(viewOf(b), viewOf(a))))
    }

    @Test
    fun `equal lamports across views are resolved by author fingerprint then event id`() {
        val higherFp = maxOf(ALICE.fingerprint, BOB.fingerprint)
        val lowerFp = minOf(ALICE.fingerprint, BOB.fingerprint)
        val high = if (ALICE.fingerprint == higherFp) ALICE else BOB
        val low = if (ALICE.fingerprint == higherFp) BOB else ALICE
        val a = listOf(
            padEvent(low, "z-low-id", put("byAuthor", "lower-author"), 7L),
            padEvent(high, "z-id", put("byId", "loser-id"), 7L),
        )
        val b = listOf(
            padEvent(high, "a-id", put("byAuthor", "higher-author"), 7L),
            padEvent(high, "zz-id", put("byId", "winner-id"), 7L),
        )
        val merged = PadCore.merge(listOf(viewOf(a), viewOf(b)))

        assertEquals("higher-author", merged.winners.getValue("byAuthor").text)
        assertEquals(higherFp, merged.winners.getValue("byAuthor").authorFp)
        assertEquals(lowerFp, a.first().authorFp)
        assertEquals("winner-id", merged.winners.getValue("byId").text)
        assertEquals("zz-id", merged.winners.getValue("byId").id)
        assertEquals(merged, viewOf(a + b))
    }

    // ---- tombstones ---------------------------------------------------------

    @Test
    fun `a tombstone in one view beats an older put in the other`() {
        val a = listOf(padEvent(ALICE, "t-1", put("note", "older text"), 3L))
        val b = listOf(padEvent(BOB, "t-2", del("note"), 8L))
        val merged = PadCore.merge(listOf(viewOf(a), viewOf(b)))

        assertTrue(merged.lines.isEmpty(), "a tombstoned key is not a live line")
        assertEquals(1, merged.winners.size)
        val winner = merged.winners.getValue("note")
        assertTrue(winner.deleted)
        assertEquals("", winner.text)
        assertEquals(8L, winner.lamport)
        assertEquals("t-2", winner.id)
        assertEquals(viewOf(a + b), merged)
        assertEquals(merged, PadCore.merge(listOf(viewOf(b), viewOf(a))), "merge order cannot resurrect it")
    }

    @Test
    fun `an older tombstone in one view does not beat a newer put in the other`() {
        val a = listOf(padEvent(ALICE, "t-3", del("note"), 2L))
        val b = listOf(padEvent(BOB, "t-4", put("note", "newer text"), 9L))
        val merged = PadCore.merge(listOf(viewOf(a), viewOf(b)))

        assertEquals(listOf("note"), merged.lines.map { it.key })
        assertEquals("newer text", merged.lines.single().text)
        val winner = merged.winners.getValue("note")
        assertFalse(winner.deleted)
        assertEquals(9L, winner.lamport)
        assertEquals("t-4", winner.id)
        assertEquals(viewOf(a + b), merged)
        assertEquals(merged, PadCore.merge(listOf(viewOf(b), viewOf(a))))
    }

    // ---- idempotence and associativity -------------------------------------

    @Test
    fun `re-merging a merged view is idempotent`() {
        val a = listOf(
            padEvent(ALICE, "i-1", put("k1", "v1"), 3L),
            padEvent(ALICE, "i-2", del("k2"), 4L),
        )
        val b = listOf(
            padEvent(BOB, "i-3", put("k1", "v2"), 8L),
            padEvent(BOB, "i-4", put("k2", "v3"), 2L), // older than the delete
        )
        val va = viewOf(a)
        val vb = viewOf(b)
        val merged = PadCore.merge(listOf(va, vb))

        assertEquals(listOf("k1"), merged.lines.map { it.key })
        assertTrue(merged.winners.getValue("k2").deleted, "the tombstone survives a re-merge")
        assertEquals(merged, PadCore.merge(listOf(merged)))
        assertEquals(merged, PadCore.merge(listOf(merged, merged)))
        assertEquals(merged, PadCore.merge(listOf(va, merged, vb)))
        assertEquals(merged, PadCore.merge(listOf(merged, va)))
        assertEquals(merged, viewOf(a + b))
    }

    @Test
    fun `merging three views is associative and independent of nesting order`() {
        val a = listOf(
            padEvent(ALICE, "m-1", put("x", "a"), 2L),
            padEvent(ALICE, "m-2", put("y", "a"), 5L),
        )
        val b = listOf(
            padEvent(BOB, "m-3", put("x", "b"), 4L),
            padEvent(BOB, "m-4", put("z", "b"), 3L),
        )
        val c = listOf(
            padEvent(ALICE, "m-5", put("y", "c"), 9L),
            padEvent(BOB, "m-6", put("w", "c"), 1L),
        )
        val va = viewOf(a)
        val vb = viewOf(b)
        val vc = viewOf(c)
        val flat = PadCore.merge(listOf(va, vb, vc))

        assertEquals(flat, PadCore.merge(listOf(PadCore.merge(listOf(va, vb)), vc)))
        assertEquals(flat, PadCore.merge(listOf(va, PadCore.merge(listOf(vb, vc)))))
        assertEquals(flat, PadCore.merge(listOf(vc, vb, va)))
        assertEquals(flat, viewOf(a + b + c))
    }

    // ---- cap ---------------------------------------------------------------

    @Test
    fun `a merged view never exceeds MAX_LINES and keeps the lexicographically first keys`() {
        val total = PadCore.MAX_LINES + 32
        val parts = (0 until 4).map { part ->
            (part until total step 4).map { i ->
                padEvent(ALICE, "cap-$i", put("key-%03d".format(i), "value-$i"), i.toLong() + 1L)
            }
        }
        val merged = PadCore.merge(parts.map { viewOf(it) })

        assertEquals(PadCore.MAX_LINES, merged.lines.size)
        assertEquals(
            (0 until PadCore.MAX_LINES).map { "key-%03d".format(it) },
            merged.lines.map { it.key },
        )
        assertEquals(total, merged.winners.size, "winners keep order keys beyond the live-line cap")
        assertEquals(viewOf(parts.flatten()).lines, merged.lines)
        assertEquals(merged.lines, PadCore.merge(listOf(merged, merged, merged)).lines)
    }

    // ---- hand-built views --------------------------------------------------

    @Test
    fun `hand-built views merge by winners metadata including tombstones and order keys`() {
        val a = PadView(
            lines = listOf(
                PadLine("k1", "stale-line", 1L, "AA", "line-1"),
                PadLine("k2", "kept", 5L, "AA", "line-2"),
            ),
            winners = mapOf(
                "k1" to PadLine("k1", "winner-from-A", 20L, "BB", "win-1"),
                "k2" to PadLine("k2", "kept", 5L, "AA", "line-2"),
                "k3" to PadLine("k3", "", 7L, "AA", "win-3", true),
            ),
        )
        val b = PadView(
            lines = listOf(PadLine("k1", "loser-from-B", 19L, "ZZ", "win-b")),
            winners = mapOf(
                "k2" to PadLine("k2", "loser-from-B", 4L, "ZZ", "win-b2"),
                "k4" to PadLine("k4", "from-B", 6L, "CC", "win-b4"),
            ),
        )
        val merged = PadCore.merge(listOf(a, b))

        assertEquals(listOf("k1", "k2", "k4"), merged.lines.map { it.key })
        assertEquals("winner-from-A", merged.winners.getValue("k1").text)
        assertEquals(20L, merged.winners.getValue("k1").lamport)
        assertEquals("BB", merged.winners.getValue("k1").authorFp)
        assertEquals("win-1", merged.winners.getValue("k1").id)
        assertEquals("kept", merged.winners.getValue("k2").text)
        assertEquals(5L, merged.winners.getValue("k2").lamport, "A's winner beats B's older winner")
        assertEquals("from-B", merged.winners.getValue("k4").text)
        assertTrue(merged.winners.getValue("k3").deleted)
        assertFalse("k3" in merged.lines.map { it.key })
        assertEquals(4, merged.winners.size)
    }

    @Test
    fun `hand-built views without winners fall back to their lines and empty input is empty`() {
        val a = PadView(
            listOf(
                PadLine("k", "from-A", 9L, "AA", "id-1"),
                PadLine("same", "A", 9L, "AA", "id-1"),
            ),
        )
        val b = PadView(
            listOf(
                PadLine("k", "from-B-lower-author", 9L, "00", "id-9"),
                PadLine("same", "B-higher-id", 9L, "AA", "id-2"),
            ),
        )
        val merged = PadCore.merge(listOf(a, b))

        assertEquals("from-A", merged.winners.getValue("k").text, "authorFp breaks a lamport tie")
        assertEquals("B-higher-id", merged.winners.getValue("same").text, "id breaks a lamport+author tie")
        assertEquals(listOf("k", "same"), merged.lines.map { it.key })
        assertEquals(PadView(emptyList()), PadCore.merge(emptyList()))
    }
}
