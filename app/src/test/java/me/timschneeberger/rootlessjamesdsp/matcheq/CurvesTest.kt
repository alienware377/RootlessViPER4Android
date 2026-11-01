package me.timschneeberger.rootlessjamesdsp.matcheq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * The grids, interpolation and smoothing sizes. These have to be exactly
 * AutoEq's or nothing downstream lines up with the profiles people already
 * know: a different grid shifts every node, and a window one point wider
 * changes how much detail survives.
 */
class CurvesTest {

    @Test
    fun `working grid is AutoEq's 695 points from 20 Hz`() {
        val g = Curves.grid
        assertEquals(695, g.size)
        assertEquals(20.0, g.first(), 0.0)
        assertTrue(g.last() <= 20000.0)
        assertEquals(19955.54, g.last(), 0.01)
        for (i in 1 until g.size) assertEquals(1.01, g[i] / g[i - 1], 1e-9)
    }

    @Test
    fun `GraphicEQ grid is 127 whole frequencies ending at 19871 Hz`() {
        val g = Curves.graphicEqGrid
        assertEquals(127, g.size)
        assertEquals(20.0, g.first(), 0.0)
        assertEquals(19871.0, g.last(), 0.0)
        g.forEach { assertEquals(it, Math.floor(it), 0.0) }
        for (i in 1 until g.size) assertTrue(g[i] > g[i - 1])
    }

    @Test
    fun `interpolation is exact for a curve linear in log frequency`() {
        val f = doubleArrayOf(50.0, 500.0, 5000.0)
        val g = DoubleArray(3) { 3.0 * log10(f[it]) - 1.0 }
        val at = doubleArrayOf(70.0, 123.0, 999.0, 2345.0, 4999.0)
        val v = Curves.interpolate(f, g, at)
        for (i in at.indices) assertEquals(3.0 * log10(at[i]) - 1.0, v[i], 1e-9)
    }

    @Test
    fun `interpolation holds the end values outside the data`() {
        val f = doubleArrayOf(100.0, 1000.0, 10000.0)
        val g = doubleArrayOf(-4.0, 0.0, 7.0)
        val v = Curves.interpolate(f, g, doubleArrayOf(20.0, 99.0, 10001.0, 20000.0))
        assertEquals(-4.0, v[0], 0.0)
        assertEquals(-4.0, v[1], 0.0)
        assertEquals(7.0, v[2], 0.0)
        assertEquals(7.0, v[3], 0.0)
    }

    @Test
    fun `smoothing windows match AutoEq's sizes`() {
        val g = Curves.grid
        assertEquals(7, Curves.windowSize(g, 1.0 / 12.0))
        assertEquals(139, Curves.windowSize(g, 2.0))
        assertEquals(15, Curves.windowSize(g, 1.0 / 5.0))
        assertEquals(3, Curves.windowSize(g, 1.0 / 24.0))
        assertEquals(13, Curves.windowSize(g, 1.0 / 6.0))
        assertEquals(23, Curves.windowSize(g, 1.0 / 3.0))
    }

    @Test
    fun `log sigmoid crosses one half at the geometric centre`() {
        assertEquals(0.5, Curves.logSigmoid(Math.sqrt(6000.0 * 8000.0), 6000.0, 8000.0), 1e-12)
        assertTrue(Curves.logSigmoid(4000.0, 6000.0, 8000.0) < 0.01)
        assertTrue(Curves.logSigmoid(12000.0, 6000.0, 8000.0) > 0.99)
    }

    @Test
    fun `stored curves survive a round trip`() {
        val c = Synthetic.headphone()
        val back = FrequencyCurve.deserialize(c.serialize())!!
        assertEquals(c.size, back.size)
        for (i in 0 until c.size) {
            assertEquals(c.freq[i], back.freq[i], 0.005)
            assertEquals(c.gain[i], back.gain[i], 0.005)
        }
        assertEquals(null, FrequencyCurve.deserialize("20,1;nonsense"))
        assertEquals(null, FrequencyCurve.deserialize(""))
    }

    @Test
    fun `a full-resolution export is stored small and still gives the same result`() {
        // Like a REW text export: 32768 points spaced 0.73 Hz apart from 0.73 Hz up.
        val n = 32768
        val f = DoubleArray(n) { (it + 1) * 24000.0 / n }
        val c = me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils.computeCoefficients(
            3000.0, 6.0, 4.0, me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType.PEAKING, 48000.0)
        val g = DoubleArray(n) {
            me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils.magnitudeResponse(c, f[it], 48000.0) -
                2.0 * kotlin.math.log2(f[it] / 1000.0)
        }
        val dense = FrequencyCurve(f, g)

        val text = dense.forStorage().serialize()
        assertTrue("stored ${text.length} characters", text.length < 20000)
        val stored = FrequencyCurve.deserialize(text)!!
        assertEquals(Curves.grid.size, stored.size)

        val target = Synthetic.target()
        val a = MatchEqSolver.solve(dense, target)
        val b = MatchEqSolver.solve(stored, target)
        for (i in a.outGain.indices) assertEquals(a.outGain[i], b.outGain[i], 0.02)

        // Anything already no bigger than the grid is kept exactly as it is.
        val small = Synthetic.headphone()
        assertTrue(small.forStorage() === small)
    }
}
