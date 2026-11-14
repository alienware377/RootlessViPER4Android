/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.sqrt

/** Finds the dry hop that best matches the current voice estimate by normalized cross-correlation. */
internal class VoiceDelayEstimator(
    private val maxDelayHops: Int = 6,
    private val minDelayHops: Int = 1,
) {
    data class Estimate(val delayHops: Int, val correlation: Float)

    init {
        require(minDelayHops >= 0 && maxDelayHops >= minDelayHops)
    }

    /**
     * [dryFrames] is a ring and [newestIndex] points at the current dry hop. Only complete,
     * sufficiently loud hops participate; quiet passages cannot move the measured delay.
     */
    fun estimate(
        dryFrames: Array<FloatArray>,
        newestIndex: Int,
        isolated: FloatArray,
        framesAvailable: Int,
    ): Estimate? {
        val maximum = minOf(maxDelayHops, framesAvailable - 1, dryFrames.size - 1)
        if (maximum < minDelayHops) return null

        val isolatedEnergy = isolated.sumOf { it.toDouble() * it }
        if (isolatedEnergy <= MIN_ENERGY) return null

        var bestDelay = -1
        var bestCorrelation = 0f
        for (delay in minDelayHops..maximum) {
            val index = (newestIndex - delay + dryFrames.size) % dryFrames.size
            val dry = dryFrames[index]
            var dryEnergy = 0.0
            var dot = 0.0
            for (sample in dry.indices) {
                val drySample = dry[sample].toDouble()
                val voiceSample = isolated[sample].toDouble()
                dryEnergy += drySample * drySample
                dot += drySample * voiceSample
            }
            if (dryEnergy <= MIN_ENERGY) continue

            val correlation = (dot / sqrt(dryEnergy * isolatedEnergy)).toFloat().coerceIn(-1f, 1f)
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestDelay = delay
            }
        }

        return if (bestDelay >= 0 && bestCorrelation >= MIN_CORRELATION) {
            Estimate(bestDelay, bestCorrelation)
        } else {
            null
        }
    }

    companion object {
        private const val MIN_ENERGY = 1e-7
        private const val MIN_CORRELATION = 0.25f
    }
}
