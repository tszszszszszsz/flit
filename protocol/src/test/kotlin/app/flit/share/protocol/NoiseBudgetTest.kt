package app.flit.share.protocol

import app.flit.share.protocol.ideas.noisebudget.NoiseBudget
import app.flit.share.protocol.ideas.noisebudget.NoiseBudget.Kind
import app.flit.share.protocol.ideas.noisebudget.NoiseBudget.Reason
import app.flit.share.protocol.ideas.noisebudget.NoiseBudget.Status
import app.flit.share.protocol.ideas.noisebudget.NoiseBudget.Window
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure arithmetic contract for [NoiseBudget]: union-of-windows accounting,
 * exact integer ceiling checks, refusal of malformed input, bounded work on
 * hostile input, and determinism. No clock, radio, file, or Android state.
 */
class NoiseBudgetTest {

    private fun announce(start: Long, duration: Long): Window =
        NoiseBudget.window(Kind.ANNOUNCE, start, duration)

    private fun scan(start: Long, duration: Long): Window =
        NoiseBudget.window(Kind.SCAN, start, duration)

    @Test
    fun `empty schedule is idle and within any ceiling`() {
        val r = NoiseBudget.validate(1_000L, 5_000, emptyList())
        assertEquals(Status.OK, r.status)
        assertTrue(r.ok)
        assertEquals(1_000L, r.periodMillis)
        assertEquals(5_000, r.ceilingBps)
        assertEquals(500L, r.allowedMillis)
        assertEquals(0L, r.activeMillis)
        assertEquals(0, r.usedBps)
        assertEquals(0, r.acceptedWindows)
        assertEquals(0, r.acceptedAnnounce)
        assertEquals(0, r.acceptedScan)
        assertEquals(0L, r.excessMillis)
        assertTrue(r.violations.isEmpty())
        assertTrue(r.explanations.isEmpty())

        // An empty schedule fits even a zero ceiling.
        val zero = NoiseBudget.validate(1_000L, 0, emptyList())
        assertEquals(Status.OK, zero.status)
        assertEquals(0L, zero.allowedMillis)
        assertEquals(0L, zero.activeMillis)
    }

    @Test
    fun `overlapping announce and scan charge the radio once`() {
        // [0,600) announce and [300,500) scan: the overlap is occupied once.
        val r = NoiseBudget.validate(1_000L, 10_000, listOf(announce(0L, 600L), scan(300L, 200L)))
        assertEquals(Status.OK, r.status)
        assertEquals(600L, r.activeMillis)
        assertEquals(6_000, r.usedBps)
        assertEquals(2, r.acceptedWindows)
        assertEquals(1, r.acceptedAnnounce)
        assertEquals(1, r.acceptedScan)
        assertTrue(r.violations.isEmpty())
    }

    @Test
    fun `touching windows equal one longer window`() {
        val split = NoiseBudget.validate(
            1_000L, 10_000, listOf(announce(200L, 300L), scan(500L, 250L)),
        )
        val joined = NoiseBudget.validate(1_000L, 10_000, listOf(announce(200L, 550L)))
        assertEquals(550L, split.activeMillis)
        assertEquals(joined.activeMillis, split.activeMillis)
        assertEquals(joined.usedBps, split.usedBps)
        assertEquals(2, split.acceptedWindows)
        assertEquals(1, joined.acceptedWindows)
        assertEquals(Status.OK, split.status)
    }

    @Test
    fun `a fully nested window adds no occupied time`() {
        val r = NoiseBudget.validate(
            1_000L, 10_000, listOf(scan(0L, 1_000L), announce(250L, 100L)),
        )
        assertEquals(1_000L, r.activeMillis)
        assertEquals(10_000, r.usedBps)
        assertEquals(2, r.acceptedWindows)
        assertEquals(1, r.acceptedAnnounce)
        assertEquals(1, r.acceptedScan)
        assertEquals(Status.OK, r.status)
        assertEquals(0L, r.excessMillis)
    }

    @Test
    fun `zero duration windows are accepted but occupy nothing`() {
        val r = NoiseBudget.validate(
            1_000L, 0, listOf(announce(0L, 0L), scan(999L, 0L)),
        )
        assertEquals(Status.OK, r.status)
        assertEquals(2, r.acceptedWindows)
        assertEquals(1, r.acceptedAnnounce)
        assertEquals(1, r.acceptedScan)
        assertEquals(0L, r.activeMillis)
        assertEquals(0, r.usedBps)
        assertEquals(0L, r.allowedMillis)
        assertTrue(r.violations.isEmpty())
    }

    @Test
    fun `permuting accepted windows leaves the whole report unchanged`() {
        val base = listOf(
            announce(0L, 200L),    // [0,200)
            scan(150L, 200L),      // [150,350) overlaps -> [0,350)
            announce(400L, 100L),  // [400,500)
            scan(480L, 20L),       // [480,500) nested
        )
        val r = NoiseBudget.validate(1_000L, 5_000, base)
        assertEquals(450L, r.activeMillis)
        assertEquals(4_500, r.usedBps)
        assertEquals(Status.OK, r.status)

        val permutations = listOf(
            base,
            base.reversed(),
            listOf(base[2], base[0], base[3], base[1]),
            base.shuffled(Random(11)),
            base.shuffled(Random(99)),
        )
        permutations.forEach { assertEquals(r, NoiseBudget.validate(1_000L, 5_000, it)) }
    }

    @Test
    fun `repeated validation is deterministic with a stable summary`() {
        val windows = listOf(announce(0L, 300L), scan(250L, 300L), scan(900L, 100L))
        val a = NoiseBudget.validate(1_000L, 6_000, windows)
        val b = NoiseBudget.validate(1_000L, 6_000, windows)
        assertEquals(a, b, "no hidden state: equal arguments yield an equal report")
        assertEquals(a.summary(), b.summary())
        assertFalse(a.summary().contains('\n'))
        assertEquals(
            "status=OVER_BUDGET period=1000ms ceiling=6000bps " +
                "allowed=600ms active=650ms used=6500bps windows=3 violations=1",
            a.summary(),
        )
    }

    @Test
    fun `exact ceiling boundary passes and one millisecond more fails via cross multiplication`() {
        // Divisible case: 10_000 * 2_500 / 10_000 == 2_500 exactly.
        val exact = NoiseBudget.validate(10_000L, 2_500, listOf(announce(0L, 2_500L)))
        assertEquals(2_500L, exact.allowedMillis)
        assertEquals(2_500L, exact.activeMillis)
        assertEquals(Status.OK, exact.status)
        assertEquals(0L, exact.excessMillis)
        assertTrue(exact.violations.isEmpty())

        val oneMore = NoiseBudget.validate(10_000L, 2_500, listOf(announce(0L, 2_501L)))
        assertEquals(Status.OVER_BUDGET, oneMore.status)
        assertEquals(1L, oneMore.excessMillis)

        // Truncating case: 7 * 1_429 = 10_003, so allowedMillis floors to 1.
        val truncating = NoiseBudget.validate(7L, 1_429, listOf(scan(0L, 1L)))
        assertEquals(1L, truncating.allowedMillis)
        assertEquals(Status.OK, truncating.status)
        val tripped = NoiseBudget.validate(7L, 1_429, listOf(scan(0L, 2L)))
        assertEquals(Status.OVER_BUDGET, tripped.status)
        assertEquals(2_857, tripped.usedBps)
        assertEquals(1L, tripped.excessMillis)
        assertEquals(Reason.OVER_BUDGET, tripped.violations.single().reason)
        assertTrue(tripped.explanations.single().contains("above ceiling"))

        // A full cycle at 100% is still inside the ceiling.
        val full = NoiseBudget.validate(1L, 10_000, listOf(scan(0L, 1L)))
        assertEquals(1L, full.allowedMillis)
        assertEquals(Status.OK, full.status)
    }

    @Test
    fun `bad periods are refused with zeroed figures`() {
        val badPeriods = listOf(
            0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, NoiseBudget.MAX_PERIOD_MILLIS + 1L,
        )
        badPeriods.forEach { period ->
            val r = NoiseBudget.validate(period, 5_000, listOf(announce(0L, 1L)))
            assertEquals(Status.INVALID, r.status, "period=$period")
            assertFalse(r.ok)
            assertEquals(Reason.BAD_PERIOD, r.violations.single().reason)
            assertEquals(-1, r.violations.single().windowIndex)
            assertEquals(0L, r.allowedMillis)
            assertEquals(0L, r.activeMillis)
            assertEquals(0, r.usedBps)
            assertEquals(0, r.acceptedWindows)
            assertEquals(1, r.violations.size)
            assertEquals(1, r.explanations.size)
            assertTrue(r.summary().startsWith("status=INVALID"))
        }

        // A bad period wins over a bad ceiling; the bounds themselves are valid.
        val both = NoiseBudget.validate(0L, -1, emptyList())
        assertEquals(listOf(Reason.BAD_PERIOD), both.violations.map { it.reason })
        assertEquals(Status.OK, NoiseBudget.validate(1L, 10_000, emptyList()).status)
        assertEquals(
            Status.OK,
            NoiseBudget.validate(NoiseBudget.MAX_PERIOD_MILLIS, 10_000, emptyList()).status,
        )
    }

    @Test
    fun `bad ceilings are refused with zeroed figures`() {
        listOf(-1, Int.MIN_VALUE, 10_001, Int.MAX_VALUE).forEach { ceiling ->
            val r = NoiseBudget.validate(1_000L, ceiling, listOf(scan(0L, 1L)))
            assertEquals(Status.INVALID, r.status, "ceiling=$ceiling")
            assertFalse(r.ok)
            assertEquals(Reason.BAD_CEILING, r.violations.single().reason)
            assertEquals(-1, r.violations.single().windowIndex)
            assertEquals(0L, r.allowedMillis)
            assertEquals(0L, r.activeMillis)
            assertEquals(0, r.usedBps)
            assertEquals(0, r.acceptedWindows)
            assertEquals(1, r.explanations.size)
        }
        assertEquals(Status.OK, NoiseBudget.validate(1_000L, 0, emptyList()).status)
        assertEquals(
            1_000L,
            NoiseBudget.validate(1_000L, 10_000, emptyList()).allowedMillis,
        )
    }

    @Test
    fun `negative windows are reported at their index and excluded`() {
        val r = NoiseBudget.validate(
            1_000L, 10_000,
            listOf(announce(-1L, 100L), scan(100L, -5L), announce(200L, 100L)),
        )
        assertEquals(Status.INVALID, r.status)
        assertEquals(1, r.acceptedWindows)
        assertEquals(1, r.acceptedAnnounce)
        assertEquals(0, r.acceptedScan)
        assertEquals(100L, r.activeMillis)
        assertEquals(
            listOf(Reason.NEGATIVE_START, Reason.NEGATIVE_DURATION),
            r.violations.map { it.reason },
        )
        assertEquals(listOf(0, 1), r.violations.map { it.windowIndex })
        assertEquals(Kind.ANNOUNCE, r.violations[0].kind)
        assertEquals(-1L, r.violations[0].startMillis)
        assertEquals(100L, r.violations[0].durationMillis)
        assertEquals(2, r.explanations.size)
        assertTrue(r.explanations[0].contains("window 0"))
    }

    @Test
    fun `outside and crossing windows are reported and excluded`() {
        val r = NoiseBudget.validate(
            1_000L, 10_000,
            listOf(
                scan(1_000L, 0L),     // start == period: outside
                scan(2_000L, 10L),    // start > period: outside
                announce(990L, 11L),  // ends past the cycle
                announce(50L, 50L),   // the only accepted window
            ),
        )
        assertEquals(Status.INVALID, r.status)
        assertEquals(
            listOf(Reason.OUTSIDE_CYCLE, Reason.OUTSIDE_CYCLE, Reason.CROSSES_CYCLE),
            r.violations.map { it.reason },
        )
        assertEquals(listOf(0, 1, 2), r.violations.map { it.windowIndex })
        assertEquals(1, r.acceptedWindows)
        assertEquals(50L, r.activeMillis)
        assertEquals(3, r.explanations.size)

        // A window ending exactly at the cycle boundary is accepted.
        val edge = NoiseBudget.validate(1_000L, 10_000, listOf(scan(900L, 100L)))
        assertEquals(Status.OK, edge.status)
        assertEquals(1, edge.acceptedWindows)
        assertEquals(100L, edge.activeMillis)
    }

    @Test
    fun `structurally invalid outranks over budget`() {
        val r = NoiseBudget.validate(
            100L, 1_000,
            listOf(scan(0L, 100L), announce(-1L, 10L)),
        )
        assertEquals(Status.INVALID, r.status)
        assertFalse(r.ok)
        assertEquals(100L, r.activeMillis)
        assertEquals(10L, r.allowedMillis)
        assertEquals(90L, r.excessMillis)
        assertEquals(
            listOf(Reason.NEGATIVE_START, Reason.OVER_BUDGET),
            r.violations.map { it.reason },
        )
        assertEquals(listOf(1, -1), r.violations.map { it.windowIndex })
        assertEquals(2, r.explanations.size)
        assertTrue(r.explanations.last().contains("above ceiling"))
    }

    @Test
    fun `hostile Long extremes never throw`() {
        val hostileWindow = listOf(
            announce(Long.MIN_VALUE, Long.MAX_VALUE),
            scan(Long.MAX_VALUE, Long.MIN_VALUE),
            announce(0L, Long.MAX_VALUE),
            scan(Long.MIN_VALUE, 0L),
            announce(-1L, -1L),
        )
        val periods = listOf(
            Long.MIN_VALUE, -1L, 0L, 1L, NoiseBudget.MAX_PERIOD_MILLIS,
            NoiseBudget.MAX_PERIOD_MILLIS + 1L, Long.MAX_VALUE,
        )
        val ceilings = listOf(Int.MIN_VALUE, -1, 0, 10_000, 10_001, Int.MAX_VALUE)

        periods.forEach { period ->
            val r = NoiseBudget.validate(period, 5_000, hostileWindow)
            assertEquals(r.violations.size, r.explanations.size, "period=$period")
            assertTrue(
                r.status == Status.INVALID || r.status == Status.OK || r.status == Status.OVER_BUDGET,
            )
        }
        ceilings.forEach { ceiling ->
            val r = NoiseBudget.validate(1_000L, ceiling, hostileWindow)
            assertEquals(r.violations.size, r.explanations.size, "ceiling=$ceiling")
            assertTrue(
                r.status == Status.INVALID || r.status == Status.OK || r.status == Status.OVER_BUDGET,
            )
        }

        val r = NoiseBudget.validate(1_000L, 10_000, hostileWindow)
        assertEquals(Status.INVALID, r.status)
        assertEquals(0L, r.activeMillis)
        assertEquals(0, r.acceptedWindows)
        assertEquals(5, r.violations.size)
        assertTrue(r.violations.all { it.reason != Reason.OVER_BUDGET })
    }

    @Test
    fun `only the first max windows are examined`() {
        assertEquals(64, NoiseBudget.MAX_WINDOWS)
        val atLimit = List(NoiseBudget.MAX_WINDOWS) { announce(0L, 10L) }

        val exact = NoiseBudget.validate(1_000L, 2_000, atLimit)
        assertEquals(Status.OK, exact.status)
        assertEquals(64, exact.acceptedWindows)
        assertEquals(64, exact.acceptedAnnounce)
        assertEquals(10L, exact.activeMillis)
        assertTrue(exact.violations.isEmpty())

        // The 65th window would fill the cycle if it were examined.
        val flooded = NoiseBudget.validate(1_000L, 2_000, atLimit + scan(0L, 1_000L))
        assertEquals(Status.INVALID, flooded.status)
        assertEquals(64, flooded.acceptedWindows)
        assertEquals(10L, flooded.activeMillis)
        assertEquals(1, flooded.violations.size)
        assertEquals(Reason.TOO_MANY_WINDOWS, flooded.violations.single().reason)
        assertEquals(-1, flooded.violations.single().windowIndex)
        assertTrue(flooded.explanations.single().contains("65"))
        assertTrue(flooded.explanations.single().contains("64"))

        // A malformed window past the cap is not examined either.
        val beyond = NoiseBudget.validate(1_000L, 2_000, atLimit + scan(-5L, 5L))
        assertEquals(listOf(Reason.TOO_MANY_WINDOWS), beyond.violations.map { it.reason })
    }

    @Test
    fun `verdict excess and explanation agree across a schedule sweep`() {
        fun windowsFor(periodMillis: Long): List<Window> {
            val first = minOf(periodMillis, periodMillis / 2 + 1)
            val secondStart = periodMillis / 3
            val secondDuration = minOf(periodMillis - secondStart, periodMillis / 4 + 1)
            return listOf(announce(0L, first), scan(secondStart, secondDuration))
        }

        val periods = listOf(1L, 2L, 3L, 7L, 10L, 55L, 1_000L, NoiseBudget.MAX_PERIOD_MILLIS)
        val ceilings = listOf(0, 1, 1_429, 2_500, 5_000, 9_999, 10_000)
        periods.forEach { period ->
            ceilings.forEach { ceiling ->
                val r = NoiseBudget.validate(period, ceiling, windowsFor(period))
                assertTrue(r.activeMillis in 0..period, "period=$period ceiling=$ceiling")
                assertTrue(r.usedBps in 0..NoiseBudget.CEILING_MAX_BPS)
                assertEquals(period * ceiling / NoiseBudget.CEILING_MAX_BPS, r.allowedMillis)
                assertEquals(r.acceptedAnnounce + r.acceptedScan, r.acceptedWindows)
                assertEquals(r.violations.size, r.explanations.size)
                when (r.status) {
                    Status.OVER_BUDGET -> {
                        assertTrue(r.activeMillis > r.allowedMillis)
                        assertEquals(r.activeMillis - r.allowedMillis, r.excessMillis)
                        assertEquals(Reason.OVER_BUDGET, r.violations.last().reason)
                        assertEquals(-1, r.violations.last().windowIndex)
                        assertTrue(r.explanations.last().contains("above ceiling"))
                    }
                    else -> {
                        assertEquals(0L, r.excessMillis)
                        assertTrue(r.violations.none { it.reason == Reason.OVER_BUDGET })
                    }
                }
            }
        }
    }

    @Test
    fun `shipped ten on forty five off schedule fits the ceiling`() {
        // The shipped cadence: 10 s of shared radio time in a 55 s cycle.
        val periodMillis = 55_000L
        val onMillis = 10_000L
        val shipped = listOf(announce(0L, onMillis), scan(0L, onMillis))

        val r = NoiseBudget.validate(periodMillis, 2_000, shipped)
        assertEquals(Status.OK, r.status)
        assertTrue(r.ok)
        assertEquals(periodMillis, r.periodMillis)
        assertEquals(11_000L, r.allowedMillis, "20% of 55 s is 11 s")
        assertEquals(10_000L, r.activeMillis, "both radios share one on-window")
        assertEquals(1_818, r.usedBps, "10 of 55 s is about 18.2%")
        assertEquals(0L, r.excessMillis)
        assertEquals(2, r.acceptedWindows)
        assertEquals(1, r.acceptedAnnounce)
        assertEquals(1, r.acceptedScan)
        assertTrue(r.violations.isEmpty())
        assertTrue(r.explanations.isEmpty())

        // The exact ratio: 1819 bps tolerates it, 1818 bps does not.
        val justAbove = NoiseBudget.validate(periodMillis, 1_819, shipped)
        assertEquals(Status.OK, justAbove.status)
        val justBelow = NoiseBudget.validate(periodMillis, 1_818, shipped)
        assertEquals(Status.OVER_BUDGET, justBelow.status)
        assertEquals(9_999L, justBelow.allowedMillis)
        assertEquals(1L, justBelow.excessMillis)
    }

    @Test
    fun `randomized hostile inputs stay inside documented bounds`() {
        val rng = Random(20261126L)
        val hostilePeriods = longArrayOf(
            Long.MIN_VALUE, -86_400_001L, -1L, 0L, 1L, 1_000L, 55_000L,
            NoiseBudget.MAX_PERIOD_MILLIS, NoiseBudget.MAX_PERIOD_MILLIS + 1L, Long.MAX_VALUE,
        )
        val hostileCeilings = intArrayOf(
            Int.MIN_VALUE, -1, 0, 1, 1_819, 2_000, 10_000, 10_001, Int.MAX_VALUE,
        )

        repeat(256) {
            val period = if (rng.nextBoolean()) {
                hostilePeriods[rng.nextInt(hostilePeriods.size)]
            } else {
                rng.nextLong(Long.MIN_VALUE, Long.MAX_VALUE)
            }
            val ceiling = if (rng.nextBoolean()) {
                hostileCeilings[rng.nextInt(hostileCeilings.size)]
            } else {
                rng.nextInt()
            }
            val windows = List(rng.nextInt(0, NoiseBudget.MAX_WINDOWS + 4)) {
                val start = if (rng.nextBoolean()) {
                    hostilePeriods[rng.nextInt(hostilePeriods.size)]
                } else {
                    rng.nextLong(Long.MIN_VALUE, Long.MAX_VALUE)
                }
                val duration = if (rng.nextBoolean()) {
                    hostilePeriods[rng.nextInt(hostilePeriods.size)]
                } else {
                    rng.nextLong(Long.MIN_VALUE, Long.MAX_VALUE)
                }
                NoiseBudget.window(
                    if (rng.nextBoolean()) Kind.ANNOUNCE else Kind.SCAN, start, duration,
                )
            }

            val r = NoiseBudget.validate(period, ceiling, windows)
            assertEquals(r.violations.size, r.explanations.size)
            if (period in 1L..NoiseBudget.MAX_PERIOD_MILLIS &&
                ceiling in 0..NoiseBudget.CEILING_MAX_BPS
            ) {
                assertTrue(r.activeMillis in 0..period, "period=$period")
                assertTrue(r.usedBps in 0..NoiseBudget.CEILING_MAX_BPS)
                assertEquals(period * ceiling / NoiseBudget.CEILING_MAX_BPS, r.allowedMillis)
                assertTrue(r.acceptedWindows <= minOf(windows.size, NoiseBudget.MAX_WINDOWS))
                assertEquals(r.acceptedAnnounce + r.acceptedScan, r.acceptedWindows)
                assertEquals(
                    if (r.activeMillis > r.allowedMillis) r.activeMillis - r.allowedMillis else 0L,
                    r.excessMillis,
                )
                if (r.status == Status.OVER_BUDGET) {
                    assertEquals(Reason.OVER_BUDGET, r.violations.last().reason)
                }
            } else {
                assertEquals(Status.INVALID, r.status)
                assertEquals(0L, r.allowedMillis)
                assertEquals(0L, r.activeMillis)
                assertEquals(0, r.usedBps)
            }
        }
    }
}
