package app.flit.share

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the sharing engine alive while the app is backgrounded. All actual
 * work lives in [FlitEngine]; the service is only the Android lifecycle wrapper.
 */
class FlitService : Service() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set on a rejected FGS start: the service shuts down but the engine lives. */
    @Volatile
    private var engineOnly = false

    companion object {
        const val NOTIFICATION_ID = 4711

        /**
         * Starts the FGS when the system allows it (Android 15 rations dataSync
         * background hours); returns false when it must run engine-only.
         */
        fun launch(context: Context): Boolean = try {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, FlitService::class.java),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        FlitEngine.channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 15+ refuses a dataSync FGS started from BOOT_COMPLETED. The
        // refusal is raised by startForeground() here — not by the receiver —
        // so it lands in this process: degrade to engine-only for that boot
        // (best effort; check once on the next normal open) instead of crashing.
        var fgOk = true
        runCatching { promoteForeground() }.onFailure { e ->
            engineOnly = true
            FlitEngine.logLine(
                "system refused background service (${e.javaClass.simpleName}) — " +
                    "running engine-only until you open Flit next",
            )
            // Engine without the wrapper: no notification, same serving ability.
            val st = FlitEngine.state.value
            if (!st.running && !st.starting) FlitEngine.start(applicationContext)
            stopSelf()
            fgOk = false
        }
        if (fgOk) {
            val st = FlitEngine.state.value
            if (!st.running && !st.starting) {
                FlitEngine.start(applicationContext)
            }

            // Re-post with the settled state (final port) once start completes; the
            // first post above is needed before the FGS timeout window.
            uiScope.launch {
                var waited = 0L
                while (waited < 15_000) {
                    delay(300)
                    waited += 300
                    val s = FlitEngine.state.value
                    if (s.running || s.error != null) break
                }
                val settled = FlitEngine.state.value
                if (settled.error != null) stopSelf() else postNotification(settled)
            }
        }
        return if (engineOnly) START_NOT_STICKY else START_STICKY
    }

    private fun promoteForeground() = postNotification(FlitEngine.state.value)

    private fun postNotification(st: FlitEngine.EngineState) {
        val notification = FlitEngine.serviceNotification(
            this,
            st.alias.ifEmpty { FlitEngine.currentAlias() },
            st.port,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        // Degraded (engine-only) boots: the engine was just started here, so do
        // not stop it along with the service.
        if (!engineOnly) FlitEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
