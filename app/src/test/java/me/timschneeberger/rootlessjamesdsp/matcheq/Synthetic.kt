package me.timschneeberger.rootlessjamesdsp.matcheq

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils

/**
 * Made-up curves for the Match EQ tests. Nothing here comes from a real
 * measurement or a published target: those are not ours to put in the
 * repository, so every fixture is built from biquads and straight lines.
 */
object Synthetic {
    val grid get() = Curves.grid

    fun flat() = FrequencyCurve(grid.copyOf(), DoubleArray(grid.size))

    fun peaking(fc: Double, gain: Double, q: Double): DoubleArray {
        val c = BiquadUtils.computeCoefficients(fc, gain, q, ParametricEqFilterType.PEAKING, 44100.0)
        return DoubleArray(grid.size) { BiquadUtils.magnitudeResponse(c, grid[it], 44100.0) }
    }

    fun curve(vararg parts: DoubleArray): FrequencyCurve {
        val g = DoubleArray(grid.size)
        for (p in parts) for (i in g.indices) g[i] += p[i]
        return FrequencyCurve(grid.copyOf(), g)
    }

    fun add(c: FrequencyCurve, vararg parts: DoubleArray): FrequencyCurve {
        val g = c.gain.copyOf()
        for (p in parts) for (i in g.indices) g[i] += p[i]
        return FrequencyCurve(c.freq.copyOf(), g)
    }

    fun constant(v: Double) = DoubleArray(grid.size) { v }

    /** A plausible-looking headphone: some bass, a presence bump, a treble peak and a dip. */
    fun headphone() = curve(
        peaking(60.0, 3.0, 0.8),
        peaking(2500.0, 4.0, 1.2),
        peaking(5500.0, -3.0, 2.0),
        peaking(9000.0, 3.5, 3.0),
    )

    /** A target that is not flat either: gentle downward tilt with a bass shelf feel. */
    fun target() = curve(
        DoubleArray(grid.size) { -0.8 * kotlin.math.log2(grid[it] / 1000.0) },
        peaking(80.0, 2.0, 0.6),
    )

    /** Index of the grid point nearest [f]. */
    fun at(f: Double): Int = grid.indices.minByOrNull { kotlin.math.abs(grid[it] - f) }!!
}
