/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Works out, per hop, how much of the voice estimate and how much of the dry signal to play.
 *
 * The background is taken as dry minus voice, so with a background reduction r, a voice gain g and
 * a vocal reduction v the result is
 *
 *     (dry - voice) * (1 - r) + voice * g * (1 - v)
 *   = dry * keep + voice * (g * (1 - v) - keep),   keep = 1 - r
 *
 * The second form is what this returns: [keep] per sample, and the voice already scaled. The dry
 * part is then added at the device rate by [HopPipeline], from the full-band input rather than the
 * 16 kHz copy the model heard, so everything above the model's 8 kHz Nyquist is kept (and turned
 * down with the rest of the background) instead of being cut off.
 *
 * The model returns its voice a hop late, and a different export could be later still, so the dry
 * hop that goes with it is found by correlation ([VoiceDelayEstimator]) over the first hops, and
 * then held. A dry signal out of step with the voice would comb-filter exactly what the model was
 * asked to keep.
 */
internal class IsolationIntensityMixer(
    frameSize: Int,
    private val modelSampleRate: Int,
    private val initialDelayHops: Int = 1,
) {
    private val dryFrames = Array(MAX_DELAY_HOPS + 1) { FloatArray(frameSize) }
    private val delayEstimator = VoiceDelayEstimator(MAX_DELAY_HOPS)
    private val speechDetector = SpeechActivityDetector()
    private var mixInitialized = false
    private var currentReduction = 0f
    private var currentVoiceGain = 1f
    private var newestDryIndex = 0
    private var dryFramesWritten = 0L
    private var delayLocked = false
    private var candidateDelay = -1
    private var candidateCount = 0

    /** Hops by which the voice estimate trails the dry input it came from. */
    var detectedDelayHops = initialDelayHops
        private set

    /** The dry hop the last [mix] matched to its voice estimate. */
    var alignedDry: FloatArray = dryFrames[0]
        private set

    val speechDetected: Boolean
        get() = speechDetector.speechDetected

    val effectiveReduction: Float
        get() = currentReduction

    init {
        require(initialDelayHops in 1..MAX_DELAY_HOPS)
    }

    fun mix(
        input: FloatArray,
        isolated: FloatArray,
        voice: FloatArray,
        keep: FloatArray,
        settings: VoiceIsolationSettings,
    ) {
        val size = dryFrames[0].size
        require(input.isNotEmpty() && input.size == size && isolated.size == size)
        require(voice.size == size && keep.size == size)

        input.copyInto(dryFrames[newestDryIndex])
        dryFramesWritten++
        if (!delayLocked && dryFramesWritten >= CALIBRATION_START_HOPS &&
            dryFramesWritten % CALIBRATION_INTERVAL_HOPS == 0L
        ) {
            updateDelay(isolated)
        }
        val dryIndex = (newestDryIndex - detectedDelayHops + dryFrames.size) % dryFrames.size
        alignedDry = dryFrames[dryIndex]
        newestDryIndex = (newestDryIndex + 1) % dryFrames.size

        val normalized = settings.normalized()
        val hasSpeech = speechDetector.update(alignedDry, isolated)
        val softenBackground = normalized.autoMode && !hasSpeech
        val targetReduction = if (softenBackground) {
            minOf(normalized.backgroundReduction, AUTO_BACKGROUND_REDUCTION)
        } else {
            normalized.backgroundReduction
        }
        val targetVoiceGain = (if (softenBackground) 1f else normalized.voiceGain) *
            (1f - normalized.voiceReduction)
        if (!mixInitialized) {
            currentReduction = targetReduction
            currentVoiceGain = targetVoiceGain
            mixInitialized = true
        }

        val rampFraction = if (normalized.autoMode) {
            val rampMs = if (hasSpeech) AUTO_ATTACK_MS else AUTO_RELEASE_MS
            (size * 1000f / modelSampleRate / rampMs).coerceAtMost(1f)
        } else {
            1f
        }
        val nextReduction = currentReduction + (targetReduction - currentReduction) * rampFraction
        val nextVoiceGain = currentVoiceGain + (targetVoiceGain - currentVoiceGain) * rampFraction
        val reductionStep = (nextReduction - currentReduction) / size
        val gainStep = (nextVoiceGain - currentVoiceGain) / size

        for (index in 0 until size) {
            val keepDry = 1f - (currentReduction + reductionStep * (index + 1))
            val voiceGain = currentVoiceGain + gainStep * (index + 1)
            keep[index] = keepDry
            voice[index] = isolated[index] * (voiceGain - keepDry)
        }
        currentReduction = nextReduction
        currentVoiceGain = nextVoiceGain
    }

    private fun updateDelay(isolated: FloatArray) {
        val estimate = delayEstimator.estimate(
            dryFrames, newestDryIndex, isolated, dryFramesWritten.toInt(),
        ) ?: return
        if (estimate.delayHops == candidateDelay) {
            candidateCount++
        } else {
            candidateDelay = estimate.delayHops
            candidateCount = 1
        }
        if (candidateCount >= CALIBRATION_CONFIRMATIONS) {
            detectedDelayHops = candidateDelay
            delayLocked = true
        }
    }

    fun reset() {
        dryFrames.forEach { it.fill(0f) }
        alignedDry = dryFrames[0]
        speechDetector.reset()
        mixInitialized = false
        currentReduction = 0f
        currentVoiceGain = 1f
        newestDryIndex = 0
        dryFramesWritten = 0
        detectedDelayHops = initialDelayHops
        delayLocked = false
        candidateDelay = -1
        candidateCount = 0
    }

    companion object {
        const val MAX_DELAY_HOPS = 6
        private const val CALIBRATION_START_HOPS = 8L
        private const val CALIBRATION_INTERVAL_HOPS = 4L
        private const val CALIBRATION_CONFIRMATIONS = 3
        private const val MAX_PEAK = 0.9999695f
        private const val SOFT_LIMIT_START = 0.9f
        private const val SOFT_LIMIT_RANGE = MAX_PEAK - SOFT_LIMIT_START
        private const val AUTO_BACKGROUND_REDUCTION = 0.2f
        private const val AUTO_ATTACK_MS = 40f
        private const val AUTO_RELEASE_MS = 250f

        /**
         * Leaves everything up to 0.9 alone and bends what is above it into the last tenth below
         * full scale, so a boosted voice does not clip on its way to a 16-bit track.
         */
        fun softLimit(sample: Float): Float {
            val magnitude = abs(sample)
            if (magnitude <= SOFT_LIMIT_START) return sample
            val limited = SOFT_LIMIT_START + SOFT_LIMIT_RANGE *
                tanh(((magnitude - SOFT_LIMIT_START) / SOFT_LIMIT_RANGE).toDouble()).toFloat()
            return if (sample < 0f) -limited else limited
        }
    }
}
