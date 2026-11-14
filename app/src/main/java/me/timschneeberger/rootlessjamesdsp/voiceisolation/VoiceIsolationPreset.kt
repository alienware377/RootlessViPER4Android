/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.abs
import kotlin.math.pow

/**
 * Built-in Voice Isolation mixes ported from the source app's intensity presets. The source
 * expressed a single intensity and derived the voice gain as `1 + 0.3 * intensity`; the two
 * controls are independent here, so each preset carries both endpoints and the Maximum preset keeps
 * the measured 2.3 dB compensation for the enhancer's own voice attenuation.
 */
enum class VoiceIsolationPreset(
    val storageValue: String,
    val reductionPct: Float,
    val boostDb: Float,
    val labelResource: Int,
) {
    Podcast("podcast", 85f, 2.0f, R.string.voice_isolation_preset_podcast),
    Film("film", 65f, 1.5f, R.string.voice_isolation_preset_film),
    Gaming("gaming", 50f, 1.2f, R.string.voice_isolation_preset_gaming),
    Maximum("maximum", 100f, 2.3f, R.string.voice_isolation_preset_maximum),
    Custom("custom", 65f, 0f, R.string.voice_isolation_preset_custom),
    ;

    val voiceGain: Float get() = 10f.pow(boostDb / 20f)

    companion object {
        fun fromStorageValue(value: String?): VoiceIsolationPreset =
            entries.firstOrNull { it.storageValue == value } ?: Film

        /** Preset whose endpoints the current values match, otherwise [Custom]. */
        fun matching(reductionPct: Float, boostDb: Float): VoiceIsolationPreset =
            entries.firstOrNull {
                it != Custom &&
                    abs(it.reductionPct - reductionPct) < TOLERANCE &&
                    abs(it.boostDb - boostDb) < TOLERANCE
            } ?: Custom

        private const val TOLERANCE = 0.05f
    }
}
