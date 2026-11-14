package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The sqrt-Hann pair at 50 % overlap has to give the signal back exactly, one hop late. That hop
 * is the delay the mixer lines the dry signal up with; any other delay, or a reconstruction that
 * is not exact, would put the model's input through the framing as well as through the model.
 */
class StftProcessorTest {
    private val hop = GtcrnModel.HOP_SIZE

    private fun stft() = StftProcessor(GtcrnModel.FFT_SIZE, GtcrnModel.HOP_SIZE)

    private fun sine(frequency: Double, count: Int) =
        FloatArray(count) { sin(2.0 * PI * frequency * it / GtcrnModel.SAMPLE_RATE).toFloat() }

    @Test
    fun analysisThenSynthesisReturnsThePreviousHopExactly() {
        val stft = stft()
        val input = sine(440.0, hop * 8)
        val spectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
        val output = FloatArray(hop)

        var previous: FloatArray? = null
        var worst = 0f
        for (frame in 0 until 8) {
            val dry = input.copyOfRange(frame * hop, (frame + 1) * hop)
            stft.analyze(dry, spectrum)
            stft.synthesize(spectrum, output)
            previous?.let { for (sample in 0 until hop) worst = maxOf(worst, abs(it[sample] - output[sample])) }
            previous = dry
        }
        assertTrue("reconstruction error $worst", worst < 1e-5f)
    }

    @Test
    fun synthesisLagsAnalysisByExactlyOneHop() {
        val stft = stft()
        val spectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
        val output = FloatArray(hop)
        val empty = FloatArray(hop)
        val click = FloatArray(hop).also { it[hop - 1] = 1f }

        stft.analyze(empty, spectrum)
        stft.synthesize(spectrum, output)
        assertTrue("empty history produced output", output.all { abs(it) < 1e-6f })

        stft.analyze(click, spectrum)
        stft.synthesize(spectrum, output)
        assertTrue("the click came out before its hop", output.all { abs(it) < 1e-5f })

        stft.analyze(empty, spectrum)
        stft.synthesize(spectrum, output)
        assertTrue("the click did not land at the end of the next hop", abs(output[hop - 1] - 1f) < 1e-5f)
    }

    @Test
    fun resetClearsTheOverlap() {
        val stft = stft()
        val spectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
        val output = FloatArray(hop)
        repeat(2) {
            stft.analyze(sine(1000.0, hop), spectrum)
            stft.synthesize(spectrum, output)
        }
        assertTrue(output.maxOf { abs(it) } > 0.1f)

        stft.reset()
        stft.analyze(FloatArray(hop), spectrum)
        stft.synthesize(spectrum, output)

        assertTrue("left over after reset: ${output.maxOf { abs(it) }}", output.all { it == 0f })
    }
}
