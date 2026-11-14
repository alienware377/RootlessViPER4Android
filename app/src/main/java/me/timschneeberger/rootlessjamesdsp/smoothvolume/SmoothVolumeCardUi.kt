package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import org.koin.core.context.GlobalContext
import rikka.shizuku.Shizuku

/**
 * The live parts of the smooth volume card: the line saying what it is doing,
 * the level slider, the volume key service row and the on-screen level switch.
 * Nothing here is a stored setting except that switch; the level and what is
 * held come from [SmoothVolumeController].
 */
class SmoothVolumeCardUi(private val fragment: PreferenceFragmentCompat) {
    private val controller: SmoothVolumeController? =
        try { GlobalContext.getOrNull()?.getOrNull<SmoothVolumeController>() } catch (_: Exception) { null }

    private val status = fragment.findPreference<Preference>(KEY_STATUS)
    private val slider = fragment.findPreference<MaterialSeekbarPreference>(KEY_LEVEL)
    private val keys = fragment.findPreference<Preference>(KEY_KEYS)
    private val hud = fragment.findPreference<Preference>(fragment.getString(R.string.key_smooth_volume_hud))

    private val listener = SmoothVolumeController.Listener { update() }
    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_REQUEST && result == PackageManager.PERMISSION_GRANTED) enableKeys()
    }

    fun attach() {
        controller?.addListener(listener)
        try { Shizuku.addRequestPermissionResultListener(shizukuResult) } catch (_: Throwable) {}

        // The step is set here: the XML attribute only takes whole numbers.
        slider?.setSeekBarIncrement(SmoothVolumeMath.STEP_DB.toFloat())
        slider?.setOnPreferenceChangeListener { _, value ->
            (value as? Float)?.let { controller?.setLevel(it.toDouble()) }
            true
        }
        keys?.setOnPreferenceClickListener {
            if (VolumeKeyAccess.isEnabled(it.context)) it.context.startActivity(VolumeKeyAccess.settingsIntent())
            else enableKeys()
            true
        }
        hud?.setOnPreferenceChangeListener { p, value ->
            if (value == true && !Settings.canDrawOverlays(p.context)) {
                // Asked for here and only here: nothing else in the app draws
                // over other apps, so nobody who leaves this off is asked.
                Toast.makeText(p.context, R.string.smooth_volume_hud_permission, Toast.LENGTH_LONG).show()
                try {
                    p.context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + p.context.packageName)))
                } catch (_: Exception) {}
            }
            true
        }
        update()
    }

    fun detach() {
        controller?.removeListener(listener)
        try { Shizuku.removeRequestPermissionResultListener(shizukuResult) } catch (_: Throwable) {}
    }

    /** Also on resume: the key service and the overlay permission are changed outside the app. */
    fun update() {
        val ctx = fragment.context ?: return
        val c = controller
        status?.summary = when (c?.blocked) {
            null, SmoothVolumeController.Blocked.OFF -> ctx.getString(R.string.smooth_volume_status_off)
            SmoothVolumeController.Blocked.MODE -> ctx.getString(R.string.smooth_volume_status_mode)
            SmoothVolumeController.Blocked.NOT_PROCESSING -> ctx.getString(R.string.smooth_volume_status_waiting)
            SmoothVolumeController.Blocked.ROOT_POWERED_OFF -> ctx.getString(R.string.smooth_volume_status_powered_off)
            SmoothVolumeController.Blocked.ROOT_MODULE_OLD -> ctx.getString(R.string.outdated_engine_card)
            SmoothVolumeController.Blocked.VOLUME_FIXED -> ctx.getString(R.string.smooth_volume_status_fixed)
            SmoothVolumeController.Blocked.NONE -> {
                val l = c.level
                val db = c.effectiveDb
                if (l == null || db == null) ctx.getString(R.string.smooth_volume_status_waiting)
                else ctx.getString(R.string.smooth_volume_status_holding,
                    SmoothVolumeMath.formatDb(db), c.nearestIndex ?: 0, c.maxIndex,
                    l.holdIndex, c.previousIndex)
            }
        }
        slider?.isEnabled = c?.active == true
        c?.effectiveDb?.let { db ->
            val v = db.toFloat().coerceIn(slider?.getMin() ?: -100f, 0f)
            if (slider != null && kotlin.math.abs(slider.getValue() - v) >= 0.25f) slider.setValue(v)
        }
        keys?.summary = ctx.getString(
            if (VolumeKeyAccess.isEnabled(ctx)) R.string.smooth_volume_keys_on
            else R.string.smooth_volume_keys_off)
        hud?.summary = ctx.getString(
            if (Settings.canDrawOverlays(ctx)) R.string.smooth_volume_hud_summary
            else R.string.smooth_volume_hud_summary_no_permission)
    }

    private fun enableKeys() {
        val ctx = fragment.context ?: return
        when {
            VolumeKeyAccess.canUseShizuku() -> fragment.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { VolumeKeyAccess.enableWithShizuku(ctx.applicationContext) }
                if (ok) Toast.makeText(ctx, R.string.smooth_volume_keys_enabled, Toast.LENGTH_SHORT).show()
                else explainKeys()
                update()
            }
            VolumeKeyAccess.shizukuRunning() && !Shizuku.isPreV11() ->
                try { Shizuku.requestPermission(SHIZUKU_REQUEST) } catch (_: Throwable) { explainKeys() }
            else -> explainKeys()
        }
    }

    private fun explainKeys() {
        val ctx = fragment.context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.smooth_volume_keys_title)
            .setMessage(R.string.smooth_volume_keys_explain)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smooth_volume_keys_open_settings) { _, _ ->
                try { ctx.startActivity(VolumeKeyAccess.settingsIntent()) } catch (_: Exception) {}
            }
            .show()
    }

    companion object {
        const val KEY_STATUS = "smooth_volume_status"
        const val KEY_LEVEL = "smooth_volume_level"
        const val KEY_KEYS = "smooth_volume_keys"
        private const val SHIZUKU_REQUEST = 4127
    }
}
