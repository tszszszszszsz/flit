package app.flit.share.protocol.ideas.noisebudget

/**
 * Pure duty-cycle arithmetic for a supplied BLE announce/scan schedule.
 *
 * A schedule is a cycle of [periodMillis] milliseconds containing a list of
 * radio-active [Window]s, each marked [Kind.ANNOUNCE] or [Kind.SCAN]. The
 * occupied time is the union of the accepted windows: two windows that overlap
 * occupy the radio for the overlapping span once, not twice. The union is
 * compared with a ceiling supplied in basis points (bps, 10_000 bps = 100%).
 *
 * This object holds no state, reads no clock, and touches no radio, socket,
 * file, or Android API. Every value comes from the caller. It never throws for
 * values of its parameters: out-of-range numbers and malformed windows are
 * reported as [Violation]s with [Report.explanations] rather than exceptions.
 *
 * Invariants (for a valid period and ceiling):
 * - `0 <= activeMillis <= periodMillis` and `0 <= usedBps <= 10_000`.
 * - `allowedMillis == periodMillis * ceilingBps / 10_000` (integer floor).
 * - A schedule is [Status.OVER_BUDGET] iff
 *   `activeMillis * 10_000 > periodMillis * ceilingBps` (cross multiplication,
 *   so rounding never changes the verdict).
 * - At most [MAX_WINDOWS] windows are examined; work and report size are
 *   bounded no matter how large the supplied list is.
 * - Repeated calls with equal arguments produce equal [Report]s, and input
 *   order does not change the computed union.
 */
object NoiseBudget {
    /** Longest accepted cycle, in milliseconds (24 hours). */
    const val MAX_PERIOD_MILLIS: Long = 86_400_000L

    /** Most windows examined in one schedule. */
    const val MAX_WINDOWS: Int = 64

    /** A duty-cycle ceiling of 100%, in basis points. */
    const val CEILING_MAX_BPS: Int = 10_000

    /** The two kinds of radio activity in a cycle. */
    enum class Kind { ANNOUNCE, SCAN }

    /** One half-open radio-active window `[startMillis, startMillis + durationMillis)`. */
    data class Window(val kind: Kind, val startMillis: Long, val durationMillis: Long)

    /** Why a schedule, a window, or the budget failed. */
    enum class Reason {
        BAD_PERIOD,
        BAD_CEILING,
        TOO_MANY_WINDOWS,
        NEGATIVE_START,
        NEGATIVE_DURATION,
        OUTSIDE_CYCLE,
        CROSSES_CYCLE,
        OVER_BUDGET,
    }

    /** A single structured finding. [windowIndex] is -1 for cycle-wide findings. */
    data class Violation(
        val reason: Reason,
        val windowIndex: Int = -1,
        val kind: Kind? = null,
        val startMillis: Long = 0L,
        val durationMillis: Long = 0L,
    )

    /** Overall verdict. [Status.INVALID] takes precedence over [Status.OVER_BUDGET]. */
    enum class Status { OK, INVALID, OVER_BUDGET }

    /**
     * Result of [validate]. [violations] and [explanations] are in the same
     * stable order: input order for window findings, then a single cycle-wide
     * budget finding last.
     */
    data class Report(
        val status: Status,
        val periodMillis: Long,
        val ceilingBps: Int,
        val allowedMillis: Long,
        val activeMillis: Long,
        val usedBps: Int,
        val acceptedWindows: Int,
        val acceptedAnnounce: Int,
        val acceptedScan: Int,
        val violations: List<Violation>,
        val explanations: List<String>,
    ) {
        /** True only when the schedule is structurally valid and within budget. */
        val ok: Boolean get() = status == Status.OK

        /** Active milliseconds above the supplied ceiling, or zero. */
        val excessMillis: Long get() = if (activeMillis > allowedMillis) activeMillis - allowedMillis else 0L

        /** One deterministic, single-line rendering of the verdict. */
        fun summary(): String =
            "status=$status period=${periodMillis}ms ceiling=${ceilingBps}bps " +
                "allowed=${allowedMillis}ms active=${activeMillis}ms used=${usedBps}bps " +
                "windows=$acceptedWindows violations=${violations.size}"
    }

    /** Convenience builder so callers need not repeat the [Window] constructor. */
    fun window(kind: Kind, startMillis: Long, durationMillis: Long): Window =
        Window(kind, startMillis, durationMillis)

    /**
     * Validates [windows] against [ceilingBps] over a cycle of [periodMillis].
     * Total on all values of its parameters: it returns an [Status.INVALID]
     * report instead of throwing when the period, ceiling, or a window is
     * malformed. No value is read from global state.
     */
    fun validate(periodMillis: Long, ceilingBps: Int, windows: List<Window>): Report {
        val violations = ArrayList<Violation>()
        val explanations = ArrayList<String>()

        if (periodMillis !in 1L..MAX_PERIOD_MILLIS) {
            violations += Violation(Reason.BAD_PERIOD)
            explanations += "period $periodMillis ms is outside 1..$MAX_PERIOD_MILLIS; no budget computed"
            return Report(
                Status.INVALID, periodMillis, ceilingBps, 0L, 0L, 0, 0, 0, 0, violations, explanations,
            )
        }
        if (ceilingBps !in 0..CEILING_MAX_BPS) {
            violations += Violation(Reason.BAD_CEILING)
            explanations += "ceiling $ceilingBps bps is outside 0..$CEILING_MAX_BPS; no budget computed"
            return Report(
                Status.INVALID, periodMillis, ceilingBps, 0L, 0L, 0, 0, 0, 0, violations, explanations,
            )
        }

        var invalid = false
        if (windows.size > MAX_WINDOWS) {
            invalid = true
            violations += Violation(Reason.TOO_MANY_WINDOWS)
            explanations += "schedule has ${windows.size} windows; only the first $MAX_WINDOWS were examined"
        }

        val intervals = ArrayList<Pair<Long, Long>>()
        var accepted = 0
        var announce = 0
        var scan = 0
        val examined = minOf(windows.size, MAX_WINDOWS)
        for (i in 0 until examined) {
            val w = windows[i]
            when {
                w.startMillis < 0L -> {
                    invalid = true
                    violations += Violation(Reason.NEGATIVE_START, i, w.kind, w.startMillis, w.durationMillis)
                    explanations += "window $i (${w.kind}) starts at ${w.startMillis} ms before the cycle; ignored"
                }
                w.durationMillis < 0L -> {
                    invalid = true
                    violations += Violation(Reason.NEGATIVE_DURATION, i, w.kind, w.startMillis, w.durationMillis)
                    explanations += "window $i (${w.kind}) has negative duration ${w.durationMillis} ms; ignored"
                }
                w.startMillis >= periodMillis -> {
                    invalid = true
                    violations += Violation(Reason.OUTSIDE_CYCLE, i, w.kind, w.startMillis, w.durationMillis)
                    explanations += "window $i (${w.kind}) starts at ${w.startMillis} ms, outside a $periodMillis ms cycle; ignored"
                }
                w.durationMillis > periodMillis - w.startMillis -> {
                    invalid = true
                    violations += Violation(Reason.CROSSES_CYCLE, i, w.kind, w.startMillis, w.durationMillis)
                    explanations += "window $i (${w.kind}) at ${w.startMillis} ms for ${w.durationMillis} ms crosses the cycle end; ignored"
                }
                else -> {
                    accepted++
                    if (w.kind == Kind.ANNOUNCE) announce++ else scan++
                    if (w.durationMillis > 0L) {
                        intervals += w.startMillis to (w.startMillis + w.durationMillis)
                    }
                }
            }
        }

        intervals.sortWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        var active = 0L
        var k = 0
        while (k < intervals.size) {
            val start = intervals[k].first
            var end = intervals[k].second
            var j = k + 1
            while (j < intervals.size && intervals[j].first <= end) {
                end = maxOf(end, intervals[j].second)
                j++
            }
            active += end - start
            k = j
        }

        val allowed = periodMillis * ceilingBps / CEILING_MAX_BPS
        val usedBps = (active * CEILING_MAX_BPS / periodMillis).toInt()
        val over = active * CEILING_MAX_BPS > periodMillis * ceilingBps.toLong()
        if (over) {
            violations += Violation(Reason.OVER_BUDGET)
            explanations += "schedule uses $active ms of $periodMillis ms ($usedBps bps), above ceiling $ceilingBps bps by ${active - allowed} ms"
        }

        val status = when {
            invalid -> Status.INVALID
            over -> Status.OVER_BUDGET
            else -> Status.OK
        }
        return Report(
            status, periodMillis, ceilingBps, allowed, active, usedBps,
            accepted, announce, scan, violations, explanations,
        )
    }
}
