package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import timber.log.Timber

/**
 * Where the loudness correction is flat.
 *
 * The correction never boosts: below the reference it brings the bass and
 * treble forward by turning the rest down. With the reference left at its
 * old default of 0 dB, switching the card on at an everyday volume cut the
 * middle by 20 dB or more at once, and that sounds like the card is broken.
 * So the first time the card is switched on, the level being listened at
 * becomes the reference. Nothing changes at that moment, and the correction
 * only grows as the volume goes down from there.
 *
 * Once a reference is stored, whether taken that way, set with the button or
 * moved on a slider, it stays. Taking it again at every switch-on would make
 * switching the card off and on to compare sound the same both ways. Settings
 * from the 4.6.0 test builds behave the same: a reference stored there was
 * set by hand, since the sliders only write a value once moved, and none
 * stored is the old 0 dB default, which nobody picked.
 */
object LoudnessReference {
    private const val MIN_DB = -60.0
    private const val MAX_DB = 0.0

    /** The preference keys of the loudness card. */
    class Keys(
        val enable: String,
        val follow: String,
        val volume: String,
        val level: String,
        val offset: String,
    ) {
        companion object {
            fun of(context: Context) = Keys(
                context.getString(R.string.key_loudness_enable),
                context.getString(R.string.key_loudness_follow_volume),
                context.getString(R.string.key_loudness_volume),
                context.getString(R.string.key_loudness_reference_level),
                context.getString(R.string.key_loudness_reference_offset),
            )
        }
    }

    /** Whether a reference is stored, from the stored [level] and [offset], each null when absent. */
    fun isSet(level: Float?, offset: Float?): Boolean = level != null || offset != null

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
     * or null to leave it alone: one is [set] already, or the media volume is
     * muted, which says nothing about where the music sounds right.
     */
    fun onSwitchOn(set: Boolean, listeningDb: Double): Float? {
        if (set || listeningDb.isNaN() || listeningDb <= MediaVolume.MUTED_DB) return null
        return listeningDb.coerceIn(MIN_DB, MAX_DB).toFloat()
    }

    private fun SharedPreferences.floatOrNull(key: String): Float? =
        if (!contains(key)) null
        else try { getFloat(key, 0f) } catch (_: ClassCastException) { null }

    private fun SharedPreferences.booleanOr(key: String, default: Boolean): Boolean =
        try { getBoolean(key, default) } catch (_: ClassCastException) { default }

    fun isSet(prefs: SharedPreferences, keys: Keys): Boolean =
        isSet(prefs.floatOrNull(keys.level), prefs.floatOrNull(keys.offset))

    fun listeningDb(prefs: SharedPreferences, keys: Keys, smoothDb: Double?, mediaDb: () -> Double): Double =
        listeningDb(
            prefs.booleanOr(keys.follow, true),
            (prefs.floatOrNull(keys.volume) ?: 0f).toDouble(),
            smoothDb, mediaDb,
        )

    /**
     * Makes the level being listened at the reference, with no offset, unless
     * one is stored already. Called before the card's switch is written, so
     * the engine never has the card on with the old reference. Returns the
     * level stored, or null when nothing was.
     */
    fun captureOnSwitchOn(prefs: SharedPreferences, keys: Keys, smoothDb: Double?, mediaDb: () -> Double): Float? {
        if (isSet(prefs, keys)) return null
        val level = onSwitchOn(false, listeningDb(prefs, keys, smoothDb, mediaDb)) ?: return null
        // commit, not apply: the sync that follows reads it next
        prefs.edit()
            .putFloat(keys.level, level)
            .putFloat(keys.offset, 0f)
            .commit()
        Timber.d("captureOnSwitchOn: reference set to $level dB")
        return level
    }

    /** After a load brought the card in: one that arrives on counts as switched on now. */
    fun captureAfterLoad(prefs: SharedPreferences, keys: Keys, smoothDb: Double?, mediaDb: () -> Double): Float? =
        if (prefs.booleanOr(keys.enable, false)) captureOnSwitchOn(prefs, keys, smoothDb, mediaDb) else null

    @Suppress("DEPRECATION")
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_LOUDNESS, Context.MODE_MULTI_PROCESS)

    /** The level being corrected for now, as the engine is sent it. */
    fun listeningDb(context: Context): Double =
        listeningDb(prefs(context), Keys.of(context), MediaVolume.smoothVolumeDb()) {
            MediaVolume.currentDb(context)
        }

    fun captureOnSwitchOn(context: Context): Float? =
        captureOnSwitchOn(prefs(context), Keys.of(context), MediaVolume.smoothVolumeDb()) {
            MediaVolume.currentDb(context)
        }

    fun captureAfterLoad(context: Context): Float? =
        captureAfterLoad(prefs(context), Keys.of(context), MediaVolume.smoothVolumeDb()) {
            MediaVolume.currentDb(context)
        }
}
