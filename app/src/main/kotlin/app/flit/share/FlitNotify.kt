package app.flit.share

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Transfer progress, rendered as live notifications (one per direction+session).
 * Tracks its own per-file ledger so parallel/multi-file transfers aggregate
 * correctly, and keeps an exponential-widow speed estimate.
 */
class FlitProgress(private val ctx: Context, private val notifId: Int) {

    enum class Kind { SEND, RECEIVE }

    private val ledger = ConcurrentHashMap<String, Long>() // fileName -> bytes done
    private var sessionTotal = 0L
    private var lastPaintMs = 0L
    private var lastPaintBytes = 0L
    private var speedBytesPerSec = 0.0
    private var title = ""

    /** Counts FlitProgress instances across ALL sessions; zero ⇒ telemetry idle. */
    private companion object {
        val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private var kind: Kind? = null

    fun begin(kind: Kind, files: Map<String, Long>) {
        this.kind = kind
        activeSessions.incrementAndGet()
        ledger.clear()
        files.forEach { (n, _) -> ledger[n] = 0L }
        sessionTotal = max(0L, files.values.sum())
        speedBytesPerSec = 0.0
        lastPaintBytes = 0
        lastPaintMs = 0
        title = if (kind == Kind.SEND) "Sending…" else "Receiving…"
        FlitEngine.setTransferActive(true)
        paint()
    }

    fun tick(fileName: String, bytes: Long) {
        ledger[fileName] = bytes
        paint(timeBudgetMs = 350)
    }

    fun fileDone(fileName: String) {
        // completion always repaints
        paint(timeBudgetMs = 0)
    }

    fun bumpTotal(by: Long) {
        sessionTotal += by
    }

    fun doneBytes(): Long = ledger.values.sum()

    private fun speed(): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastPaintMs
        if (dt > 0) {
            val inst = (doneBytes() - lastPaintBytes) * 1000.0 / dt
            speedBytesPerSec = if (speedBytesPerSec == 0.0) inst else speedBytesPerSec * 0.7 + inst * 0.3
        }
        return speedBytesPerSec.toLong()
    }

    /**
     * Repaints the notification. Rate-limited to `timeBudgetMs` between paints
     * (0 = always). Speed is sampled on every call, paints carry the latest.
     */
    private fun paint(timeBudgetMs: Long = 0) {
        val now = System.currentTimeMillis()
        val sp = speed()
        val doneNow = doneBytes()
        // In-app readout (hero card / dock ring) refreshes every call, faster
        // than the notification budget — it's just a StateFlow assignment.
        kind?.let { k ->
            FlitEngine.pushTelemetry(
                FlitEngine.TransferTelemetry(
                    isSend = k == Kind.SEND,
                    doneBytes = doneNow,
                    totalBytes = sessionTotal,
                    speedBytesPerSec = sp,
                ),
            )
        }
        if (timeBudgetMs > 0 && now - lastPaintMs < timeBudgetMs) return
        lastPaintMs = now
        lastPaintBytes = doneNow

        val done = doneNow
        // 10,000-step scale: immune to setProgress Int overflow, plenty smooth.
        val scaled = if (sessionTotal > 0) (done.coerceAtMost(sessionTotal) * 10_000 / sessionTotal).toInt() else 0
        val progressText = if (sessionTotal > 0) {
            "${FlitEngine.fmtBytes(done)} / ${FlitEngine.fmtBytes(sessionTotal)}"
        } else FlitEngine.fmtBytes(done)
        val speedText = if (sp > 1_000) "  ·  ${FlitEngine.fmtBytes(sp)}/s" else ""
        val current = ledger.entries.firstOrNull { it.value > 0 }?.key ?: ""

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            notifId,
            NotificationCompat.Builder(ctx, CHANNEL_TRANSFERS)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(if (current.isNotEmpty()) "$current · $progressText$speedText" else "$progressText$speedText")
                .setProgress(10_000, scaled, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(FlitEngine.openAppIntent())
                .build(),
        )
    }

    /** Final, sticky-until-swiped summary notification replacing the progress bar. */
    fun finish(summary: String, ok: Boolean, iconDone: Boolean) {
        kind = null
        if (activeSessions.decrementAndGet() <= 0) FlitEngine.pushTelemetry(null)
        FlitEngine.setTransferActive(false)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            notifId,
            NotificationCompat.Builder(ctx, CHANNEL_TRANSFERS)
                .setSmallIcon(
                    when {
                        iconDone -> android.R.drawable.stat_sys_download_done
                        ok -> android.R.drawable.stat_sys_upload_done
                        else -> android.R.drawable.stat_notify_error
                    }
                )
                .setContentTitle(if (ok) title.removeSuffix("…") else title)
                .setContentText(if (ok) summary else "✗ $summary")
                .setContentIntent(FlitEngine.openAppIntent())
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
                .build(),
        )
        if (ok) playChime()
    }

    /** Options → "Sound when transfers finish": a short system notification tone. */
    private fun playChime() {
        if (!FlitEngine.prefs().getBoolean("transferSound", false)) return
        runCatching {
            val uri = android.media.RingtoneManager
                .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            android.media.RingtoneManager.getRingtone(ctx, uri)?.play()
        }
    }
}
