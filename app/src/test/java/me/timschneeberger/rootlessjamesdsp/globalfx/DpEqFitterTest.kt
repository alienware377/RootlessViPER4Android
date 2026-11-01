package me.timschneeberger.rootlessjamesdsp.globalfx

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.cos

/**
 * The fitter, replayed through a model of AOSP's DynamicsProcessing bins:
 * a frequency f lands in bin int(0.5 + f N / fs), and a bin gets the gain of
 * the band whose range contains it (DPFrequency.cpp, computeBinStartStop).
 *
 * Numbers are worst errors in dB. "Fit" compares the bands with the target at
 * every bin; "third octave" compares what a listener gets at the standard
 * 1/3-octave centres with the smooth curve the cards describe, so it includes
 * the bin grid's own coarseness, which no fitter can remove.
 */
class DpEqFitterTest {

    private fun peqCurve(fs: Int, n: Int): DoubleArray {
        val bands = ParametricEqBandList().apply {
            add(ParametricEqBand(105.0, 6.0, 0.71, ParametricEqFilterType.LOW_SHELF))
            add(ParametricEqBand(2500.0, -4.0, 2.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(5500.0, 5.0, 3.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(8200.0, -6.0, 4.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(11000.0, 3.0, 2.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(10000.0, -3.0, 0.71, ParametricEqFilterType.HIGH_SHELF))
        }
        val inputs = EqInputs(peqEnabled = true, peqBands = bands.serialize())
        return EqTargetBuilder.build(inputs, DpEqFitter.binFrequencies(n, fs), fs.toDouble()).left
    }

    /** A smooth, AutoEq-shaped headphone correction written here, not downloaded. */
    private fun autoEqLikeString(): String {
        val sb = StringBuilder("GraphicEQ: ")
        for (k in 0 until 127) {
            val f = 20.0 * 1.0563.pow(k)
            val x = kotlin.math.ln(f / 20.0) / kotlin.math.ln(1000.0)  // 0 at 20 Hz, 1 at 20 kHz
            val g = 5.0 * (1 - x).pow(6) + 2.5 * sin(9.0 * x) - 3.0 * x.pow(4) + 1.5 * cos(23.0 * x) * x * x
            sb.append("%.1f %.1f; ".format(java.util.Locale.ROOT, f, g))
        }
        return sb.toString()
    }

    private fun autoEqCurve(fs: Int, n: Int): DoubleArray =
        EqTargetBuilder.build(EqInputs(geqEnabled = true, geqNodes = autoEqLikeString()),
            DpEqFitter.binFrequencies(n, fs), fs.toDouble()).left

    private fun continuous(inputs: EqInputs, f: Double, fs: Int) =
        EqTargetBuilder.build(inputs, doubleArrayOf(f), fs.toDouble()).left[0]

    private val thirdOctaves = doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0,
        4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0)

    /** Worst error at the 1/3-octave centres, below and from 100 Hz. */
    private fun thirdOctaveErrors(fit: DpEqFit, inputs: EqInputs, n: Int, fs: Int): Pair<Double, Double> {
        val realised = DpEqFitter.realisedGains(fit.bands, n, fs)
        var low = 0.0
        var high = 0.0
        for (f in thirdOctaves) {
            val bin = (0.5 + f * n / fs).toInt().coerceAtMost(n / 2)
            val e = abs(realised[bin] - continuous(inputs, f, fs))
            if (f < 100.0) low = maxOf(low, e) else high = maxOf(high, e)
        }
        return low to high
    }

    private fun peqInputs(): EqInputs {
        val bands = ParametricEqBandList().apply {
            add(ParametricEqBand(105.0, 6.0, 0.71, ParametricEqFilterType.LOW_SHELF))
            add(ParametricEqBand(2500.0, -4.0, 2.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(5500.0, 5.0, 3.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(8200.0, -6.0, 4.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(11000.0, 3.0, 2.0, ParametricEqFilterType.PEAKING))
            add(ParametricEqBand(10000.0, -3.0, 0.71, ParametricEqFilterType.HIGH_SHELF))
        }
        return EqInputs(peqEnabled = true, peqBands = bands.serialize())
    }

    /** Count of bands whose cutoff does not land on its intended last bin. */
    private fun roundTripMisses(fs: Int, n: Int, cutoff: (Int) -> Float): Int {
        val fit = DpEqFitter.fit(autoEqCurve(fs, n), n, fs)
        var misses = 0
        for (i in 0 until fit.bands.size - 1) {
            if (DpEqFitter.aospBinStop(cutoff(fit.endBins[i]), n, fs) != fit.endBins[i]) misses++
        }
        return misses
    }

    @Test
    fun `block sizes follow AOSP's rounding`() {
        assertEquals(512, DpEqFitter.blockSize(10f, 48000))
        assertEquals(1024, DpEqFitter.blockSize(20f, 48000))
        assertEquals(2048, DpEqFitter.blockSize(40f, 48000))
        assertEquals(2048, DpEqFitter.blockSize(40f, 44100))
        assertEquals(4096, DpEqFitter.blockSize(80f, 48000))
        assertEquals(4096, DpEqFitter.blockSize(40f, 96000))
        // An exact power of two stays as it is, as in configureVariant.
        assertEquals(2048, DpEqFitter.blockSize(2048f / 48f, 48000))
        assertEquals(16384, DpEqFitter.blockSize(1000f, 192000))
    }

    @Test
    fun `a flat curve covers every bin exactly once with 128 bands`() {
        for ((fs, n) in listOf(48000 to 2048, 44100 to 2048, 96000 to 4096)) {
            val fit = DpEqFitter.fit(DoubleArray(n / 2 + 1), n, fs)
            assertEquals(128, fit.bands.size)
            assertTrue(fit.bands.all { it.gainDb == 0f })
            assertEquals(DpEqFitter.TOP_CUTOFF_HZ, fit.bands.last().cutoffHz)
            for (i in 1 until fit.bands.size)
                assertTrue("cutoffs must rise", fit.bands[i].cutoffHz > fit.bands[i - 1].cutoffHz)
            // Replay AOSP's start/stop walk and count how often each bin is written.
            val hits = IntArray(n / 2 + 1)
            var start = 0
            for (b in fit.bands) {
                val stop = DpEqFitter.aospBinStop(b.cutoffHz, n, fs)
                for (k in start..minOf(stop, n / 2)) hits[k]++
                start = stop + 1
            }
            assertTrue("every bin written exactly once at $fs/$n", hits.all { it == 1 })
        }
    }

    @Test
    fun `every cutoff lands on its own last bin at every rate and size`() {
        for (fs in intArrayOf(44100, 48000, 96000)) for (n in intArrayOf(1024, 2048, 4096)) {
            assertEquals("fs=$fs n=$n", 0,
                roundTripMisses(fs, n) { DpEqFitter.cutoffForEndBin(it, n, fs) })
        }
    }

    @Test
    fun `the round trip check catches a cutoff placed off its bin`() {
        // Deliberately broken placements. If these passed, the check above
        // could not see the fault it is there for.
        val fs = 48000
        val n = 2048
        assertTrue(roundTripMisses(fs, n) { ((it - 0.6) * fs / n).toFloat() } > 0)
        assertTrue(roundTripMisses(fs, n) { ((it + 0.6) * fs / n).toFloat() } > 0)
    }

    @Test
    fun `a parametric curve fits within a quarter dB at 40 ms`() {
        val fs = 48000
        val n = DpEqFitter.blockSize(40f, fs)
        val fit = DpEqFitter.fit(peqCurve(fs, n), n, fs)
        assertTrue("fit ${fit.maxErrorDb}", fit.maxErrorDb <= 0.25)
        val (low, high) = thirdOctaveErrors(fit, peqInputs(), n, fs)
        println("PEQ 48k N=$n fit=${"%.3f".format(fit.maxErrorDb)} third-octave <100Hz=${"%.3f".format(low)} >=100Hz=${"%.3f".format(high)}")
        assertTrue("below 100 Hz $low", low <= 1.0)
        assertTrue("from 100 Hz $high", high <= 0.5)
    }

    @Test
    fun `an AutoEq style curve fits within the same bounds`() {
        for (fs in intArrayOf(44100, 48000)) {
            val n = DpEqFitter.blockSize(40f, fs)
            val inputs = EqInputs(geqEnabled = true, geqNodes = autoEqLikeString())
            val fit = DpEqFitter.fit(autoEqCurve(fs, n), n, fs)
            val (low, high) = thirdOctaveErrors(fit, inputs, n, fs)
            println("AutoEq-like $fs N=$n fit=${"%.3f".format(fit.maxErrorDb)} third-octave <100Hz=${"%.3f".format(low)} >=100Hz=${"%.3f".format(high)}")
            assertTrue("fit ${fit.maxErrorDb}", fit.maxErrorDb <= 0.25)
            assertTrue("below 100 Hz $low", low <= 1.0)
            assertTrue("from 100 Hz $high", high <= 0.5)
        }
    }

    @Test
    fun `the 10 ms Android default is measurably worse in the bass`() {
        // Why global mode asks for a 40 ms frame instead of taking the default.
        val fs = 48000
        val n10 = DpEqFitter.blockSize(10f, fs)
        val n40 = DpEqFitter.blockSize(40f, fs)
        val e10 = thirdOctaveErrors(DpEqFitter.fit(peqCurve(fs, n10), n10, fs), peqInputs(), n10, fs).first
        val e40 = thirdOctaveErrors(DpEqFitter.fit(peqCurve(fs, n40), n40, fs), peqInputs(), n40, fs).first
        println("bass error 10 ms ${"%.3f".format(e10)} dB, 40 ms ${"%.3f".format(e40)} dB")
        assertTrue(e10 > 2 * e40)
    }

    @Test
    fun `fewer bands stay within their own bounds`() {
        val fs = 48000
        val n = 2048
        val target = peqCurve(fs, n)
        for ((bands, bound) in listOf(16 to 1.5, 32 to 0.8, 64 to 0.4)) {
            val fit = DpEqFitter.fit(target, n, fs, bands)
            assertTrue(fit.bands.size <= bands)
            println("$bands bands: fit ${"%.3f".format(fit.maxErrorDb)}")
            assertTrue("$bands bands: ${fit.maxErrorDb}", fit.maxErrorDb <= bound)
        }
    }

    @Test
    fun `the fit error it reports is the real one`() {
        val fs = 48000
        val n = 2048
        val target = autoEqCurve(fs, n)
        val fit = DpEqFitter.fit(target, n, fs)
        val realised = DpEqFitter.realisedGains(fit.bands, n, fs)
        var worst = 0.0
        for (k in target.indices) worst = maxOf(worst, abs(realised[k] - target[k]))
        assertEquals(fit.maxErrorDb, worst, 1e-5)
    }

    @Test
    fun `broken input still gives finite bands`() {
        val fs = 48000
        val n = 2048
        val freqs = DpEqFitter.binFrequencies(n, fs)
        for (geq in listOf("", "GraphicEQ: ", "GraphicEQ: nonsense", "GraphicEQ: 100 NaN; 200 3",
                "GraphicEQ: 1000 4", "GraphicEQ: 100 1e400; 200 -1e400", "garbage;;;")) {
            val t = EqTargetBuilder.build(EqInputs(geqEnabled = true, geqNodes = geq,
                eqEnabled = true, eqBands = "1;2;3", peqEnabled = true, peqBands = "PEQ: x y z"), freqs, fs.toDouble())
            val fit = DpEqFitter.fit(t.left, n, fs)
            assertTrue(geq, fit.bands.all { it.gainDb.isFinite() && it.cutoffHz.isFinite() })
            assertTrue(geq, fit.maxErrorDb.isFinite())
        }
        // NaN straight into the fitter
        val nan = DoubleArray(n / 2 + 1) { if (it % 7 == 0) Double.NaN else 1.0 }
        assertTrue(DpEqFitter.fit(nan, n, fs).bands.all { it.gainDb.isFinite() })
    }
}
