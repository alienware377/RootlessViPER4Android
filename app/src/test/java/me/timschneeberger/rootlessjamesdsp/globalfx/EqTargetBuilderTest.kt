package me.timschneeberger.rootlessjamesdsp.globalfx

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.GraphicEqCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqTargetBuilderTest {

    // The same nodes and probe frequencies as tools/globalfx_interp_harness.c.
    private val nodeFreq = doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0,
        1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0)
    private val nodeGain = doubleArrayOf(6.0, 5.0, 3.5, 1.0, -2.0, -2.5, 0.0, 1.5, 0.5, -1.0, 2.0, 4.0, 3.0, -3.0, -6.0)
    private val probes = doubleArrayOf(10.0, 30.0, 50.0, 80.0, 130.0, 200.0, 320.0, 500.0, 800.0, 1300.0,
        2000.0, 3200.0, 5000.0, 8000.0, 12500.0, 18000.0, 22000.0)

    // Printed by the engine's own interpolation.c on the emulator (x86_64),
    // tools/globalfx_interp_harness.c. If the Kotlin port drifts from the C
    // code these stop matching.
    private val enginePchip = doubleArrayOf(6.000000000, 5.814057353, 4.347986366, 2.318017456, -0.856960594,
        -2.338916903, -1.543321602, 0.913083364, 1.170680186, -0.445406930, 0.055936595, 3.293454047,
        3.758543974, 0.374871838, -4.807037940, -6.000000000, -6.000000000)
    private val engineMakima = doubleArrayOf(6.185584416, 5.725356429, 4.348647642, 2.321381756, -0.811916916,
        -2.537293723, -1.309530230, 0.945698644, 1.087407337, -0.427345710, 0.041901279, 3.241463780,
        3.903275899, 0.181889692, -4.717466254, -6.214177707, -6.258892569)

    private fun meqString() =
        (nodeFreq.map { it.toString() } + nodeGain.map { it.toString() }).joinToString(";")

    @Test
    fun `the default Multiband EQ text matches the app's`() {
        assertEquals(Constants.DEFAULT_EQ, EqInputs.DEFAULT_EQ)
    }

    @Test
    fun `PCHIP matches the engine's interpolation c`() {
        val curve = EqTargetBuilder.multibandCurve(EqInputs(eqEnabled = true, eqInterpolation = 0, eqBands = meqString()))!!
        for (i in probes.indices)
            assertEquals("at ${probes[i]} Hz", enginePchip[i], curve.valueAt(probes[i]), 1e-6)
    }

    @Test
    fun `makima matches the engine's interpolation c`() {
        val curve = EqTargetBuilder.multibandCurve(EqInputs(eqEnabled = true, eqInterpolation = 1, eqBands = meqString()))!!
        // Up to the 24 kHz end node; past it this holds where the engine extends.
        for (i in probes.indices)
            assertEquals("at ${probes[i]} Hz", engineMakima[i], curve.valueAt(probes[i]), 1e-6)
    }

    @Test
    fun `the Multiband EQ passes through every node and does not overshoot a monotone run`() {
        for (mode in 0..1) {
            val curve = EqTargetBuilder.multibandCurve(EqInputs(eqEnabled = true, eqInterpolation = mode, eqBands = meqString()))!!
            for (i in nodeFreq.indices)
                assertEquals(nodeGain[i], curve.valueAt(nodeFreq[i]), 1e-9)
        }
        // PCHIP's promise: between 1600 Hz (-1) and 4000 Hz (+4) the nodes
        // rise, so the curve stays between -1 and 4 there.
        val pchip = EqTargetBuilder.multibandCurve(EqInputs(eqEnabled = true, eqInterpolation = 0, eqBands = meqString()))!!
        var f = 1600.0
        while (f <= 4000.0) {
            val v = pchip.valueAt(f)
            assertTrue("$f Hz gives $v", v >= -1.0 - 1e-9 && v <= 4.0 + 1e-9)
            f += 10.0
        }
    }

    @Test
    fun `graphic plus parametric equals the engine's own merge`() {
        val bands = ParametricEqBandList().apply {
            add(ParametricEqBand(120.0, 4.0, 0.8, ParametricEqFilterType.LOW_SHELF))
            add(ParametricEqBand(3000.0, -5.0, 1.5, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(15000.0, 0.0, 0.7, ParametricEqFilterType.LOW_PASS))
        }
        val geq = "GraphicEQ: 20 2; 200 -1; 2000 3; 20000 -4"
        val preamp = -2.5
        // The engine's way (JamesDspBaseEngine.setGraphicEqCombined at 48 kHz).
        val peq = BiquadUtils.computeCombinedResponse(bands, numPoints = 512)
        val nodes = GraphicEqCurve.parse(geq)
        val engine = peq.map { (f, g) -> g + GraphicEqCurve.interpolate(nodes, f) + preamp }
        val freqs = peq.map { it.first }.toDoubleArray()
        val ours = EqTargetBuilder.build(
            EqInputs(geqEnabled = true, geqNodes = geq, peqEnabled = true, peqBands = bands.serialize(), peqPreamp = preamp),
            freqs, 48000.0).left
        for (i in freqs.indices) {
            val expected = engine[i].coerceIn(EqTargetBuilder.FLOOR_DB, EqTargetBuilder.CEILING_DB)
            assertEquals("at ${freqs[i]} Hz", expected, ours[i], 1e-9)
        }
    }

    @Test
    fun `bands for one ear make two curves`() {
        val bands = ParametricEqBandList().apply {
            add(ParametricEqBand(1000.0, 6.0, 4.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT))
            add(ParametricEqBand(4000.0, -6.0, 4.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT))
            add(ParametricEqBand(100.0, 2.0, 4.0, ParametricEqFilterType.PEAKING))
        }
        val t = EqTargetBuilder.build(EqInputs(peqEnabled = true, peqBands = bands.serialize()),
            doubleArrayOf(100.0, 1000.0, 4000.0), 48000.0)
        assertTrue(t.isStereoSplit)
        assertEquals(6.0, t.left[1], 0.1)
        assertEquals(0.0, t.right[1], 0.1)
        assertEquals(-6.0, t.right[2], 0.1)
        assertEquals(0.0, t.left[2], 0.1)
        // The shared curve has only the both-ears band.
        assertEquals(2.0, t.shared[0], 0.1)
        assertEquals(0.0, t.shared[1], 0.1)
    }

    @Test
    fun `bass boost becomes an 80 Hz shelf`() {
        val t = EqTargetBuilder.build(EqInputs(bassEnabled = true, bassMaxGain = 9.0),
            doubleArrayOf(5.0, 20.0, 1000.0, 15000.0), 48000.0).left
        assertEquals(9.0, t[1], 0.3)
        assertEquals(t[1], t[0], 1e-9) // held below 20 Hz
        assertEquals(0.0, t[2], 0.1)
        assertEquals(0.0, t[3], 0.01)
    }

    @Test
    fun `cards that are off add nothing`() {
        val t = EqTargetBuilder.build(EqInputs(eqBands = meqString(), geqNodes = "GraphicEQ: 100 9; 1000 9",
            peqBands = "PEQ: 1000 6 1 0; ", bassMaxGain = 12.0), doubleArrayOf(50.0, 1000.0, 10000.0), 48000.0)
        assertTrue(t.left.all { it == 0.0 })
        assertTrue(!t.isStereoSplit)
    }

    @Test
    fun `a low-pass does not run off to minus infinity at Nyquist`() {
        val bands = ParametricEqBandList().apply {
            add(ParametricEqBand(8000.0, 96.0, 0.7, ParametricEqFilterType.LOW_PASS))
        }
        val t = EqTargetBuilder.build(EqInputs(peqEnabled = true, peqBands = bands.serialize()),
            doubleArrayOf(23900.0, 24000.0), 48000.0).left
        assertTrue(t.all { it.isFinite() && it >= EqTargetBuilder.FLOOR_DB })
    }
}
