package me.timschneeberger.rootlessjamesdsp.globalfx

import android.content.Context
import androidx.annotation.StringRes
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants

/**
 * Reads the cards global mode can use, with the same keys, namespaces and
 * fallbacks as JamesDspBaseEngine.syncWithPreferences, so a never-touched card
 * means the same thing in both modes. Reads through PreferenceCache's
 * multi-process path because presets and device profiles copy the dsp_* files
 * on disk rather than editing them in memory.
 */
object GlobalSettingsReader {

    private inline fun <reified T : Any> Context.read(ns: String, @StringRes key: Int, default: T): T =
        PreferenceCache.uncachedGet(this, ns, key, default, T::class)

    fun eqInputs(ctx: Context) = EqInputs(
        eqEnabled = ctx.read(Constants.PREF_EQ, R.string.key_eq_enable, false),
        eqInterpolation = ctx.read(Constants.PREF_EQ, R.string.key_eq_interpolation, "0").toIntOrNull() ?: 0,
        eqBands = ctx.read(Constants.PREF_EQ, R.string.key_eq_bands, Constants.DEFAULT_EQ),
        geqEnabled = ctx.read(Constants.PREF_GEQ, R.string.key_geq_enable, false),
        geqNodes = ctx.read(Constants.PREF_GEQ, R.string.key_geq_nodes, Constants.DEFAULT_GEQ),
        peqEnabled = ctx.read(Constants.PREF_PEQ, R.string.key_peq_enable, false),
        peqBands = ctx.read(Constants.PREF_PEQ, R.string.key_peq_bands, Constants.DEFAULT_PEQ),
        peqPreamp = ctx.read(Constants.PREF_PEQ, R.string.key_peq_preamp, 0f).toDouble(),
        bassEnabled = ctx.read(Constants.PREF_BASS, R.string.key_bass_enable, false),
        bassMaxGain = ctx.read(Constants.PREF_BASS, R.string.key_bass_max_gain, 5f).toDouble(),
    )

    fun dynamicsInputs(ctx: Context) = DynamicsInputs(
        outputPostGain = ctx.read(Constants.PREF_OUTPUT, R.string.key_output_postgain, 0f).toDouble(),
        limiterThreshold = ctx.read(Constants.PREF_OUTPUT, R.string.key_limiter_threshold, -0.1f).toDouble(),
        limiterRelease = ctx.read(Constants.PREF_OUTPUT, R.string.key_limiter_release, 60f).toDouble(),
        limiterMode = ctx.read(Constants.PREF_OUTPUT, R.string.key_limiter_mode, "0").toIntOrNull() ?: 0,
        fetEnabled = ctx.read(Constants.PREF_FETCOMP, R.string.key_fetcomp_enable, false),
        fetThreshold = ctx.read(Constants.PREF_FETCOMP, R.string.key_fetcomp_threshold, -18f).toDouble(),
        fetRatio = ctx.read(Constants.PREF_FETCOMP, R.string.key_fetcomp_ratio, 4f).toDouble(),
        fetAttack = ctx.read(Constants.PREF_FETCOMP, R.string.key_fetcomp_attack, 5f).toDouble(),
        fetRelease = ctx.read(Constants.PREF_FETCOMP, R.string.key_fetcomp_release, 120f).toDouble(),
        fetMakeup = ctx.read(Constants.PREF_FETCOMP, R.string.key_fetcomp_makeup, 0f).toDouble(),
        maxrEnabled = ctx.read(Constants.PREF_MAXIMIZER, R.string.key_maxr_enable, false),
        maxrGain = ctx.read(Constants.PREF_MAXIMIZER, R.string.key_maxr_gain, 6f).toDouble(),
        maxrCeiling = ctx.read(Constants.PREF_MAXIMIZER, R.string.key_maxr_ceiling, -0.3f).toDouble(),
        maxrRelease = ctx.read(Constants.PREF_MAXIMIZER, R.string.key_maxr_release, 200f).toDouble(),
        balanceEnabled = ctx.read(Constants.PREF_BALANCE, R.string.key_balance_enable, false),
        balance = ctx.read(Constants.PREF_BALANCE, R.string.key_balance_balance, 0f).toDouble(),
    )

    /** Names of the cards that are on but cannot run in global mode, for the check screen. */
    fun ignoredEnabledCards(ctx: Context): List<String> =
        GlobalCards.support.filterValues { it == GlobalCards.Support.NONE }.keys.filter { ns ->
            val prefs = PreferenceCache.getPreferences(ctx, ns)
            prefs.all.any { (k, v) -> k.endsWith("_enable") && v == true && !k.endsWith("_band2_enable") }
        }.sorted()
}
