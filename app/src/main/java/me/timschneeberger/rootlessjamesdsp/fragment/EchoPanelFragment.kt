package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentEchoPanelBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.view.KnobView

/**
 * Knob panel for the echo/delay, laid out like a hardware delay unit: delay
 * time, model, feedback with its filter and bit/rate crushing, modulation,
 * diffusion, feedback distortion, and the input/output mix.
 *
 * Every control writes straight to the effect's preference namespace, so the
 * engine picks changes up live through the usual preference listener.
 */
class EchoPanelFragment : Fragment() {

    private var _binding: FragmentEchoPanelBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences(Constants.PREF_ECHODELAY, Context.MODE_MULTI_PROCESS)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEchoPanelBinding.inflate(inflater, container, false)

        bindKnob(binding.knobInput, R.string.key_echo_input, 100f)
        bindKnob(binding.knobTime, R.string.key_echo_time, 350f)
        bindKnob(binding.knobSmoothing, R.string.key_echo_smoothing, 20f)
        bindKnob(binding.knobOffset, R.string.key_echo_offset, 0f)
        bindKnob(binding.knobStereo, R.string.key_echo_stereo, 50f)
        bindKnob(binding.knobFeedback, R.string.key_echo_feedback, 40f)
        bindKnob(binding.knobCutoff, R.string.key_echo_cutoff, 12000f)
        bindKnob(binding.knobRes, R.string.key_echo_res, 10f)
        bindKnob(binding.knobSmpRate, R.string.key_echo_smp_rate, 100f)
        bindKnob(binding.knobBits, R.string.key_echo_bits, 24f)
        bindKnob(binding.knobModRate, R.string.key_echo_mod_rate, 0f)
        bindKnob(binding.knobModTime, R.string.key_echo_mod_time, 0f)
        bindKnob(binding.knobModCutoff, R.string.key_echo_mod_cutoff, 0f)
        bindKnob(binding.knobDiffusion, R.string.key_echo_diffusion, 0f)
        bindKnob(binding.knobSpread, R.string.key_echo_spread, 0f)
        bindKnob(binding.knobDistLevel, R.string.key_echo_dist_level, 0f)
        bindKnob(binding.knobKnee, R.string.key_echo_knee, 50f)
        bindKnob(binding.knobSymmetry, R.string.key_echo_symmetry, 0f)
        bindKnob(binding.knobTone, R.string.key_echo_tone, 0f)
        bindKnob(binding.knobWet, R.string.key_echo_wet, 35f)
        bindKnob(binding.knobDry, R.string.key_echo_dry, 100f)

        bindSwitch(binding.switchKeepPitch, R.string.key_echo_keep_pitch)

        bindToggle(
            binding.groupModel, R.string.key_echo_model, "1",
            intArrayOf(R.id.model_mono, R.id.model_stereo, R.id.model_pingpong, R.id.model_off)
        )
        bindToggle(
            binding.groupFilter, R.string.key_echo_filter, "0",
            intArrayOf(R.id.filter_lp, R.id.filter_hp, R.id.filter_bp, R.id.filter_off)
        )
        bindToggle(
            binding.groupDist, R.string.key_echo_dist_mode, "1",
            intArrayOf(R.id.dist_limit, R.id.dist_sat)
        )

        refreshUsability()

        return binding.root
    }


    /**
     * Dims and locks knobs the current settings make inert, instead of hiding
     * them - hiding would reflow the panel and move controls mid-gesture.
     * Dead cases, from the engine: filter Off ignores cutoff/res; mod rate 0
     * freezes the LFO so its time/cutoff do nothing; diffusion 0 bypasses
     * spread; distortion level 0 bypasses mode/knee/symmetry; model Off
     * bypasses the whole effect.
     */
    private fun refreshUsability() {
        fun set(v: View, usable: Boolean) {
            v.isEnabled = usable
            v.alpha = if (usable) 1f else 0.35f
        }
        val model = (prefs.getString(getString(R.string.key_echo_model), "1") ?: "1")
        val on = model != "3"
        val filterOff = (prefs.getString(getString(R.string.key_echo_filter), "0") ?: "0") == "3"
        val modOff = prefs.getFloat(getString(R.string.key_echo_mod_rate), 0f) <= 0.01f
        val diffOff = prefs.getFloat(getString(R.string.key_echo_diffusion), 0f) <= 0.01f
        val distOff = prefs.getFloat(getString(R.string.key_echo_dist_level), 0f) <= 0.01f

        set(binding.knobCutoff, on && !filterOff)
        set(binding.knobRes, on && !filterOff)
        set(binding.knobModTime, on && !modOff)
        set(binding.knobModCutoff, on && !modOff)
        set(binding.knobSpread, on && !diffOff)
        set(binding.groupDist, on && !distOff)
        set(binding.knobKnee, on && !distOff)
        set(binding.knobSymmetry, on && !distOff)
        listOf(binding.knobTime, binding.switchKeepPitch, binding.knobSmoothing,
            binding.knobOffset, binding.knobStereo, binding.knobFeedback,
            binding.groupFilter, binding.knobSmpRate, binding.knobBits,
            binding.knobModRate, binding.knobDiffusion, binding.knobInput,
            binding.knobWet, binding.knobTone).forEach { set(it, on) }
    }

    private fun bindKnob(knob: KnobView, keyRes: Int, default: Float) {
        val key = getString(keyRes)
        knob.value = prefs.getFloat(key, default)
        knob.setOnValueChangedListener {
            prefs.edit().putFloat(key, knob.value).apply()
            refreshUsability()
        }
    }

    private fun bindSwitch(switch: MaterialSwitch, keyRes: Int) {
        val key = getString(keyRes)
        switch.isChecked = prefs.getBoolean(key, false)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    /** Segmented buttons persist the selected index as a string, matching ListPreference. */
    private fun bindToggle(
        group: MaterialButtonToggleGroup, keyRes: Int, default: String, ids: IntArray
    ) {
        val key = getString(keyRes)
        val current = (prefs.getString(key, default) ?: default).toIntOrNull() ?: 0
        ids.getOrNull(current)?.let { group.check(it) }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val index = ids.indexOf(checkedId)
            if (index >= 0) {
                prefs.edit().putString(key, index.toString()).apply()
                refreshUsability()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = EchoPanelFragment()
    }
}
