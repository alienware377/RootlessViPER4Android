package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.EngineUtils.toggleEnginePower
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Processing on or off per output device.
 *
 * Each device profile can say whether processing should run while it is the
 * output, and switching output applies the new profile's choice. The usual
 * case is the phone speaker: most listening happens on it, few people want
 * effects there, and the capture notification and its cost are the price of
 * leaving processing on.
 *
 * Rooted, off means the engine's own power flag, so processing really stops.
 * Rootless, off means stopping capture altogether, which is the only thing
 * that removes the notification and the cost. Coming back on means starting
 * capture again, and Android normally only allows that through a consent
 * dialog: one every time a cable went in would be worse than the notification.
 * So on the rootless build this only runs once the app may restart capture
 * silently, which takes the PROJECT_MEDIA permission granted once through
 * Shizuku or ADB, plus permission to start from the background. Until then
 * the setting is shown greyed out with the reason.
 *
 * Nothing changes for anyone who does not turn this on: an output with no
 * choice made counts as on, and the feature itself starts off.
 */
object ProfilePower : KoinComponent {
    private val prefs: Preferences.App by inject()

    /** Both switches: profiles at all, and power following them */
    fun isEnabled(): Boolean =
        prefs.get<Boolean>(R.string.key_device_profiles_enable) &&
            prefs.get<Boolean>(R.string.key_profile_power_follow)

    /** Can processing be switched back on with nobody there to tap a dialog? */
    fun canRestartSilently(context: Context): Boolean =
        !isRootless() ||
            (SdkCheck.isQ && context.hasProjectMediaAppOp() && Settings.canDrawOverlays(context))

    private fun offIds(): Set<String> = try {
        Json.decodeFromString<List<String>>(prefs.get<String>(R.string.key_profile_power_off)).toSet()
    } catch (ex: Exception) {
        Timber.w(ex)
        emptySet()
    }

    private fun setOffIds(ids: Set<String>) =
        prefs.set(R.string.key_profile_power_off, Json.encodeToString(ids.toList().sorted()))

    fun isOn(profileId: String): Boolean = profileId !in offIds()

    /** Bring processing into line with what this output asks for */
    fun apply(context: Context, profileId: String?) {
        if (profileId == null || !isEnabled())
            return
        val on = isOn(profileId)
        Timber.d("Output '$profileId' wants processing ${if (on) "on" else "off"}")

        if (!isRootless()) {
            if (prefs.get<Boolean>(R.string.key_powered_on) != on)
                context.toggleEnginePower(on)
            return
        }

        val running = BaseAudioProcessorService.activeServices > 0
        if (!on && running) {
            RootlessAudioProcessorService.stop(context)
        }
        else if (on && !running) {
            if (!canRestartSilently(context)) {
                // The setting cannot be turned on in this state, but the
                // permission can be withdrawn afterwards. Do nothing rather
                // than throw a consent dialog up out of nowhere.
                Timber.w("Output wants processing on, but capture cannot restart silently")
                return
            }
            // The same silent start the boot receiver uses
            context.toggleEnginePower(true) { intent ->
                context.startActivity(intent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
            }
        }
    }

    /** Apply for whatever is playing right now */
    fun applyCurrent(context: Context) {
        val app = context.applicationContext as? MainApplication ?: return
        apply(context, app.profileManager.currentProfileId())
    }

    /** Tick the outputs processing should run on */
    fun showOutputsDialog(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext as? MainApplication ?: return
        val profiles = app.profileManager.allProfiles
        if (profiles.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.profile_power_outputs)
                .setMessage(R.string.profile_power_outputs_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val off = offIds()
        val checked = BooleanArray(profiles.size) { profiles[it].id !in off }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.profile_power_outputs)
            .setMultiChoiceItems(profiles.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Only the profiles shown are rewritten; ids of profiles not
                // on this list (deleted since) are kept as they were.
                val shown = profiles.map { it.id }.toSet()
                val newOff = off.filter { it !in shown }.toMutableSet()
                profiles.forEachIndexed { i, p -> if (!checked[i]) newOff.add(p.id) }
                setOffIds(newOff)
                applyCurrent(context)
                onDone?.invoke()
            }
            .show()
    }
}
