package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class VoiceDelayEstimatorTest {
    private val frameSize = 128

    @Test
    fun findsTheDryHopMatchingTheVoiceEstimate() {
        val random = Random(41)
        val dry = Array(7) {
            FloatArray(frameSize) { random.nextFloat() * 0.4f - 0.2f }
        }
        val estimator = VoiceDelayEstimator(maxDelayHops = 6)

        val estimate = estimator.estimate(dry, newestIndex = 6, dry[3], framesAvailable = 7)

        assertEquals(3, estimate?.delayHops)
        assertEquals(1f, estimate?.correlation ?: 0f, 1e-5f)
    }

    @Test
    fun quietAudioDoesNotProduceAnUnstableEstimate() {
        val dry = Array(7) { FloatArray(frameSize) }
        val estimator = VoiceDelayEstimator(maxDelayHops = 6)

        assertNull(estimator.estimate(dry, newestIndex = 6, dry[3], framesAvailable = 7))
    }
}
