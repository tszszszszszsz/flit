package app.flit.share

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Quick Settings toggle for sharing. Tap toggles the engine via [FlitService].
 * Subtitle mirrors the UI: "visible" with port / idle.
 */
class FlitTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        paint(FlitEngine.state.value)
    }

    override fun onClick() {
        super.onClick()
        val st = FlitEngine.state.value
        if (st.running || st.starting) {
            stopService(Intent(applicationContext, FlitService::class.java))
        } else if (!FlitService.launch(applicationContext)) {
            // FGS budget exhausted: run engine-only while something is foreground.
            FlitEngine.start(applicationContext)
            android.widget.Toast.makeText(applicationContext, "Flit runs while an app is open", android.widget.Toast.LENGTH_SHORT).show()
        }
        paint(FlitEngine.state.value)
    }

    private fun paint(st: FlitEngine.EngineState) {
        val tile = qsTile ?: return
        when {
            st.running -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "visible · port ${st.port}"
            }
            st.starting -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "starting…"
            }
            st.error != null -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "start failed"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "off"
            }
        }
        tile.updateTile()
    }
}
