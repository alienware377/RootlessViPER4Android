package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.view.SubHarmonicSurface

/**
 * The bar chart at the top of the sub-harmonic expander card. It only reads
 * the card's own settings and redraws when one of them moves.
 *
 * After ExpanderGraphPreference in evoeram's Loudness-PEQ-RootlessJamesDSP
 * (https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP, GNU GPL
 * version 3), see THIRD_PARTY_NOTICES.md.
 */
class SubHarmonicPreviewPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {

    private var surface: SubHarmonicSurface? = null

    init {
        layoutResource = R.layout.preference_subharmonic_preview
        isSelectable = false
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDetached() {
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        surface = null
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        surface = holder.findViewById(R.id.subharmonic_surface) as? SubHarmonicSurface
        update()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == null || key.startsWith("subharmonic_")) update()
    }

    private fun update() {
        val s = surface ?: return
        val p = sharedPreferences ?: return
        val amounts = FloatArray(SubHarmonicSurface.DIVIDERS) {
            p.getFloat(context.getString(AMOUNT_KEYS[it]), if (it == 0) 100f else 0f)
        }
        s.set(
            amounts,
            p.getFloat(context.getString(R.string.key_subharmonic_band), 120f),
            p.getFloat(context.getString(R.string.key_subharmonic_mix), 50f),
        )
    }

    private companion object {
        val AMOUNT_KEYS = intArrayOf(
            R.string.key_subharmonic_amount2, R.string.key_subharmonic_amount3,
            R.string.key_subharmonic_amount4, R.string.key_subharmonic_amount5,
            R.string.key_subharmonic_amount6, R.string.key_subharmonic_amount7,
            R.string.key_subharmonic_amount8, R.string.key_subharmonic_amount9,
            R.string.key_subharmonic_amount10,
        )
    }
}
