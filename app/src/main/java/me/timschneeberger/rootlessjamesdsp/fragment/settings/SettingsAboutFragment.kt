package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.Translator
import me.timschneeberger.rootlessjamesdsp.updates.GithubUpdater
import me.timschneeberger.rootlessjamesdsp.utils.isPlugin
import java.util.Locale


class SettingsAboutFragment : SettingsBaseFragment() {

    private val version by lazy { findPreference<Preference>(getString(R.string.key_credits_version)) }
    private val buildInfo by lazy { findPreference<Preference>(getString(R.string.key_credits_build_info)) }
    private val selfCheckUpdates by lazy { findPreference<Preference>(getString(R.string.key_credits_check_update)) }
    private val translatorsGroup by lazy { findPreference<PreferenceGroup>(getString(R.string.key_translators)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_about_preferences, rootKey)

        version?.summary = getString(
            R.string.about_version_summary,
            BuildConfig.VERSION_NAME,
            BuildConfig.UPSTREAM_VERSION
        )
        buildInfo?.isVisible = BuildConfig.DEBUG || BuildConfig.PREVIEW || isPlugin()
        val type = if(BuildConfig.PREVIEW)
            "Preview"
        else if(BuildConfig.DEBUG)
            "Debug"
        else
            "Release"

        buildInfo?.summary = "$type build (${BuildConfig.FLAVOR_dependencies}) @${BuildConfig.COMMIT_SHA} (compiled at ${BuildConfig.BUILD_TIME})"

        // The GitHub updater, for the rootless and rootful builds alike. The upstream
        // checker asked the original author's server and only ever ran on rootful.
        selfCheckUpdates?.isVisible = GithubUpdater.supported
        selfCheckUpdates?.setOnPreferenceClickListener {
            activity?.let { GithubUpdater.checkNow(it) }
            true
        }

        Translator.readLanguageMap(requireContext()).forEach { (cc, tls) ->
            translatorsGroup?.addPreference(Preference(requireContext()).apply {
                val language = Locale.forLanguageTag(cc).getDisplayLanguage(requireContext().resources.configuration.locales[0])
                val region = Locale.forLanguageTag(cc).getDisplayCountry(requireContext().resources.configuration.locales[0])

                isIconSpaceReserved = false
                title = if(region.isNullOrBlank()) language else "$language ($region)"
                summary = tls.joinToString(", ") { it.name }

                setOnPreferenceClickListener {
                    if(tls.size == 1)
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/profile/${tls[0].username}")))
                    else {
                        this@SettingsAboutFragment.context?.let { ctx ->
                            MaterialAlertDialogBuilder(ctx)
                                .setItems(tls.map { it.name }.toTypedArray()) { dialogInterface, i ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/profile/${tls[i].username}")))
                                    dialogInterface.dismiss()
                                }
                                .setTitle(title)
                                .setNegativeButton(getString(android.R.string.cancel)){ _, _ -> }
                                .create()
                                .show()
                        }
                    }
                    true
                }
            })
        }
    }

    companion object {
        fun newInstance(): SettingsAboutFragment {
            return SettingsAboutFragment()
        }
    }
}