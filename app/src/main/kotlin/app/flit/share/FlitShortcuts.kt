package app.flit.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Direct-share targets (Android Sharing Shortcuts): every pinned peer becomes
 * "Flit · peer" inside the system share sheet — Gallery → Share → "Flit ·
 * André" sends to André in one tap.
 *
 * Contract kept deliberately small:
 *  - [sync] is idempotent and safe to call from anywhere (engine, activity):
 *    it rewrites dynamic shortcuts only when the identity set actually changed,
 *    so announce storms cannot thrash ShortcutManager.
 *  - The shortcut intent carries [EXTRA_PEER_FP]; MainActivity does the rest
 *    (probe → intake ready → dispatch to that fingerprint only if online).
 *  - Only TRUSTED peers get targets: an accidental "share into the wood" is a
 *    trust decision this code refuses to make.
 */
object FlitShortcuts {

    /** This app's shortcut category — must match res/xml/share_targets.xml. */
    const val SHARE_CATEGORY = "app.flit.share.category"

    /** Which trusted peer a share-sheet shortcut aims at. */
    const val EXTRA_PEER_FP = "app.flit.share.PEER_FP"

    private var lastPushed: List<String> = emptyList()

    /** Resync dynamic shortcuts from the roster (pinned peers only). */
    fun sync(context: Context) {
        try {
            val current = FlitRoster.all()
                .sortedByDescending { it.pinnedMs }
                .take(6) // the sheet reads best with a handful of direct targets
            val identity = current.map { "${it.fingerprint}|${it.alias}" }
            if (identity == lastPushed) return
            lastPushed = identity

            val ctx = context.applicationContext
            val shortcuts = current.map { pinned ->
                val label = "Flit · ${pinned.alias}"
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "*/*"
                    putExtra(EXTRA_PEER_FP, pinned.fingerprint)
                }
                ShortcutInfoCompat.Builder(ctx, pinned.fingerprint)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(iconFor(pinned.alias, pinned.fingerprint))
                    .setIntent(intent)
                    .setCategories(setOf(SHARE_CATEGORY))
                    .setLongLived(true)
                    .build()
            }
            // Full replace: unpinned peers vanish from the sheet immediately,
            // ranks reset only when the trust set actually changed.
            ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts)
        } catch (_: Throwable) {
            // Sheets without ShortcutManager (rare OEMs, work profiles) — the
            // plain app target keeps every flow working.
        }
    }

    /** Tell the system this direct target was used — feeds sheet ranking. */
    fun reportUsed(context: Context, fingerprint: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, fingerprint) }
    }

    /** Initial-cap letter tile: glyph colors from the fingerprint, never two alike. */
    private fun iconFor(alias: String, fp: String): IconCompat {
        val out = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hsv = floatArrayOf(
            SheetUi.hueFor(fp).toFloat(),
            0.52f,
            0.82f,
        )
        paint.color = android.graphics.Color.HSVToColor(hsv)
        canvas.drawCircle(54f, 54f, 54f, paint)
        val glyph = SheetUi.initials(alias)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = replySize(glyph.length)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val baseline = 54f - (text.ascent() + text.descent()) / 2
        canvas.drawText(glyph, 54f, baseline, text)
        return IconCompat.createWithBitmap(out)
    }

    /** Two-glyph initials fit a tighter font; singles deserve the bigger statement. */
    private fun replySize(n: Int) = if (n >= 2) 44f else 52f
}
