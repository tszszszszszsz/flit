package app.flit.share

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the sharing engine alive while the app is backgrounded. All actual
 * work lives in [FlitEngine]; the service is only the Android lifecycle wrapper.
 *
 * It must survive an external viewer/chooser taking the foreground (Circle →
 * "Open file" → system chooser → viewer). That hinges on two things:
 *  - a *valid* FGS. An over-broad type request (connectedDevice without a live
 *    Bluetooth grant) makes API 34+/MIUI reject startForeground and silently
 *    drops us into engine-only mode, which dies the moment we background;
 *  - an honest stop path. One refused start must not poison every later Stop.
 */
class FlitService : Service() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** True only while this instance owns the engine through a live FGS. */
    @Volatile
    private var foregrounded = false

    /** A dataSync timeout deliberately left the engine running without us. */
    @Volatile
    private var timedOut = false

    /** One settled-notification repost at a time (a re-launch must not stack them). */
    private var settleJob: Job? = null

    companion object {
        const val NOTIFICATION_ID = 4711

        /**
         * Starts the FGS when the system allows it; returns false only when the
         * *start request itself* is refused (e.g. a strict ROM blocks starting
         * from the background). A refusal raised later, inside startForeground,
         * is handled in [onStartCommand] and keeps the engine running engine-only.
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
        // Promote FIRST: Android gives us only a few seconds from
        // startForegroundService() to call startForeground(), and that clock
        // starts here. A sticky restart calls this with a null intent.
        val refusal = runCatching { promoteForeground() }.exceptionOrNull()
        if (refusal == null) {
            foregrounded = true
            // A live FGS means sharing is genuinely wanted — remember it so a
            // process death or a dataSync timeout can be restored on relaunch.
            FlitRoster.setSharingDesired(true)
            val st = FlitEngine.state.value
            if (!st.running && !st.starting) FlitEngine.start(applicationContext)
            repostSettled(startId)
            return START_STICKY
        }

        // FGS refused (e.g. dataSync from BOOT_COMPLETED on Android 15, or a
        // MIUI battery rule). Keep the engine alive in-process — that still
        // serves peers while one of our own surfaces is foreground — but do
        // not let this one refused start turn every later Stop into a no-op.
        foregrounded = false
        FlitEngine.logLine(
            "system refused foreground service (${refusal.javaClass.simpleName}) — " +
                "running engine-only until you open Flit next",
        )
        FlitEngine.toast("background sharing limited by system; works while Flit is open")
        val st = FlitEngine.state.value
        if (!st.running && !st.starting) FlitEngine.start(applicationContext)
        stopSelf(startId) // only this start; a concurrently promoted start stays up
        return START_NOT_STICKY
    }

    /** Re-post with the settled state (final port) once start completes. */
    private fun repostSettled(startId: Int) {
        settleJob?.cancel()
        settleJob = uiScope.launch {
            var waited = 0L
            while (waited < 15_000) {
                delay(300)
                waited += 300
                val s = FlitEngine.state.value
                if (s.running || s.error != null) break
            }
            val settled = FlitEngine.state.value
            if (settled.error != null) {
                stopSelf(startId) // onDestroy tears the failed engine down too
            } else {
                runCatching { postNotification(settled) }
            }
        }
    }

    private fun promoteForeground() = postNotification(FlitEngine.state.value)

    private fun postNotification(st: FlitEngine.EngineState) {
        val notification = FlitEngine.serviceNotification(
            this,
            st.alias.ifEmpty { FlitEngine.currentAlias() },
            st.port,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            // dataSync is always correct here (UDP discovery + TCP/TLS file
            // transfers) and is declared in the manifest, so it is never the
            // reason a start is refused. connectedDevice is only legal while
            // the Bluetooth carrier is enabled AND its runtime grant exists;
            // asking for it on a Wi-Fi-only session makes strict ROMs throw
            // SecurityException and silently cost us the FGS — exactly the
            // "sharing off after the viewer backgrounds us" failure.
            val connectedDevice = FlitEngine.prefs().getBoolean("bluetoothTransfer", false) &&
                FlitBluetooth.hasPermission(this)
            val flags = if (connectedDevice) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(NOTIFICATION_ID, notification, flags)
            } catch (_: SecurityException) {
                // A ROM rejected one of the types: retry with the baseline that
                // keeps the process alive. Losing the FGS is worse than losing
                // the connectedDevice label.
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Android 15+ caps dataSync FGS at six hours per day. Stop the notification
     * promptly — otherwise the system throws a fatal RemoteServiceException —
     * but leave the engine alone: while the UI is open it still keeps the
     * process alive, and a persisted "sharing desired" flag restores the engine
     * on the next cold start.
     */
    override fun onTimeout(startId: Int, flags: Int) {
        super.onTimeout(startId, flags)
        timedOut = true
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        uiScope.cancel()
        // Engine-only starts and dataSync timeouts deliberately outlive the
        // wrapper; an ordinary Stop must tear the engine down — and clear the
        // persisted intent so a later relaunch does not silently re-enable it.
        if (foregrounded && !timedOut) {
            FlitEngine.stop()
            FlitRoster.setSharingDesired(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
