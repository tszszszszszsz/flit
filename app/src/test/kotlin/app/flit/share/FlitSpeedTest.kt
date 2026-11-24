package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-app speed readout regression: a 30 MB/s transfer used to appear as
 * KB/s and then spike into the gigabits because every progress callback
 * re-divided the same paint window against an ever-growing byte count.
 */
class FlitSpeedTest {

    @Test
    fun `repeated samples inside one paint window do not inflate the rate`() {
        val rate = FlitSpeed()
        assertEquals(0L, rate.sample(1_000L, 0L))

        // 0.5 MB in 100 ms = ~5 MB/s. Ten callbacks in the same 16 ms must
        // not multiply that into a fake gigabit reading.
        var reported = 0L
        for (i in 1..10) reported = rate.sample(1_016L, 512L * 1024L)
        assertEquals(0L, reported, "sub-minimum samples must be ignored")

        reported = rate.sample(1_100L, 5L * 1024 * 1024)
        assertTrue(reported in 45_000_000L..60_000_000L, "honest rate was $reported B/s")
    }

    @Test
    fun `a re-reported or lower byte count never produces a negative or giant rate`() {
        val rate = FlitSpeed()
        rate.sample(0L, 0L)
        rate.sample(200L, 4L * 1024 * 1024)
        val before = rate.sample(400L, 8L * 1024 * 1024)
        val after = rate.sample(600L, 1L * 1024 * 1024) // out-of-order progress
        assertTrue(after >= 0L, "rate must never be negative: $after")
        assertTrue(after <= before, "a stall must not accelerate the readout: $before -> $after")
    }

    @Test
    fun `a fast window cannot jump the smoothed rate straight to a gigabit`() {
        val rate = FlitSpeed()
        rate.sample(0L, 0L)
        rate.sample(100L, 3L * 1024 * 1024) // ~30 MB/s
        val shown = rate.sample(200L, 900L * 1024 * 1024) // one insane 900 MB sample
        assertTrue(shown < 300L * 1024 * 1024, "half-life smoothing failed: $shown B/s")
        assertTrue(shown > 0L)
    }

    @Test
    fun `reset forgets the previous file's baseline`() {
        val rate = FlitSpeed()
        rate.sample(0L, 0L)
        rate.sample(200L, 1024L)
        rate.reset()
        assertEquals(0L, rate.sample(10_000L, 0L)) // reseeded, no rate yet
        val fresh = rate.sample(10_100L, 1L * 1024 * 1024)
        assertTrue(fresh in 8_000_000L..12_000_000L, "new session rate was $fresh B/s")
    }
}
