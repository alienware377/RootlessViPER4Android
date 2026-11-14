package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The stage as the engine drives it: both channels, the left one on its worker thread, float and
 * 16-bit blocks, and what happens when the model fails part way.
 */
class VoiceIsolationProcessorTest {
    private class OneHopLate(private val failAfter: Int = Int.MAX_VALUE) : EnhancerBackend {
        private val previous = FloatArray(GtcrnModel.HOP_SIZE)
        var calls = 0
        var closed = false
        override val modelSampleRate = GtcrnModel.SAMPLE_RATE
        override val hopSize = GtcrnModel.HOP_SIZE
        override fun process(input: FloatArray, output: FloatArray) {
            if (++calls > failAfter) throw IllegalStateException("model gave up")
            previous.copyInto(output)
            input.copyInto(previous)
        }
        override fun inferenceMs() = 0f
        override fun close() { closed = true }
    }

    private fun stereoNoise(frames: Int, seed: Long = 5): FloatArray {
        val random = Random(seed)
        return FloatArray(frames * 2) { random.nextFloat() - 0.5f }
    }

    @Test
    fun neutralSettingsDelayBothChannelsAndChangeNothingElse() {
        val stage = VoiceIsolationProcessor(48_000) { OneHopLate() }
        stage.settings = VoiceIsolationSettings(0f, 1f)
        val input = stereoNoise(48_000)
        val output = input.copyOf()
        val block = 2048
        for (start in 0 until 48_000 step block) stage.processFloat(output, start * 2, minOf(block, 48_000 - start))

        val delay = (stage.latencyMs * 48 + 0.5f).toInt()
        assertEquals(770 + 864, delay)
        for (frame in delay until 48_000) {
            assertEquals(input[2 * (frame - delay)], output[2 * frame], 0f)
            assertEquals(input[2 * (frame - delay) + 1], output[2 * frame + 1], 0f)
        }
        assertEquals(0L, stage.underruns())
        stage.close()
    }

    @Test
    fun shortBlocksGoThroughTheSamePath() {
        val stage = VoiceIsolationProcessor(44_100) { OneHopLate() }
        stage.settings = VoiceIsolationSettings(0f, 1f)
        val random = Random(1)
        val input = ShortArray(44_100 * 2) { (random.nextInt(65_536) - 32_768).toShort() }
        val output = ShortArray(input.size)
        val block = 1024
        val floats = FloatArray(block * 2)
        for (start in 0 until 44_100 step block) {
            val frames = minOf(block, 44_100 - start)
            assertTrue(stage.processShort(input, start * 2, frames, floats))
            val shorts = ShortArray(frames * 2)
            PcmConverter.toShortRounded(floats, shorts, frames * 2)
            shorts.copyInto(output, start * 2)
        }

        // Full-scale samples included: nothing on the way bends them.
        val delay = 708 + 793
        for (frame in delay until 44_100) {
            assertEquals(input[2 * (frame - delay)], output[2 * frame])
            assertEquals(input[2 * (frame - delay) + 1], output[2 * frame + 1])
        }
        stage.close()
    }

    @Test
    fun aFailingModelStopsTheStageAndLeavesTheAudioAlone() {
        val failures = AtomicInteger()
        val stage = VoiceIsolationProcessor(48_000) { OneHopLate(failAfter = 20) }
        stage.onFailure = { failures.incrementAndGet() }
        val buffer = stereoNoise(1024)
        repeat(40) { stage.processFloat(buffer, 0, 1024) }

        assertNotNull(stage.failure)
        assertEquals(1, failures.get())

        val untouched = stereoNoise(1024, seed = 8)
        val copy = untouched.copyOf()
        stage.processFloat(copy, 0, 1024)
        assertTrue("a failed stage still changed the audio", copy.contentEquals(untouched))
        stage.close()
    }

    @Test
    fun closeFreesBothModelSessions() {
        val backends = mutableListOf<OneHopLate>()
        val stage = VoiceIsolationProcessor(48_000) { OneHopLate().also { backends += it } }
        stage.processFloat(stereoNoise(4096), 0, 4096)
        stage.close()

        assertEquals(2, backends.size)
        assertTrue(backends.all { it.closed && it.calls > 0 })
        assertNull(stage.failure)
    }

    /** Stands in for a slow phone: the left channel's first hop takes longer than any timeout would. */
    private class SlowLeft : EnhancerBackend {
        val entered = CountDownLatch(1)
        @Volatile var inside = false
        @Volatile var closedWhileRunning = false
        override val modelSampleRate = GtcrnModel.SAMPLE_RATE
        override val hopSize = GtcrnModel.HOP_SIZE
        override fun process(input: FloatArray, output: FloatArray) {
            inside = true
            if (entered.count > 0) {
                entered.countDown()
                Thread.sleep(800)
            }
            output.fill(0f)
            inside = false
        }
        override fun inferenceMs() = 0f
        override fun close() { if (inside) closedWhileRunning = true }
    }

    @Test
    fun closeLetsABlockInFlightFinish() {
        val left = SlowLeft()
        var made = 0
        val stage = VoiceIsolationProcessor(48_000) { if (made++ == 0) left else OneHopLate() }
        val buffer = stereoNoise(4096)
        var leftStillRunningOnReturn = false
        var ran = false
        val audio = Thread {
            stage.processFloat(buffer, 0, 4096)
            ran = true
            leftStillRunningOnReturn = left.inside
        }
        audio.start()
        assertTrue(left.entered.await(5, TimeUnit.SECONDS))
        stage.close()
        audio.join(5000)

        assertTrue(ran)
        assertFalse("the block came back while the worker still wrote into it", leftStillRunningOnReturn)
        assertFalse("a session was closed under a run", left.closedWhileRunning)
        assertNull(stage.failure)

        // Once closed, a block goes through untouched.
        val untouched = stereoNoise(1024, seed = 4)
        val copy = untouched.copyOf()
        stage.processFloat(copy, 0, 1024)
        assertTrue(copy.contentEquals(untouched))
        val shorts = ShortArray(2048) { it.toShort() }
        assertFalse(stage.processShort(shorts, 0, 1024, FloatArray(2048)))
    }

    @Test
    fun aModelThatWillNotOpenLeavesNothingBehind() {
        val made = mutableListOf<OneHopLate>()
        var attempts = 0
        val result = runCatching {
            VoiceIsolationProcessor(48_000) {
                if (++attempts == 2) throw UnsatisfiedLinkError("no runtime")
                OneHopLate().also { made += it }
            }
        }
        assertTrue(result.exceptionOrNull() is UnsatisfiedLinkError)
        assertTrue("the first session was not closed", made.single().closed)
    }
}
