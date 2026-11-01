package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.matcheq.AutoEqTargets
import me.timschneeberger.rootlessjamesdsp.matcheq.FrequencyCurve
import me.timschneeberger.rootlessjamesdsp.matcheq.LevelMatch
import me.timschneeberger.rootlessjamesdsp.matcheq.MatchEqParams
import me.timschneeberger.rootlessjamesdsp.matcheq.MatchEqResult
import me.timschneeberger.rootlessjamesdsp.matcheq.MatchEqSolver
import me.timschneeberger.rootlessjamesdsp.matcheq.Rig

/**
 * Reads and writes Match EQ's settings in the dsp_matcheq namespace.
 *
 * The measurement and target curves are stored there too, as text, rather than
 * as files. A preset or device profile copies every dsp_ namespace, so it then
 * carries everything needed to recompute the same result offline, and a preset
 * loaded later can never point at a curve that is no longer on the phone.
 */
object MatchEqStore {
    const val TARGET_FILE = "file"

    // Multi-process mode, like every other dsp_ namespace: a preset load swaps
    // the file on disk, and this makes the next read pick the new one up
    // instead of writing the old values back over it.
    @Suppress("DEPRECATION")
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(Constants.PREF_MATCHEQ, Context.MODE_MULTI_PROCESS)

    private fun key(ctx: Context, id: Int) = ctx.getString(id)

    fun measurement(ctx: Context, p: SharedPreferences = prefs(ctx)): FrequencyCurve? =
        FrequencyCurve.deserialize(p.getString(key(ctx, R.string.key_matcheq_measurement), null))

    fun rig(ctx: Context, p: SharedPreferences = prefs(ctx)): Rig? {
        val parts = p.getString(key(ctx, R.string.key_matcheq_measurement_rig), null)?.split('\t') ?: return null
        if (parts.size != 3) return null
        return Rig(parts[0], parts[1], parts[2].ifEmpty { null })
    }

    fun targetId(ctx: Context, p: SharedPreferences = prefs(ctx)): String =
        p.getString(key(ctx, R.string.key_matcheq_target), AutoEqTargets.FLAT_ID) ?: AutoEqTargets.FLAT_ID

    /** The curve for the target currently picked, or null if it has not been downloaded. */
    fun target(ctx: Context, p: SharedPreferences = prefs(ctx)): FrequencyCurve? = when (targetId(ctx, p)) {
        AutoEqTargets.FLAT_ID -> MatchEqSolver.flatTarget()
        TARGET_FILE -> FrequencyCurve.deserialize(p.getString(key(ctx, R.string.key_matcheq_target_file_curve), null))
        else -> FrequencyCurve.deserialize(p.getString(key(ctx, R.string.key_matcheq_target_curve), null))
    }

    fun params(ctx: Context, p: SharedPreferences = prefs(ctx)): MatchEqParams {
        fun f(id: Int, def: Float) = p.getFloat(key(ctx, id), def).toDouble()
        val smoothing = p.getString(key(ctx, R.string.key_matcheq_smoothing), "12")?.toDoubleOrNull() ?: 12.0
        return MatchEqParams(
            bassBoost = f(R.string.key_matcheq_bass, 0f),
            trebleBoost = f(R.string.key_matcheq_treble, 0f),
            tilt = f(R.string.key_matcheq_tilt, 0f),
            levelMatch = if (p.getString(key(ctx, R.string.key_matcheq_level_match), "average") == "1k")
                LevelMatch.AT_1KHZ else LevelMatch.AVERAGE,
            smoothing = 1.0 / smoothing.coerceIn(1.0, 48.0),
            trebleStrength = f(R.string.key_matcheq_treble_strength, 100f) / 100.0,
            maxBoost = f(R.string.key_matcheq_max_boost, 6f),
            maxCut = f(R.string.key_matcheq_max_cut, 30f),
            correctFrom = f(R.string.key_matcheq_correct_from, 20f),
            correctUpTo = f(R.string.key_matcheq_correct_up_to, 20000f),
            avoidNarrowBoosts = p.getBoolean(key(ctx, R.string.key_matcheq_avoid_narrow), true),
        )
    }

    /** Runs the solver on what is stored. Null until there is both a measurement and a target. */
    fun solve(ctx: Context, p: SharedPreferences = prefs(ctx)): MatchEqResult? {
        val m = measurement(ctx, p) ?: return null
        val t = target(ctx, p) ?: return null
        return runCatching { MatchEqSolver.solve(m, t, params(ctx, p)) }.getOrNull()
    }

    fun putMeasurement(
        ctx: Context, curve: FrequencyCurve, name: String, origin: String, rig: Rig?, estimated: Boolean,
    ) {
        prefs(ctx).edit()
            .putString(key(ctx, R.string.key_matcheq_measurement), curve.serialize())
            .putString(key(ctx, R.string.key_matcheq_measurement_name), name)
            .putString(key(ctx, R.string.key_matcheq_measurement_origin), origin)
            .putString(key(ctx, R.string.key_matcheq_measurement_rig),
                rig?.let { "${it.source}\t${it.form}\t${it.rig ?: ""}" } ?: "")
            .putBoolean(key(ctx, R.string.key_matcheq_measurement_estimated), estimated)
            .apply()
    }

    /** Selects a target, storing its curve where [target] will find it, and resets the bass shelf to its default. */
    fun putTarget(ctx: Context, id: String, curve: FrequencyCurve?, bassBoost: Double, fileName: String? = null) {
        val e = prefs(ctx).edit()
            .putString(key(ctx, R.string.key_matcheq_target), id)
            .putFloat(key(ctx, R.string.key_matcheq_bass), bassBoost.toFloat())
        when (id) {
            AutoEqTargets.FLAT_ID -> {}
            TARGET_FILE -> {
                e.putString(key(ctx, R.string.key_matcheq_target_file_curve), curve?.serialize())
                e.putString(key(ctx, R.string.key_matcheq_target_name), fileName)
            }
            else -> e.putString(key(ctx, R.string.key_matcheq_target_curve), curve?.serialize())
        }
        e.apply()
    }
}
