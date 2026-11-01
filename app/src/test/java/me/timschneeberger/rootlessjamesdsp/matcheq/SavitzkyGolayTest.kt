package me.timschneeberger.rootlessjamesdsp.matcheq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavitzkyGolayTest {

    @Test
    fun `weights sum to one and match the published 7 point set`() {
        val c = SavitzkyGolay.coefficients(7)
        assertEquals(1.0, c.sum(), 1e-12)
        // Savitzky and Golay's table: (-2, 3, 6, 7, 6, 3, -2) / 21
        val expected = doubleArrayOf(-2.0, 3.0, 6.0, 7.0, 6.0, 3.0, -2.0).map { it / 21.0 }
        for (i in c.indices) assertEquals(expected[i], c[i], 1e-12)
    }

    @Test
    fun `a parabola passes through untouched, edges included`() {
        // This is what proves the edge handling: a fixed centre window would
        // bend the first and last few points of any curved input.
        val y = DoubleArray(200) { val x = it - 37.0; 0.01 * x * x - 0.3 * x + 4.0 }
        for (w in intArrayOf(7, 15, 139)) {
            val s = SavitzkyGolay.smooth(y, w)
            for (i in y.indices) assertEquals("window $w at $i", y[i], s[i], 1e-7)
        }
    }

    @Test
    fun `a one-point spike is mostly removed`() {
        val y = DoubleArray(Curves.grid.size)
        val k = Synthetic.at(2000.0)
        y[k] = 10.0
        val s = SavitzkyGolay.smooth(y, 7)
        assertTrue("spike left at ${s[k]}", s[k] < 3.5)
        val wide = SavitzkyGolay.smooth(y, 15)
        assertTrue("spike left at ${wide[k]}", wide[k] < 2.0)
    }

    @Test
    fun `a window wider than the data still smooths`() {
        val y = DoubleArray(9) { (it * it).toDouble() }
        val s = SavitzkyGolay.smooth(y, 139)
        for (i in y.indices) assertEquals(y[i], s[i], 1e-9)
    }
}
