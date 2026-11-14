/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.round

/**
 * PCM16 <-> float conversion conventions of the ported pipeline (1/32768 scale, truncating
 * quantization without dither).
 */
internal object PcmConverter {
    private const val SHORT_SCALE = 32768f

    fun toFloat(input: ShortArray, output: FloatArray, count: Int = input.size) {
        require(count <= input.size && count <= output.size)
        for (index in 0 until count) {
            output[index] = input[index] / SHORT_SCALE
        }
    }

    fun toShort(input: FloatArray, output: ShortArray, gain: Float = 1f) {
        require(input.size <= output.size)
        for (index in input.indices) {
            val sample = (input[index] * gain).coerceIn(-1f, 0.9999695f)
            output[index] = (sample * SHORT_SCALE).toInt().toShort()
        }
    }

    /** Same conversions restricted to the [offset, offset + count) region of the PCM16 buffer. */
    fun toFloatRange(input: ShortArray, output: FloatArray, offset: Int, count: Int) {
        require(offset >= 0 && count in 0..(input.size - offset) && count <= output.size)
        for (index in 0 until count) {
            output[index] = input[offset + index] / SHORT_SCALE
        }
    }

    /**
     * Float to PCM16 the way the engine's own 16-bit path does it: rounded to the nearest step
     * and clamped to the 16-bit range, so a block that went through the engine as floats comes
     * out exactly as it would have through the 16-bit entry.
     */
    fun toShortRounded(input: FloatArray, output: ShortArray, count: Int) {
        require(count in 0..minOf(input.size, output.size))
        for (index in 0 until count) {
            val scaled = input[index] * SHORT_SCALE
            output[index] = when {
                scaled.isNaN() -> 0
                scaled >= 32767f -> 32767
                scaled <= -32768f -> -32768
                else -> round(scaled).toInt()
            }.toShort()
        }
    }

    fun toShortRange(input: FloatArray, output: ShortArray, offset: Int, count: Int) {
        require(offset >= 0 && count in 0..(output.size - offset) && count <= input.size)
        for (index in 0 until count) {
            val sample = input[index].coerceIn(-1f, 0.9999695f)
            output[offset + index] = (sample * SHORT_SCALE).toInt().toShort()
        }
    }
}
