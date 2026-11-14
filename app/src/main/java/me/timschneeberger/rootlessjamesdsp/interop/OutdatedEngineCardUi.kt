package me.timschneeberger.rootlessjamesdsp.interop

import androidx.preference.PreferenceFragmentCompat
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.isRoot

/**
 * Greys out, in root mode, the cards an older root module would accept and
 * then ignore, and says the module needs flashing again. Only the screen
 * changes: the card's settings stay as they are for when it is flashed.
 */
object OutdatedEngineCardUi {
    /** The cards an older module ignores, and the module version each needs. */
    val needsModuleVersion = mapOf(
        Constants.PREF_SHAPER to 5,
        Constants.PREF_BINAURAL to 5,
        Constants.PREF_BASSEX to 6,
        Constants.PREF_VDYNBASS to 6,
        Constants.PREF_DIFFSURROUND to 6,
        Constants.PREF_MULTIBANDDIST to 7,
        Constants.PREF_LUFS to 8,
        Constants.PREF_LOUDNESS to 8,
    )

    fun apply(fragment: PreferenceFragmentCompat, namespace: String?) {
        if (!isRoot()) return
        val needs = needsModuleVersion[namespace] ?: return
        if (JamesDspRemoteEngine.isPluginInstalled() != JamesDspRemoteEngine.PluginState.Outdated) return
        if (JamesDspRemoteEngine.installedEngineVersion() >= needs) return
        val ctx = fragment.context ?: return
        val screen = fragment.preferenceScreen ?: return
        if (screen.preferenceCount == 0) return
        // Disabling the header disables its rows with it, and its switch.
        val header = screen.getPreference(0)
        header.isEnabled = false
        header.summaryProvider = null
        header.summary = ctx.getString(R.string.outdated_engine_card)
        for (i in 1 until screen.preferenceCount) screen.getPreference(i).isEnabled = false
    }
}
