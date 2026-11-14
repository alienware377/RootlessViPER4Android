package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessCurveTest {

    private val freqs = LoudnessCurve.logSpaced(10.0, 20000.0, 200)

    @Test
    fun `at the reference volume both modes are exactly flat`() {
        for (mode in listOf(LoudnessCurve.MODE_CLASSIC, LoudnessCurve.MODE_ISO)) {
            val s = LoudnessCurve.Settings(mode = mode, refLevel = -20.0, refOffset = 4.0)
            LoudnessCurve.responseDb(s, -24.0, freqs).forEach { assertEquals(0.0, it, 0.0) }
        }
    }

    @Test
    fun `strength 0 is flat at any volume`() {
        val s = LoudnessCurve.Settings(strength = 0.0)
        LoudnessCurve.responseDb(s, -40.0, freqs).forEach { assertEquals(0.0, it, 0.0) }
    }

    // EqualizerAPO's LoudnessCorrectionFilter at its own settings, written out
    // from its source with the cookbook shelves evaluated as biquads.
    private fun shelfDb(high: Boolean, g: Double, f0: Double, s: Double, f: Double, fs: Double): Double {
        if (g == 0.0) return 0.0
        val a = 10.0.pow(g / 40.0)
        val w0 = 2 * PI * f0 / fs
        val cs = cos(w0)
        val al = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
        val be = 2 * sqrt(a) * al
        val c = if (!high) doubleArrayOf(
            a * ((a + 1) - (a - 1) * cs + be), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - be),
            (a + 1) + (a - 1) * cs + be, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - be)
        else doubleArrayOf(
            a * ((a + 1) + (a - 1) * cs + be), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - be),
            (a + 1) - (a - 1) * cs + be, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - be)
        val w = 2 * PI * f / fs
        fun mag2(b0: Double, b1: Double, b2: Double): Double {
            val re = b0 + b1 * cos(w) + b2 * cos(2 * w)
            val im = -b1 * sin(w) - b2 * sin(2 * w)
            return re * re + im * im
        }
        return 10 * log10(mag2(c[0], c[1], c[2]) / mag2(c[3], c[4], c[5]))
    }

    private fun equalizerApoDb(volDiff: Double, f: Double, fs: Double): Double {
        var gLs = 0.0
        var pre = 0.0
        if (volDiff > 0) { gLs = volDiff * 0.55 / (1 - 0.55); pre = -gLs }
        else if (volDiff < 0) gLs = volDiff * 0.55 * exp(volDiff / 90.0)
        val hv = volDiff - pre
        val gHs = if (hv > 0) hv * 0.225 * exp(-hv / 100.0) else if (hv < 0) hv * 0.175 * exp(hv / 80.0) else 0.0
        return pre + shelfDb(false, gLs, 75.0, 0.52, f, fs) + shelfDb(true, gHs, 10000.0, 0.9, f, fs)
    }

    @Test
    fun `classic is EqualizerAPO's two shelves, below and above the reference`() {
        for (fs in listOf(44100.0, 48000.0)) {
            for (volume in listOf(-30.0, -12.0)) {
                val got = LoudnessCurve.responseDb(LoudnessCurve.Settings(), volume, freqs, fs)
                for (i in freqs.indices) {
                    if (freqs[i] > fs * 0.45) continue
                    assertEquals("$volume dB at ${freqs[i]} Hz", equalizerApoDb(-volume, freqs[i], fs), got[i], 0.01)
                }
            }
            // Above the reference both shelves dip and nothing is cut overall.
            val above = LoudnessCurve.Settings(refLevel = -30.0)
            val got = LoudnessCurve.responseDb(above, 0.0, freqs, fs)
            for (i in freqs.indices) {
                if (freqs[i] > fs * 0.45) continue
                assertEquals(equalizerApoDb(-30.0, freqs[i], fs), got[i], 0.01)
            }
        }
    }

    @Test
    fun `classic lifts the bass and treble against the middle at -30 dB`() {
        val r = LoudnessCurve.responseDb(LoudnessCurve.Settings(), -30.0, doubleArrayOf(40.0, 1000.0, 12000.0))
        assertTrue("bass ${r[0] - r[1]}", r[0] - r[1] > 15.0)
        assertTrue("treble ${r[2] - r[1]}", r[2] - r[1] > 4.0)
    }

    @Test
    fun `ISO lands on the contour difference at every band centre`() {
        for (fs in listOf(44100.0, 48000.0)) {
            for ((phon, volume) in listOf(80.0 to -30.0, 60.0 to -15.0, 90.0 to -45.0)) {
                val s = LoudnessCurve.Settings(mode = LoudnessCurve.MODE_ISO, phon = phon)
                val r = LoudnessCurve.responseDb(s, volume, LoudnessCurve.isoFrequencies, fs)
                val at1k = r[17]
                for (i in r.indices) {
                    val want = LoudnessCurve.isoDifference(i, phon, phon + volume)
                    assertEquals("$phon phon, $volume dB, ${LoudnessCurve.isoFrequencies[i]} Hz",
                        want, r[i] - at1k, 0.1)
                }
            }
        }
    }

    @Test
    fun `the ISO correction is smooth between the band centres`() {
        val s = LoudnessCurve.Settings(mode = LoudnessCurve.MODE_ISO)
        val f = LoudnessCurve.isoFrequencies
        val mids = DoubleArray(f.size - 2) { sqrt(f[it + 1] * f[it + 2]) }
        val r = LoudnessCurve.responseDb(s, -30.0, mids + 1000.0)
        for (i in mids.indices) {
            val line = 0.5 * (LoudnessCurve.isoDifference(i + 1, 80.0, 50.0) + LoudnessCurve.isoDifference(i + 2, 80.0, 50.0))
            assertEquals("${mids[i]} Hz", line, r[i] - r.last(), 0.3)
        }
    }

    // ISO 226:2003, Table 1 and its formula. The 2023 edition changed the
    // contours by well under a decibel, so the two must agree that closely: a
    // slip in one of the 87 table entries above would move a contour by more.
    private val af2003 = doubleArrayOf(0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330,
        0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242,
        0.242, 0.245, 0.254, 0.271, 0.301)
    private val lu2003 = doubleArrayOf(-31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
        -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2,
        -10.7, -3.1)
    private val tf2003 = doubleArrayOf(78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4,
        11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3)

    private fun spl2003(i: Int, phon: Double): Double {
        val a = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) +
            (0.4 * 10.0.pow((tf2003[i] + lu2003[i]) / 10.0 - 9.0)).pow(af2003[i])
        return 10.0 / af2003[i] * log10(a) - lu2003[i] + 94.0
    }

    @Test
    fun `the 2023 contours stay within half a decibel of the 2003 ones`() {
        var worst = 0.0
        for (phon in 20..90 step 5)
            for (i in 0 until 29)
                worst = maxOf(worst, abs(LoudnessCurve.isoSpl(i, phon.toDouble()) - spl2003(i, phon.toDouble())))
        assertTrue("worst $worst dB", worst < 0.5)
    }

    @Test
    fun `at 1 kHz a phon is a decibel`() {
        for (phon in 20..90 step 10)
            assertEquals(phon.toDouble(), LoudnessCurve.isoSpl(17, phon.toDouble()), 1e-9)
    }

    @Test
    fun `nothing is ever lifted above 0 dB`() {
        val cases = listOf(
            LoudnessCurve.Settings(),
            LoudnessCurve.Settings(mode = LoudnessCurve.MODE_ISO, strength = 200.0, phon = 90.0),
            LoudnessCurve.Settings(bassAmount = 0.0, trebleAmount = 100.0),
            LoudnessCurve.Settings(subsonic = true, subsonicQ = 2.0, subsonicOrder = 8),
            LoudnessCurve.Settings(bassSlope = 0.05, trebleSlope = 0.05, strength = 200.0, bassAmount = 90.0),
            LoudnessCurve.Settings(refLevel = Double.NaN, strength = Double.POSITIVE_INFINITY),
        )
        for (s in cases)
            for (volume in listOf(-100.0, -45.0, -10.0, 0.0))
                LoudnessCurve.responseDb(s, volume, freqs).forEach {
                    assertTrue("$s at $volume dB: $it", it.isFinite() && it < 0.1)
                }
    }

    @Test
    fun `the subsonic filter is two Butterworth halves at order 4`() {
        val s = LoudnessCurve.Settings(subsonic = true, subsonicHz = 20.0, subsonicOrder = 4, subsonicQ = 0.7071)
        val r = LoudnessCurve.responseDb(s, 0.0, doubleArrayOf(20.0, 200.0))
        assertEquals(-6.02, r[0], 0.05)
        assertEquals(0.0, r[1], 0.02)
    }
}
