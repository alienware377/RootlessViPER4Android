package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import timber.log.Timber

/**
 * Where the loudness correction is flat, until someone picks it.
 *
 * The correction never boosts: below the reference it brings the bass and
 * treble forward by turning the rest down. With the reference left at its
 * old default of 0 dB, switching the card on at an everyday volume cut the
 * middle by 20 dB or more at once, and that sounds like the card is broken.
 * So while no reference has been picked, switching the card on makes the
 * level being listened at the reference. Nothing changes at that moment, and
 * the correction only grows as the volume goes down from there.
 *
 * A reference the card took itself is stored with a copy of the level it
 * took, under its own key. Moving the reference or the offset, or tapping the
 * button, drops that copy, and from then on the reference is the user's and
 * is never overwritten. Settings from before the copy existed have none: a
 * reference stored there was set by hand, and none stored is the old 0 dB
 * default, which nobody picked.
 */
object LoudnessReference {
    private const val MIN_DB = -60.0
    private const val MAX_DB = 0.0

    /**
     * Whether the reference was picked by the user, from what is stored: the
     * [level], the [offset] and the level the card took itself ([auto]), each
     * null when absent.
     */
    fun isChosen(level: Float?, offset: Float?, auto: Float?): Boolean {
        // Compared as well as looked up, so a slider moved while the card was
        // not on screen to see it still counts.
        if (auto != null)
            return level != auto || (offset ?: 0f) != 0f
        return level != null || offset != null
    }

    /**
     * The level the engine is sent: the listening volume set on the card
     * when the media volume is not followed ([manualDb]), otherwise smooth
     * volume's level while it holds the media volume ([smoothDb]), otherwise
     * the media volume itself.
     */
    fun listeningDb(follow: Boolean, manualDb: Double, smoothDb: Double?, mediaDb: () -> Double): Double =
        if (!follow) manualDb else smoothDb ?: mediaDb()

    /**
     * The reference to store when the card is switched on at [listeningDb],
     * or null to leave it alone: it was [chosen], or the media volume is
     * muted, which says nothing about where the music sounds right.
     */
    fun onSwitchOn(chosen: Boolean, listeningDb: Double): Float? {
        if (chosen || listeningDb.isNaN() || listeningDb <= MediaVolume.MUTED_DB) return null
        return listeningDb.coerceIn(MIN_DB, MAX_DB).toFloat()
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_LOUDNESS, Context.MODE_MULTI_PROCESS)

    private fun SharedPreferences.floatOrNull(key: String): Float? =
        if (!contains(key)) null
        else try { getFloat(key, 0f) } catch (_: ClassCastException) { null }

    /** The level being corrected for now, as the engine is sent it. */
    fun listeningDb(context: Context, prefs: SharedPreferences = prefs(context)): Double {
        val follow = try {
            prefs.getBoolean(context.getString(R.string.key_loudness_follow_volume), true)
        } catch (_: ClassCastException) { true }
        val manual = prefs.floatOrNull(context.getString(R.string.key_loudness_volume)) ?: 0f
        return listeningDb(follow, manual.toDouble(), MediaVolume.smoothVolumeDb()) {
            MediaVolume.currentDb(context)
        }
    }

    fun isChosen(context: Context, prefs: SharedPreferences = prefs(context)): Boolean = isChosen(
        prefs.floatOrNull(context.getString(R.string.key_loudness_reference_level)),
        prefs.floatOrNull(context.getString(R.string.key_loudness_reference_offset)),
        prefs.floatOrNull(context.getString(R.string.key_loudness_reference_auto)),
    )

    /**
     * Makes the level being listened at the reference, with no offset, unless
     * the user picked one. Called before the card's switch is written, so the
     * engine never has the card on with the old reference. Returns the level
     * stored, or null when nothing was.
     */
    fun captureOnSwitchOn(context: Context): Float? {
        val prefs = prefs(context)
        val level = onSwitchOn(isChosen(context, prefs), listeningDb(context, prefs)) ?: return null
        // commit, not apply: the sync that follows reads it next
        prefs.edit()
            .putFloat(context.getString(R.string.key_loudness_reference_level), level)
            .putFloat(context.getString(R.string.key_loudness_reference_offset), 0f)
            .putFloat(context.getString(R.string.key_loudness_reference_auto), level)
            .commit()
        Timber.d("captureOnSwitchOn: reference set to $level dB")
        return level
    }

    /**
     * After a preset, a backup or a device profile was loaded. A card that
     * arrives on with a reference nobody picked counts as switched on now.
     */
    fun captureAfterLoad(context: Context) {
        val on = try {
            prefs(context).getBoolean(context.getString(R.string.key_loudness_enable), false)
        } catch (_: ClassCastException) { false }
        if (on) captureOnSwitchOn(context)
    }

    /** The reference is the user's from now on. */
    fun markChosen(context: Context, prefs: SharedPreferences = prefs(context)) {
        val key = context.getString(R.string.key_loudness_reference_auto)
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
    }

    /**
     * The reference or the offset changed. Unless it is the card's own write,
     * which leaves them equal to the copy, it was the user.
     */
    fun onReferenceChanged(context: Context, prefs: SharedPreferences) {
        if (isChosen(context, prefs)) markChosen(context, prefs)
    }
}
