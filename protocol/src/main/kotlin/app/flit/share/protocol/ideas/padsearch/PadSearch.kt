package app.flit.share.protocol.ideas.padsearch

import app.flit.share.protocol.circle.PadView

/**
 * Bounded, case-insensitive search over the lines of a supplied [PadView].
 *
 * This is a JVM-pure function of its arguments. It reads no global state, no
 * clock, no filesystem and no network, and it keeps nothing between calls. It
 * does not build highlighted text: each [Hit] carries only the index of the
 * matching line, that line's key, and the half-open UTF-16 offset range of the
 * match in the original, unmodified line text.
 *
 * The caller owns any verification or replay check that produced the view.
 * This object searches the data it is given; it makes no claim about where
 * that data came from.
 */
object PadSearch {
    /** Longest accepted query, in UTF-16 code units. */
    const val MAX_QUERY_CHARS = 128

    /** Only this leading prefix of any line text is scanned. */
    const val MAX_TEXT_SCAN_CHARS = 4 * 1024

    /** Only this many leading lines of the view are scanned. */
    const val MAX_LINES_SCANNED = 256

    /** Hard ceiling on returned hits, whatever [limit] asks for. */
    const val MAX_HITS = 64

    /**
     * One match. [start] is inclusive and [end] is exclusive; both are indices
     * into the original `PadLine.text`, counted in UTF-16 code units. Neither
     * field points into a lowercased or normalized copy. No matched text, markup
     * or snippet is retained here.
     */
    data class Hit(
        val lineIndex: Int,
        val key: String,
        val start: Int,
        val end: Int,
    )

    /**
     * Result of one search. [hits] are ordered by ascending [Hit.lineIndex],
     * then by ascending [Hit.start]. [scannedLines] counts the lines actually
     * visited, never more than [MAX_LINES_SCANNED]. [truncated] is true when a
     * cap hid any line, text tail or match from the result. [queryRejected] is
     * true when the query was empty or longer than [MAX_QUERY_CHARS], in which
     * case no scan ran at all.
     */
    data class Result(
        val hits: List<Hit>,
        val scannedLines: Int,
        val truncated: Boolean,
        val queryRejected: Boolean,
    )

    private val REJECTED = Result(emptyList(), 0, false, true)
    private val EMPTY = Result(emptyList(), 0, false, false)
    private val FAILED = Result(emptyList(), 0, true, false)

    /**
     * Searches [view] for [query], ignoring ASCII and non-ASCII letter case as
     * defined by the Kotlin standard library's `ignoreCase` character
     * comparison. Matching is per UTF-16 code unit and applies no Unicode
     * normalization, so case folding never shifts an offset. Matches on one
     * line do not overlap: the scan resumes at the end of the previous match.
     * An empty query or a query over [MAX_QUERY_CHARS] is rejected without a
     * scan; a whitespace-only query is a normal query. [limit] is clamped to
     * `0..MAX_HITS`, so a negative limit returns no hits and an oversized one
     * cannot exceed the cap.
     *
     * The function is total: malformed data, hostile line collections and
     * unusual Unicode input produce a bounded result rather than an exception.
     */
    fun search(view: PadView, query: String, limit: Int = MAX_HITS): Result =
        runCatching { scan(view, query, limit) }.getOrElse { FAILED }

    private fun scan(view: PadView, query: String, limit: Int): Result {
        if (query.isEmpty() || query.length > MAX_QUERY_CHARS) return REJECTED
        val cap = limit.coerceIn(0, MAX_HITS)
        if (cap == 0) return EMPTY.copy(truncated = true)

        val lines = view.lines
        val truncatedInput = lines.size > MAX_LINES_SCANNED
        val bounded = if (truncatedInput) lines.take(MAX_LINES_SCANNED) else lines

        val hits = ArrayList<Hit>()
        var truncated = truncatedInput
        var scanned = 0
        for ((index, line) in bounded.withIndex()) {
            scanned++
            val text = line.text
            val window = if (text.length > MAX_TEXT_SCAN_CHARS) {
                truncated = true
                text.substring(0, MAX_TEXT_SCAN_CHARS)
            } else {
                text
            }
            // A non-empty needle can only fit when from + query.length <=
            // window.length, so indexOf is never called with an invalid start.
            var from = 0
            while (from <= window.length - query.length) {
                val at = window.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                val end = at + query.length
                if (hits.size < cap) {
                    hits.add(Hit(index, line.key, at, end))
                } else {
                    truncated = true
                }
                from = end
            }
        }
        return Result(hits, scanned, truncated, false)
    }
}
