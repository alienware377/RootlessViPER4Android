package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.AppCompatRadioButton
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

        bindRadio(
            R.string.key_echo_model, "1",
            listOf(binding.modelMono, binding.modelStereo, binding.modelPingpong, binding.modelOff)
        )
        bindRadio(
            R.string.key_echo_filter, "0",
            listOf(binding.filterLp, binding.filterHp, binding.filterBp, binding.filterOff)
        )
        bindRadio(
            R.string.key_echo_dist_mode, "1",
            listOf(binding.distLimit, binding.distSat)
        )

        return binding.root
    }

    private fun bindKnob(knob: KnobView, keyRes: Int, default: Float) {
        val key = getString(keyRes)
        knob.value = prefs.getFloat(key, default)
        knob.setOnValueChangedListener {
            prefs.edit().putFloat(key, knob.value).apply()
        }
    }

    private fun bindSwitch(switch: MaterialSwitch, keyRes: Int) {
        val key = getString(keyRes)
        switch.isChecked = prefs.getBoolean(key, false)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    /** Radio groups persist the selected index as a string, matching ListPreference. */
    private fun bindRadio(keyRes: Int, default: String, buttons: List<AppCompatRadioButton>) {
        val key = getString(keyRes)
        val current = (prefs.getString(key, default) ?: default).toIntOrNull() ?: 0
        buttons.getOrNull(current)?.isChecked = true
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                buttons.forEach { it.isChecked = false }
                button.isChecked = true
                prefs.edit().putString(key, index.toString()).apply()
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
