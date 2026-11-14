/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Sliding STFT pair: [fftSize] samples analysed with a [hopSize] advance, and overlap-add
 * synthesis. The sqrt-Hann window's squares sum to one at 50 % overlap, so analysis followed by
 * synthesis gives the input back exactly.
 *
 * The spectrum is stored split, real bins first and then the imaginary ones. A frame has no
 * look-ahead: the newest hop fills the second half of the window, so synthesis returns the hop
 * before the one just analysed. That one hop is the whole delay.
 */
internal class StftProcessor(val fftSize: Int, val hopSize: Int) {
    val binCount = fftSize / 2 + 1
    val spectrumSize = binCount * 2

    private val window = FloatArray(fftSize) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / fftSize)).toFloat() }
    private val analysisBuffer = FloatArray(fftSize)
    private val overlapBuffer = FloatArray(fftSize)
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val fft = Fft(fftSize)

    init {
        require(hopSize in 1..fftSize)
    }

    fun analyze(input: FloatArray, spectrum: FloatArray) {
        require(input.size == hopSize && spectrum.size == spectrumSize)

        analysisBuffer.copyInto(analysisBuffer, destinationOffset = 0, startIndex = hopSize, endIndex = fftSize)
        input.copyInto(analysisBuffer, destinationOffset = fftSize - hopSize)

        for (index in 0 until fftSize) {
            real[index] = analysisBuffer[index] * window[index]
            imaginary[index] = 0f
        }
        fft.transform(real, imaginary, inverse = false)

        for (bin in 0 until binCount) {
            spectrum[bin] = real[bin]
            spectrum[bin + binCount] = imaginary[bin]
        }
    }

    fun synthesize(spectrum: FloatArray, output: FloatArray) {
        require(spectrum.size == spectrumSize && output.size == hopSize)

        for (bin in 0 until binCount) {
            real[bin] = spectrum[bin]
            imaginary[bin] = spectrum[bin + binCount]
        }
        for (bin in 1 until binCount - 1) {
            real[fftSize - bin] = real[bin]
            imaginary[fftSize - bin] = -imaginary[bin]
        }
        fft.transform(real, imaginary, inverse = true)

        for (index in 0 until fftSize) overlapBuffer[index] += real[index] * window[index]
        overlapBuffer.copyInto(output, endIndex = hopSize)
        overlapBuffer.copyInto(overlapBuffer, destinationOffset = 0, startIndex = hopSize, endIndex = fftSize)
        overlapBuffer.fill(0f, fromIndex = fftSize - hopSize)
    }

    fun reset() {
        analysisBuffer.fill(0f)
        overlapBuffer.fill(0f)
        real.fill(0f)
        imaginary.fill(0f)
    }
}
