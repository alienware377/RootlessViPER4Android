package me.timschneeberger.rootlessjamesdsp.matcheq

import me.timschneeberger.rootlessjamesdsp.matcheq.Synthetic.at
import me.timschneeberger.rootlessjamesdsp.matcheq.Synthetic.peaking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The whole chain from measurement and target to GraphicEQ text.
 *
 * During development the same code was also checked against AutoEq's own
 * published results for 29 headphones (the result CSVs carry the measurement,
 * the target and AutoEq's equalization side by side): the target matched to
 * 0.005 dB, the correction to 0.005 dB, and the GraphicEQ text was identical
 * to the published profile. Those files are not ours to commit, so the tests
 * here use made-up curves and check the behaviour each step promises.
 */
class MatchEqSolverTest {

    private val flat = Synthetic.flat()

    @Test
    fun `the overall level of a measurement makes no difference`() {
        val m = Synthetic.headphone()
        val a = MatchEqSolver.solve(m, Synthetic.target())
        val b = MatchEqSolver.solve(Synthetic.add(m, Synthetic.constant(7.0)), Synthetic.target())
        for (i in a.outGain.indices) assertEquals(a.outGain[i], b.outGain[i], 1e-9)
    }

    @Test
    fun `a measurement that already matches gives a flat curve`() {
        val r = MatchEqSolver.solve(Synthetic.target(), Synthetic.target())
        r.correction.forEach { assertEquals(0.0, it, 1e-9) }
        r.outGain.forEach { assertEquals(-0.2, it, 1e-9) }
        assertEquals(0.2, r.volumeDrop, 1e-9)
    }

    @Test
    fun `a broad bump is cut by its own height, and only there`() {
        val m = Synthetic.add(Synthetic.target(), peaking(1000.0, 4.0, 0.7))
        val r = MatchEqSolver.solve(m, Synthetic.target(), MatchEqParams(levelMatch = LevelMatch.AT_1KHZ))
        // Centred at 1 kHz, so the bump's top is the reference: the correction
        // reads 0 there and +4 well away from it. Relative to the far bass:
        val relative = r.correction[at(1000.0)] - r.correction[at(30.0)]
        assertEquals(-4.0, relative, 0.3)
        assertEquals(r.correction[at(30.0)], r.correction[at(80.0)], 0.3)
    }

    @Test
    fun `boost never goes past the limit`() {
        val dip = Synthetic.add(flat, peaking(100.0, -15.0, 0.7))
        val six = MatchEqSolver.solve(dip, flat)
        assertTrue(six.correction.max() <= 6.05)
        assertEquals(6.0, six.correction.max(), 0.1)
        val three = MatchEqSolver.solve(dip, flat, MatchEqParams(maxBoost = 3.0))
        assertTrue(three.correction.max() <= 3.05)
        assertEquals(3.0, three.correction.max(), 0.1)
    }

    @Test
    fun `cut never goes past the limit`() {
        val peak = Synthetic.add(flat, peaking(3000.0, 20.0, 2.0))
        val r = MatchEqSolver.solve(peak, flat, MatchEqParams(maxCut = 8.0, levelMatch = LevelMatch.AT_1KHZ))
        // The final smoothing may round the corner a hair past the limit.
        assertTrue("min ${r.correction.min()}", r.correction.min() >= -8.1)
        val free = MatchEqSolver.solve(peak, flat, MatchEqParams(levelMatch = LevelMatch.AT_1KHZ))
        assertTrue(free.correction.min() < -12.0)
    }

    @Test
    fun `bass and treble limits fade the correction out`() {
        val m = Synthetic.curve(peaking(40.0, -5.0, 1.0), peaking(14000.0, 8.0, 0.7))
        val r = MatchEqSolver.solve(m, flat, MatchEqParams(
            correctFrom = 150.0, correctUpTo = 5000.0, levelMatch = LevelMatch.AT_1KHZ))
        assertEquals(0.0, r.correction[at(40.0)], 0.3)
        assertEquals(0.0, r.correction[at(14000.0)], 0.3)
        val open = MatchEqSolver.solve(m, flat, MatchEqParams(levelMatch = LevelMatch.AT_1KHZ))
        assertTrue("${open.correction[at(40.0)]}", open.correction[at(40.0)] > 3.0)
        assertTrue("${open.correction[at(14000.0)]}", open.correction[at(14000.0)] < -3.0)
    }

    @Test
    fun `treble correction at zero leaves the treble alone`() {
        val m = Synthetic.curve(peaking(500.0, 4.0, 1.0), peaking(12000.0, 8.0, 1.0))
        val full = MatchEqSolver.solve(m, flat, MatchEqParams(levelMatch = LevelMatch.AT_1KHZ))
        val none = MatchEqSolver.solve(m, flat, MatchEqParams(levelMatch = LevelMatch.AT_1KHZ, trebleStrength = 0.0))
        for (i in r(10000.0, 20000.0)) assertTrue("${Curves.grid[i]} Hz: ${none.correction[i]}", abs(none.correction[i]) < 0.5)
        for (i in r(20.0, 4000.0)) assertEquals(full.correction[i], none.correction[i], 1e-4)
        assertTrue("${full.correction[at(12000.0)]}", full.correction[at(12000.0)] < -3.0)
    }

    @Test
    fun `average level match zeroes the mean error, the 1 kHz mode does not`() {
        val m = Synthetic.add(Synthetic.headphone(), peaking(1000.0, -6.0, 4.0))
        val t = Synthetic.target()
        for (mode in LevelMatch.values()) {
            val r = MatchEqSolver.solve(m, t, MatchEqParams(levelMatch = mode))
            val err = DoubleArray(r.grid.size) { r.measurement[it] - r.target[it] }
            val mean = Curves.meanInBand(r.grid, err, 100.0, 10000.0)
            if (mode == LevelMatch.AVERAGE) assertEquals(0.0, mean, 1e-9)
            else assertTrue("mean $mean", abs(mean) > 0.3)
        }
    }

    @Test
    fun `target shelves and tilt are added to the target`() {
        val p = MatchEqParams(levelMatch = LevelMatch.AT_1KHZ)
        val bass = MatchEqSolver.solve(flat, flat, p.copy(bassBoost = 6.0))
        // 6 dB shelf at 105 Hz: nearly all of it at 20 Hz, next to none at 1 kHz.
        assertTrue("${bass.target[at(20.0)]}", bass.target[at(20.0)] in 5.0..6.0)
        assertEquals(0.0, bass.target[at(1000.0)], 0.1)
        val treble = MatchEqSolver.solve(flat, flat, p.copy(trebleBoost = -4.0))
        assertTrue("${treble.target[at(19000.0)]}", treble.target[at(19000.0)] in -4.5..-3.0)
        // tilt: +1 dB for every octave
        val tilt = MatchEqSolver.solve(flat, flat, p.copy(tilt = 1.0))
        assertEquals(1.0, tilt.target[at(4000.0)] - tilt.target[at(2000.0)], 0.05)
        assertEquals(-3.0, tilt.target[at(125.0)] - tilt.target[at(1000.0)], 0.05)
    }

    @Test
    fun `a narrow notch is not filled by a narrow spike`() {
        val m = Synthetic.add(flat, peaking(3000.0, -20.0, 8.0))
        val p = MatchEqParams(maxBoost = 30.0, levelMatch = LevelMatch.AT_1KHZ)
        val limited = MatchEqSolver.solve(m, flat, p)
        val raw = MatchEqSolver.solve(m, flat, p.copy(avoidNarrowBoosts = false))
        val k = at(3000.0)
        assertTrue("unlimited boost ${raw.correction[k]}", raw.correction[k] > 8.0)
        assertTrue("limited boost ${limited.correction[k]}", limited.correction[k] < raw.correction[k] - 3.0)
    }

    @Test
    fun `an estimated measurement reproduces the profile it came from`() {
        val t = Synthetic.target()
        val m = Synthetic.headphone()
        val bass = 4.0
        val original = MatchEqSolver.solve(m, t, MatchEqParams(bassBoost = bass))
        val profile = FrequencyCurve(original.outFreq, original.outGain)
        val est = MatchEqSolver.estimateMeasurement(profile, t, bass)
        val again = MatchEqSolver.solve(est, t, MatchEqParams(bassBoost = bass))
        var worst = 0.0
        for (i in original.outGain.indices) worst = maxOf(worst, abs(original.outGain[i] - again.outGain[i]))
        // Not exact: the profile was smoothed once already and is smoothed again.
        assertTrue("worst difference $worst dB", worst < 0.6)
    }

    private fun r(lo: Double, hi: Double) = Curves.grid.indices.filter { Curves.grid[it] in lo..hi }
}
