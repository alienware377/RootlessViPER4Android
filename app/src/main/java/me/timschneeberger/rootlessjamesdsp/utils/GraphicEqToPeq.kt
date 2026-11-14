package me.timschneeberger.rootlessjamesdsp.utils

/*
 * The fitting approach (log-frequency target, greedy peaking filters at the
 * largest error, joint Nelder-Mead refinement after each one, a penalty on
 * high Q, a preamp against clipping) is ported from GraphEqToPeqConverter.kt
 * in evoeram's Loudness / PEQ fork of RootlessJamesDSP,
 * https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP, licensed under
 * the GNU GPL version 3 like this app. See THIRD_PARTY_NOTICES.md.
 */

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fits a few peaking filters to a graphic EQ curve, for when a curve has to
 * go somewhere that only takes a parametric EQ (EqualizerAPO presets, other
 * players, or this app's own PEQ with its per-ear bands).
 *
 * Filters go in one at a time where the fit is worst, each is first fitted
 * on its own against what the others leave, and then all of them are refined
 * together. It stops early once nothing is off by more than half a decibel,
 * so a simple curve gets a few filters rather than the whole allowance.
 *
 * The overall level of the curve is not something peaking filters can make,
 * so the filters fit the curve's shape around its median and the level goes
 * into the preamp, which is then lowered further if the filters would push
 * anything above 0 dB.
 *
 * Every response here comes from BiquadUtils at 48 kHz, which is how the
 * engine turns the parametric EQ into its filter, so the reported error is
 * the error of what is actually applied.
 */
object GraphicEqToPeq {

    /** The filter counts offered. */
    val FILTER_COUNTS = intArrayOf(8, 12, 16, 20)
    const val DEFAULT_FILTER_COUNT = 16

    const val MIN_FREQ = 20.0
    const val MAX_FREQ = 20000.0
    const val MIN_Q = 0.2
    const val MAX_Q = 10.0
    const val MAX_GAIN = 24.0
    /** The parametric EQ's own preamp floor. */
    const val MIN_PREAMP = -30.0

    private const val SAMPLE_RATE = 48000.0
    private const val FIT_POINTS = 256
    private const val CHECK_POINTS = 512
    private const val GOOD_ENOUGH_DB = 0.5
    private const val WEAK_GAIN_DB = 0.25
    private const val MAX_FILTERS = 20
    /** Half an octave either side, in natural log units. */
    private val BARREN_SPAN = ln(2.0) / 2.0

    // A high Q rings for longer, so Q above this costs in the fit.
    private const val SHARP_Q = 4.0
    private const val SHARPNESS_WEIGHT = 0.5

    enum class Quality {
        EXCELLENT, GOOD, FAIR, POOR;

        companion object {
            fun of(maxErrorDb: Double) = when {
                maxErrorDb < 0.5 -> EXCELLENT
                maxErrorDb < 1.0 -> GOOD
                maxErrorDb < 2.0 -> FAIR
                else -> POOR
            }
        }
    }

    class Result(
        /** Peaking bands on both ears, lowest frequency first. */
        val bands: List<ParametricEqBand>,
        val preampDb: Double,
        /** Largest difference from the curve's shape, 20 Hz to 20 kHz. */
        val maxErrorDb: Double,
        val rmsErrorDb: Double,
    ) {
        val quality get() = Quality.of(maxErrorDb)

        fun toBandList() = ParametricEqBandList().also { it.addAll(bands) }

        fun toApoString(): String = toBandList().toApoString(preampDb)
    }

    /**
     * @param nodes (frequency Hz, gain dB) pairs in any order. Pairs that are
     *   not finite or sit at or below 0 Hz are left out.
     * @param maxFilters upper bound on the filters used, held to 1..20
     * @param cancelled polled while working; when it turns true the result so
     *   far comes back at once
     * @param onProgress the number of filters placed so far, from this thread
     */
    fun convert(
        nodes: List<Pair<Double, Double>>,
        maxFilters: Int = DEFAULT_FILTER_COUNT,
        cancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): Result {
        val clean = nodes
            .filter { it.first.isFinite() && it.second.isFinite() && it.first > 0.0 }
            .sortedBy { it.first }
        val limit = maxFilters.coerceIn(1, MAX_FILTERS)

        val grid = Grid(FIT_POINTS)
        val target = DoubleArray(grid.size) { GraphicEqCurve.interpolate(clean, grid.freqs[it]) }
        val level = median(target)
        val shape = DoubleArray(grid.size) { target[it] - level }
        val fit = Fit(grid, shape)

        val params = DoubleArray(limit * 3)
        val model = DoubleArray(grid.size)
        var count = 0
        // Places where a filter was tried and bought nothing, usually an edge
        // of the range a peaking filter cannot bend. Without this every later
        // filter lands on the same spot and the allowance is spent on nothing.
        val barren = mutableListOf<Double>()
        var attempts = 0

        while (count < limit && attempts++ < limit * 3 && !cancelled()) {
            fit.cascadeDb(params, count, model)
            var worst = -1
            var worstErr = 0.0
            for (i in shape.indices) {
                val e = abs(shape[i] - model[i])
                if (e > worstErr && barren.none { abs(ln(grid.freqs[i] / it)) < BARREN_SPAN }) {
                    worst = i
                    worstErr = e
                }
            }
            if (worst < 0 || worstErr < GOOD_ENOUGH_DB) break
            val peak = shape[worst] - model[worst]

            // Width of the error around its peak, for a starting Q
            var lo = worst
            var hi = worst
            while (lo > 0 && (shape[lo - 1] - model[lo - 1]) * peak > peak * peak / 4.0) lo--
            while (hi < grid.size - 1 && (shape[hi + 1] - model[hi + 1]) * peak > peak * peak / 4.0) hi++
            val centre = grid.freqs[worst]
            val q = if (hi > lo) centre / (grid.freqs[hi] - grid.freqs[lo]) else MAX_Q

            val before = fit.loss(params.copyOf(count * 3), count, null)
            val start = doubleArrayOf(
                ln(centre),
                ln(q.coerceIn(MIN_Q, MAX_Q)),
                peak.coerceIn(-MAX_GAIN, MAX_GAIN),
            )
            val alone = nelderMead(start, 200, cancelled) { fit.loss(it, 1, model) }
            val trial = params.copyOf(count * 3 + 3)
            alone.copyInto(trial, count * 3)
            val joint = nelderMead(trial, 100 + 30 * (count + 1), cancelled) {
                fit.loss(it, count + 1, null)
            }

            if (fit.loss(joint, count + 1, null) > before * 0.98) {
                barren.add(centre)
                continue
            }
            // Refining all of them together can also leave a filter, the new
            // one or an older one, with next to no gain. It is dropped and its
            // place counted as barren, so the slot goes to something useful.
            var kept = 0
            for (k in 0..count) {
                if (abs(joint[k * 3 + 2]) < WEAK_GAIN_DB) {
                    barren.add(exp(joint[k * 3]))
                    continue
                }
                joint.copyInto(params, kept * 3, k * 3, k * 3 + 3)
                kept++
            }
            count = kept
            onProgress(count)
        }

        // A simplex that has shrunk onto a slope stops early; one more run
        // from a fresh simplex around the result usually still finds a little.
        if (count > 0 && !cancelled()) {
            val current = params.copyOf(count * 3)
            val polished = nelderMead(current, 100 + 30 * count, cancelled) { fit.loss(it, count, null) }
            if (fit.loss(polished, count, null) < fit.loss(current, count, null))
                polished.copyInto(params)
        }

        val bands = (0 until count)
            .map { i -> band(params, i) }
            .sortedBy { it.frequency }

        return finish(clean, level, bands)
    }

    /** Errors and preamp from the rounded bands, through the engine's own curve. */
    private fun finish(nodes: List<Pair<Double, Double>>, level: Double, bands: List<ParametricEqBand>): Result {
        val response = if (bands.isEmpty()) null
            else BiquadUtils.computeCombinedResponse(bands, CHECK_POINTS, MIN_FREQ, MAX_FREQ, SAMPLE_RATE)
        var maxError = 0.0
        var sumSq = 0.0
        var maxGain = 0.0
        val freqs = Grid(CHECK_POINTS).freqs
        for (i in freqs.indices) {
            val got = response?.get(i)?.second ?: 0.0
            val err = GraphicEqCurve.interpolate(nodes, freqs[i]) - level - got
            maxError = maxOf(maxError, abs(err))
            sumSq += err * err
            maxGain = maxOf(maxGain, got)
        }
        val preamp = minOf(level, -maxGain, 0.0).coerceAtLeast(MIN_PREAMP)
        return Result(bands, rounded(preamp, 100.0), maxError, sqrt(sumSq / freqs.size))
    }

    private fun band(params: DoubleArray, i: Int): ParametricEqBand {
        val freq = exp(params[i * 3]).coerceIn(MIN_FREQ, MAX_FREQ)
        val q = exp(params[i * 3 + 1]).coerceIn(MIN_Q, MAX_Q)
        val gain = params[i * 3 + 2].coerceIn(-MAX_GAIN, MAX_GAIN)
        return ParametricEqBand(
            rounded(freq, 10.0), rounded(gain, 100.0), rounded(q, 1000.0),
            ParametricEqFilterType.PEAKING,
        )
    }

    private fun rounded(v: Double, scale: Double) = (v * scale).roundToLong() / scale

    private fun median(v: DoubleArray): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private class Grid(points: Int) {
        val size = points
        val freqs = DoubleArray(points) { MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(it / (points - 1.0)) }
        val cos1 = DoubleArray(points)
        val sin1 = DoubleArray(points)
        val cos2 = DoubleArray(points)
        val sin2 = DoubleArray(points)

        init {
            for (i in 0 until points) {
                val w = 2.0 * Math.PI * freqs[i] / SAMPLE_RATE
                cos1[i] = cos(w)
                sin1[i] = sin(w)
                cos2[i] = cos(2.0 * w)
                sin2[i] = sin(2.0 * w)
            }
        }
    }

    /**
     * The loss and the cascade response, on one grid. Parameters are three per
     * filter: ln(frequency), ln(Q) and gain in dB, which spreads frequency and
     * Q evenly for the simplex.
     */
    private class Fit(val grid: Grid, val shape: DoubleArray) {
        private val power = DoubleArray(grid.size)
        private val scratch = DoubleArray(grid.size)

        /**
         * The cascade in dB. The filters' power responses multiply, so only
         * one logarithm per point is needed however many filters there are.
         */
        fun cascadeDb(params: DoubleArray, count: Int, out: DoubleArray) {
            power.fill(1.0)
            for (k in 0 until count) {
                val c = BiquadUtils.computeCoefficients(
                    exp(params[k * 3]), params[k * 3 + 2], exp(params[k * 3 + 1]),
                    ParametricEqFilterType.PEAKING, SAMPLE_RATE)
                for (i in 0 until grid.size) {
                    val nr = c.b0 + c.b1 * grid.cos1[i] + c.b2 * grid.cos2[i]
                    val ni = c.b1 * grid.sin1[i] + c.b2 * grid.sin2[i]
                    val dr = c.a0 + c.a1 * grid.cos1[i] + c.a2 * grid.cos2[i]
                    val di = c.a1 * grid.sin1[i] + c.a2 * grid.sin2[i]
                    power[i] *= (nr * nr + ni * ni) / (dr * dr + di * di)
                }
            }
            for (i in 0 until grid.size) out[i] = 10.0 * log10(power[i])
        }

        /**
         * Mean squared error against the shape plus the sharpness penalty.
         * [fixed] is a response already in place that these filters add to.
         */
        fun loss(params: DoubleArray, count: Int, fixed: DoubleArray?): Double {
            for (v in params) if (!v.isFinite()) return Double.MAX_VALUE
            cascadeDb(params, count, scratch)
            var sum = 0.0
            for (i in 0 until grid.size) {
                val e = shape[i] - scratch[i] - (fixed?.get(i) ?: 0.0)
                sum += e * e
            }
            var penalty = 0.0
            for (k in 0 until count) {
                val q = exp(params[k * 3 + 1])
                if (q > SHARP_Q) penalty += SHARPNESS_WEIGHT * (q - SHARP_Q) * (q - SHARP_Q)
            }
            val l = sum / grid.size + penalty
            return if (l.isFinite()) l else Double.MAX_VALUE
        }
    }

    private val LOWER = doubleArrayOf(ln(MIN_FREQ), ln(MIN_Q), -MAX_GAIN)
    private val UPPER = doubleArrayOf(ln(MAX_FREQ), ln(MAX_Q), MAX_GAIN)

    /**
     * Nelder-Mead simplex, with each vertex held inside the per-filter bounds.
     */
    private fun nelderMead(
        initial: DoubleArray,
        maxIter: Int,
        cancelled: () -> Boolean,
        fn: (DoubleArray) -> Double,
    ): DoubleArray {
        val n = initial.size
        fun clamp(v: DoubleArray) = v.also {
            for (j in it.indices) it[j] = it[j].coerceIn(LOWER[j % 3], UPPER[j % 3])
        }

        val simplex = ArrayList<DoubleArray>(n + 1)
        simplex.add(clamp(initial.copyOf()))
        for (i in 0 until n) {
            val v = simplex[0].copyOf()
            val step = (UPPER[i % 3] - LOWER[i % 3]) * 0.05
            // Step inwards when the start sits on the upper bound
            v[i] = if (v[i] + step <= UPPER[i % 3]) v[i] + step else v[i] - step
            simplex.add(v)
        }
        val values = DoubleArray(n + 1) { fn(simplex[it]) }
        val centroid = DoubleArray(n)

        for (iter in 0 until maxIter) {
            if (cancelled()) break
            val order = (0..n).sortedBy { values[it] }
            val best = order[0]
            val worst = order[n]
            val secondWorst = order[n - 1]
            if (values[worst] - values[best] < 1e-10) break

            centroid.fill(0.0)
            for (i in 0..n) if (i != worst) for (j in 0 until n) centroid[j] += simplex[i][j]
            for (j in 0 until n) centroid[j] /= n.toDouble()

            val reflected = clamp(DoubleArray(n) { centroid[it] + (centroid[it] - simplex[worst][it]) })
            val fr = fn(reflected)
            when {
                fr < values[best] -> {
                    val expanded = clamp(DoubleArray(n) { centroid[it] + 2.0 * (reflected[it] - centroid[it]) })
                    val fe = fn(expanded)
                    if (fe < fr) { simplex[worst] = expanded; values[worst] = fe }
                    else { simplex[worst] = reflected; values[worst] = fr }
                }
                fr < values[secondWorst] -> { simplex[worst] = reflected; values[worst] = fr }
                else -> {
                    val contracted = clamp(DoubleArray(n) { centroid[it] + 0.5 * (simplex[worst][it] - centroid[it]) })
                    val fc = fn(contracted)
                    if (fc < values[worst]) {
                        simplex[worst] = contracted; values[worst] = fc
                    } else {
                        for (i in 0..n) {
                            if (i == best) continue
                            simplex[i] = clamp(DoubleArray(n) { simplex[best][it] + 0.5 * (simplex[i][it] - simplex[best][it]) })
                            values[i] = fn(simplex[i])
                        }
                    }
                }
            }
        }
        return simplex[(0..n).minBy { values[it] }]
    }
}
