package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.LoudnessCurve
import me.timschneeberger.rootlessjamesdsp.utils.MediaVolume
import me.timschneeberger.rootlessjamesdsp.view.LoudnessSurface
import kotlin.math.roundToInt

/**
 * The loudness correction as it stands: the card's settings at the volume it
 * is correcting for, redrawn whenever either changes.
 */
class LoudnessGraphPreference : Preference, SharedPreferences.OnSharedPreferenceChangeListener {

    private var surface: LoudnessSurface? = null
    private var caption: TextView? = null
    private val prefs = PreferenceCache.getPreferences(context, Constants.PREF_LOUDNESS)
    private var mediaDb = 0.0
    private val tracker = MediaVolume.Tracker(context) { db ->
        mediaDb = db
        update()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_loudness_graph
        isSelectable = false
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?) :
        this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(context: Context) : this(context, null)

    override fun onAttached() {
        super.onAttached()
        prefs.registerOnSharedPreferenceChangeListener(this)
        tracker.start()
    }

    override fun onDetached() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        tracker.stop()
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        surface = holder.findViewById(R.id.loudness_graph_surface) as? LoudnessSurface
        caption = holder.findViewById(R.id.loudness_graph_caption) as? TextView
        update()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = update()

    private fun float(key: Int, default: Float) = prefs.getFloat(context.getString(key), default).toDouble()

    private fun update() {
        val s = LoudnessCurve.Settings(
            mode = prefs.getString(context.getString(R.string.key_loudness_mode), "0")?.toIntOrNull() ?: 0,
            refLevel = float(R.string.key_loudness_reference_level, 0f),
            refOffset = float(R.string.key_loudness_reference_offset, 0f),
            strength = float(R.string.key_loudness_strength, 100f),
            bassHz = float(R.string.key_loudness_bass_freq, 75f),
            bassSlope = float(R.string.key_loudness_bass_slope, 0.52f),
            bassAmount = float(R.string.key_loudness_bass_amount, 55f),
            trebleHz = float(R.string.key_loudness_treble_freq, 10000f),
            trebleSlope = float(R.string.key_loudness_treble_slope, 0.9f),
            trebleAmount = float(R.string.key_loudness_treble_amount, 22.5f),
            phon = float(R.string.key_loudness_phon, 80f),
            subsonic = prefs.getBoolean(context.getString(R.string.key_loudness_subsonic), false),
            subsonicHz = float(R.string.key_loudness_subsonic_freq, 20f),
            subsonicOrder = float(R.string.key_loudness_subsonic_order, 4f).toInt(),
            subsonicQ = float(R.string.key_loudness_subsonic_q, 0.707f),
        )
        val follow = prefs.getBoolean(context.getString(R.string.key_loudness_follow_volume), true)
        val volume = if (follow) mediaDb else float(R.string.key_loudness_volume, 0f)
        val freqs = LoudnessCurve.logSpaced(10.0, 20000.0, 160)
        surface?.setCurve(freqs, LoudnessCurve.responseDb(s, volume, freqs))
        caption?.text = if (volume <= MediaVolume.MUTED_DB) context.getString(R.string.loudness_graph_muted)
            else context.getString(R.string.loudness_graph_summary, volume.roundToInt().toString())
    }
}
