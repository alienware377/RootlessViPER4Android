package me.timschneeberger.rootlessjamesdsp.matcheq

import me.timschneeberger.rootlessjamesdsp.matcheq.Synthetic.at
import me.timschneeberger.rootlessjamesdsp.matcheq.Synthetic.peaking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class SlopeLimiterTest {

    private val grid = Curves.grid

    @Test
    fun `peaks need to stand out by their prominence`() {
        val y = doubleArrayOf(0.0, 2.0, 1.5, 3.0, 0.0, 0.5, 0.0)
        // 2.0 only rises 0.5 above the saddle towards the higher 3.0
        assertArrayEquals(intArrayOf(1, 3, 5), SlopeLimiter.localMaxima(y))
        assertEquals(0.5, SlopeLimiter.prominence(y, 1), 1e-12)
        assertEquals(3.0, SlopeLimiter.prominence(y, 3), 1e-12)
        assertArrayEquals(intArrayOf(3), SlopeLimiter.findPeaks(y, 1.0))
    }

    @Test
    fun `a flat top counts once, in its middle, and the ends never count`() {
        val y = doubleArrayOf(5.0, 1.0, 4.0, 4.0, 4.0, 4.0, 1.0, 6.0)
        assertArrayEquals(intArrayOf(3), SlopeLimiter.localMaxima(y))
    }

    @Test
    fun `a narrow boost is held to the slope limit while a broad one is untouched`() {
        val narrow = peaking(3000.0, 20.0, 8.0)
        val broad = peaking(200.0, 8.0, 0.7)
        val y = DoubleArray(grid.size) { narrow[it] + broad[it] }
        val limited = SlopeLimiter.limit(grid, y, 18.0)

        // The narrow spike only gets what an 18 dB per octave climb allows.
        assertTrue("limited spike ${limited[at(3000.0)]}", limited[at(3000.0)] < 8.0)
        for (i in 1 until grid.size) {
            if (limited[i] == y[i] && limited[i - 1] == y[i - 1]) continue
            val slope = (limited[i] - limited[i - 1]) / (ln(grid[i] / grid[i - 1]) / ln(2.0))
            assertTrue("slope $slope dB/oct at ${grid[i]} Hz", slope <= 18.0 + 1e-6)
        }
        // The broad boost climbs gently, so it is left exactly as it was.
        for (i in grid.indices.filter { grid[it] in 40.0..1000.0 })
            assertEquals(y[i], limited[i], 1e-9)
    }

    @Test
    fun `without peaks or dips nothing changes`() {
        val y = DoubleArray(grid.size) { 2.0 * ln(grid[it]) }
        assertArrayEquals(y, SlopeLimiter.limit(grid, y), 0.0)
    }

    @Test
    fun `the way out of a cut is never limited`() {
        // A dip in the correction followed by recovery: the climb back up does
        // not lead to a peak, so it must stay exactly as steep as it is.
        val y = peaking(2000.0, -15.0, 6.0)
        val limited = SlopeLimiter.limit(grid, y)
        for (i in grid.indices) assertEquals(y[i], limited[i], 1e-9)
    }
}
