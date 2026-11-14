package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The capture loop's blocks have nothing in common with the model's hop. These pin the framing:
 * every block gets its own frame count back, no hop is dropped, the delay is the same for any
 * block size, and the voice and the full-band dry part line up when they are added back together.
 */
class HopPipelineTest {
    private val settings = VoiceIsolationSettings()

    /** Keeps all of the dry signal and none of the voice: the stage then only delays. */
    private class NeutralStage(var hops: Int = 0) : HopPipeline.Stage {
        override fun process(dry: FloatArray, voice: FloatArray, keep: FloatArray, settings: VoiceIsolationSettings): Int {
            hops++
            voice.fill(0f)
            keep.fill(1f)
            return 1
        }
    }

    /** Stands in for the model: gives back the hop before, which is what the STFT does. */
    private class OneHopLate(override val hopSize: Int) : EnhancerBackend {
        private val previous = FloatArray(hopSize)
        override val modelSampleRate = GtcrnModel.SAMPLE_RATE
        override fun process(input: FloatArray, output: FloatArray) {
            previous.copyInto(output)
            input.copyInto(previous)
        }
        override fun inferenceMs() = 0f
        override fun reset() = previous.fill(0f)
        override fun close() {}
    }

    private fun neutral(rate: Int, stage: HopPipeline.Stage = NeutralStage()) =
        HopPipeline(rate, GtcrnModel.SAMPLE_RATE, GtcrnModel.HOP_SIZE, stage)

    private fun withMixer(rate: Int): HopPipeline {
        val backend = OneHopLate(GtcrnModel.HOP_SIZE)
        val mixer = IsolationIntensityMixer(GtcrnModel.HOP_SIZE, GtcrnModel.SAMPLE_RATE)
        val isolated = FloatArray(GtcrnModel.HOP_SIZE)
        return HopPipeline(rate, GtcrnModel.SAMPLE_RATE, GtcrnModel.HOP_SIZE) { dry, voice, keep, s ->
            backend.process(dry, isolated)
            mixer.mix(dry, isolated, voice, keep, s)
            mixer.detectedDelayHops
        }
    }

    /** Runs [input] (one channel) through in blocks of [sizes], in turn, and returns the output. */
    private fun run(
        pipeline: HopPipeline,
        input: FloatArray,
        sizes: IntArray,
        mix: VoiceIsolationSettings = settings,
    ): FloatArray {
        val output = FloatArray(input.size)
        val buffer = FloatArray(sizes.max() * 2)
        var position = 0
        var block = 0
        while (position < input.size) {
            val frames = minOf(sizes[block++ % sizes.size], input.size - position)
            for (frame in 0 until frames) buffer[2 * frame] = input[position + frame]
            pipeline.process(buffer, 0, frames, 0, mix)
            for (frame in 0 until frames) output[position + frame] = buffer[2 * frame]
            position += frames
        }
        return output
    }

    private fun noise(count: Int, seed: Long = 3): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { random.nextFloat() * 1.2f - 0.6f }
    }

    @Test
    fun neutralSettingsGiveTheInputBackExactlyDelayedForAnyBlockSize() {
        for (rate in listOf(48_000, 44_100)) {
            val input = noise(rate)
            for (sizes in listOf(intArrayOf(128), intArrayOf(4096), intArrayOf(8192), intArrayOf(77, 1000, 3, 512, 4097))) {
                val pipeline = neutral(rate)
                val output = run(pipeline, input, sizes)
                val delay = pipeline.latencyFrames.toInt()
                for (index in 0 until input.size) {
                    val expected = if (index >= delay) input[index - delay] else 0f
                    assertEquals("$rate Hz, blocks ${sizes.joinToString("/")}, frame $index", expected, output[index], 0f)
                }
                assertEquals(0L, pipeline.underruns)
            }
        }
    }

    @Test
    fun theDelayIsAboutThirtyFourMillisecondsAtBothRates() {
        for (rate in listOf(48_000, 44_100)) {
            val latency = neutral(rate).latencyMs
            assertTrue("$rate Hz: $latency ms", latency in 33f..35f)
        }
        // a hop at the device rate to fill, then the resamplers and the hop the voice is late by
        assertEquals(770L + 864L, neutral(48_000).latencyFrames)
        assertEquals(708L + 793L, neutral(44_100).latencyFrames)
    }

    @Test
    fun randomBlockSizesNeverStarveTheOutput() {
        val random = Random(11)
        val sizes = IntArray(400) { 1 + random.nextInt(3000) }
        for (rate in listOf(48_000, 44_100)) {
            val pipeline = neutral(rate)
            run(pipeline, noise(rate * 4), sizes)
            assertEquals("$rate Hz", 0L, pipeline.underruns)
        }
    }

    @Test
    fun partialHopsAreCarriedOverInsteadOfDropped() {
        val stage = NeutralStage()
        val pipeline = neutral(48_000, stage)
        // 128 frames at 48 kHz are 42.7 model samples, so a hop completes every six blocks or so
        run(pipeline, noise(128 * 100), intArrayOf(128))

        // (12800 / 3) model samples, less the decimator's priming tail, in whole hops
        assertEquals(16, stage.hops)
        assertEquals(16L, pipeline.processedHops)
    }

    @Test
    fun everythingAboveTheModelsBandIsKeptAtNeutral() {
        val rate = 48_000
        val input = FloatArray(rate) { (0.5 * sin(2.0 * PI * 15_000.0 * it / rate)).toFloat() }
        val output = run(withMixer(rate), input, intArrayOf(1024), VoiceIsolationSettings(0f, 1f))
        assertEquals(rms(input, 20_000, 40_000), rms(output, 20_000, 40_000), 1e-4)
    }

    /**
     * With a model that keeps everything, half background reduction must give the input back at
     * full level: the voice path and the full-band dry path then carry the same signal, and only
     * when they line up does the sum come back as one sine instead of a comb.
     */
    @Test
    fun theVoiceAndTheFullBandDryPartLineUp() {
        for (rate in listOf(48_000, 44_100)) {
            for (frequency in listOf(300.0, 1000.0, 3000.0)) {
                val input = FloatArray(rate) { (0.5 * sin(2.0 * PI * frequency * it / rate)).toFloat() }
                val pipeline = withMixer(rate)
                val output = run(pipeline, input, intArrayOf(960), VoiceIsolationSettings(0.5f, 1f))
                val delay = pipeline.latencyFrames.toInt()
                var worst = 0f
                for (index in rate / 2 until rate) worst = maxOf(worst, abs(output[index] - input[index - delay]))
                assertTrue("$rate Hz, $frequency Hz: off by $worst", worst < 2e-3f)
            }
        }
    }

    @Test
    fun fullReductionLeavesOnlyWhatTheModelHears() {
        val rate = 48_000
        val input = FloatArray(rate) {
            (0.3 * sin(2.0 * PI * 1000.0 * it / rate) + 0.3 * sin(2.0 * PI * 12_000.0 * it / rate)).toFloat()
        }
        val output = run(withMixer(rate), input, intArrayOf(512), VoiceIsolationSettings(1f, 1f))
        val low = FloatArray(rate) { (0.3 * sin(2.0 * PI * 1000.0 * it / rate)).toFloat() }
        // the 12 kHz part is background to a 16 kHz model, so only the 1 kHz part is left
        assertEquals(rms(low, 20_000, 40_000), rms(output, 20_000, 40_000), 2e-3)
    }

    @Test
    fun theTwoChannelsKeepTheirOwnSignals() {
        val left = neutral(48_000)
        val right = neutral(48_000)
        val frames = 1000
        val buffer = FloatArray(frames * 2)
        repeat(20) { round ->
            for (frame in 0 until frames) {
                buffer[2 * frame] = 0.5f
                buffer[2 * frame + 1] = -0.25f
            }
            left.process(buffer, 0, frames, 0, settings)
            right.process(buffer, 1, frames, 0, settings)
            if (round < 3) return@repeat
            for (frame in 0 until frames) {
                assertEquals(0.5f, buffer[2 * frame], 0f)
                assertEquals(-0.25f, buffer[2 * frame + 1], 0f)
            }
        }
    }

    @Test
    fun resetStartsOverFromSilence() {
        val stage = NeutralStage()
        val pipeline = neutral(48_000, stage)
        run(pipeline, noise(10_000), intArrayOf(500))
        assertTrue(pipeline.processedHops > 0)

        pipeline.reset()
        val output = run(pipeline, noise(500, seed = 9), intArrayOf(500))

        assertEquals(0L, pipeline.processedHops)
        assertTrue("old audio came out after the reset", output.all { it == 0f })
    }

    @Test
    fun aNanInTheInputDoesNotReachTheModel() {
        var sawNan = false
        val pipeline = neutral(48_000) { dry, voice, keep, _ ->
            if (dry.any { it.isNaN() }) sawNan = true
            voice.fill(0f); keep.fill(1f); 1
        }
        val input = noise(20_000).also { it[5000] = Float.NaN; it[6000] = Float.POSITIVE_INFINITY }
        val output = run(pipeline, input, intArrayOf(256))

        assertTrue(!sawNan)
        assertTrue(output.all { it.isFinite() })
    }

    private fun rms(signal: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (index in from until to) sum += signal[index].toDouble() * signal[index]
        return sqrt(sum / (to - from))
    }
}
