package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.os.Bundle
import androidx.preference.*
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.model.preference.ThemeMode
import me.timschneeberger.rootlessjamesdsp.preference.ThemesPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.isDynamicColorAvailable

class SettingsAppearanceFragment : SettingsBaseFragment() {

    private val themeMode by lazy { findPreference<ListPreference>(getString(R.string.key_appearance_theme_mode)) }
    private val amoledMode by lazy { findPreference<Preference>(getString(R.string.key_appearance_pure_black)) }
    private val appTheme by lazy { findPreference<ThemesPreference>(getString(R.string.key_appearance_app_theme)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_appearance_preferences, rootKey)

        findPreference<androidx.preference.Preference>("theme_builder_open")
            ?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(),
                    me.timschneeberger.rootlessjamesdsp.activity.ThemeBuilderActivity::class.java))
                true
            }

        val appThemes = AppTheme.values().filter {
            val monetFilter = if (it == AppTheme.MONET) {
                isDynamicColorAvailable
            } else {
                true
            }
            it.titleResId != null && monetFilter
        }
        appTheme?.entries = appThemes

        // The listener runs before the new value is stored, so it has to use
        // the value it is handed. Reading the stored one showed or hid Pure
        // black for the previous theme mode.
        themeMode?.setOnPreferenceChangeListener { _, newValue ->
            updateViewStates(newValue as? String)
            true
        }
        updateViewStates()

        savedInstanceState?.let {
            appTheme?.lastScrollPosition = it.getInt(STATE_THEMES_SCROLL_POSITION, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appTheme?.let {
            outState.putInt(STATE_THEMES_SCROLL_POSITION, it.lastScrollPosition ?: 0)
        }
        super.onSaveInstanceState(outState)
    }

    private fun updateViewStates(mode: String? = themeMode?.value) {
        amoledMode?.isVisible = mode?.toIntOrNull()?.let { ThemeMode.fromInt(it) } != ThemeMode.Light
    }

    companion object {
        private const val STATE_THEMES_SCROLL_POSITION = "stateThemesScrollPosition"

        fun newInstance(): SettingsAppearanceFragment {
            return SettingsAppearanceFragment()
        }
    }
}
