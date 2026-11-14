package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The mixer hands back the scaled voice and the share of dry signal to keep. Added together they
 * have to be the ported mix, (dry - voice) * (1 - reduction) + voice * gain * (1 - vocal), with the
 * dry hop lined up with the voice estimate it produced.
 */
class IsolationIntensityMixerTest {
    private val frameSize = GtcrnModel.HOP_SIZE

    private fun tone(amplitude: Float, cycles: Int = 8) =
        FloatArray(frameSize) { amplitude * sin(2.0 * PI * cycles * it / frameSize).toFloat() }

    private fun settings(reduction: Float, gain: Float = 1f, auto: Boolean = false, voiceReduction: Float = 0f) =
        VoiceIsolationSettings(reduction, gain, auto, voiceReduction)

    private class Mixed(val mixer: IsolationIntensityMixer) {
        val voice = FloatArray(GtcrnModel.HOP_SIZE)
        val keep = FloatArray(GtcrnModel.HOP_SIZE)

        fun mix(dry: FloatArray, isolated: FloatArray, settings: VoiceIsolationSettings): FloatArray {
            mixer.mix(dry, isolated, voice, keep, settings)
            return FloatArray(voice.size) { voice[it] + keep[it] * mixer.alignedDry[it] }
        }
    }

    private fun mixer() = Mixed(IsolationIntensityMixer(frameSize, GtcrnModel.SAMPLE_RATE))

    @Test
    fun neutralControlsLeaveTheSignalUntouchedApartFromOneHopOfDelay() {
        val mixed = mixer()
        val dry = tone(0.25f)
        val isolated = tone(0.25f, cycles = 3)

        mixed.mix(dry, isolated, settings(0f))
        val output = mixed.mix(dry, isolated, settings(0f))

        assertArrayEquals(dry, output, 1e-6f)
        assertTrue("neutral still sends voice", mixed.voice.all { it == 0f })
        assertTrue(mixed.keep.all { it == 1f })
    }

    @Test
    fun backgroundIsTurnedDownByTheReduction() {
        val mixed = mixer()
        val dry = tone(0.4f)
        val isolated = tone(0.2f, cycles = 5)

        mixed.mix(dry, isolated, settings(0.5f))
        val output = mixed.mix(dry, isolated, settings(0.5f))

        assertArrayEquals(FloatArray(frameSize) { 0.5f * (dry[it] - isolated[it]) + isolated[it] }, output, 1e-6f)
    }

    @Test
    fun fullReductionLeavesOnlyTheVoice() {
        val mixed = mixer()
        val dry = tone(0.4f)
        val isolated = tone(0.2f, cycles = 5)

        mixed.mix(dry, isolated, settings(1f))
        val output = mixed.mix(dry, isolated, settings(1f))

        assertArrayEquals(isolated, output, 1e-6f)
        assertTrue(mixed.keep.all { it == 0f })
    }

    @Test
    fun voiceGainBoostsOnlyTheVoice() {
        val mixed = mixer()
        val dry = tone(0.3f)

        mixed.mix(dry, dry, settings(0f, gain = 2f))
        val output = mixed.mix(dry, dry, settings(0f, gain = 2f))

        // reduction 0 keeps the whole background, gain 2 doubles the voice: dry + voice
        assertArrayEquals(FloatArray(frameSize) { 2f * dry[it] }, output, 1e-6f)
    }

    @Test
    fun vocalReductionTurnsDownOnlyTheVoice() {
        val mixed = mixer()
        val dry = tone(0.3f)
        val isolated = tone(0.15f, cycles = 3)

        mixed.mix(dry, isolated, settings(0.25f, voiceReduction = 0.5f))
        val output = mixed.mix(dry, isolated, settings(0.25f, voiceReduction = 0.5f))

        assertArrayEquals(FloatArray(frameSize) { 0.75f * (dry[it] - isolated[it]) + 0.5f * isolated[it] }, output, 1e-6f)
    }

    @Test
    fun karaokeTakesTheVoiceOutOfTheDrySignal() {
        val mixed = mixer()
        val dry = tone(0.3f)
        val isolated = tone(0.1f, cycles = 3)

        mixed.mix(dry, isolated, settings(0f, voiceReduction = 1f))
        val output = mixed.mix(dry, isolated, settings(0f, voiceReduction = 1f))

        assertArrayEquals(FloatArray(frameSize) { dry[it] - isolated[it] }, output, 1e-6f)
    }

    @Test
    fun theMeasuredDelayDecidesWhichDryHopIsUsed() {
        val random = java.util.Random(7)
        val dry = Array(20) { FloatArray(frameSize) { random.nextFloat() * 0.4f - 0.2f } }
        val mixed = mixer()
        val silence = FloatArray(frameSize)

        var output = FloatArray(frameSize)
        for (hop in dry.indices) output = mixed.mix(dry[hop], if (hop >= 2) dry[hop - 2] else silence, settings(0f))

        assertEquals(2, mixed.mixer.detectedDelayHops)
        assertArrayEquals(dry[17], output, 1e-6f)
    }

    @Test
    fun autoModeHoldsTheReductionBackWhileNobodyTalks() {
        val mixed = mixer()
        val silent = FloatArray(frameSize)

        mixed.mix(silent, silent, settings(0.65f, auto = true))
        assertEquals(0.2f, mixed.mixer.effectiveReduction, 1e-6f)

        val speech = tone(0.3f)
        val trajectory = mutableListOf<Float>()
        repeat(12) {
            mixed.mix(speech, speech, settings(0.65f, auto = true))
            trajectory += mixed.mixer.effectiveReduction
        }

        assertTrue("attack is not monotonic: $trajectory", trajectory == trajectory.sorted())
        assertTrue("never left the idle cap: $trajectory", trajectory.last() > 0.6f)
        assertTrue("overshot: $trajectory", trajectory.all { it <= 0.65f + 1e-4f })
    }

    @Test
    fun manualModeAppliesTheReductionAtOnce() {
        val mixed = mixer()
        mixed.mix(FloatArray(frameSize), FloatArray(frameSize), settings(0.65f))
        assertEquals(0.65f, mixed.mixer.effectiveReduction, 1e-6f)
    }

    @Test
    fun resetClearsTheRampAndTheDryHistory() {
        val mixed = mixer()
        val speech = tone(0.3f)
        repeat(6) { mixed.mix(speech, speech, settings(0.65f, auto = true)) }
        assertTrue(mixed.mixer.effectiveReduction > 0.2f)

        mixed.mixer.reset()

        assertEquals(0f, mixed.mixer.effectiveReduction, 0f)
        assertFalse(mixed.mixer.speechDetected)
        // the first hop after a reset has nothing to line up with, so a neutral mix is silent
        assertArrayEquals(FloatArray(frameSize), mixed.mix(speech, speech, settings(0f)), 1e-6f)
    }

    @Test
    fun hopsOfTheWrongSizeAreRefused() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnModel.SAMPLE_RATE)
        val short = FloatArray(frameSize - 1)
        val right = FloatArray(frameSize)

        assertThrows(IllegalArgumentException::class.java) { mixer.mix(short, short, right, right, settings(0f)) }
        assertThrows(IllegalArgumentException::class.java) { mixer.mix(right, right, short, right, settings(0f)) }
    }
}
