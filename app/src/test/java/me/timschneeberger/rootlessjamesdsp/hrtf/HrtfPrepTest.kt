package me.timschneeberger.rootlessjamesdsp.hrtf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class HrtfPrepTest {

    private val n = BinauralSpeakers.COUNT * 2

    /** Magnitude in dB of [h] at frequency [f]. */
    private fun magDb(h: FloatArray, from: Int, len: Int, f: Double, fs: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in 0 until len) {
            val a = -2 * PI * f * i / fs
            re += h[from + i] * cos(a)
            im += h[from + i] * sin(a)
        }
        return 10 * log10(re * re + im * im + 1e-30)
    }

    /** RBJ peaking biquad, run on an impulse. */
    private fun peakImpulse(fs: Double, f0: Double, db: Double, q: Double, len: Int, delay: Int): DoubleArray {
        val a = 10.0.pow(db / 40)
        val w0 = 2 * PI * f0 / fs
        val al = sin(w0) / (2 * q)
        val a0 = 1 + al / a
        val b0 = (1 + al * a) / a0
        val b1 = -2 * cos(w0) / a0
        val b2 = (1 - al * a) / a0
        val a1 = -2 * cos(w0) / a0
        val a2 = (1 - al / a) / a0
        val y = DoubleArray(len)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in 0 until len) {
            val x = if (i == delay) 1.0 else 0.0
            val v = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = v
            y[i] = v
        }
        return y
    }

    private fun powerSpectrum(h: DoubleArray, p: Int): FloatArray {
        val re = DoubleArray(p)
        val im = DoubleArray(p)
        for (i in 0 until minOf(p, h.size)) re[i] = h[i]
        Fft.forward(re, im)
        return FloatArray(p / 2 + 1) { (re[it] * re[it] + im[it] * im[it]).toFloat() }
    }

    private fun raw(fs: Float, taps: Int, hrir: FloatArray, diffuse: FloatArray = FloatArray(257) { 1f }) =
        RawHrtfSet(fs, taps, hrir, diffuse, FloatArray(n), 710, "Test", "Free", "Here")

    /** Every response one impulse; left ears at [l], right ears at [r]. */
    private fun impulses(taps: Int, l: IntArray, r: IntArray): FloatArray {
        val h = FloatArray(n * taps)
        for (s in 0 until BinauralSpeakers.COUNT) {
            h[(s * 2) * taps + l[s]] = 0.5f
            h[(s * 2 + 1) * taps + r[s]] = 0.5f
        }
        return h
    }

    @Test
    fun `minimum phase keeps the magnitude and puts the energy first`() {
        val k = 1025
        val mag = DoubleArray(k) { i ->
            val oct = ln((i + 1.0) / 100.0) / ln(2.0)
            10.0.pow((8.0 * exp(-oct * oct) - 3.0 * exp(-(oct - 3) * (oct - 3))) / 20)
        }
        val h = minPhase(mag)
        val re = h.copyOf()
        val im = DoubleArray(h.size)
        Fft.forward(re, im)
        var worst = 0.0
        for (i in 0 until k) {
            val got = 10 * log10(re[i] * re[i] + im[i] * im[i])
            worst = max(worst, abs(got - 20 * log10(mag[i])))
        }
        assertTrue("magnitude off by $worst dB", worst < 0.05)
        val total = h.sumOf { it * it }
        val early = h.take(h.size / 10).sumOf { it * it }
        assertTrue("only ${early / total} of the energy in the first tenth", early / total > 0.95)
    }

    private fun minPhase(mag: DoubleArray) = HrtfPrep.minimumPhase(mag)

    @Test
    fun `a flat average needs no correction`() {
        val e = HrtfPrep.equalisationDb(FloatArray(257) { 2f }, 44100.0)
        assertTrue(e.all { abs(it) < 1e-9 })
    }

    @Test
    fun `the correction is the inverse of the average, within its limits`() {
        val fs = 48000.0
        val k = 1025
        fun avg(db: Double) = FloatArray(k) { i ->
            val f = i * fs / 2048
            val oct = if (f > 0) ln(f / 3000) / ln(2.0) else -10.0
            10.0.pow(db * exp(-oct * oct * 2) / 10).toFloat()
        }
        val at3k = (3000 * 2048 / fs).toInt()
        val at300 = (300 * 2048 / fs).toInt()
        // Against the untouched part of the range, since 0 dB is the mid-range average.
        val e = HrtfPrep.equalisationDb(avg(6.0), fs)
        assertEquals(-6.0, e[at3k] - e[at300], 0.5)
        assertEquals(-20.0, HrtfPrep.equalisationDb(avg(40.0), fs)[at3k], 1e-9)
        assertEquals(10.0, HrtfPrep.equalisationDb(avg(-40.0), fs)[at3k], 1e-9)
    }

    @Test
    fun `the correction is held flat below 100 Hz and above 16 kHz`() {
        val fs = 48000.0
        val k = 1025
        // A measuring speaker that falls away in the bass and the top.
        val d = FloatArray(k) { i ->
            val f = max(i * fs / 2048, 1.0)
            val lo = (f / 200).pow(4) / (1 + (f / 200).pow(4))
            val hi = 1 / (1 + (f / 15000).pow(8))
            (lo * hi).toFloat()
        }
        val e = HrtfPrep.equalisationDb(d, fs)
        val i100 = (0 until k).first { it * fs / 2048 >= 100 }
        val i16k = (0 until k).last { it * fs / 2048 <= 16000 }
        for (i in 0 until i100) assertEquals(e[i100], e[i], 1e-12)
        for (i in i16k + 1 until k) assertEquals(e[i16k], e[i], 1e-12)
        assertTrue(e.all { it in -20.0..10.0 })
    }

    @Test
    fun `evening out a coloured set leaves the centre flat`() {
        val fs = 48000f
        val taps = 512
        // Every direction carries the same colour: what the measuring speaker
        // and the dummy head's ear canal leave in a real set.
        val colour = peakImpulse(fs.toDouble(), 2500.0, 9.0, 1.5, taps, 0)
        val h = FloatArray(n * taps)
        for (i in 0 until n) {
            val d = 30 + (i * 7) % 25
            for (t in 0 until taps - d) h[i * taps + d + t] = (0.3 * colour[t]).toFloat()
        }
        val diffuse = powerSpectrum(colour, 512)
        val flat = HrtfPrep.prepare(raw(fs, taps, h, diffuse), evenOut = true)
        val plain = HrtfPrep.prepare(raw(fs, taps, h, diffuse), evenOut = false)
        fun spread(set: PreparedHrtfSet): Double {
            val bands = generateSequence(200.0) { it * 2.0.pow(1.0 / 3) }.takeWhile { it <= 12000 }.toList()
            val db = bands.map { magDb(set.hrir, 0, set.taps, it, fs.toDouble()) }
            return db.max() - db.min()
        }
        assertTrue("evened out, the centre still spans ${spread(flat)} dB", spread(flat) < 2.0)
        assertTrue("left alone, the colour should show (${spread(plain)} dB)", spread(plain) > 6.0)
        assertTrue(flat.evenedOut)
    }

    @Test
    fun `lining up keeps the time differences between the ears`() {
        val taps = 512
        val l = intArrayOf(60, 52, 70, 48, 75, 46, 80, 50, 72)
        val r = intArrayOf(60, 70, 52, 75, 48, 80, 46, 72, 50)
        val set = HrtfPrep.prepare(raw(48000f, taps, impulses(taps, l, r)), evenOut = false)
        fun peak(s: Int, e: Int) = set.response(s, e).withIndex().maxBy { abs(it.value) }.index
        // The earliest of all (46) lands on the lead-in.
        assertEquals(HrtfPrep.LEAD, minOf((0 until 9).minOf { peak(it, 0) }, (0 until 9).minOf { peak(it, 1) }))
        for (s in 0 until BinauralSpeakers.COUNT)
            assertEquals(r[s] - l[s], peak(s, 1) - peak(s, 0))
        // The centre's peak is what the engine lines the plain signal up with.
        assertEquals(60 - 46 + HrtfPrep.LEAD, set.cOff)
    }

    @Test
    fun `cut to the same time at every rate`() {
        for ((fs, want) in listOf(44100f to 235, 48000f to 256, 96000f to 512)) {
            val taps = 1024
            val set = HrtfPrep.prepare(raw(fs, taps, impulses(taps, IntArray(9) { 20 }, IntArray(9) { 24 })), false)
            assertEquals(want, set.taps)
            assertEquals(n * want, set.hrir.size)
        }
    }

    @Test
    fun `the centre comes out as loud as it went in`() {
        val taps = 512
        val set = HrtfPrep.prepare(raw(48000f, taps, impulses(taps, IntArray(9) { 30 }, IntArray(9) { 30 })), false)
        // The centre speaker gets sqrt(2) times a mono input.
        val p = 2 * 0.5 * (HrtfPrep.pinkPower(set.hrir, 0, set.taps, 48000.0) +
            HrtfPrep.pinkPower(set.hrir, set.taps, set.taps, 48000.0))
        assertEquals(0.0, 10 * log10(p), 0.01)
        // An impulse stays an impulse: 1 / sqrt(2) at the peak.
        assertEquals(1 / sqrt(2.0), set.hrir[set.cOff].toDouble(), 1e-3)
    }

    @Test
    fun `sets that cannot be used are refused`() {
        val taps = 64
        val good = impulses(taps, IntArray(9) { 10 }, IntArray(9) { 10 })
        fun refused(r: RawHrtfSet) = try { HrtfPrep.prepare(r, false); false } catch (e: HrtfPrep.BadSetException) { true }
        assertTrue(refused(raw(48000f, taps, good.copyOf().also { it[5] = Float.NaN })))
        assertTrue(refused(raw(48000f, taps, good.copyOf(good.size - 1))))
        assertTrue(refused(raw(1000f, taps, good)))
        // A silent centre has nothing to line up or level with.
        assertTrue(refused(raw(48000f, taps, good.copyOf().also { for (i in 0 until 2 * taps) it[i] = 0f })))
        assertTrue(!refused(raw(48000f, taps, good)))
    }

    @Test
    fun `a prepared set survives its file`() {
        val taps = 256
        val set = HrtfPrep.prepare(raw(44100f, taps, impulses(taps, IntArray(9) { 12 + it }, IntArray(9) { 14 + it })), true)
        val bytes = ByteArrayOutputStream().also { HrtfSetFile.write(set, it) }.toByteArray()
        assertEquals(set, HrtfSetFile.read(ByteArrayInputStream(bytes)))
        // Anything short, padded or from something else is not trusted.
        assertNull(HrtfSetFile.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 3))))
        assertNull(HrtfSetFile.read(ByteArrayInputStream(bytes + byteArrayOf(1))))
        assertNull(HrtfSetFile.read(ByteArrayInputStream(bytes.copyOf().also { it[0] = 0 })))
        assertNull(HrtfSetFile.read(ByteArrayInputStream(ByteArray(0))))
        // A centre offset outside the set is caught.
        val bad = bytes.copyOf()
        bad[16] = 0x7f
        assertNull(HrtfSetFile.read(ByteArrayInputStream(bad)))
    }

    @Test
    fun `the FFT goes there and back`() {
        val re = DoubleArray(64) { sin(it * 0.3) + 0.2 * it }
        val im = DoubleArray(64)
        val keep = re.copyOf()
        Fft.forward(re, im)
        Fft.inverse(re, im)
        for (i in keep.indices) {
            assertEquals(keep[i], re[i], 1e-9)
            assertEquals(0.0, im[i], 1e-9)
        }
        try {
            Fft.forward(DoubleArray(12), DoubleArray(12))
            fail("a size that is not a power of two")
        } catch (_: IllegalArgumentException) {
        }
    }
}
