package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import me.timschneeberger.hiddenapi_impl.ShizukuSystemServerApi
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Switching the volume key service on. Android only allows that from its
 * accessibility settings, or to something holding the shell's rights. With
 * Shizuku running and allowed, this app has those rights and does it in one
 * tap; otherwise the card explains and opens the settings page.
 */
object VolumeKeyAccess {
    fun component(context: Context) = ComponentName(context, VolumeKeyService::class.java)

    fun isEnabled(context: Context): Boolean {
        val list = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = component(context)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Shizuku is running; [canUseShizuku] says whether it has allowed this app yet. */
    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun canUseShizuku(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /**
     * Adds the service to the enabled list through Shizuku, keeping every
     * other service that is on. Blocks on the shell, so not on the main
     * thread. True if the service is on afterwards.
     */
    fun enableWithShizuku(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (!canUseShizuku()) return false
        val me = component(context).flattenToString()
        val current = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val list = current.split(':').filter { it.isNotBlank() && it != me } + me
        // Component names are package and class names, so nothing in the list
        // needs escaping inside single quotes.
        ShizukuSystemServerApi.exec(
            "settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} '${list.joinToString(":")}'\n" +
            "settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1"
        )
        return isEnabled(context).also { Timber.i("Volume key service enabled through Shizuku: $it") }
    }

    /** The system's accessibility settings, where it can be switched on by hand. */
    fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
