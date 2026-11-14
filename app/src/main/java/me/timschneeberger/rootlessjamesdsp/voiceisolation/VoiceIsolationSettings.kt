/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.pow
import kotlin.math.log10

/**
 * Voice Isolation mix controls: how much of the estimated background is attenuated and how much
 * the estimated voice is boosted, plus the energy-ratio auto mode.
 */
data class VoiceIsolationSettings(
    val backgroundReduction: Float = DEFAULT_REDUCTION,
    val voiceGain: Float = 1f,
    val autoMode: Boolean = false,
    val voiceReduction: Float = 0f,
) {
    fun normalized(): VoiceIsolationSettings {
        val reduction = if (backgroundReduction.isFinite()) {
            backgroundReduction.coerceIn(0f, 1f)
        } else {
            DEFAULT_REDUCTION
        }
        val gain = if (voiceGain.isFinite()) voiceGain.coerceIn(MINIMUM_VOICE_GAIN, MAXIMUM_VOICE_GAIN) else 1f
        val voice = if (voiceReduction.isFinite()) voiceReduction.coerceIn(0f, 1f) else 0f
        return if (reduction == backgroundReduction && gain == voiceGain && voice == voiceReduction) {
            this
        } else {
            copy(backgroundReduction = reduction, voiceGain = gain, voiceReduction = voice)
        }
    }

    companion object {
        const val DEFAULT_REDUCTION = 0.65f
        const val MINIMUM_VOICE_GAIN = 1f
        const val MAXIMUM_VOICE_GAIN = 2f
        const val MAXIMUM_BOOST_DB = 6f

        /** UI stores the voice boost in dB; the mixer works on the linear gain. */
        fun boostDbToGain(boostDb: Float): Float =
            if (!boostDb.isFinite()) 1f else 10.0.pow(boostDb.coerceIn(0f, MAXIMUM_BOOST_DB) / 20.0).toFloat()

        fun gainToBoostDb(voiceGain: Float): Float =
            if (voiceGain <= 0f) 0f else (20.0 * log10(voiceGain.toDouble())).toFloat().coerceIn(0f, MAXIMUM_BOOST_DB)
    }
}
