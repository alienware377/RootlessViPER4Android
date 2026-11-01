package me.timschneeberger.rootlessjamesdsp.globalfx

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.settings.SettingsBaseFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService

/** Settings, Global mode (experimental). Reached only on the rootless build. */
class GlobalModeSettingsFragment : SettingsBaseFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_global_mode_preferences, rootKey)

        // The same tap-to-expand info row the effect cards use.
        findPreference<Preference>(getString(R.string.key_global_info))?.let { p ->
            val full = p.summary
            var expanded = false
            p.title = null
            p.setIcon(R.drawable.ic_twotone_info_24dp)
            p.summary = getString(R.string.section_info_tap_hint)
            p.setOnPreferenceClickListener {
                expanded = !expanded
                p.summary = if (expanded) full else getString(R.string.section_info_tap_hint)
                true
            }
        }

        val switch = findPreference<TwoStatePreference>(getString(R.string.key_global_mode))
        if (!GlobalMode.isAvailable()) {
            switch?.isEnabled = false
            switch?.summary = getString(R.string.global_mode_unavailable)
        }
        switch?.setOnPreferenceChangeListener { _, value ->
            val on = value as Boolean
            val captureRunning = on && BaseAudioProcessorService.activeServices > 0 && !GlobalEffectService.isAlive
            GlobalPower.onSwitchChanged(requireContext(), on)
            if (captureRunning) requireContext().toast(R.string.global_mode_capture_running, false)
            true
        }

        findPreference<Preference>(getString(R.string.key_global_check))?.setOnPreferenceClickListener {
            GlobalCheckDialog.newInstance().show(parentFragmentManager, GlobalCheckDialog.TAG)
            true
        }
    }
}
