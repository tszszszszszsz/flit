package app.flit.share

import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.circle.PadCore.PadLine
import app.flit.share.protocol.circle.PadCore.PadView
import app.flit.share.protocol.ideas.padsearch.PadSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure JVM pins for the Android-free pad UI projection. Nothing here touches
 * Compose, an Android context, the store or the signing path: only the mapping
 * from [PadView] to display rows, the two input-bound checks and the promise
 * that reading a view never writes to it. The search block pins the second
 * projection — [PadSearch] hits to display rows with offsets into the original
 * line text — and checks that rejected queries cannot start a scan or leak a
 * row.
 *
 * The error helpers are deliberately checked through [errorText] rather than
 * by exact message: the callable surface is a bound check, so what matters is
 * "accepted" versus "refused", not the wording shown in a text field. The
 * accepted/refused outcomes are cross-checked against [PadCore.padPutBody],
 * which enforces the same bounds at the only place a put body is minted.
 */
class PadUiTest {

    private fun viewOf(vararg lines: PadLine): PadView = PadView(lines.toList())

    private fun line(key: String, text: String = "text for $key"): PadLine = PadLine(key, text)

    /**
     * Normalise the two natural shapes of a bound check to a single answer:
     * null means "no error". A refusal is any non-blank string or Boolean true.
     */
    private fun errorText(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) "refused" else null
        is String -> value.takeIf { it.isNotBlank() }
        else -> value.toString().takeIf { it.isNotBlank() }
    }

    @Test
    fun `rows maps live lines to key text pairs in the view order`() {
        val view = viewOf(
            line("alpha", "first"),
            line("beta", "second line"),
            line("gamma", ""),
        )
        val pairs = PadUi.rows(view).map { it.key to it.text }

        assertEquals(
            listOf("alpha" to "first", "beta" to "second line", "gamma" to ""),
            pairs,
        )
        assertEquals(view.lines.map { it.key }, pairs.map { it.first })
    }

    @Test
    fun `rows is empty for an empty view and for a view holding only tombstones`() {
        assertEquals(emptyList<Any>(), PadUi.rows(PadView(emptyList())).map { it.key to it.text })

        // Tombstones stay in `winners` for merge; they are not display rows.
        val deleted = line("gone", "").copy(deleted = true, lamport = 7L, id = "del-1")
        val view = PadView(emptyList(), mapOf(deleted.key to deleted))
        assertEquals(emptyList<Any>(), PadUi.rows(view).map { it.key to it.text })
    }

    @Test
    fun `rows keeps keys and texts verbatim without trimming or rewriting`() {
        val keys = listOf(" spaced key ", "Ünicode/77", "a.b-c_d")
        val texts = listOf("  leading and trailing  ", "line\nwith newline", "tab\there")
        val view = PadView(keys.zip(texts).map { (k, t) -> line(k, t) })

        assertEquals(keys.zip(texts).sortedBy { it.first }, PadUi.rows(view).map { it.key to it.text })
        // rows sorts a hand-built view by key; the values stay verbatim.
        assertEquals(keys.sorted(), PadUi.rows(view).map { it.key })
    }

    @Test
    fun `rows never mutates the view list or its winning operations`() {
        val live = arrayListOf(line("b", "second"), line("a", "first"))
        val winner = line("a", "first").copy(lamport = 3L, id = "e-1")
        val winners = LinkedHashMap<String, PadLine>().apply { put("a", winner) }
        val view = PadView(live, winners)
        val liveBefore = live.toList()
        val winnersBefore = winners.toList()

        val first = PadUi.rows(view).map { it.key to it.text }
        val second = PadUi.rows(view).map { it.key to it.text }

        assertEquals(first, second)
        assertSame(live, view.lines)
        assertSame(winners, view.winners)
        assertEquals(liveBefore, view.lines.toList())
        assertEquals(winnersBefore, view.winners.toList())
        assertEquals("second", view.lines[0].text)
        assertEquals("first", view.lines[1].text)
    }

    @Test
    fun `keyError refuses blank and over-long keys but accepts the exact bound`() {
        assertEquals(null, errorText(PadUi.keyError("k")))
        assertEquals(null, errorText(PadUi.keyError("line key 42")))
        assertEquals(null, errorText(PadUi.keyError("k".repeat(PadCore.MAX_KEY_CHARS))))

        assertTrue(errorText(PadUi.keyError("")) != null, "empty key must be refused")
        assertTrue(errorText(PadUi.keyError("   ")) != null, "blank key must be refused")
        assertTrue(errorText(PadUi.keyError("\t\n")) != null, "whitespace key must be refused")
        assertTrue(
            errorText(PadUi.keyError("k".repeat(PadCore.MAX_KEY_CHARS + 1))) != null,
            "a key one character past MAX_KEY_CHARS must be refused",
        )
    }

    @Test
    fun `textError refuses blank and over-long text but accepts the exact bound`() {
        assertEquals(null, errorText(PadUi.textError("t")))
        assertEquals(null, errorText(PadUi.textError("a full sentence with spaces")))
        assertEquals(null, errorText(PadUi.textError("t".repeat(PadCore.MAX_TEXT_CHARS))))

        assertTrue(errorText(PadUi.textError("")) != null, "empty text must be refused")
        assertTrue(errorText(PadUi.textError("   ")) != null, "blank text must be refused")
        assertTrue(
            errorText(PadUi.textError("t".repeat(PadCore.MAX_TEXT_CHARS + 1))) != null,
            "text one character past MAX_TEXT_CHARS must be refused",
        )
    }

    @Test
    fun `the two bounds agree with the put body gate in PadCore`() {
        val accepted = listOf(
            "k" to "v",
            "k".repeat(PadCore.MAX_KEY_CHARS) to "t".repeat(PadCore.MAX_TEXT_CHARS),
        )
        for ((key, text) in accepted) {
            assertEquals(null, errorText(PadUi.keyError(key)), "key should be accepted by PadCore too")
            assertEquals(null, errorText(PadUi.textError(text)), "text should be accepted by PadCore too")
            assertTrue(PadCore.validPadBody(PadCore.padPutBody(key, text)))
        }

        val refused = listOf(
            "" to "v",
            "k".repeat(PadCore.MAX_KEY_CHARS + 1) to "v",
            "k" to "",
            "k" to "t".repeat(PadCore.MAX_TEXT_CHARS + 1),
        )
        for ((key, text) in refused) {
            val keyRefused = errorText(PadUi.keyError(key)) != null
            val textRefused = errorText(PadUi.textError(text)) != null
            assertTrue(keyRefused || textRefused, "at least one bound must refuse $key / ${text.take(8)}")
            assertFalse(
                runCatching { PadCore.padPutBody(key, text) }.isSuccess,
                "PadCore must refuse the same input",
            )
        }
    }

    @Test
    fun `bound checks are repeatable and do not mutate their inputs`() {
        val key = "stable key".repeat(3)
        val text = "stable text".repeat(3)
        val keyBefore = key
        val textBefore = text

        repeat(3) {
            assertEquals(errorText(PadUi.keyError(key)), errorText(PadUi.keyError(key)))
            assertEquals(errorText(PadUi.textError(text)), errorText(PadUi.textError(text)))
        }

        assertEquals(keyBefore, key)
        assertEquals(textBefore, text)
    }

    // ---- search projection ---------------------------------------------------

    @Test
    fun `search maps hits to rows carrying the key original text and offsets`() {
        val view = viewOf(
            line("alpha", "one HIT two HIT"),
            line("beta", "HIT three"),
            line("gamma", "four HIT"),
        )
        val projected = PadUi.search(view, "hit")
        val direct = PadSearch.search(view, "hit")

        assertEquals(direct.hits.size, projected.rows.size)
        assertEquals(direct.hits.map { it.key }, projected.rows.map { it.key })
        assertEquals(direct.hits.map { it.lineIndex }, projected.rows.map { it.lineIndex })
        assertEquals(
            direct.hits.map { it.start to it.end },
            projected.rows.map { it.start to it.end },
        )
        assertEquals(listOf("alpha", "alpha", "beta", "gamma"), projected.rows.map { it.key })
        for (row in projected.rows) {
            // The row carries the whole, unmodified line; offsets locate the match.
            assertEquals(view.lines[row.lineIndex].text, row.text)
            assertTrue(
                row.text.substring(row.start, row.end).equals("hit", ignoreCase = true),
                "offsets must slice the matched text out of the original line",
            )
        }
    }

    @Test
    fun `search offsets address UTF-16 original text without rewriting the view`() {
        val original = "\uD83D\uDE42 hit here" // one supplementary char = two code units
        val view = viewOf(line("emoji", original))
        val linesBefore = view.lines.toList()
        val winnersBefore = view.winners.toList()

        val row = PadUi.searchRows(view, "hit").single()

        assertEquals("emoji", row.key)
        assertEquals(original, row.text)
        assertEquals(3, row.start)
        assertEquals(6, row.end)
        assertEquals("hit", original.substring(row.start, row.end))
        assertEquals(linesBefore, view.lines.toList())
        assertEquals(winnersBefore, view.winners.toList())
    }

    @Test
    fun `searchRows is the rows half of search and honours the limit`() {
        val view = viewOf(line("many", "hit ".repeat(PadSearch.MAX_HITS + 2)))
        val two = PadUi.searchRows(view, "hit", limit = 2)

        assertEquals(2, two.size)
        assertEquals(listOf(0, 4), two.map { it.start })
        assertEquals(PadUi.search(view, "hit", limit = 2).rows, two)
        assertEquals(PadUi.searchRows(view, "hit"), searchRows(view, "hit"))
        assertTrue(PadUi.searchRows(view, "hit", limit = -5).isEmpty())
        assertEquals(
            PadSearch.MAX_HITS,
            PadUi.searchRows(view, "hit", limit = Int.MAX_VALUE).size,
        )
    }

    @Test
    fun `search forwards PadSearch status flags and stays bounded`() {
        val view = viewOf(line("many", "hit ".repeat(PadSearch.MAX_HITS + 2)))
        val projected = PadUi.search(view, "hit")
        val direct = PadSearch.search(view, "hit")

        assertEquals(direct.scannedLines, projected.scannedLines)
        assertEquals(direct.truncated, projected.truncated)
        assertEquals(direct.queryRejected, projected.queryRejected)
        assertTrue(projected.truncated)
        assertEquals(PadSearch.MAX_HITS, projected.rows.size)

        val none = PadUi.search(view, "hit", limit = 0)
        assertTrue(none.rows.isEmpty())
        assertEquals(0, none.scannedLines)
        assertTrue(none.truncated)
        assertFalse(none.queryRejected)
    }

    @Test
    fun `invalid queries are contained with no rows and no scan`() {
        val view = viewOf(line("k", "hit here"))
        val invalid = listOf("", "x".repeat(PadSearch.MAX_QUERY_CHARS + 1))

        for (query in invalid) {
            val projected = PadUi.search(view, query)
            assertTrue(projected.queryRejected, "query should be rejected")
            assertTrue(projected.rows.isEmpty(), "a rejected query must draw no rows")
            assertEquals(0, projected.scannedLines, "a rejected query must not scan")
            assertFalse(projected.truncated)
            assertTrue(PadUi.searchRows(view, query).isEmpty())
        }

        // Whitespace is an ordinary query, not an invalid one.
        val spaces = viewOf(line("k", "a  b"))
        assertFalse(PadUi.search(spaces, " ").queryRejected)
        assertEquals(2, PadUi.searchRows(spaces, " ").size)
    }

    @Test
    fun `search and rejected queries leave the view untouched and stay repeatable`() {
        val live = arrayListOf(line("k", "hit here"))
        val winner = line("k", "hit here").copy(lamport = 3L, id = "e-1")
        val winners = LinkedHashMap<String, PadLine>().apply { put("k", winner) }
        val view = PadView(live, winners)
        val linesBefore = live.toList()
        val winnersBefore = winners.toList()

        val first = PadUi.search(view, "hit")
        val again = PadUi.search(view, "hit")
        val refused = PadUi.search(view, "")
        val refusedAgain = PadUi.search(view, "")

        assertEquals(first, again)
        assertEquals(refused, refusedAgain)
        assertTrue(refused.rows.isEmpty())
        assertSame(live, view.lines)
        assertSame(winners, view.winners)
        assertEquals(linesBefore, view.lines.toList())
        assertEquals(winnersBefore, view.winners.toList())
        assertEquals("hit here", view.lines[0].text)
    }

    @Test
    fun `a hostile view is contained by the search projection`() {
        val hostile = object : AbstractList<PadLine>() {
            override val size: Int get() = 3
            override fun get(index: Int): PadLine = throw IllegalStateException("hostile list")
        }
        val projected = PadUi.search(PadView(hostile), "x")

        assertTrue(projected.rows.isEmpty())
        assertEquals(0, projected.scannedLines)
        assertTrue(projected.truncated)
        assertFalse(projected.queryRejected)
    }
}
