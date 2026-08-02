package me.timschneeberger.rootlessjamesdsp.preference

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceParametricEqualizerBinding
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import timber.log.Timber
import java.util.MissingFormatArgumentException

class ParametricEqualizerPreference : Preference {

    private var binding: PreferenceParametricEqualizerBinding? = null
    private var initialValue: String = ""

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateFromPreferences()
        }
    }

    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int
    ) : this(context, attrs, defStyleAttr, 0)

    constructor(
        context: Context, attrs: AttributeSet?
    ) : this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(
        context: Context
    ) : this(context, null)

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_parametric_equalizer
    }

    override fun onAttached() {
        context.registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        super.onAttached()
    }

    override fun onDetached() {
        context.unregisterLocalReceiver(broadcastReceiver)
        super.onDetached()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        initialValue = getPersistedString(defaultValue as? String ?: "")
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index).toString()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        binding = PreferenceParametricEqualizerBinding.bind(holder.itemView)
        setEqualizerViewValues(initialValue)
    }

    fun updateFromPreferences() {
        initialValue = getPersistedString(initialValue)
        setEqualizerViewValues(initialValue)
    }

    private fun setEqualizerViewValues(value: String) {
        val bands = ParametricEqBandList()
        bands.deserialize(value)

        // Read preamp from SharedPreferences so the main screen preview reflects it
        val preampDb = context.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
            .getFloat(context.getString(R.string.key_peq_preamp), 0f)
            .toDouble()

        binding?.layoutEqualizer?.setBands(bands, preampDb)
        try {
            binding?.bandCount?.text =
                context.resources.getQuantityString(R.plurals.peq_bands_count, bands.size, bands.size)
        } catch (ex: MissingFormatArgumentException) {
            Timber.e(ex)
            binding?.bandCount?.text = context.getString(R.string.peq_bands)
        }
    }
}
