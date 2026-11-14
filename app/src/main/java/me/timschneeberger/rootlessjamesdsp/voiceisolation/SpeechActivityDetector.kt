/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * Speech proxy: compares the energy retained by the enhancer against the energy of the
 * latency-aligned dry frame.
 */
internal class SpeechActivityDetector {
    var speechDetected: Boolean = false
        private set

    private var activeFrames = 0
    private var remainingHangoverFrames = 0

    fun update(dry: FloatArray, isolated: FloatArray): Boolean {
        require(dry.size == isolated.size && dry.isNotEmpty())
        var dryEnergy = 0.0
        var isolatedEnergy = 0.0
        for (index in dry.indices) {
            dryEnergy += dry[index] * dry[index]
            isolatedEnergy += isolated[index] * isolated[index]
        }

        // The enhancer's retained energy is a conservative speech proxy, not a separate VAD model.
        // Vocals or music retained by the separator may also activate this detector.
        val minimumRms = if (speechDetected) 0.005 else 0.008
        val minimumRatio = if (speechDetected) 0.12 else 0.18
        val active = dryEnergy > MINIMUM_DRY_ENERGY * dry.size &&
            isolatedEnergy >= minimumRms * minimumRms * dry.size &&
            isolatedEnergy / dryEnergy >= minimumRatio

        if (active) {
            activeFrames = (activeFrames + 1).coerceAtMost(ATTACK_FRAMES)
            if (speechDetected || activeFrames >= ATTACK_FRAMES) {
                speechDetected = true
                remainingHangoverFrames = HANGOVER_FRAMES
            }
        } else {
            activeFrames = 0
            if (remainingHangoverFrames > 0) {
                remainingHangoverFrames--
            } else {
                speechDetected = false
            }
        }
        return speechDetected
    }

    fun reset() {
        speechDetected = false
        activeFrames = 0
        remainingHangoverFrames = 0
    }

    companion object {
        private const val MINIMUM_DRY_ENERGY = 1e-8
        private const val ATTACK_FRAMES = 2
        // 18 enhancer hops at 16 kHz keep short pauses from pumping the background.
        internal const val HANGOVER_FRAMES = 18
    }
}
