package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.os.Bundle
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.ProfilePower
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert

class SettingsDeviceProfilesFragment : SettingsBaseFragment() {

    private val profilesInfo by lazy { findPreference<Preference>(getString(R.string.key_device_profiles_info)) }
    private val powerFollow by lazy { findPreference<Preference>(getString(R.string.key_profile_power_follow)) }
    private val powerOutputs by lazy { findPreference<Preference>(getString(R.string.key_profile_power_outputs)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_device_profiles_preferences, rootKey)

        profilesInfo?.setOnPreferenceClickListener {
            context?.showAlert(
                R.string.profiles_info_title,
                R.string.profiles_info_content
            )
            true
        }

        powerFollow?.setOnPreferenceChangeListener { _, newValue ->
            // The value is only written once this returns, and applying reads it
            if (newValue == true)
                view?.post { context?.let(ProfilePower::applyCurrent) }
            true
        }

        powerOutputs?.setOnPreferenceClickListener {
            context?.let(ProfilePower::showOutputsDialog)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Checked on every return, since the usual way to make it available is
        // to leave, grant the permission, and come back
        val ctx = context ?: return
        val available = ProfilePower.canRestartSilently(ctx)
        powerFollow?.isEnabled = available
        powerFollow?.setSummary(
            if (available) R.string.profile_power_follow_summary
            else R.string.profile_power_follow_unavailable
        )
    }

    companion object {
        fun newInstance(): SettingsDeviceProfilesFragment {
            return SettingsDeviceProfilesFragment()
        }
    }
}
