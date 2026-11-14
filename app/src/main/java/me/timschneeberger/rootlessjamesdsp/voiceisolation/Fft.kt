/*
 * Based on the transform in android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 complex FFT of one power-of-two length.
 *
 * The twiddles and the bit reversal are tables made once, rather than a rotation multiplied up
 * inside each stage, so the error does not grow along the stage and nothing is computed with
 * cos or sin per call. The inverse is scaled by 1/size.
 */
internal class Fft(val size: Int) {
    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val reversed: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size $size is not a power of two" }
        cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
        sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
        val bits = Integer.numberOfTrailingZeros(size)
        reversed = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun transform(real: FloatArray, imaginary: FloatArray, inverse: Boolean) {
        for (index in 0 until size) {
            val other = reversed[index]
            if (index < other) {
                val r = real[index]; real[index] = real[other]; real[other] = r
                val i = imaginary[index]; imaginary[index] = imaginary[other]; imaginary[other] = i
            }
        }

        val sign = if (inverse) 1f else -1f
        var stage = 2
        while (stage <= size) {
            val half = stage / 2
            val tableStep = size / stage
            var offset = 0
            while (offset < size) {
                for (index in 0 until half) {
                    val weightReal = cosTable[index * tableStep]
                    val weightImaginary = sign * sinTable[index * tableStep]
                    val odd = offset + index + half
                    val even = offset + index
                    val oddReal = weightReal * real[odd] - weightImaginary * imaginary[odd]
                    val oddImaginary = weightReal * imaginary[odd] + weightImaginary * real[odd]
                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]
                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary
                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary
                }
                offset += stage
            }
            stage = stage shl 1
        }

        if (inverse) {
            val scale = 1f / size
            for (index in 0 until size) {
                real[index] *= scale
                imaginary[index] *= scale
            }
        }
    }
}
