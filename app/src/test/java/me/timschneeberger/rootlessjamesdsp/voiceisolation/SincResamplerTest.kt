package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The stream goes through this twice, down to 16 kHz and back. What the stage relies on: unity
 * gain in the passband, a delay of exactly halfWidth input samples, no crosstalk between the
 * interleaved channels, and an output count that follows the ratio.
 */
class SincResamplerTest {
    @Test
    fun theDelayIsTheKernelHalfWidth() {
        assertEquals(48, SincResampler(48_000, 16_000).delay)
        assertEquals(44, SincResampler(44_100, 16_000).delay)
        assertEquals(16, SincResampler(16_000, 48_000).delay)
        assertEquals(16, SincResampler(16_000, 44_100).delay)
    }

    @Test
    fun directCurrentPassesAtUnityGainBothWaysAndAtBothRates() {
        for ((input, output) in listOf(48_000 to 16_000, 16_000 to 48_000, 44_100 to 16_000, 16_000 to 44_100)) {
            val resampler = SincResampler(input, output)
            resampler.push(FloatArray(2000) { 0.75f }, 0, 1, 2000)
            val out = FloatArray(4000)
            val emitted = resampler.poll(out, 0, 1, out.size)
            assertTrue(emitted > 100)
            for (index in 0 until emitted) assertEquals("$input to $output at $index", 0.75f, out[index], 1e-4f)
        }
    }

    @Test
    fun anImpulseLandsAtTheAnnouncedDelay() {
        val resampler = SincResampler(48_000, 16_000)
        resampler.push(FloatArray(resampler.primingInputSamples), 0, 1, resampler.primingInputSamples)
        val input = FloatArray(480).also { it[240] = 1f }
        resampler.push(input, 0, 1, input.size)
        val output = FloatArray(200)
        val emitted = resampler.poll(output, 0, 1, output.size)

        var peak = 0
        for (index in 0 until emitted) if (abs(output[index]) > abs(output[peak])) peak = index
        // output n stands for input 3n - 48 once primed, so input 240 is output (240 + 48) / 3
        assertEquals(96, peak)
        // a click is broadband: only the 0.45 * 16 kHz passband gets through, 2 * 7.2 / 48 = 0.3
        assertEquals(0.3f, output[peak], 0.03f)
    }

    @Test
    fun aSineSurvivesTheRoundTripAtTheSumOfBothDelays() {
        for (rate in listOf(48_000, 44_100)) {
            val down = SincResampler(rate, 16_000)
            val up = SincResampler(16_000, rate)
            down.push(FloatArray(down.primingInputSamples), 0, 1, down.primingInputSamples)
            up.push(FloatArray(up.primingInputSamples), 0, 1, up.primingInputSamples)

            val frequency = 1000.0
            val input = FloatArray(rate / 2) { (0.5 * sin(2.0 * PI * frequency * it / rate)).toFloat() }
            down.push(input, 0, 1, input.size)
            val middle = FloatArray(input.size)
            val made = down.poll(middle, 0, 1, middle.size)
            up.push(middle, 0, 1, made)
            val output = FloatArray(input.size)
            val back = up.poll(output, 0, 1, output.size)

            // the whole delay in input samples: one half width each side, the second one scaled
            val delay = down.delay + up.delay * rate.toDouble() / 16_000
            var worst = 0.0
            for (index in 2000 until back - 10) {
                val expected = 0.5 * sin(2.0 * PI * frequency * (index - delay) / rate)
                worst = maxOf(worst, abs(output[index] - expected))
            }
            assertTrue("$rate: round trip off by $worst", worst < 2e-3)
        }
    }

    @Test
    fun interleavedChannelsDoNotLeakIntoEachOther() {
        val frames = 480
        val interleaved = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            interleaved[2 * frame] = 0.5f
            interleaved[2 * frame + 1] = -0.25f
        }
        val left = SincResampler(48_000, 16_000)
        val right = SincResampler(48_000, 16_000)
        left.push(interleaved, 0, 2, frames)
        right.push(interleaved, 1, 2, frames)

        val out = FloatArray(frames * 2)
        val leftEmitted = left.poll(out, 0, 2, 100)
        val rightEmitted = right.poll(out, 1, 2, 100)

        assertEquals(leftEmitted, rightEmitted)
        for (index in 0 until leftEmitted) {
            assertEquals(0.5f, out[2 * index], 1e-4f)
            assertEquals(-0.25f, out[2 * index + 1], 1e-4f)
        }
    }

    @Test
    fun outputCountFollowsTheRatioAndStopsWhenTheInputRunsOut() {
        val resampler = SincResampler(48_000, 16_000)
        val output = FloatArray(1000)

        assertEquals(0, resampler.poll(output, 0, 1, 10))
        resampler.push(FloatArray(300) { 1f }, 0, 1, 300)

        var total = resampler.poll(output, 0, 1, 50)
        total += resampler.poll(output, total, 1, 50)
        // (300 - 2 * 48) / 3: the whole kernel has to be in the history
        assertEquals(68, total)
        assertEquals(0, resampler.poll(output, total, 1, 10))

        resampler.push(FloatArray(3) { 1f }, 0, 1, 3)
        assertEquals(1, resampler.poll(output, total, 1, 10))
    }

    @Test
    fun resetForgetsTheHistory() {
        val resampler = SincResampler(48_000, 16_000)
        resampler.push(FloatArray(480) { 1f }, 0, 1, 480)
        assertTrue(resampler.poll(FloatArray(160), 0, 1, 160) > 0)

        resampler.reset()

        assertEquals(0, resampler.poll(FloatArray(160), 0, 1, 160))
    }

    @Test
    fun ratesWithTooManyPhasesAreRefusedUpFront() {
        assertTrue(SincResampler.supports(48_000, 16_000))
        assertTrue(SincResampler.supports(44_100, 16_000))
        assertTrue(SincResampler.supports(96_000, 16_000))
        assertFalse(SincResampler.supports(47_999, 16_000))
        assertFalse(SincResampler.supports(0, 16_000))
    }
}
