package app.flit.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Optional (Options → "Start on device boot"): brings sharing back after a
 * reboot so peers can reach this device without anyone opening Flit first.
 * The FGS start is system-exempted here; if the ROM still rations it, the
 * engine falls back to app-only, which survives until the system reclaims it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_USER_UNLOCKED -> "first unlock"
            else -> return
        }
        FlitEngine.logLine("boot receiver fired ($why)")
        if (!FlitEngine.prefs().getBoolean("startOnBoot", false)) {
            FlitEngine.logLine("boot: start-on-boot off — staying quiet")
            return
        }
        if (!FlitService.launch(context.applicationContext)) {
            FlitEngine.logLine("boot: FGS rationed — starting engine-only")
            FlitEngine.start(context.applicationContext)
        }
    }
}
