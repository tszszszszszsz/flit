package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.circle.PadCore.PadLine
import app.flit.share.protocol.circle.PadCore.PadView
import app.flit.share.protocol.ideas.padsearch.PadSearch
import java.util.AbstractList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure JVM pins for [PadSearch]. Only the Kotlin test library and the protocol
 * sources are used: no fixture, no node, no transport and no device. The
 * assertions cover the matching rule, UTF-16 offsets into the original line
 * text, non-overlap, the query gate, all four caps, the limit clamp, ordering
 * and determinism, totality on hostile input, the `PadCore.view` seam and the
 * fixed point a repeated call reaches.
 */
class PadSearchTest {

    private fun line(key: String, text: String): PadLine = PadLine(key, text)

    private fun viewOf(vararg lines: PadLine): PadView = PadView(lines.toList())

    private fun result(
        hits: List<PadSearch.Hit>,
        scannedLines: Int,
        truncated: Boolean = false,
        queryRejected: Boolean = false,
    ): PadSearch.Result = PadSearch.Result(hits, scannedLines, truncated, queryRejected)

    // ---- matching ----------------------------------------------------------

    @Test
    fun `matching is case-insensitive for ASCII and finds every plain substring`() {
        val view = viewOf(line("k", "Alpha beta ALPHA beta alpha"))

        assertEquals(
            listOf(PadSearch.Hit(0, "k", 6, 10), PadSearch.Hit(0, "k", 17, 21)),
            PadSearch.search(view, "beta").hits,
        )
        assertEquals(
            listOf(
                PadSearch.Hit(0, "k", 0, 5),
                PadSearch.Hit(0, "k", 11, 16),
                PadSearch.Hit(0, "k", 22, 27),
            ),
            PadSearch.search(view, "ALpHa").hits,
        )
        assertEquals(result(emptyList(), 1), PadSearch.search(view, "gamma"))

        val whole = PadSearch.search(view, "Alpha beta ALPHA beta alpha")
        assertEquals(listOf(PadSearch.Hit(0, "k", 0, 27)), whole.hits)
    }

    @Test
    fun `non-ASCII case is ignored without Unicode normalization or length-changing folds`() {
        val view = viewOf(line("greek", "ΛΟΓΟΣ logos λογος"))
        assertEquals(
            listOf(PadSearch.Hit(0, "greek", 0, 5), PadSearch.Hit(0, "greek", 12, 17)),
            PadSearch.search(view, "λογος").hits,
        )
        assertEquals(
            listOf(PadSearch.Hit(0, "greek", 0, 5), PadSearch.Hit(0, "greek", 12, 17)),
            PadSearch.search(view, "ΛΟΓΟΣ").hits,
        )
        assertEquals(
            result(emptyList(), 1),
            PadSearch.search(viewOf(line("greekOnly", "ΛΟΓΟΣ")), "logos"),
        )

        // "é" and "e" + combining acute are different code-unit sequences here.
        assertEquals(
            result(emptyList(), 1),
            PadSearch.search(viewOf(line("combining", "cafe\u0301")), "\u00e9"),
        )
        // Kotlin's ignoreCase compares code units: sharp s does not fold to ss.
        assertEquals(
            listOf(PadSearch.Hit(0, "sharp", 8, 14)),
            PadSearch.search(viewOf(line("sharp", "STRASSE straße")), "straße").hits,
        )
    }

    @Test
    fun `offsets are UTF-16 code units into the original text not a folded copy`() {
        val text = "\uD83D\uDE42ab\uD83D\uDE42cat"
        val view = viewOf(line("emoji", text))

        val cat = PadSearch.search(view, "cat")
        assertEquals(listOf(PadSearch.Hit(0, "emoji", 6, 9)), cat.hits)
        assertEquals("cat", text.substring(cat.hits.single().start, cat.hits.single().end))

        assertEquals(listOf(PadSearch.Hit(0, "emoji", 2, 4)), PadSearch.search(view, "ab").hits)
        assertEquals(
            listOf(PadSearch.Hit(0, "emoji", 0, 2), PadSearch.Hit(0, "emoji", 4, 6)),
            PadSearch.search(view, "\uD83D\uDE42").hits,
        )
        // A 1:1 non-ASCII fold never shifts the reported range either.
        val accents = "École ÉCOLE"
        assertEquals(
            listOf(PadSearch.Hit(0, "accents", 0, 5), PadSearch.Hit(0, "accents", 6, 11)),
            PadSearch.search(viewOf(line("accents", accents)), "école").hits,
        )
    }

    @Test
    fun `hits on one line never overlap the previous match`() {
        fun hits(text: String, query: String) =
            PadSearch.search(viewOf(line("k", text)), query).hits

        assertEquals(
            listOf(PadSearch.Hit(0, "k", 0, 2), PadSearch.Hit(0, "k", 2, 4)),
            hits("aaaa", "aa"),
        )
        assertEquals(listOf(PadSearch.Hit(0, "k", 0, 3)), hits("ababa", "aba"))
        assertEquals(
            listOf(
                PadSearch.Hit(0, "k", 0, 3),
                PadSearch.Hit(0, "k", 3, 6),
                PadSearch.Hit(0, "k", 6, 9),
            ),
            hits("abcabcabc", "abc"),
        )
    }

    // ---- query gate --------------------------------------------------------

    @Test
    fun `empty and over-cap queries are rejected without a scan`() {
        val view = viewOf(line("k", "needle here"))

        assertEquals(result(emptyList(), 0, queryRejected = true), PadSearch.search(view, ""))
        assertEquals(
            result(emptyList(), 0, queryRejected = true),
            PadSearch.search(view, "x".repeat(PadSearch.MAX_QUERY_CHARS + 1)),
        )
        assertEquals(result(emptyList(), 1), PadSearch.search(view, "x".repeat(PadSearch.MAX_QUERY_CHARS)))

        val exact = viewOf(line("k", "x".repeat(PadSearch.MAX_QUERY_CHARS)))
        assertEquals(
            result(listOf(PadSearch.Hit(0, "k", 0, PadSearch.MAX_QUERY_CHARS)), 1),
            PadSearch.search(exact, "x".repeat(PadSearch.MAX_QUERY_CHARS)),
        )
    }

    @Test
    fun `whitespace-only queries are ordinary queries`() {
        val view = viewOf(line("k", "a b  c"))

        assertEquals(
            listOf(
                PadSearch.Hit(0, "k", 1, 2),
                PadSearch.Hit(0, "k", 3, 4),
                PadSearch.Hit(0, "k", 4, 5),
            ),
            PadSearch.search(view, " ").hits,
        )
        assertEquals(
            listOf(PadSearch.Hit(0, "k", 3, 5)),
            PadSearch.search(view, "  ").hits,
        )
        assertEquals(
            listOf(PadSearch.Hit(0, "k", 1, 2)),
            PadSearch.search(viewOf(line("k", "x\ty")), "\t").hits,
        )
    }

    // ---- caps --------------------------------------------------------------

    @Test
    fun `text scanning is capped at 4096 UTF-16 units and marks truncation`() {
        val boundary = PadSearch.MAX_TEXT_SCAN_CHARS
        assertEquals(
            result(listOf(PadSearch.Hit(0, "k", boundary - 1, boundary)), 1),
            PadSearch.search(viewOf(line("k", "z".repeat(boundary - 1) + "Q")), "Q"),
        )
        assertEquals(
            result(emptyList(), 1, truncated = true),
            PadSearch.search(viewOf(line("k", "z".repeat(boundary) + "Q")), "Q"),
        )
        assertEquals(
            result(listOf(PadSearch.Hit(0, "k", boundary - 1, boundary)), 1, truncated = true),
            PadSearch.search(viewOf(line("k", "z".repeat(boundary - 1) + "Q" + "z".repeat(10))), "Q"),
        )
        // A match starting inside the window but ending past it is not returned.
        assertEquals(
            result(emptyList(), 1, truncated = true),
            PadSearch.search(viewOf(line("k", "z".repeat(boundary - 1) + "NEEDLE")), "NEEDLE"),
        )
        assertEquals(
            result(emptyList(), 1, truncated = true),
            PadSearch.search(viewOf(line("k", "z".repeat(boundary - 1) + "AB")), "ABC"),
        )
    }

    @Test
    fun `scanning is capped at 256 leading lines and marks truncation`() {
        val cap = PadSearch.MAX_LINES_SCANNED
        val hiddenTail = List(cap + 1) { i ->
            if (i == cap) line("last", "target") else line("k$i", "none")
        }
        assertEquals(
            result(emptyList(), cap, truncated = true),
            PadSearch.search(PadView(hiddenTail), "target"),
        )

        val lastInside = List(cap) { i ->
            if (i == cap - 1) line("last", "target") else line("k$i", "none")
        }
        assertEquals(
            result(listOf(PadSearch.Hit(cap - 1, "last", 0, 6)), cap),
            PadSearch.search(PadView(lastInside), "target"),
        )
    }

    @Test
    fun `results are capped at 64 hits and a hidden later match marks truncation`() {
        val cap = PadSearch.MAX_HITS
        val withTail = List(cap + 1) { i -> line("k$i", "hit") }
        val capped = PadSearch.search(PadView(withTail), "hit")
        assertEquals(cap, capped.hits.size)
        assertEquals(cap + 1, capped.scannedLines)
        assertTrue(capped.truncated)
        assertEquals(PadSearch.Hit(cap - 1, "k${cap - 1}", 0, 3), capped.hits.last())

        val exact = List(cap) { i -> line("k$i", "hit") }
        assertEquals(
            result((0 until cap).map { PadSearch.Hit(it, "k$it", 0, 3) }, cap),
            PadSearch.search(PadView(exact), "hit"),
        )
    }

    @Test
    fun `limit is clamped into zero through 64`() {
        val view = PadView(List(PadSearch.MAX_HITS + 1) { i -> line("k$i", "hit") })

        for (small in listOf(-5, 0, Int.MIN_VALUE)) {
            assertEquals(
                result(emptyList(), 0, truncated = true),
                PadSearch.search(view, "hit", small),
                "limit $small must return no hits and no scan",
            )
        }
        for (large in listOf(PadSearch.MAX_HITS + 1, Int.MAX_VALUE)) {
            val r = PadSearch.search(view, "hit", large)
            assertEquals(PadSearch.MAX_HITS, r.hits.size)
            assertEquals(PadSearch.MAX_HITS + 1, r.scannedLines)
            assertTrue(r.truncated)
        }
        val one = PadSearch.search(view, "hit", 1)
        assertEquals(listOf(PadSearch.Hit(0, "k0", 0, 3)), one.hits)
        assertEquals(PadSearch.MAX_HITS + 1, one.scannedLines)
        assertTrue(one.truncated)
    }

    // ---- order, fixed point and totality ----------------------------------

    @Test
    fun `hits follow view order with ascending offsets inside each line`() {
        val view = viewOf(
            line("z", "find find"),
            line("a", "find"),
            line("m", "xxfindyy find"),
        )
        assertEquals(
            listOf(
                PadSearch.Hit(0, "z", 0, 4),
                PadSearch.Hit(0, "z", 5, 9),
                PadSearch.Hit(1, "a", 0, 4),
                PadSearch.Hit(2, "m", 2, 6),
                PadSearch.Hit(2, "m", 9, 13),
            ),
            PadSearch.search(view, "find").hits,
        )
    }

    @Test
    fun `repeated calls reach the same fixed point with no retained state`() {
        val view = viewOf(line("a", "needle here"), line("b", "other NEEDLE"))
        val first = PadSearch.search(view, "needle")

        repeat(4) { assertEquals(first, PadSearch.search(view, "needle")) }
        assertFalse(first.queryRejected)
        assertFalse(first.truncated)

        // Interleaved work with other queries/limits cannot colour the answer.
        PadSearch.search(view, "other", 1)
        PadSearch.search(view, "")
        PadSearch.search(view, "needle", Int.MAX_VALUE)
        assertEquals(first, PadSearch.search(view, "needle"))
        assertEquals(first, PadSearch.search(PadView(view.lines.toList(), view.winners), "needle"))
    }

    /** A finite size whose iterator throws on the first `get`. */
    private class ThrowingGet : AbstractList<PadLine>() {
        override val size: Int get() = 3
        override fun get(index: Int): PadLine = throw IllegalStateException("hostile get")
    }

    /** A collection that throws as soon as the line count is requested. */
    private class ThrowingSize : AbstractList<PadLine>() {
        override val size: Int get() = throw IllegalStateException("hostile size")
        override fun get(index: Int): PadLine = PadLine("k", "hit")
    }

    @Test
    fun `hostile line collections yield a bounded truncated result instead of throwing`() {
        val failed = result(emptyList(), 0, truncated = true)
        assertEquals(failed, PadSearch.search(PadView(ThrowingGet()), "hit"))
        assertEquals(failed, PadSearch.search(PadView(ThrowingSize()), "hit"))
        // The gate still wins over hostile data.
        assertEquals(
            result(emptyList(), 0, queryRejected = true),
            PadSearch.search(PadView(ThrowingGet()), ""),
        )
    }

    @Test
    fun `lone surrogates empty keys and blank text stay total`() {
        val view = viewOf(
            line("", "\uD800abc\uDC00"),
            line("k", ""),
            line("j", "\uD800\uD800"),
        )
        assertEquals(
            result(listOf(PadSearch.Hit(0, "", 1, 4)), 3),
            PadSearch.search(view, "abc"),
        )
        assertEquals(3, PadSearch.search(view, "\uD800").scannedLines)
        assertEquals(result(emptyList(), 3), PadSearch.search(view, "missing"))
        assertEquals(result(emptyList(), 0, truncated = true), PadSearch.search(view, "abc", 0))
    }

    @Test
    fun `search never mutates the supplied view`() {
        val live = arrayListOf(line("b", "hit hit"), line("a", "hit"))
        val winners = LinkedHashMap<String, PadLine>().apply {
            put("a", line("a", "hit").copy(lamport = 3L, id = "e-1"))
        }
        val view = PadView(live, winners)
        val liveBefore = live.toList()
        val winnersBefore = winners.toList()

        val capped = PadSearch.search(view, "hit", 1)
        val full = PadSearch.search(view, "hit")

        assertSame(live, view.lines)
        assertSame(winners, view.winners)
        assertEquals(liveBefore, view.lines.toList())
        assertEquals(winnersBefore, winners.toList())
        assertEquals(2, capped.scannedLines, "a full cap does not stop the line scan")
        assertEquals(3, full.hits.size)
    }

    // ---- seam and constants ------------------------------------------------

    @Test
    fun `searches a view folded by PadCore from pad events`() {
        fun put(id: String, key: String, text: String, lamport: Long = 1L, circleId: String = "c") =
            CircleEvent(
                id = id, circleId = circleId, authorFp = "fp", lamport = lamport,
                type = CircleEvent.PAD, body = PadCore.padPutBody(key, text),
                sig = "", createdMs = 1L,
            )

        val events = listOf(
            put("e1", "b", "old beta"),
            put("e2", "a", "Beta alpha"),
            put("e3", "b", "final beta", lamport = 2L),
            put("e4", "c", "beta gone"),
            CircleEvent(
                id = "e5", circleId = "c", authorFp = "fp", lamport = 2L,
                type = CircleEvent.PAD, body = PadCore.padDelBody("c"),
                sig = "", createdMs = 1L,
            ),
            put("e6", "a", "foreign beta", circleId = "other"),
            CircleEvent(
                id = "e7", circleId = "c", authorFp = "fp", lamport = 3L,
                type = CircleEvent.MESSAGE, body = """{"text":"beta"}""",
                sig = "", createdMs = 1L,
            ),
            put("e8", "d", "ignored").copy(
                body = """{"op":"put","key":"d","text":"beta but malformed","extra":1}""",
            ),
        )
        val view = PadCore.view(events, "c")
        assertEquals(listOf("a", "b"), view.lines.map { it.key })

        assertEquals(
            result(
                listOf(PadSearch.Hit(0, "a", 0, 4), PadSearch.Hit(1, "b", 6, 10)),
                2,
            ),
            PadSearch.search(view, "beta"),
        )
        assertEquals(
            listOf(PadSearch.Hit(0, "a", 0, 4)),
            PadSearch.search(view, "beta", 1).hits,
        )
    }

    @Test
    fun `declared caps are the pad core limits with the stated exact values`() {
        assertEquals(PadCore.MAX_KEY_CHARS, PadSearch.MAX_QUERY_CHARS)
        assertEquals(PadCore.MAX_TEXT_CHARS, PadSearch.MAX_TEXT_SCAN_CHARS)
        assertEquals(PadCore.MAX_LINES, PadSearch.MAX_LINES_SCANNED)

        assertEquals(128, PadSearch.MAX_QUERY_CHARS)
        assertEquals(4096, PadSearch.MAX_TEXT_SCAN_CHARS)
        assertEquals(256, PadSearch.MAX_LINES_SCANNED)
        assertEquals(64, PadSearch.MAX_HITS)
    }
}
