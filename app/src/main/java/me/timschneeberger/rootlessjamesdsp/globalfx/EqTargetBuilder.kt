package me.timschneeberger.rootlessjamesdsp.globalfx

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import me.timschneeberger.rootlessjamesdsp.utils.GraphicEqCurve

/**
 * The equaliser cards' settings, as global mode reads them.
 *
 * Plain values only, so the curve can be built and tested without Android.
 * The defaults are the ones the engine falls back to when a card was never
 * touched (JamesDspBaseEngine.syncWithPreferences).
 */
data class EqInputs(
    val eqEnabled: Boolean = false,
    /** 0 PCHIP, 1 makima, as key_eq_interpolation */
    val eqInterpolation: Int = 0,
    val eqBands: String = DEFAULT_EQ,
    val geqEnabled: Boolean = false,
    val geqNodes: String = "GraphicEQ: ",
    val peqEnabled: Boolean = false,
    val peqBands: String = "PEQ: ",
    val peqPreamp: Double = 0.0,
    val bassEnabled: Boolean = false,
    val bassMaxGain: Double = 5.0,
) {
    companion object {
        // Same text as Constants.DEFAULT_EQ, repeated so this file has no
        // Android build dependency. EqTargetBuilderTest checks they agree.
        const val DEFAULT_EQ = "25.0;40.0;63.0;100.0;160.0;250.0;400.0;630.0;1000.0;1600.0;2500.0;4000.0;6300.0;10000.0;16000.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0"
    }
}

/** One target curve per ear, plus the one channels past the first two get. */
class EqTarget(val left: DoubleArray, val right: DoubleArray, val shared: DoubleArray) {
    val isStereoSplit: Boolean get() = !left.contentEquals(right) || !left.contentEquals(shared)
}

/**
 * Adds the equaliser cards up into one curve in dB.
 *
 * Global mode hands sound to Android's own equaliser, which knows nothing of
 * how this app's cards are built, so every card that changes the tone has to
 * be turned into one gain per frequency first:
 *
 *  - Multiband EQ: its fifteen nodes with the end nodes the engine adds at
 *    0 Hz and 24 kHz, joined by PCHIP or makima on a linear frequency axis,
 *    exactly as multimodalEQ.c does.
 *  - Graphic EQ (AutoEq imports and Match EQ end up here too): straight lines
 *    on a log axis between its nodes, the same reading the engine uses.
 *  - Parametric EQ: the biquads' own magnitude response plus its preamp,
 *    per ear when bands are assigned to one ear.
 *  - Bass boost: the engine's bass boost follows the music. Android's
 *    equaliser cannot, so it becomes a fixed 80 Hz low shelf at the card's
 *    maximum gain.
 *
 * The graphic, parametric and bass parts hold their 20 Hz and 20 kHz values
 * outside that range, which is the range the engine builds them over; past
 * it a low-pass biquad heads for minus infinity at Nyquist and would drag the
 * fitter's whole top band down with it.
 */
object EqTargetBuilder {
    const val BASS_SHELF_HZ = 80.0
    const val BASS_SHELF_Q = 0.707
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0

    // How far the finished curve may go. Deeper than -60 dB is silence next to
    // the music around it, and anything past +40 dB is a mistake, not a choice.
    const val FLOOR_DB = -60.0
    const val CEILING_DB = 40.0

    fun build(inputs: EqInputs, freqs: DoubleArray, sampleRate: Double): EqTarget {
        val n = freqs.size
        val common = DoubleArray(n)

        multibandCurve(inputs)?.let { curve ->
            for (i in 0 until n) common[i] += curve.valueAt(freqs[i].coerceIn(0.0, MEQ_TOP_HZ))
        }

        if (inputs.geqEnabled) {
            val nodes = GraphicEqCurve.parse(inputs.geqNodes)
                .filter { it.first.isFinite() && it.second.isFinite() && it.first > 0.0 }
            if (nodes.isNotEmpty())
                for (i in 0 until n) common[i] += GraphicEqCurve.interpolate(nodes, clampAudible(freqs[i]))
        }

        if (inputs.bassEnabled && inputs.bassMaxGain != 0.0) {
            val shelf = BiquadUtils.computeCoefficients(
                BASS_SHELF_HZ, inputs.bassMaxGain, BASS_SHELF_Q, ParametricEqFilterType.LOW_SHELF, sampleRate)
            for (i in 0 until n)
                common[i] += BiquadUtils.magnitudeResponse(shelf, clampAudible(freqs[i]), sampleRate)
        }

        val bands = ParametricEqBandList().apply { deserialize(inputs.peqBands) }
            .filter { it.frequency.isFinite() && it.frequency > 0.0 && it.q.isFinite() && it.q > 0.0 && it.gain.isFinite() }
        if (!inputs.peqEnabled || bands.isEmpty())
            return finish(common, common, common)

        val preamp = if (inputs.peqPreamp.isFinite()) inputs.peqPreamp else 0.0
        val perEar = bands.any { it.channel != ParametricEqChannel.BOTH }
        val both = peqCurve(bands, freqs, sampleRate, preamp)
        if (!perEar)
            return finish(add(common, both), null, null)

        val left = peqCurve(bands.filter { it.channel != ParametricEqChannel.RIGHT }, freqs, sampleRate, preamp)
        val right = peqCurve(bands.filter { it.channel != ParametricEqChannel.LEFT }, freqs, sampleRate, preamp)
        val shared = peqCurve(bands.filter { it.channel == ParametricEqChannel.BOTH }, freqs, sampleRate, preamp)
        return finish(add(common, left), add(common, right), add(common, shared))
    }

    /** The Multiband EQ's interpolated curve, or null when the card is off or unreadable. */
    fun multibandCurve(inputs: EqInputs): HermiteCurve? {
        if (!inputs.eqEnabled) return null
        val values = inputs.eqBands.split(";").map { it.trim().toDoubleOrNull() }
        if (values.size < 2 * MEQ_NODES || values.take(2 * MEQ_NODES).any { it == null || !it.isFinite() })
            return null
        val freq = DoubleArray(MEQ_NODES + 2)
        val gain = DoubleArray(MEQ_NODES + 2)
        for (i in 0 until MEQ_NODES) {
            freq[i + 1] = values[i]!!
            gain[i + 1] = values[MEQ_NODES + i]!!.coerceIn(-64.0, 64.0)
        }
        freq[0] = 0.0
        gain[0] = gain[1]
        freq[MEQ_NODES + 1] = MEQ_TOP_HZ
        gain[MEQ_NODES + 1] = gain[MEQ_NODES]
        // The engine needs strictly rising frequencies too; a hand-edited
        // preset that breaks that is skipped rather than drawn wrong.
        for (i in 1 until freq.size) if (freq[i] <= freq[i - 1]) return null
        return if (inputs.eqInterpolation == 1) HermiteCurve.makima(freq, gain)
               else HermiteCurve.pchip(freq, gain)
    }

    private fun peqCurve(bands: List<ParametricEqBand>, freqs: DoubleArray, fs: Double, preamp: Double): DoubleArray {
        val sections = bands.flatMap { BiquadUtils.cascadeFor(it, fs) }
        val out = DoubleArray(freqs.size)
        if (sections.isEmpty()) return out
        for (i in freqs.indices) {
            val f = clampAudible(freqs[i])
            var g = preamp
            for (c in sections) g += BiquadUtils.magnitudeResponse(c, f, fs)
            out[i] = g
        }
        return out
    }

    private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }

    private fun finish(left: DoubleArray, right: DoubleArray?, shared: DoubleArray?): EqTarget {
        fun clean(c: DoubleArray) = DoubleArray(c.size) {
            val v = c[it]
            if (v.isFinite()) v.coerceIn(FLOOR_DB, CEILING_DB) else if (v > 0) CEILING_DB else if (v < 0) FLOOR_DB else 0.0
        }
        val l = clean(left)
        val r = right?.let(::clean) ?: l
        val s = shared?.let(::clean) ?: l
        return EqTarget(l, r, s)
    }

    private fun clampAudible(f: Double) = f.coerceIn(MIN_HZ, MAX_HZ)

    private const val MEQ_NODES = 15
    private const val MEQ_TOP_HZ = 24000.0
}
