package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The graphic to parametric EQ fit. The targets are built from known filters
 * or simple shapes, so what a good fit looks like is known in advance.
 */
class GraphicEqToPeqTest {

    /** A curve sampled the way AutoEq writes one: about 1/12 octave apart. */
    private fun sampled(gainAt: (Double) -> Double): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var f = 20.0
        while (f <= 20000.0) {
            out.add(f to gainAt(f))
            f *= 2.0.pow(1.0 / 12.0)
        }
        return out
    }

    private fun filterCurve(vararg bands: ParametricEqBand): List<Pair<Double, Double>> {
        val response = BiquadUtils.computeCombinedResponse(bands.toList(), 512)
        return sampled { f -> GraphicEqCurve.interpolate(response, f) }
    }

    private fun assertSane(r: GraphicEqToPeq.Result, maxFilters: Int) {
        assertTrue("too many filters: ${r.bands.size}", r.bands.size <= maxFilters)
        assertTrue(r.preampDb.isFinite() && r.preampDb <= 0.0 && r.preampDb >= GraphicEqToPeq.MIN_PREAMP)
        assertTrue(r.maxErrorDb.isFinite() && r.rmsErrorDb.isFinite())
        assertTrue(r.rmsErrorDb <= r.maxErrorDb + 1e-9)
        for (b in r.bands) {
            assertTrue("freq ${b.frequency}", b.frequency in GraphicEqToPeq.MIN_FREQ..GraphicEqToPeq.MAX_FREQ)
            assertTrue("q ${b.q}", b.q in GraphicEqToPeq.MIN_Q..GraphicEqToPeq.MAX_Q)
            assertTrue("gain ${b.gain}", abs(b.gain) <= GraphicEqToPeq.MAX_GAIN)
            assertEquals(ParametricEqFilterType.PEAKING, b.filterType)
        }
        // Lowest frequency first, as the PEQ screen lists them
        assertEquals(r.bands.sortedBy { it.frequency }, r.bands)
        // Filters that do nothing are not handed out
        for (b in r.bands) assertTrue("idle filter ${b.gain}", abs(b.gain) >= 0.25)
    }

    @Test
    fun `a single 6 dB peak is found with one filter, within 0_25 dB`() {
        val target = filterCurve(ParametricEqBand(1000.0, 6.0, 1.4))
        val r = GraphicEqToPeq.convert(target, 8)
        assertSane(r, 8)
        assertEquals(1, r.bands.size)
        assertTrue("max error ${r.maxErrorDb}", r.maxErrorDb < 0.25)
        // The strongest band is the peak itself
        val main = r.bands.maxBy { it.gain }
        assertEquals(1000.0, main.frequency, 60.0)
        assertEquals(6.0, main.gain, 0.5)
        // Lifted 6 dB at most, so the preamp takes 6 dB back
        assertEquals(-6.0, r.preampDb, 0.3)
    }

    @Test
    fun `a 6 dB peak drawn as a triangle of nodes is fitted within 0_5 dB`() {
        val target = listOf(20.0 to 0.0, 500.0 to 0.0, 1000.0 to 6.0, 2000.0 to 0.0, 20000.0 to 0.0)
        val r = GraphicEqToPeq.convert(target, 8)
        assertSane(r, 8)
        assertTrue(r.bands.size <= 4)
        assertTrue("max error ${r.maxErrorDb}", r.maxErrorDb < 0.5)
    }

    @Test
    fun `a 6 dB bass shelf is matched by peaking filters within 0_5 dB`() {
        // The shape of the Harman bass shelf
        val target = filterCurve(ParametricEqBand(105.0, 6.0, 0.7, ParametricEqFilterType.LOW_SHELF))
        val r = GraphicEqToPeq.convert(target, 8)
        assertSane(r, 8)
        assertTrue("max error ${r.maxErrorDb}", r.maxErrorDb < 0.5)
        // Nothing above the shelf, so the preamp only takes back the boost
        assertEquals(-6.0, r.preampDb, 0.5)
    }

    @Test
    fun `a busy curve keeps every limit at every filter count`() {
        // A headphone-like correction with deep narrow notches and big swings
        val target = sampled { f ->
            val x = kotlin.math.log10(f)
            8.0 * kotlin.math.sin(x * 7.3) + 5.0 * kotlin.math.cos(x * 19.0) - 3.0 * (x - 3.0)
        }
        val errors = GraphicEqToPeq.FILTER_COUNTS.map { n ->
            val r = GraphicEqToPeq.convert(target, n)
            assertSane(r, n)
            assertEquals(n, r.bands.size)
            r.maxErrorDb
        }
        // More filters, a closer fit: 20 of them get this one within 1.5 dB
        assertTrue("$errors", errors.last() < errors.first())
        assertTrue("$errors", errors.last() < 1.5)
    }

    @Test
    fun `nonsense input gives a usable result and never NaN`() {
        val wild = listOf(
            1000.0 to Double.NaN, Double.NaN to 3.0, -50.0 to 4.0, 0.0 to 2.0,
            Double.POSITIVE_INFINITY to 1.0, 50.0 to Double.NEGATIVE_INFINITY,
            100.0 to 90.0, 101.0 to -90.0, 100.0 to 30.0, 15000.0 to -40.0,
        )
        val r = GraphicEqToPeq.convert(wild.shuffled(), 12)
        assertSane(r, 12)
    }

    @Test
    fun `no nodes give no filters`() {
        val r = GraphicEqToPeq.convert(emptyList())
        assertTrue(r.bands.isEmpty())
        assertEquals(0.0, r.preampDb, 0.0)
        assertEquals(0.0, r.maxErrorDb, 0.0)
        assertEquals(GraphicEqToPeq.Quality.EXCELLENT, r.quality)
    }

    @Test
    fun `one node is a level, which goes into the preamp`() {
        val down = GraphicEqToPeq.convert(listOf(1000.0 to -6.0))
        assertTrue(down.bands.isEmpty())
        assertEquals(-6.0, down.preampDb, 1e-9)

        // A boost cannot be a preamp, which never goes above 0 dB
        val up = GraphicEqToPeq.convert(listOf(1000.0 to 6.0))
        assertTrue(up.bands.isEmpty())
        assertEquals(0.0, up.preampDb, 0.0)
    }

    @Test
    fun `the filter count is held to its range`() {
        val target = filterCurve(
            ParametricEqBand(60.0, 8.0, 1.0), ParametricEqBand(3000.0, -6.0, 3.0),
            ParametricEqBand(8000.0, 5.0, 2.0))
        assertSane(GraphicEqToPeq.convert(target, 0), 1)
        assertSane(GraphicEqToPeq.convert(target, -3), 1)
        assertSane(GraphicEqToPeq.convert(target, 100), 20)
    }

    @Test
    fun `nothing applied goes above 0 dB`() {
        val target = filterCurve(ParametricEqBand(80.0, 9.0, 0.8), ParametricEqBand(6000.0, 4.0, 2.0))
        val r = GraphicEqToPeq.convert(target, 8)
        val applied = BiquadUtils.computeCombinedResponse(r.bands, 2048)
        for ((_, g) in applied) assertTrue("$g", g + r.preampDb <= 0.05)
    }

    @Test
    fun `stopping early hands back what is there`() {
        var calls = 0
        val r = GraphicEqToPeq.convert(
            filterCurve(ParametricEqBand(200.0, 6.0, 1.0), ParametricEqBand(5000.0, -6.0, 2.0)),
            16, cancelled = { ++calls > 3 })
        assertSane(r, 16)
    }

    @Test
    fun `the preset text reads back as the same bands`() {
        val r = GraphicEqToPeq.convert(
            filterCurve(ParametricEqBand(150.0, 4.0, 1.0), ParametricEqBand(4000.0, -5.0, 2.5)), 8)
        val apo = r.toApoString()
        assertTrue(apo.startsWith("Preamp: "))
        val back = ParametricEqBandList()
        val imported = back.fromApoString(apo)
        assertEquals(r.preampDb, imported.preampDb, 1e-9)
        assertEquals(r.bands, back.toList())
    }

    @Test
    fun `quality follows the largest error`() {
        assertEquals(GraphicEqToPeq.Quality.EXCELLENT, GraphicEqToPeq.Quality.of(0.3))
        assertEquals(GraphicEqToPeq.Quality.GOOD, GraphicEqToPeq.Quality.of(0.7))
        assertEquals(GraphicEqToPeq.Quality.FAIR, GraphicEqToPeq.Quality.of(1.5))
        assertEquals(GraphicEqToPeq.Quality.POOR, GraphicEqToPeq.Quality.of(3.0))
    }
}
