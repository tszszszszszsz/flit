package app.flit.share

/**
 * Smoothed bytes-per-second for a live transfer readout.
 *
 * The old estimator sampled on every progress callback but reset its clock
 * only when the notification was repainted (every 350 ms). Each callback in
 * that window re-divided the *same* elapsed time against an ever-growing
 * byte count, so a healthy 30 MB/s transfer appeared as KB/s and then as a
 * bogus multi-gigabit spike when the window closed.
 *
 * Counting here — separate from painting — makes sampling and rendering
 * independent. Samples closer than [MIN_SAMPLE_MS] are ignored: a progress
 * callback burst with no real time delta can only produce noise, never
 * information. [halfLifeMs] bounds how eagerly one lucky window can move
 * the displayed rate, and a single sample may never exceed four times the
 * current estimate — the first real sample of a transfer seeds it outright.
 */
class FlitSpeed(
    private val minSampleMs: Long = 80L,
    private val halfLifeMs: Long = 700L,
) {
    @Volatile
    private var lastMs = -1L
    private var lastBytes = 0L
    private var rate = 0.0

    /** Feed one observation (monotonic bytes). Returns the current smoothed rate. */
    @Synchronized
    fun sample(nowMs: Long, bytes: Long): Long {
        if (lastMs < 0L) {
            lastMs = nowMs
            lastBytes = bytes
            return 0L
        }
        val dt = nowMs - lastMs
        if (dt < minSampleMs) return rate.toLong()
        val db = bytes - lastBytes
        lastMs = nowMs
        lastBytes = bytes
        if (db <= 0L) return rate.toLong() // a re-report must not create a negative rate
        var instant = db * 1000.0 / dt
        if (rate > 0.0) instant = minOf(instant, rate * 4.0)
        // Exponential smoothing expressed as a half-life, so one fast window
        // can never launch the readout into the gigabits.
        val alpha = 1.0 - Math.pow(0.5, dt.toDouble() / halfLifeMs)
        rate = if (rate == 0.0) instant else rate + alpha * (instant - rate)
        return rate.toLong()
    }

    @Synchronized
    fun reset() {
        lastMs = -1L
        lastBytes = 0L
        rate = 0.0
    }
}
