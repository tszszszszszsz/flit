package app.flit.share.protocol

import app.flit.share.protocol.server.ChunkTracker
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Piece bookkeeping for multi-socket uploads: chunks arrive in any order,
 * overlapping retry writes must merge, and only a contiguous prefix from
 * zero counts as salvageable.
 */
class ChunkTrackerTest {

    @Test
    fun `out of order pieces never fake completeness`() {
        val t = ChunkTracker(total = 1_000)
        t.add(500, 500)
        assertEquals(0, t.base(), "hole at the front: nothing salvageable")
        assertFalse(t.complete())
        t.add(0, 500)
        assertEquals(1_000, t.base())
        assertTrue(t.complete())
    }

    @Test
    fun `overlapping retry writes merge instead of double-tracking`() {
        val t = ChunkTracker(total = 4)
        t.add(0, 2) // [0,2)
        t.add(1, 2) // [1,3) — overlap
        assertTrue(t.base() >= 2, "merged interval covers + collapses")
        assertEquals(4, t.bytesWritten, "written sums raw bytes (may exceed total)")
        t.add(2, 2)
        assertTrue(t.complete())
        assertEquals(4, t.base())
    }

    @Test
    fun `base is the prefix only`() {
        val t = ChunkTracker(total = 10)
        t.add(1, 2)
        t.add(4, 6)
        assertEquals(0, t.base())
        t.add(0, 1)  // closes the front gap
        assertEquals(3, t.base(), "coverage [0,3), tails stay unfinished")
        assertFalse(t.complete())
    }

    @Test
    fun `tidy full pass completes immediately`() {
        val t = ChunkTracker(total = 8)
        t.add(0, 8)
        assertTrue(t.complete())
        t.clear()
        assertFalse(t.complete())
        assertEquals(0, t.base())
        assertEquals(0, t.bytesWritten)
    }

    @Test
    fun `scrambled boundaries still assemble exactly once`() {
        // Chain segments like a real 4-stream race: shuffled, adjacent, one
        // resent twice — coverage must still fuse into [0,total) cleanly.
        val total = 9L
        val pieces = listOf(0L to 3L, 3L to 3L, 6L to 3L, 0L to 3L) // one resend
        val t = ChunkTracker(total)
        for ((start, len) in pieces.reversed()) t.add(start, len)
        assertTrue(t.complete(), "resend of an already-covered first piece keeps full coverage")
        assertEquals(9, t.base())
    }

    @Test
    fun `random non-covering shuffles never complete early`() {
        val rng = Random(7)
        repeat(64) {
            val total = 100L
            val t = ChunkTracker(total)
            // cover a gapless [k, 100) leaving a front hole at 0..k-1
            val k = rng.nextLong(1, total)
            t.add(k, total - k)
            assertEquals(0, t.base())
            assertFalse(t.complete())
            t.add(0, k)
            assertEquals(total, t.base())
            assertTrue(t.complete())
        }
    }
}
