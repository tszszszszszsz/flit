package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Remaining sample/clock edge cases for the independent speed estimator,
 * complementing [FlitSpeedTest]. All clocks are supplied by the test: no
 * sleeps, no wall-clock reads, fully deterministic.
 */
class FlitSpeedEdgeTest {

    @Test
    fun `a huge first sample after reset only seeds the baseline`() {
        val rate = FlitSpeed()
        rate.sample(0L, 0L)
        rate.sample(200L, 4L * 1024 * 1024) // establish a real rate first
        rate.reset()

        // New file appears mid-run with an already-large byte count: the first
        // observation must be treated as a baseline, not as 900 MB of transfer.
        assertEquals(0L, rate.sample(50_000L, 900L * 1024 * 1024))

        // Only the *new* 1 MiB in 100 ms counts: ~10 MB/s, not the 900 MB seed.
        val honest = rate.sample(50_100L, 901L * 1024 * 1024)
        assertTrue(honest in 8_000_000L..12_000_000L, "post-reset rate was $honest B/s")
    }

    @Test
    fun `monotonic bytes with irregular sample intervals keep an honest rate`() {
        val rate = FlitSpeed()
        assertEquals(0L, rate.sample(0L, 0L))

        // Each accepted sample carries a constant 20 MB/s of real progress;
        // the gaps between samples vary, and one is below the sample minimum.
        val points = listOf(
            100L to 2L * 1024 * 1024,       // dt 100
            350L to 7L * 1024 * 1024,       // dt 250
            390L to 7L * 1024 * 1024 + 512L * 1024,  // dt 40 -> ignored, value unchanged
            890L to 17L * 1024 * 1024 + 512L * 1024, // dt 500 -> 10 MiB / 500 ms
        )
        for ((now, bytes) in points) {
            val shown = rate.sample(now, bytes)
            assertTrue(
                shown in 19_000_000L..21_000_000L,
                "irregular interval at t=$now inflated the rate to $shown B/s",
            )
        }
    }

    @Test
    fun `decreasing and repeated byte counts never make the rate negative`() {
        val rate = FlitSpeed()
        rate.sample(0L, 0L)
        rate.sample(100L, 10L * 1024 * 1024)
        val peak = rate.sample(200L, 12L * 1024 * 1024)
        assertTrue(peak > 0L)

        // Out-of-order reports, a stall, and a byte count that rewinds to zero
        // must all preserve a non-negative readout and never accelerate it.
        for ((now, bytes) in listOf(300L to 3L * 1024 * 1024, 400L to 3L * 1024 * 1024, 500L to 0L)) {
            val shown = rate.sample(now, bytes)
            assertTrue(shown >= 0L, "negative rate at t=$now: $shown B/s")
            assertTrue(shown <= peak, "a rewind accelerated the rate: $peak -> $shown B/s")
        }
    }

    @Test
    fun `a sudden 100x byte jump stays bounded by the per sample limiter`() {
        val rate = FlitSpeed()
        rate.sample(0L, 0L)
        val steady = rate.sample(100L, 5L * 1024 * 1024) // 50 MB/s
        assertTrue(steady > 0L)

        // 500 MiB in 100 ms is 100x the established rate. The 4x per-sample
        // cap plus half-life smoothing must keep the readout near steady,
        // never launching it into the gigabits.
        val jumped = rate.sample(200L, 505L * 1024 * 1024)
        assertTrue(jumped >= steady, "a 100x jump should still raise the estimate: $steady -> $jumped")
        assertTrue(jumped <= steady * 4, "4x per-sample limiter failed: $steady -> $jumped B/s")
        assertTrue(jumped < 100L * 1024 * 1024, "readout jumped into the gigabits: $jumped B/s")

        // And the next ordinary sample must not inherit a poisoned baseline.
        val after = rate.sample(300L, 505L * 1024 * 1024)
        assertTrue(after >= 0L)
        assertTrue(after < 100L * 1024 * 1024, "jump left a poisoned baseline: $after B/s")
    }
}
