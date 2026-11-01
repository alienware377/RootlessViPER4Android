package me.timschneeberger.rootlessjamesdsp.hrtf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns what the reader took out of a .sofa file into what the engine plays.
 * Pure maths on arrays, so it is tested on the JVM.
 *
 *  1. Diffuse field equalisation (optional, on by default). A measured set
 *     carries the colour of the measuring loudspeaker and of the dummy head's
 *     ear canal in every direction. The power average over all directions,
 *     each weighted by the cosine of its elevation so the crowded poles do not
 *     count extra, holds exactly that common colour, and its inverse takes it
 *     out (the method of the MIT KEMAR FAQ for its own diffuse set; Larcher,
 *     Jot and Vandernoot, AES 105th Convention, 1998). The average is smoothed
 *     to a third of an octave, the correction is held flat below 100 Hz and
 *     above 16 kHz and limited to +10 / -20 dB, and it is made minimum phase
 *     with the real cepstrum (Oppenheim and Schafer, "Discrete-Time Signal
 *     Processing", homomorphic processing), so it adds no delay of note.
 *  2. Lining up: the earliest onset of the eighteen responses (first sample
 *     within 20 dB of its own peak) is moved to sample 8. Every one moves by
 *     the same amount, so the time differences between ears are kept.
 *  3. Cut to 256 taps at 48 kHz (5.3 ms; the same time at other rates), the
 *     last 32 faded out.
 *  4. One gain for all nine pairs, so pink noise in the centre comes out of
 *     each ear as loud as it went in: switching on does not change loudness.
 */
object HrtfPrep {
    const val LEAD = 8
    private const val TAPS_AT_48K = 256
    private const val FADE_AT_48K = 32
    private const val MAX_BOOST_DB = 10.0
    private const val MAX_CUT_DB = -20.0
    private const val FLAT_BELOW_HZ = 100.0
    private const val FLAT_ABOVE_HZ = 16000.0
    private const val REF_LO_HZ = 200.0
    private const val REF_HI_HZ = 8000.0
    /** The engine refuses values this big; a set this hot after levelling is broken. */
    private const val MAX_VALUE = 50f

    class BadSetException(message: String) : Exception(message)

    fun prepare(raw: RawHrtfSet, evenOut: Boolean): PreparedHrtfSet {
        val n = BinauralSpeakers.COUNT * 2
        if (raw.taps < 16 || raw.hrir.size != n * raw.taps)
            throw BadSetException("wrong size: ${raw.hrir.size} values for ${raw.taps} taps")
        if (!(raw.fs >= 8000f && raw.fs <= 384000f))
            throw BadSetException("sample rate out of range: ${raw.fs}")
        if (raw.hrir.any { !it.isFinite() })
            throw BadSetException("not a number in the responses")

        var responses = Array(n) { i ->
            DoubleArray(raw.taps) { raw.hrir[i * raw.taps + it].toDouble() }
        }
        if (evenOut && raw.diffuse.size >= 3 && raw.diffuse.all { it.isFinite() && it >= 0f }) {
            val eq = diffuseFieldFilter(raw.diffuse, raw.fs.toDouble())
            responses = Array(n) { convolve(responses[it], eq, raw.taps + eq.size / 2) }
        }

        // Line up on the earliest onset.
        val onsets = responses.map { onset(it) }
        val d0 = max(0, onsets.min() - LEAD)
        val taps = max(16, (TAPS_AT_48K * raw.fs / 48000.0).roundToInt())
        val fade = max(4, (FADE_AT_48K * raw.fs / 48000.0).roundToInt())
        val out = FloatArray(n * taps)
        for (i in 0 until n) {
            val src = responses[i]
            for (t in 0 until taps) {
                val j = t + d0
                var v = if (j < src.size) src[j] else 0.0
                if (t >= taps - fade) v *= 0.5 + 0.5 * cos(PI * (t - (taps - fade) + 1) / (fade + 1))
                out[i * taps + t] = v.toFloat()
            }
        }

        // The centre pair's peak, which the engine lines the plain signal up with.
        var cOff = 0
        var peak = -1f
        for (t in 0 until taps) {
            val v = abs(out[t])
            if (v > peak) { peak = v; cOff = t }
        }
        if (peak <= 0f) throw BadSetException("the centre response is silent")

        // Level: the centre speaker is fed sqrt(2) times a mono signal, so
        // 2 |H|^2, averaged as pink noise, should come out as 1.
        val p = 0.5 * (pinkPower(out, 0, taps, raw.fs.toDouble()) +
            pinkPower(out, taps, taps, raw.fs.toDouble()))
        if (!(p > 1e-12)) throw BadSetException("the centre response is silent")
        val g = (1.0 / sqrt(2.0 * p)).toFloat()
        for (i in out.indices) out[i] *= g
        if (out.any { !it.isFinite() || abs(it) >= MAX_VALUE })
            throw BadSetException("the responses are far too loud after levelling")

        return PreparedHrtfSet(raw.fs, taps, cOff, out, raw.dirs.copyOf(), evenOut,
            raw.title, raw.license, raw.database)
    }

    /** First sample within 20 dB of the response's own peak. */
    fun onset(x: DoubleArray): Int {
        var peak = 0.0
        for (v in x) peak = max(peak, abs(v))
        if (peak <= 0.0) return x.size
        val thr = 0.1 * peak
        for (i in x.indices) if (abs(x[i]) >= thr) return i
        return x.size
    }

    /** The correction in dB at bins 0 .. K - 1 of the diffuse average. */
    fun equalisationDb(diffuse: FloatArray, fs: Double): DoubleArray {
        val k = diffuse.size
        val nfft = 2 * (k - 1)
        val hz = DoubleArray(k) { it * fs / nfft }
        // A third of an octave either side, in power.
        val q = 2.0.pow(1.0 / 6.0)
        val sm = DoubleArray(k) { i ->
            if (i == 0) return@DoubleArray diffuse[0].toDouble().coerceAtLeast(diffuse.getOrElse(1) { 0f }.toDouble())
            val lo = max(1, (i / q).roundToInt())
            val hi = min(k - 1, max(i, (i * q).roundToInt()))
            var s = 0.0
            for (j in lo..hi) s += diffuse[j]
            s / (hi - lo + 1)
        }
        val floor = (sm.maxOrNull() ?: 1.0) * 1e-12 + 1e-30
        val e = DoubleArray(k) { -10.0 * log10(max(sm[it], floor)) }
        // Held flat outside the range the average can be trusted in.
        val top = min(FLAT_ABOVE_HZ, 0.45 * fs)
        val iLo = hz.indexOfFirst { it >= FLAT_BELOW_HZ }.coerceAtLeast(1)
        val iHi = hz.indexOfLast { it <= top }.coerceAtLeast(iLo)
        for (i in 0 until iLo) e[i] = e[iLo]
        for (i in iHi + 1 until k) e[i] = e[iHi]
        // 0 dB is the mid range, evenly weighted per octave (1 / f).
        var num = 0.0
        var den = 0.0
        for (i in 1 until k) if (hz[i] in REF_LO_HZ..REF_HI_HZ) { num += e[i] / i; den += 1.0 / i }
        val ref = if (den > 0) num / den else 0.0
        for (i in 0 until k) e[i] = (e[i] - ref).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        return e
    }

    /** The diffuse field correction as a minimum phase FIR. */
    fun diffuseFieldFilter(diffuse: FloatArray, fs: Double): DoubleArray {
        val db = equalisationDb(diffuse, fs)
        // Worked out on a finer grid than the average's own, so the cepstrum
        // has room and does not alias.
        val k = db.size
        val nc = max(2048, Integer.highestOneBit((k - 1) * 2) * 4)
        val kc = nc / 2 + 1
        val mag = DoubleArray(kc) { i ->
            val pos = i.toDouble() * (k - 1) / (kc - 1)
            val a = pos.toInt().coerceAtMost(k - 2)
            val f = pos - a
            10.0.pow((db[a] * (1 - f) + db[a + 1] * f) / 20.0)
        }
        val h = minimumPhase(mag)
        // Most of a minimum phase response is at its start; keep the first
        // quarter and fade its end.
        val len = nc / 4
        val fade = len / 8
        return DoubleArray(len) { i ->
            val w = if (i >= len - fade) 0.5 + 0.5 * cos(PI * (i - (len - fade) + 1) / (fade + 1)) else 1.0
            h[i] * w
        }
    }

    /**
     * Minimum phase impulse response with magnitude [mag] at bins 0 .. N / 2
     * of an N-point FFT, by folding the real cepstrum. Returns N samples.
     */
    fun minimumPhase(mag: DoubleArray): DoubleArray {
        val n = 2 * (mag.size - 1)
        require(n >= 4 && n and (n - 1) == 0) { "size must be a power of two plus one" }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val m = mag[if (i <= n / 2) i else n - i]
            re[i] = ln(max(m, 1e-12))
        }
        Fft.inverse(re, im)
        // Fold: keep c[0] and c[N/2], double the causal half, drop the rest.
        for (i in 1 until n / 2) { re[i] *= 2.0; im[i] = 0.0 }
        for (i in n / 2 + 1 until n) { re[i] = 0.0; im[i] = 0.0 }
        im[0] = 0.0; im[n / 2] = 0.0
        Fft.forward(re, im)
        for (i in 0 until n) {
            val a = exp(re[i])
            val b = im[i]
            re[i] = a * cos(b)
            im[i] = a * sin(b)
        }
        Fft.inverse(re, im)
        return re
    }

    /** Linear convolution, kept to [len] samples. */
    fun convolve(x: DoubleArray, h: DoubleArray, len: Int): DoubleArray {
        val y = DoubleArray(len)
        for (i in x.indices) {
            val xi = x[i]
            if (xi == 0.0) continue
            val end = min(h.size, len - i)
            for (j in 0 until end) y[i + j] += xi * h[j]
        }
        return y
    }

    /** Mean power of a response as pink noise hears it, 100 Hz to 16 kHz. */
    fun pinkPower(src: FloatArray, from: Int, taps: Int, fs: Double): Double {
        var n = 256
        while (n < 4 * taps) n *= 2
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until taps) re[i] = src[from + i].toDouble()
        Fft.forward(re, im)
        var num = 0.0
        var den = 0.0
        val top = min(16000.0, 0.45 * fs)
        for (k in 1..n / 2) {
            val f = k * fs / n
            if (f < 100.0 || f > top) continue
            val w = 1.0 / f
            num += w * (re[k] * re[k] + im[k] * im[k])
            den += w
        }
        return if (den > 0) num / den else 0.0
    }
}

/** In-place radix-2 complex FFT (Cooley and Tukey); size a power of two. */
object Fft {
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, -1.0)

    /** Inverse, scaled by 1 / N. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, 1.0)
        val s = 1.0 / re.size
        for (i in re.indices) { re[i] *= s; im[i] *= s }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, sign: Double) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val t = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}
