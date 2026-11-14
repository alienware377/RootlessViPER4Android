package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pins the speech proxy ported from Voice Isolation for Android: a two-frame attack, an
 * asymmetric threshold pair (0.18 to start, 0.12 to hold) and an 18-hop hangover that keeps short
 * pauses from pumping the background in auto mode.
 */
class SpeechActivityDetectorTest {
    private val frameSize = GtcrnModel.HOP_SIZE

    private fun tone(amplitude: Float) =
        FloatArray(frameSize) { amplitude * sin(2.0 * PI * 8.0 * it / frameSize).toFloat() }

    private fun scaled(dry: FloatArray, factor: Float) =
        FloatArray(dry.size) { dry[it] * factor }

    @Test
    fun silenceNeverCountsAsSpeech() {
        val detector = SpeechActivityDetector()
        val silent = FloatArray(frameSize)

        repeat(5) { assertFalse(detector.update(silent, silent)) }
        assertFalse(detector.speechDetected)
    }

    @Test
    fun attackNeedsTwoConsecutiveActiveFrames() {
        val detector = SpeechActivityDetector()
        val dry = tone(0.3f)

        assertFalse(detector.update(dry, dry))
        assertTrue(detector.update(dry, dry))
        assertTrue(detector.speechDetected)
    }

    @Test
    fun quietLeakageIsNotSpeechEvenWhenTheRatioIsPerfect() {
        val detector = SpeechActivityDetector()
        val whisper = tone(1e-3f)

        repeat(6) { assertFalse(detector.update(whisper, whisper)) }
    }

    @Test
    fun detectionIsHeldForEighteenFramesAfterTheSignalStops() {
        val detector = SpeechActivityDetector()
        val dry = tone(0.3f)
        detector.update(dry, dry)
        assertTrue(detector.update(dry, dry))

        val silent = FloatArray(frameSize)
        repeat(SpeechActivityDetector.HANGOVER_FRAMES) {
            assertTrue("hangover expired too early", detector.update(silent, silent))
        }
        assertFalse(detector.update(silent, silent))
    }

    @Test
    fun energyRatioBetweenTheReleaseAndAttackThresholdDoesNotRestartDetection() {
        val detector = SpeechActivityDetector()
        val dry = tone(0.5f)
        val silent = FloatArray(frameSize)

        // finish the hangover so the detector is idle again
        detector.update(dry, dry)
        detector.update(dry, dry)
        repeat(SpeechActivityDetector.HANGOVER_FRAMES + 1) { detector.update(silent, silent) }
        assertFalse(detector.speechDetected)

        // 0.387^2 = 0.15: above the 0.12 hold threshold, below the 0.18 attack threshold
        val weak = scaled(dry, 0.387f)
        repeat(10) { assertFalse(detector.update(dry, weak)) }

        // 0.45^2 = 0.2: attacks within the documented two frames
        val strong = scaled(dry, 0.45f)
        detector.update(dry, strong)
        assertTrue(detector.update(dry, strong))
    }

    @Test
    fun resetForgetsBothTheAttackCountAndTheHangover() {
        val detector = SpeechActivityDetector()
        val dry = tone(0.3f)
        detector.update(dry, dry)
        detector.update(dry, dry)
        assertTrue(detector.speechDetected)

        detector.reset()

        assertFalse(detector.speechDetected)
        val silent = FloatArray(frameSize)
        assertFalse("hangover survived the reset", detector.update(silent, silent))
        assertFalse(detector.update(dry, dry))
        assertTrue(detector.update(dry, dry))
    }

    @Test
    fun mismatchedOrEmptyFramesAreRejected() {
        val detector = SpeechActivityDetector()
        val dry = tone(0.3f)

        assertThrows(IllegalArgumentException::class.java) {
            detector.update(dry, FloatArray(frameSize - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            detector.update(FloatArray(0), FloatArray(0))
        }
    }

    @Test
    fun holdThresholdIsLowerThanTheAttackThreshold() {
        val detector = SpeechActivityDetector()
        val dry = tone(0.5f)
        val moderate = scaled(dry, 0.4f) // ratio 0.16: cannot attack

        repeat(6) { detector.update(dry, moderate) }
        assertFalse(detector.speechDetected)

        // once detection is active, the same frame must keep it alive instead of releasing
        val strong = scaled(dry, 0.5f)
        detector.update(dry, strong)
        assertTrue(detector.update(dry, strong))
        repeat(4) { assertTrue("hold threshold rejected a 0.16 ratio", detector.update(dry, moderate)) }
        assertTrue(detector.speechDetected)
    }
}
