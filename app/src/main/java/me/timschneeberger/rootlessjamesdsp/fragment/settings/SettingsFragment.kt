package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.os.Bundle
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.isPlugin
import me.timschneeberger.rootlessjamesdsp.utils.isRootless


class SettingsFragment : SettingsBaseFragment() {

    private val processing by lazy { findPreference<Preference>(getString(R.string.key_audio_format)) }
    private val troubleshooting by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_preferences, rootKey)

        findPreference<androidx.preference.TwoStatePreference>(getString(R.string.key_v4a_mode))?.apply {
            val appPrefs = requireContext().getSharedPreferences(
                me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_APP,
                android.content.Context.MODE_MULTI_PROCESS)
            isChecked = appPrefs.getBoolean(me.timschneeberger.rootlessjamesdsp.utils.V4aMode.KEY, false)
            setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                appPrefs.edit().putBoolean(me.timschneeberger.rootlessjamesdsp.utils.V4aMode.KEY, on).apply()
                if (on)
                    me.timschneeberger.rootlessjamesdsp.utils.V4aMode.disableNonV4aEffects(requireContext())
                true
            }
        }

        processing?.summary = getString(
            when {
                isRootless() -> R.string.audio_format_summary
                isPlugin() -> R.string.audio_format_summary_plugin
                else -> R.string.audio_format_summary_root
            }
        )
        troubleshooting?.isVisible = isRootless()
    }

    companion object {
        fun newInstance(): SettingsFragment {
            return SettingsFragment()
        }
    }
}