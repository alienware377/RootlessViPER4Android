/*
 * Match EQ: frequency grids and curve helpers.
 *
 * The grids, the interpolation and the sigmoid weighting follow AutoEq by
 * Jaakko Pasanen (https://github.com/jaakkopasanen/AutoEq, MIT licence,
 * Copyright (c) 2018-2022 Jaakko Pasanen). The full notice is in
 * THIRD_PARTY_NOTICES.md. This file is a Kotlin port written for this app; no
 * AutoEq data is included.
 */
package me.timschneeberger.rootlessjamesdsp.matcheq

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** A response in dB, sampled at strictly increasing frequencies in Hz. */
class FrequencyCurve(val freq: DoubleArray, val gain: DoubleArray) {
    init {
        require(freq.size == gain.size) { "frequency and gain counts differ" }
    }

    val size get() = freq.size

    /** Value at [f], linear in log frequency, holding the end values outside the data. */
    fun valueAt(f: Double): Double = Curves.interpolate(freq, gain, doubleArrayOf(f))[0]

    /** This curve resampled onto [grid]. */
    fun resample(grid: DoubleArray) = FrequencyCurve(grid, Curves.interpolate(freq, gain, grid))

    /** Plain "frequency,gain" lines, two decimals, for storage. */
    fun serialize(): String {
        val sb = StringBuilder()
        for (i in freq.indices) {
            if (i > 0) sb.append(';')
            sb.append(fmt(freq[i])).append(',').append(fmt(gain[i]))
        }
        return sb.toString()
    }

    companion object {
        private fun fmt(v: Double): String {
            val r = Math.round(v * 100.0) / 100.0
            return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        }

        /** Reads what [serialize] wrote. Returns null for anything malformed. */
        fun deserialize(s: String?): FrequencyCurve? {
            if (s.isNullOrBlank()) return null
            val f = ArrayList<Double>()
            val g = ArrayList<Double>()
            for (pair in s.split(';')) {
                val parts = pair.split(',')
                if (parts.size != 2) return null
                f.add(parts[0].toDoubleOrNull() ?: return null)
                g.add(parts[1].toDoubleOrNull() ?: return null)
            }
            if (f.size < 2) return null
            return FrequencyCurve(f.toDoubleArray(), g.toDoubleArray())
        }
    }
}

object Curves {
    const val F_MIN = 20.0
    const val F_MAX = 20000.0
    const val STEP = 1.01
    const val GRAPHIC_EQ_STEP = 1.0563

    /**
     * The working grid: 20 Hz, then each point 1% above the last, up to 20 kHz.
     * Built by repeated multiplication rather than powers, as AutoEq does, so
     * the points are the same ones its CSV files use.
     */
    val grid: DoubleArray by lazy {
        val out = ArrayList<Double>(700)
        var f = F_MIN
        while (f <= F_MAX) {
            out.add(f)
            f *= STEP
        }
        out.toDoubleArray()
    }

    /**
     * The output grid: 127 whole frequencies from 20 Hz to 19871 Hz, the same
     * points every AutoEq GraphicEQ profile uses.
     */
    val graphicEqGrid: DoubleArray by lazy {
        val n = kotlin.math.ceil(ln(F_MAX / F_MIN) / ln(GRAPHIC_EQ_STEP)).toInt()
        (0 until n).map { (F_MIN * GRAPHIC_EQ_STEP.pow(it)).toInt() }
            .distinct().sorted().map { it.toDouble() }.toDoubleArray()
    }

    /**
     * Linear interpolation in log frequency. Outside the data the first and last
     * values are held. AutoEq extrapolates the end slopes instead, which can run
     * away when a measurement stops short of 20 Hz or 20 kHz; holding is the
     * safer choice and only differs where there is no data at all.
     */
    fun interpolate(freq: DoubleArray, gain: DoubleArray, at: DoubleArray): DoubleArray {
        require(freq.isNotEmpty())
        val lf = DoubleArray(freq.size) { log10(freq[it]) }
        val out = DoubleArray(at.size)
        var j = 0
        for (i in at.indices) {
            val x = log10(at[i])
            if (x <= lf[0]) { out[i] = gain[0]; continue }
            if (x >= lf[lf.size - 1]) { out[i] = gain[gain.size - 1]; continue }
            // at is usually ascending, so the search carries on from last time
            if (j > 0 && lf[j] > x) j = 0
            while (j < lf.size - 2 && lf[j + 1] < x) j++
            val span = lf[j + 1] - lf[j]
            val t = if (span > 0) (x - lf[j]) / span else 0.0
            out[i] = gain[j] + t * (gain[j + 1] - gain[j])
        }
        return out
    }

    /** Mean of [values] where [freq] lies in [lo, hi]. 0 if none do. */
    fun meanInBand(freq: DoubleArray, values: DoubleArray, lo: Double, hi: Double): Double {
        var sum = 0.0
        var n = 0
        for (i in freq.indices) {
            if (freq[i] >= lo && freq[i] <= hi) { sum += values[i]; n++ }
        }
        return if (n > 0) sum / n else 0.0
    }

    /**
     * Log-frequency sigmoid: about 0 below [fLower], about 1 above [fUpper],
     * centred at their geometric mean. AutoEq's log_f_sigmoid.
     */
    fun logSigmoid(f: Double, fLower: Double, fUpper: Double): Double {
        val fc = sqrt(fUpper / fLower) * fLower
        val halfRange = log10(fUpper) - log10(fc)
        val z = (log10(f) - log10(fc)) / (halfRange / 4.0)
        return 1.0 / (1.0 + exp(-z))
    }

    /**
     * Savitzky-Golay window length in points for a width in octaves, made odd.
     * Rounds half to even, as Python's round does, so the sizes match AutoEq's.
     */
    fun windowSize(freq: DoubleArray, octaves: Double): Int {
        var sum = 0.0
        for (i in 1 until freq.size) sum += freq[i] / freq[i - 1]
        val step = sum / (freq.size - 1)
        var n = Math.rint(ln(2.0.pow(octaves)) / ln(step)).toInt()
        if (n % 2 == 0) n += 1
        return n
    }

    fun maxAbs(values: DoubleArray): Double = values.maxOfOrNull { abs(it) } ?: 0.0
}
