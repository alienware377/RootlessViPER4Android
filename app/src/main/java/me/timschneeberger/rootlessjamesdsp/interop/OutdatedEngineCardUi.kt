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
    /** The cards that need the module version EngineName.REQUIRED_VERSION. */
    val needsCurrentModule = setOf(Constants.PREF_SHAPER, Constants.PREF_BINAURAL)

    fun apply(fragment: PreferenceFragmentCompat, namespace: String?) {
        if (!isRoot() || namespace !in needsCurrentModule) return
        if (JamesDspRemoteEngine.isPluginInstalled() != JamesDspRemoteEngine.PluginState.Outdated) return
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
