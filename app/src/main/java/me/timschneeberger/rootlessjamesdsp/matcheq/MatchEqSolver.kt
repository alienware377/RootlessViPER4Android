/*
 * Match EQ: from a measurement and a target to a graphic EQ curve.
 *
 * The processing order and every default follow AutoEq's
 * FrequencyResponse.process / equalize / eqapo_graphic_eq by Jaakko Pasanen
 * (https://github.com/jaakkopasanen/AutoEq, MIT licence, Copyright (c)
 * 2018-2022 Jaakko Pasanen). The full notice is in THIRD_PARTY_NOTICES.md.
 * This is a Kotlin port written for this app; no AutoEq data is included.
 */
package me.timschneeberger.rootlessjamesdsp.matcheq

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.log2

enum class LevelMatch { AVERAGE, AT_1KHZ }

data class MatchEqParams(
    /** Low shelf added to the target at 105 Hz, in dB. */
    val bassBoost: Double = 0.0,
    /** High shelf added to the target at 10 kHz, in dB. */
    val trebleBoost: Double = 0.0,
    /** Target tilt in dB per octave; positive is brighter. */
    val tilt: Double = 0.0,
    val levelMatch: LevelMatch = LevelMatch.AVERAGE,
    /** Smoothing width below 6 kHz, in octaves. */
    val smoothing: Double = 1.0 / 12.0,
    /** How much of the treble correction to keep, 0 to 1. */
    val trebleStrength: Double = 1.0,
    val maxBoost: Double = 6.0,
    val maxCut: Double = 30.0,
    /** Below this the correction fades out. 20 Hz or less means no fade. */
    val correctFrom: Double = Curves.F_MIN,
    /** Above this the correction fades out. 20 kHz or more means no fade. */
    val correctUpTo: Double = Curves.F_MAX,
    val avoidNarrowBoosts: Boolean = true,
    val maxSlope: Double = SlopeLimiter.DEFAULT_MAX_SLOPE,
)

class MatchEqResult(
    /** The 695-point working grid. */
    val grid: DoubleArray,
    /** Measurement, centred at 1 kHz. */
    val measurement: DoubleArray,
    /** Target with its shelves and tilt, level-matched to the measurement. */
    val target: DoubleArray,
    /** Correction on the grid before the level is lowered for headroom. */
    val correction: DoubleArray,
    /** The 127 GraphicEQ frequencies. */
    val outFreq: DoubleArray,
    /** GraphicEQ gains, lowered so the highest sits at -0.2 dB. */
    val outGain: DoubleArray,
    /** How much the output was lowered so nothing can clip (negative: raised). */
    val volumeDrop: Double,
) {
    fun graphicEqString(): String {
        val df = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
        val sb = StringBuilder("GraphicEQ: ")
        for (i in outFreq.indices) {
            if (i > 0) sb.append("; ")
            sb.append(outFreq[i].toLong()).append(' ').append(df.format(outGain[i] + 0.0))
        }
        return sb.toString()
    }
}

object MatchEqSolver {

    const val SAMPLE_RATE = 44100.0
    const val TREBLE_F_LOWER = 6000.0
    const val TREBLE_F_UPPER = 8000.0
    const val TREBLE_SMOOTHING = 2.0
    const val FINAL_SMOOTHING = 1.0 / 5.0
    const val HEADROOM = 0.2

    /** Low and high shelf plus tilt, in dB on [grid]. AutoEq's create_target. */
    fun shelves(grid: DoubleArray, bass: Double, treble: Double, tilt: Double): DoubleArray {
        val ls = BiquadUtils.computeCoefficients(
            AutoEqTargets.BASS_FC, bass, AutoEqTargets.BASS_Q, ParametricEqFilterType.LOW_SHELF, SAMPLE_RATE)
        val hs = BiquadUtils.computeCoefficients(
            AutoEqTargets.TREBLE_FC, treble, AutoEqTargets.TREBLE_Q, ParametricEqFilterType.HIGH_SHELF, SAMPLE_RATE)
        val centre = 20.0 * kotlin.math.sqrt(1000.0)
        return DoubleArray(grid.size) { i ->
            val f = grid[i]
            BiquadUtils.magnitudeResponse(ls, f, SAMPLE_RATE) +
                BiquadUtils.magnitudeResponse(hs, f, SAMPLE_RATE) +
                tilt * log2(f / centre)
        }
    }

    /** [curve] on the grid, shifted so it reads 0 dB at 1 kHz. */
    fun centred(curve: FrequencyCurve, grid: DoubleArray = Curves.grid): DoubleArray {
        val v = Curves.interpolate(curve.freq, curve.gain, grid)
        val at1k = Curves.interpolate(grid, v, doubleArrayOf(1000.0))[0]
        for (i in v.indices) v[i] -= at1k
        return v
    }

    /** AutoEq's two-band smoothing: a narrow window, blended into a wide one above 6 to 8 kHz. */
    fun smoothTwoBand(grid: DoubleArray, y: DoubleArray, octaves: Double, trebleOctaves: Double): DoubleArray {
        val normal = SavitzkyGolay.smooth(y, Curves.windowSize(grid, octaves))
        if (octaves == trebleOctaves) return normal
        val treble = SavitzkyGolay.smooth(y, Curves.windowSize(grid, trebleOctaves))
        return DoubleArray(y.size) { i ->
            val w = Curves.logSigmoid(grid[i], TREBLE_F_LOWER, TREBLE_F_UPPER)
            (1.0 - w) * normal[i] + w * treble[i]
        }
    }

    fun solve(measurement: FrequencyCurve, target: FrequencyCurve, p: MatchEqParams = MatchEqParams()): MatchEqResult {
        val grid = Curves.grid
        val m = centred(measurement, grid)

        val t = centred(target, grid)
        val sh = shelves(grid, p.bassBoost, p.trebleBoost, p.tilt)
        for (i in t.indices) t[i] += sh[i]

        val e = DoubleArray(grid.size) { m[it] - t[it] }
        if (p.levelMatch == LevelMatch.AVERAGE) {
            // Shift so the error averages zero over 100 Hz to 10 kHz, which stops
            // one narrow feature right at 1 kHz from moving the whole curve.
            val d = Curves.meanInBand(grid, e, 100.0, 10000.0)
            for (i in e.indices) { e[i] -= d; t[i] += d }
        }

        val smoothed = smoothTwoBand(grid, e, p.smoothing, TREBLE_SMOOTHING)
        var y = DoubleArray(grid.size) { -smoothed[it] }

        if (p.avoidNarrowBoosts) y = SlopeLimiter.limit(grid, y, p.maxSlope)

        for (i in y.indices) {
            val f = grid[i]
            // Treble strength, faded in over the same 6 to 8 kHz band.
            val w = Curves.logSigmoid(f, TREBLE_F_LOWER, TREBLE_F_UPPER)
            var v = y[i] * (1.0 + w * (p.trebleStrength - 1.0))
            v = v.coerceAtMost(p.maxBoost).coerceAtLeast(-p.maxCut)
            if (p.correctFrom > Curves.F_MIN)
                v *= Curves.logSigmoid(f, p.correctFrom / 2.0, p.correctFrom)
            if (p.correctUpTo < Curves.F_MAX)
                v *= 1.0 - Curves.logSigmoid(f, p.correctUpTo, p.correctUpTo * 2.0)
            y[i] = v
        }

        // Round off the corners the limits left behind.
        val correction = smoothTwoBand(grid, y, FINAL_SMOOTHING, FINAL_SMOOTHING)

        val outF = Curves.graphicEqGrid
        val outG = Curves.interpolate(grid, correction, outF)
        val drop = (outG.maxOrNull() ?: 0.0) + HEADROOM
        for (i in outG.indices) outG[i] -= drop

        return MatchEqResult(grid, m, t, correction, outF, outG, drop)
    }

    /**
     * Rebuilds an approximate measurement from an AutoEq GraphicEQ profile, for
     * the many results whose raw measurement was never published.
     *
     * AutoEq made the profile as roughly (target - measurement), so measurement
     * is roughly (target - profile), up to a level that centring removes. What
     * AutoEq clipped away (the boost cap, slope limits, smoothing) cannot come
     * back, but running the result through the same limits clips it again.
     *
     * @param defaultTarget the target AutoEq used for that rig, without its shelf
     * @param bassBoost the shelf AutoEq added to it
     */
    fun estimateMeasurement(graphicEq: FrequencyCurve, defaultTarget: FrequencyCurve, bassBoost: Double): FrequencyCurve {
        val grid = Curves.grid
        val t = centred(defaultTarget, grid)
        val sh = shelves(grid, bassBoost, 0.0, 0.0)
        val g = Curves.interpolate(graphicEq.freq, graphicEq.gain, grid)
        val est = DoubleArray(grid.size) { t[it] + sh[it] - g[it] }
        val at1k = Curves.interpolate(grid, est, doubleArrayOf(1000.0))[0]
        for (i in est.indices) est[i] -= at1k
        return FrequencyCurve(grid.copyOf(), est)
    }

    /** The built-in flat target. */
    fun flatTarget(): FrequencyCurve = FrequencyCurve(Curves.grid.copyOf(), DoubleArray(Curves.grid.size))
}
