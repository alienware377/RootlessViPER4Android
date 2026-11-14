package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FftTest {
    private fun signal(size: Int): Pair<FloatArray, FloatArray> {
        val real = FloatArray(size) { (sin(2.0 * PI * it * 7 / size) + 0.5 * cos(2.0 * PI * it * 31 / size)).toFloat() }
        val imaginary = FloatArray(size) { (0.25 * cos(2.0 * PI * it * 3 / size)).toFloat() }
        return real to imaginary
    }

    private fun naiveDft(real: FloatArray, imaginary: FloatArray): Pair<FloatArray, FloatArray> {
        val size = real.size
        val outReal = FloatArray(size)
        val outImaginary = FloatArray(size)
        for (k in 0 until size) {
            var sumReal = 0.0
            var sumImaginary = 0.0
            for (n in 0 until size) {
                val angle = -2.0 * PI * k * n / size
                sumReal += real[n] * cos(angle) - imaginary[n] * sin(angle)
                sumImaginary += real[n] * sin(angle) + imaginary[n] * cos(angle)
            }
            outReal[k] = sumReal.toFloat()
            outImaginary[k] = sumImaginary.toFloat()
        }
        return outReal to outImaginary
    }

    @Test
    fun matchesTheDirectSumAtTheModelsLength() {
        val (real, imaginary) = signal(GtcrnModel.FFT_SIZE)
        val (expectedReal, expectedImaginary) = naiveDft(real, imaginary)

        Fft(GtcrnModel.FFT_SIZE).transform(real, imaginary, inverse = false)

        var worst = 0f
        for (index in real.indices) {
            worst = maxOf(worst, abs(real[index] - expectedReal[index]), abs(imaginary[index] - expectedImaginary[index]))
        }
        assertTrue("worst error against the direct sum: $worst", worst < 2e-3f)
    }

    @Test
    fun inverseUndoesTheForwardTransform() {
        val (real, imaginary) = signal(GtcrnModel.FFT_SIZE)
        val originalReal = real.copyOf()
        val originalImaginary = imaginary.copyOf()
        val fft = Fft(GtcrnModel.FFT_SIZE)

        fft.transform(real, imaginary, inverse = false)
        fft.transform(real, imaginary, inverse = true)

        for (index in real.indices) {
            assertEquals(originalReal[index], real[index], 1e-5f)
            assertEquals(originalImaginary[index], imaginary[index], 1e-5f)
        }
    }

    @Test
    fun anImpulseHasAFlatSpectrum() {
        val real = FloatArray(64).also { it[0] = 1f }
        val imaginary = FloatArray(64)

        Fft(64).transform(real, imaginary, inverse = false)

        for (index in real.indices) {
            assertEquals(1f, real[index], 1e-6f)
            assertEquals(0f, imaginary[index], 1e-6f)
        }
    }

    @Test
    fun lengthsThatAreNotPowersOfTwoAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { Fft(960) }
        assertThrows(IllegalArgumentException::class.java) { Fft(1) }
    }
}
