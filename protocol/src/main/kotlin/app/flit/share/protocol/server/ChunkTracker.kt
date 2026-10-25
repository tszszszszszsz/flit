package app.flit.share.protocol.server

import java.util.concurrent.atomic.AtomicLong

/**
 * Piece bookkeeping for one inbound multi-socket upload.
 *
 * Chunks of a single file arrive on concurrent connections, in any order, and
 * occasionally overlap (a sender retries a range after a transport hiccup).
 * The receiver therefore cannot stream-hash the body; it writes each chunk at
 * its declared offset and tracks coverage here:
 *
 * - [base] is the contiguous prefix from byte 0 — the only part that can be
 *   salvaged into a classic resumable part when the exchange is abandoned.
 * - [complete] decides when the assembled whole can be hashed and accepted.
 *
 * Synchronized throughout: up to [app.flit.share.protocol.server.ProtocolServer.MAX_TURBO_STREAMS]
 * chunk streams mutate one tracker concurrently.
 */
internal class ChunkTracker(private val total: Long) {

    /** Sorted, coalesced half-open covered intervals [start, endExclusive). */
    private var iv = ArrayList<Pair<Long, Long>>()
    private val written = AtomicLong(0)

    /** Bytes written so far, summed across requests (overlaps double-count). */
    val bytesWritten: Long get() = written.get()

    /** Records `len` bytes at [start]. Overlaps are absorbed, not double-tracked. */
    @Synchronized
    fun add(start: Long, len: Long) {
        if (len <= 0) return
        var s = start
        var e = start + len
        val merged = ArrayList<Pair<Long, Long>>(iv.size + 1)
        var i = 0
        while (i < iv.size) {
            val (cs, ce) = iv[i]
            when {
                ce < s -> merged.add(iv[i])
                cs > e -> break
                else -> { // overlapping/adjacent — absorb into the new interval
                    s = minOf(s, cs)
                    e = maxOf(e, ce)
                }
            }
            i++
        }
        merged.add(s to e)
        while (i < iv.size) merged.add(iv[i++])
        iv = merged
        written.addAndGet(len)
    }

    /** Contiguous prefix from byte 0 that is fully covered. */
    @Synchronized
    fun base(): Long = iv.firstOrNull()?.takeIf { it.first == 0L }?.second ?: 0L

    /** True when coverage reaches [total]. */
    @Synchronized
    fun complete(): Boolean = base() >= total

    @Synchronized
    fun clear() {
        iv.clear()
        written.set(0)
    }
}
