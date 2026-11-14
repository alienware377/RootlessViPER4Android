package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The loudness correction's frequency response, for the graph on its card.
 *
 * Works the design out the way the engine does (Effects/loudnessCorrection.c)
 * and evaluates it exactly, so the curve on screen is the one that is heard
 * rather than a sketch of it. The two must change together.
 */
object LoudnessCurve {
    const val MODE_CLASSIC = 0
    const val MODE_ISO = 1

    data class Settings(
        val mode: Int = MODE_CLASSIC,
        val refLevel: Double = 0.0,
        val refOffset: Double = 0.0,
        val strength: Double = 100.0,
        val bassHz: Double = 75.0,
        val bassSlope: Double = 0.52,
        val bassAmount: Double = 55.0,
        val trebleHz: Double = 10000.0,
        val trebleSlope: Double = 0.9,
        val trebleAmount: Double = 22.5,
        val phon: Double = 80.0,
        val subsonic: Boolean = false,
        val subsonicHz: Double = 20.0,
        val subsonicOrder: Int = 4,
        val subsonicQ: Double = 0.707,
    ) {
        /** Held to the card's ranges, as the engine holds them. */
        fun held() = copy(
            mode = if (mode == MODE_ISO) MODE_ISO else MODE_CLASSIC,
            refLevel = hold(refLevel, -60.0, 0.0),
            refOffset = hold(refOffset, -30.0, 30.0),
            strength = hold(strength, 0.0, 200.0),
            bassHz = hold(bassHz, 20.0, 500.0),
            bassSlope = hold(bassSlope, 0.05, 1.0),
            bassAmount = hold(bassAmount, 0.0, 90.0),
            trebleHz = hold(trebleHz, 2000.0, 16000.0),
            trebleSlope = hold(trebleSlope, 0.05, 1.0),
            trebleAmount = hold(trebleAmount, 0.0, 100.0),
            phon = hold(phon, 40.0, 90.0),
            subsonicHz = hold(subsonicHz, 10.0, 100.0),
            subsonicOrder = subsonicOrder.coerceIn(1, 8),
            subsonicQ = hold(subsonicQ, 0.1, 2.0),
        )
    }

    /** ISO 226:2023, Table 1: the preferred third-octave frequencies. */
    val isoFrequencies = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
    )
    // The exponent for loudness perception, the magnitude of the linear
    // transfer function normalised at 1 kHz, and the threshold of hearing.
    private val isoAf = doubleArrayOf(
        0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412, 0.391,
        0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292,
        0.290, 0.290, 0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354,
    )
    private val isoLu = doubleArrayOf(
        -31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3, -4.6,
        -3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2,
        -1.2, 1.4, 2.3, 1.0, -2.3, -7.2, -11.2, -10.9, -3.5,
    )
    private val isoTf = doubleArrayOf(
        78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
        14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
        -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
    )

    private const val BANDS = 29
    private const val MAX_DB = 40.0
    private const val ZERO_DB = 1e-4
    private const val ISO_Q = 2.0
    private const val FIT_PASSES = 8
    private const val PEAK_POINTS = 294

    /**
     * ISO 226:2023 formula (1): the level in dB SPL of a tone at band [i] that
     * sounds as loud as [phon] phon.
     */
    fun isoSpl(i: Int, phon: Double): Double {
        val af = isoAf[i]
        val inner = 4e-10.pow(0.3 - af) * (10.0.pow(0.03 * phon) - 10.0.pow(0.072)) +
            10.0.pow(af * (isoTf[i] + isoLu[i]) / 10.0)
        return 10.0 / af * log10(inner) - isoLu[i]
    }

    /** How much more than 1 kHz band [i] needs at [curPhon] to sound as it did at [refPhon]. */
    fun isoDifference(i: Int, refPhon: Double, curPhon: Double): Double =
        isoSpl(i, curPhon) - isoSpl(i, refPhon) - (curPhon - refPhon)

    /**
     * The response in dB at each of [freqs], with the media volume at
     * [volumeDb] and the engine at [fs].
     */
    fun responseDb(settings: Settings, volumeDb: Double, freqs: DoubleArray, fs: Double = 48000.0): DoubleArray {
        val d = design(settings.held(), volumeDb.coerceIn(-100.0, 0.0), fs)
        return DoubleArray(freqs.size) { d.responseDb(freqs[it]) }
    }

    /** Frequencies spread evenly on a log axis, for drawing. */
    fun logSpaced(from: Double, to: Double, count: Int): DoubleArray =
        DoubleArray(count) { from * (to / from).pow(it.toDouble() / (count - 1)) }

    // ---- the design, as in the engine ----------------------------------------

    /** A state-variable section: g, k and the mix m0, m1, m2. Flat is m0 = 1, m1 = m2 = 0. */
    private class Section(val g: Double, val k: Double, val m0: Double, val m1: Double, val m2: Double) {
        val flat get() = m0 == 1.0 && m1 == 0.0 && m2 == 0.0

        fun mag2(cw: Double, c2w: Double): Double {
            val g2 = g * g
            val mid = (m0 * k + m1) * g
            val low = (m0 + m2) * g2
            val b0 = m0 + mid + low
            val b1 = 2.0 * (low - m0)
            val b2 = m0 - mid + low
            val a0 = 1.0 + k * g + g2
            val a1 = 2.0 * (g2 - 1.0)
            val a2 = 1.0 - k * g + g2
            val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cw + 2.0 * b0 * b2 * c2w
            val den = a0 * a0 + a1 * a1 + a2 * a2 + 2.0 * (a0 * a1 + a1 * a2) * cw + 2.0 * a0 * a2 * c2w
            return if (den > 1e-30) num / den else 1.0
        }
    }

    private class Design(val sections: List<Section>, val firstOrderG: Double?, val gainDb: Double, val fs: Double) {
        fun responseDb(freq: Double): Double = gainDb + sectionsDb(sections, firstOrderG, freq, fs)
    }

    private fun hold(v: Double, lo: Double, hi: Double) = if (v.isNaN()) lo else v.coerceIn(lo, hi)
    private fun clampDb(g: Double) = g.coerceIn(-MAX_DB, MAX_DB)
    private fun prewarp(freq: Double, fs: Double) = tan(PI * freq / fs)

    private fun shelf(high: Boolean, gainDb: Double, freq: Double, slope: Double, fs: Double): Section {
        val a = 10.0.pow(gainDb / 40.0)
        val k = sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        return if (!high)
            Section(prewarp(freq, fs) / sqrt(a), k, 1.0, k * (a - 1.0), a * a - 1.0)
        else
            Section(prewarp(freq, fs) * sqrt(a), k, a * a, k * (1.0 - a) * a, 1.0 - a * a)
    }

    private fun peak(gainDb: Double, freq: Double, fs: Double): Section {
        val a = 10.0.pow(gainDb / 40.0)
        val k = 1.0 / (ISO_Q * a)
        return Section(prewarp(freq, fs), k, 1.0, k * (a * a - 1.0), 0.0)
    }

    private fun sectionsDb(sections: List<Section>, firstOrderG: Double?, freq: Double, fs: Double): Double {
        val w = 2.0 * PI * freq / fs
        val cw = cos(w)
        val c2w = cos(2.0 * w)
        var m = 1.0
        for (s in sections) if (!s.flat) m *= s.mag2(cw, c2w)
        var db = 10.0 * log10(m.coerceAtLeast(1e-30))
        if (firstOrderG != null) {
            val t = prewarp(freq, fs) / firstOrderG
            db += 10.0 * log10(t * t / (1.0 + t * t))
        }
        return db
    }

    private fun design(s: Settings, volumeDb: Double, fs: Double): Design {
        val sections = ArrayList<Section>()
        var firstOrderG: Double? = null
        if (s.subsonic) {
            val q = s.subsonicQ
            if (s.subsonicOrder % 2 == 1) firstOrderG = prewarp(s.subsonicHz, fs)
            repeat(s.subsonicOrder / 2) {
                sections += Section(prewarp(s.subsonicHz, fs), 1.0 / q, 1.0, -1.0 / q, -1.0)
            }
        }
        val att = s.strength * 0.01
        val volDiff = s.refLevel - s.refOffset - volumeDb
        var preDb = 0.0
        if (s.mode == MODE_ISO) sections += designIso(s, volDiff, att, fs)
        else {
            val r = s.bassAmount * 0.01
            var gLs = when {
                volDiff > 0.0 -> volDiff * r / (1.0 - r) * att
                volDiff < 0.0 -> volDiff * r * exp(volDiff / 90.0) * att
                else -> 0.0
            }
            gLs = clampDb(gLs)
            if (gLs > 0.0) preDb = -gLs
            val hv = volDiff - preDb
            val t = s.trebleAmount * 0.01
            val gHs = clampDb(when {
                hv > 0.0 -> hv * t * exp(-hv / 100.0) * att
                hv < 0.0 -> hv * t * 0.778 * exp(hv / 80.0) * att
                else -> 0.0
            })
            sections += shelf(false, if (abs(gLs) > ZERO_DB) gLs else 0.0, s.bassHz, s.bassSlope, fs)
            sections += shelf(true, if (abs(gHs) > ZERO_DB) gHs else 0.0, s.trebleHz, s.trebleSlope, fs)
        }
        // Never a boost: the highest point, looked for up to Nyquist itself,
        // where a high shelf reaches its full gain, comes down to 0 dB.
        var top = 0.0
        if (firstOrderG != null || sections.any { !it.flat }) {
            val probes = DoubleArray(PEAK_POINTS) { 5.0 * 2.0.pow(it / 24.0) } + isoFrequencies + (fs * 0.5)
            for (f in probes) {
                if (f > fs * 0.5) continue
                top = maxOf(top, sectionsDb(sections, firstOrderG, f, fs))
            }
        }
        return Design(sections, firstOrderG, minOf(preDb, -top), fs)
    }

    private fun designIso(s: Settings, volDiff: Double, att: Double, fs: Double): List<Section> {
        val refPhon = s.phon.coerceIn(20.0, 90.0)
        val curPhon = (s.phon - volDiff).coerceIn(20.0, 90.0)
        val target = DoubleArray(BANDS) { clampDb(isoDifference(it, refPhon, curPhon) * att) }
        if (target.all { abs(it) <= ZERO_DB }) return emptyList()
        val cw = DoubleArray(BANDS) { cos(2.0 * PI * isoFrequencies[it] / fs) }
        val c2w = DoubleArray(BANDS) { cos(4.0 * PI * isoFrequencies[it] / fs) }
        // What 1 dB on each band does at every centre; every pass corrects the
        // gains by what is still missing there.
        val shape = Array(BANDS) { i ->
            DoubleArray(BANDS) { j -> 10.0 * log10(peak(1.0, isoFrequencies[j], fs).mag2(cw[i], c2w[i])) }
        }
        val g = solve(shape, target)
        repeat(FIT_PASSES) {
            for (j in 0 until BANDS) g[j] = clampDb(g[j])
            val bands = List(BANDS) { peak(g[it], isoFrequencies[it], fs) }
            val miss = DoubleArray(BANDS) { i ->
                var m = 1.0
                for (b in bands) m *= b.mag2(cw[i], c2w[i])
                target[i] - 10.0 * log10(m.coerceAtLeast(1e-30))
            }
            val step = solve(shape, miss)
            for (j in 0 until BANDS) g[j] += step[j]
        }
        return List(BANDS) {
            val gain = clampDb(g[it])
            peak(if (abs(gain) > ZERO_DB) gain else 0.0, isoFrequencies[it], fs)
        }
    }

    /** a x = b by Gaussian elimination with partial pivoting; a and b are left alone. */
    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (k in 0 until n) {
            var p = k
            for (i in k + 1 until n) if (abs(a[i][k]) > abs(a[p][k])) p = i
            if (p != k) {
                val t = a[k]; a[k] = a[p]; a[p] = t
                val tb = b[k]; b[k] = b[p]; b[p] = tb
            }
            if (abs(a[k][k]) < 1e-12) continue
            for (i in k + 1 until n) {
                val m = a[i][k] / a[k][k]
                for (j in k until n) a[i][j] -= m * a[k][j]
                b[i] -= m * b[k]
            }
        }
        val x = DoubleArray(n)
        for (k in n - 1 downTo 0) {
            var v = b[k]
            for (j in k + 1 until n) v -= a[k][j] * x[j]
            x[k] = if (abs(a[k][k]) < 1e-12) 0.0 else v / a[k][k]
        }
        return x
    }
}
