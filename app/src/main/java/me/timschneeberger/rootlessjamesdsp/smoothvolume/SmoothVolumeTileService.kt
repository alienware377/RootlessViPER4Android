package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.koin.core.context.GlobalContext

/**
 * Quick settings tile: smooth volume on or off, with the level underneath
 * while it is holding.
 */
class SmoothVolumeTileService : TileService() {
    private val controller: SmoothVolumeController?
        get() = try { GlobalContext.getOrNull()?.getOrNull<SmoothVolumeController>() } catch (_: Exception) { null }

    private val listener = SmoothVolumeController.Listener { update() }

    override fun onStartListening() {
        super.onStartListening()
        controller?.addListener(listener)
        update()
    }

    override fun onStopListening() {
        controller?.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val c = controller ?: return
        @Suppress("DEPRECATION")
        getSharedPreferences(Constants.PREF_SMOOTH_VOLUME, Context.MODE_MULTI_PROCESS).edit()
            .putBoolean(getString(R.string.key_smooth_volume_enable), !c.enabled)
            .commit()
        c.refresh()
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val c = controller
        tile.state = when {
            c == null -> Tile.STATE_UNAVAILABLE
            c.enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                c == null || !c.enabled -> null
                c.active -> c.effectiveDb?.let { SmoothVolumeMath.formatDb(it) }
                else -> getString(R.string.smooth_volume_tile_waiting)
            }
        }
        tile.updateTile()
    }
}
