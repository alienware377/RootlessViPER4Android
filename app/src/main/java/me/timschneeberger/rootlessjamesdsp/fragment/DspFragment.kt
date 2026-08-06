package me.timschneeberger.rootlessjamesdsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDspBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.EffectLayoutManager
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

class DspFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefsApp: Preferences.App by inject()
    private val prefsVar: Preferences.Var by inject()

    private lateinit var binding: FragmentDspBinding
    private var layoutManager: EffectLayoutManager? = null
    private var updateNoticeOnClick: (() -> Unit)? = null
    private var updateNoticeOnCloseClick: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDspBinding.inflate(layoutInflater, container, false)

        binding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        binding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/rootlessjamesdsp")))
            hideTranslationNotice()
        }

        binding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        binding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Should show notice?
        Timber.e(Locale.getDefault().language.toString())
        binding.translationNotice.isVisible =
           prefsVar.get<Long>(R.string.key_snooze_translation_notice) < (System.currentTimeMillis() / 1000L) &&
                    !Locale.getDefault().language.equals("en")
        binding.updateNotice.isVisible = false

        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        binding.cardContainer.layoutTransition = transition
        // Inflating every effect card at once blocks the first frame for
        // seconds. Commit the first few immediately, then let the rest fill
        // in on the next frame so the app opens instantly.
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.card_device_profiles, DeviceProfilesCardFragment.newInstance())
            .replace(
                R.id.card_output_control, PreferenceGroupFragment.newInstance(Constants.PREF_OUTPUT,
                    R.xml.dsp_output_control_preferences
                ))
            .replace(
                R.id.card_compressor, PreferenceGroupFragment.newInstance(Constants.PREF_COMPANDER,
                    R.xml.dsp_compander_preferences
                ))
            .replace(
                R.id.card_bass, PreferenceGroupFragment.newInstance(Constants.PREF_BASS,
                    R.xml.dsp_bass_preferences
                ))
            .replace(
                R.id.card_bassex, PreferenceGroupFragment.newInstance(Constants.PREF_BASSEX,
                    R.xml.dsp_bassex_preferences
                ))
            .replace(
                R.id.card_vdynbass, PreferenceGroupFragment.newInstance(Constants.PREF_VDYNBASS,
                    R.xml.dsp_vdynbass_preferences
                ))
            .commitAllowingStateLoss()

        // The remaining cards are installed only once they're about to scroll
        // into view. Committing them all up front cost ~3.4s of main-thread
        // time (measured), which is what froze the UI on startup.
        deferredCards.clear()
        deferredCards.addAll(deferredCardSpecs)
        binding.dspScrollview.viewTreeObserver.addOnScrollChangedListener {
            installVisibleCards()
        }
        binding.root.post { installVisibleCards() }

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }
        setupEffectSearch()
        setupLayoutCustomizer()


        return binding.root
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            getString(R.string.key_device_profiles_enable) -> {
                (binding.cardDeviceProfiles.parent as ViewGroup).isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
        }
    }

    private fun hideTranslationNotice() {
        binding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    fun setUpdateCardVisible(visible: Boolean) {
        binding.updateNotice.isVisible = visible
    }

    fun setUpdateCardTitle(title: String) {
        binding.updateNotice.titleText = title
    }

    fun setUpdateCardOnClick(onClick: () -> Unit) {
        updateNoticeOnClick = onClick
    }

    fun setUpdateCardOnCloseClick(onClick: () -> Unit) {
        updateNoticeOnCloseClick = onClick
    }

    fun restartFragment(id: Int, newFragment: Fragment) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                childFragmentManager.beginTransaction()
                    .replace(id, newFragment)
                    .commitAllowingStateLoss()
            }
            catch(ex: IllegalStateException) {
                Timber.e("Failed to restart fragment")
                Timber.i(ex)
            }
        }
    }

    companion object {
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }

    private data class CardEntry(val cardId: Int, val titleRes: Int)

    private val searchableCards = listOf(
        CardEntry(R.id.card_output_control, R.string.output_control_header),
        CardEntry(R.id.card_compressor, R.string.compander_enable_v2),
        CardEntry(R.id.card_bass, R.string.bass_enable),
        CardEntry(R.id.card_bassex, R.string.bassex_enable),
        CardEntry(R.id.card_vdynbass, R.string.v4a_vdynbass_title),
        CardEntry(R.id.card_diffsurround, R.string.diffsurround_enable),
        CardEntry(R.id.card_clarity, R.string.clarity_enable),
        CardEntry(R.id.card_fieldsurround, R.string.fieldsurround_enable),
        CardEntry(R.id.card_hpsurround, R.string.v4a_hpsurround_title),
        CardEntry(R.id.card_fetcomp, R.string.fetcomp_enable),
        CardEntry(R.id.card_cure, R.string.cure_enable),
        CardEntry(R.id.card_viperbass, R.string.viperbass_enable),
        CardEntry(R.id.card_vreverb, R.string.vreverb_enable),
        CardEntry(R.id.card_speakeropt, R.string.speakeropt_enable),
        CardEntry(R.id.card_pitchshift, R.string.pitchshift_enable),
        CardEntry(R.id.card_echo, R.string.echo_enable),
        CardEntry(R.id.card_agc, R.string.v4a_agc_title),
        CardEntry(R.id.card_eq, R.string.v4a_eq_title),
        CardEntry(R.id.card_geq, R.string.geq_enable),
        CardEntry(R.id.card_peq, R.string.peq_enable),
        CardEntry(R.id.card_ddc, R.string.v4a_ddc_title),
        CardEntry(R.id.card_convolver, R.string.convolver_enable),
        CardEntry(R.id.card_liveprog, R.string.liveprog_enable),
        CardEntry(R.id.card_liveprog2, R.string.liveprog2_enable),
        CardEntry(R.id.card_liveprog3, R.string.liveprog3_enable),
        CardEntry(R.id.card_liveprog4, R.string.liveprog4_enable),
        CardEntry(R.id.card_tube, R.string.v4a_tube_title),
        CardEntry(R.id.card_spectrumext, R.string.spectrumext_enable),
        CardEntry(R.id.card_stereowide, R.string.stereowide_enable),
        CardEntry(R.id.card_crossfeed, R.string.crossfeed_enable),
        CardEntry(R.id.card_reverb, R.string.reverb_enable),
    )

    /** Hides cards whose title doesn't match the query. Empty query restores everything. */
    private fun applyEffectSearch(query: String) {
        val q = query.trim().lowercase(Locale.getDefault())
        val searching = q.isNotEmpty()
        var matches = 0

        searchableCards.forEach { entry ->
            val card = binding.root.findViewById<View>(entry.cardId)?.parent as? View
            if (card != null) {
                val title = getString(entry.titleRes).lowercase(Locale.getDefault())
                val userHidden = layoutManager?.isHidden(
                    resources.getResourceEntryName(entry.cardId)
                ) == true
                val visible = !userHidden && (!searching || title.contains(q))
                card.isVisible = visible
                if (visible && searching) matches++
            }
        }

        // Group headers and non-effect cards only make sense outside of search
        binding.root.findViewById<View>(R.id.v4a_section_header)?.isVisible = !searching
        binding.root.findViewById<View>(R.id.card_device_profiles)?.let {
            (it.parent as? View)?.isVisible = !searching
        }
        binding.searchEmpty.isVisible = searching && matches == 0
    }

    private data class CardSpec(val viewId: Int, val prefName: String, val xmlRes: Int)

    /** Cards not shown on first paint; installed lazily as the user scrolls. */
    private val deferredCardSpecs = listOf(
        CardSpec(R.id.card_diffsurround, Constants.PREF_DIFFSURROUND, R.xml.dsp_diffsurround_preferences),
        CardSpec(R.id.card_clarity, Constants.PREF_CLARITY, R.xml.dsp_clarity_preferences),
        CardSpec(R.id.card_fieldsurround, Constants.PREF_FIELDSURROUND, R.xml.dsp_fieldsurround_preferences),
        CardSpec(R.id.card_hpsurround, Constants.PREF_HPSURROUND, R.xml.dsp_hpsurround_preferences),
        CardSpec(R.id.card_fetcomp, Constants.PREF_FETCOMP, R.xml.dsp_fetcomp_preferences),
        CardSpec(R.id.card_cure, Constants.PREF_CURE, R.xml.dsp_cure_preferences),
        CardSpec(R.id.card_viperbass, Constants.PREF_VIPERBASS, R.xml.dsp_viperbass_preferences),
        CardSpec(R.id.card_vreverb, Constants.PREF_VREVERB, R.xml.dsp_vreverb_preferences),
        CardSpec(R.id.card_speakeropt, Constants.PREF_SPEAKEROPT, R.xml.dsp_speakeropt_preferences),
        CardSpec(R.id.card_pitchshift, Constants.PREF_PITCHSHIFT, R.xml.dsp_pitchshift_preferences),
        CardSpec(R.id.card_echo, Constants.PREF_ECHODELAY, R.xml.dsp_echo_preferences),
        CardSpec(R.id.card_liveprog2, Constants.PREF_LIVEPROG2, R.xml.dsp_liveprog2_preferences),
        CardSpec(R.id.card_liveprog3, Constants.PREF_LIVEPROG3, R.xml.dsp_liveprog3_preferences),
        CardSpec(R.id.card_liveprog4, Constants.PREF_LIVEPROG4, R.xml.dsp_liveprog4_preferences),
        CardSpec(R.id.card_agc, Constants.PREF_AGC, R.xml.dsp_agc_preferences),
        CardSpec(R.id.card_eq, Constants.PREF_EQ, R.xml.dsp_equalizer_preferences),
        CardSpec(R.id.card_geq, Constants.PREF_GEQ, R.xml.dsp_graphiceq_preferences),
        CardSpec(R.id.card_peq, Constants.PREF_PEQ, R.xml.dsp_parametriceq_preferences),
        CardSpec(R.id.card_ddc, Constants.PREF_DDC, R.xml.dsp_ddc_preferences),
        CardSpec(R.id.card_convolver, Constants.PREF_CONVOLVER, R.xml.dsp_convolver_preferences),
        CardSpec(R.id.card_liveprog, Constants.PREF_LIVEPROG, R.xml.dsp_liveprog_preferences),
        CardSpec(R.id.card_tube, Constants.PREF_TUBE, R.xml.dsp_tube_preferences),
        CardSpec(R.id.card_spectrumext, Constants.PREF_SPECTRUMEXT, R.xml.dsp_spectrumext_preferences),
        CardSpec(R.id.card_stereowide, Constants.PREF_STEREOWIDE, R.xml.dsp_stereowide_preferences),
        CardSpec(R.id.card_crossfeed, Constants.PREF_CROSSFEED, R.xml.dsp_crossfeed_preferences),
        CardSpec(R.id.card_reverb, Constants.PREF_REVERB, R.xml.dsp_reverb_preferences),
    )

    private val deferredCards = ArrayList<CardSpec>()
    private var installingCards = false

    /**
     * Installs the preference fragment for any pending card that is within one
     * screen height of the viewport. Keeps startup cheap without the user ever
     * seeing an empty card.
     */
    private fun installVisibleCards() {
        if (installingCards || deferredCards.isEmpty() || !isAdded) return
        installingCards = true
        try {
            val scroll = binding.dspScrollview
            val top = scroll.scrollY
            val bottom = top + scroll.height + scroll.height / 2 // half a screen of lookahead

            val ready = deferredCards.filter { spec ->
                val card = binding.root.findViewById<View>(spec.viewId)?.parent as? View
                    ?: return@filter false
                card.top < bottom && card.bottom > top - scroll.height
            }
            if (ready.isEmpty()) return

            // Cap per pass so a fling never triggers a long stall
            val batch = ready.take(2)
            val tx = childFragmentManager.beginTransaction().setReorderingAllowed(true)
            batch.forEach { spec ->
                tx.replace(
                    spec.viewId,
                    PreferenceGroupFragment.newInstance(spec.prefName, spec.xmlRes)
                )
            }
            tx.commitAllowingStateLoss()
            deferredCards.removeAll(batch.toSet())

            if (deferredCards.isNotEmpty()) {
                binding.root.postDelayed({ installVisibleCards() }, 48)
            } else {
                layoutManager?.applyLayout()
            }
        } finally {
            installingCards = false
        }
    }

    private fun setupEffectSearch() {
        binding.searchInput.addTextChangedListener(
            afterTextChanged = { applyEffectSearch(it?.toString() ?: "") }
        )
        // iOS-style: search sits just above the content, revealed by pulling down
        binding.dspScrollview.post {
            val h = binding.searchCard.height
            if (h > 0 && binding.searchInput.text.isNullOrEmpty()) {
                binding.dspScrollview.scrollTo(0, h)
            }
        }
    }


    private fun setupLayoutCustomizer() {
        val entries = ArrayList<EffectLayoutManager.Item>()
        entries.add(
            EffectLayoutManager.Item(
                "group_v4a", R.id.v4a_section_header, R.string.v4a_section_header, isHeader = true
            )
        )
        searchableCards.forEach { card ->
            entries.add(
                EffectLayoutManager.Item(
                    resources.getResourceEntryName(card.cardId), card.cardId, card.titleRes
                )
            )
        }

        val manager = EffectLayoutManager(requireContext(), binding.cardContainer, entries)
        layoutManager = manager
        manager.applyLayout()

        manager.onEditModeChanged = { editing ->
            binding.editLayoutButton.setImageResource(
                if (editing) R.drawable.ic_twotone_check_24dp else R.drawable.ic_twotone_edit_24dp
            )
            binding.searchInput.isEnabled = !editing
            binding.chainOrderButton.isVisible = editing
            if (editing) {
                Snackbar.make(binding.root, R.string.effect_edit_hint, Snackbar.LENGTH_LONG).show()
            }
        }

        binding.chainOrderButton.setOnClickListener {
            ProcessingOrderDialogFragment.newInstance()
                .show(childFragmentManager, "processing_order")
        }

        binding.editLayoutButton.setOnLongClickListener {
            requireContext().showYesNoAlert(
                R.string.effect_reset_layout,
                R.string.effect_reset_layout_confirm
            ) { confirmed ->
                if (confirmed) {
                    if (manager.editMode) manager.exitEditMode()
                    manager.resetLayout()
                }
            }
            true
        }

        binding.editLayoutButton.setOnClickListener {
            if (!manager.editMode) {
                binding.searchInput.setText("")
                applyEffectSearch("")
            }
            manager.toggleEditMode()
        }
    }

    /** Lets the host activity close edit mode with the back button. */
    fun exitEditModeIfActive(): Boolean {
        val manager = layoutManager ?: return false
        if (!manager.editMode) return false
        manager.exitEditMode()
        return true
    }

}