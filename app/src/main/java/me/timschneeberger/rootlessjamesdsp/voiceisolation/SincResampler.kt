/*
 * Based on the resampler of the evoeram fork of RootlessJamesDSP
 * (https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP), GPLv3.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler with linear phase: brings the stream down to the model's
 * 16 kHz and the model's output back up to the device rate.
 *
 * Every output sample sits at an exact rational position in the input, so the kernels are made for
 * the exact phases the ratio produces (one for 48 to 16 kHz, three for 16 to 48, 160 and 441 for
 * the 44.1 kHz pair) when the resampler is built, and nothing is worked out while audio runs. The
 * delay is therefore exact: [halfWidth] input samples once [primingInputSamples] zeros have been
 * pushed, less [phaseOffset] / [outputStep] of an input sample. That is what lets [HopPipeline]
 * line the full-band signal up with the model's output.
 *
 * Output is produced by [poll] only for samples whose whole kernel window is already in the ring.
 */
internal class SincResampler(
    val inputRate: Int,
    val outputRate: Int,
    /**
     * Moves every output earlier in the input by this many [outputStep]ths of an input sample,
     * which is 1/[inputStep] of an output sample. Used to make a fractional delay a whole one.
     */
    val phaseOffset: Int = 0,
    tapsPerSide: Int = DEFAULT_TAPS_PER_SIDE,
) {
    /** Kernel half width in input samples, widened when decimating so the cutoff stays sharp. */
    val halfWidth: Int

    /** Input samples per period of the ratio, and output samples (phases) in that period. */
    val inputStep: Int
    val outputStep: Int
    private val kernels: Array<FloatArray>
    private val ring = FloatArray(RING_SIZE)

    private var written = 0L
    private var produced = 0L

    init {
        require(inputRate > 0 && outputRate > 0)
        halfWidth = halfWidthFor(inputRate, outputRate, tapsPerSide)
        val common = gcd(inputRate, outputRate)
        inputStep = inputRate / common
        outputStep = outputRate / common
        require(outputStep <= MAX_PHASES) { "$inputRate to $outputRate Hz needs $outputStep kernel phases" }
        require(phaseOffset in 0 until outputStep)
        require(2 * halfWidth < RING_SIZE / 4)
        val cutoff = 0.45 * min(inputRate, outputRate).toDouble() / inputRate
        // Indexed by the position's remainder, which runs through every value as outputs go by.
        kernels = Array(outputStep) { remainder -> buildKernel(remainder.toDouble() / outputStep, cutoff) }
    }

    /** Group delay in input samples once primed, before [phaseOffset] is taken off. */
    val delay: Int get() = halfWidth

    /** Zeros to push before the first real sample, so that every later output is complete. */
    val primingInputSamples: Int get() = 2 * halfWidth

    fun push(input: FloatArray, offset: Int, stride: Int, count: Int) {
        var index = offset
        repeat(count) {
            ring[(written and RING_MASK).toInt()] = input[index]
            index += stride
            written++
        }
    }

    /**
     * Writes up to [count] output samples into [destination] from [offset], [stride] apart, and
     * returns how many it wrote: fewer only when the input has run out.
     */
    fun poll(destination: FloatArray, offset: Int, stride: Int, count: Int): Int {
        var emitted = 0
        var index = offset
        while (emitted < count) {
            val position = produced * inputStep + phaseOffset
            val center = position / outputStep + halfWidth
            val oldest = center - halfWidth + 1
            if (center + halfWidth >= written || oldest < written - RING_SIZE) break

            val kernel = kernels[(position % outputStep).toInt()]
            var accumulator = 0f
            var sample = oldest
            for (tap in kernel.indices) {
                accumulator += ring[(sample and RING_MASK).toInt()] * kernel[tap]
                sample++
            }
            destination[index] = accumulator
            index += stride
            produced++
            emitted++
        }
        return emitted
    }

    fun reset() {
        ring.fill(0f)
        written = 0
        produced = 0
    }

    private fun buildKernel(phase: Double, cutoff: Double): FloatArray {
        val kernel = FloatArray(halfWidth * 2)
        var sum = 0.0
        for (tap in kernel.indices) {
            val distance = phase - (tap - halfWidth + 1)
            val windowPosition = distance / (2.0 * halfWidth) + 0.5
            val window = if (windowPosition < 0.0 || windowPosition > 1.0) 0.0
                else 0.42 - 0.5 * cos(2.0 * PI * windowPosition) + 0.08 * cos(4.0 * PI * windowPosition)
            val angle = 2.0 * PI * cutoff * distance
            val sinc = if (distance == 0.0) 1.0 else sin(angle) / angle
            val value = window * sinc
            kernel[tap] = value.toFloat()
            sum += value
        }
        if (sum != 0.0) for (tap in kernel.indices) kernel[tap] = (kernel[tap] / sum).toFloat()
        return kernel
    }

    companion object {
        private const val DEFAULT_TAPS_PER_SIDE = 16
        private const val MAX_PHASES = 4096
        private const val RING_SIZE = 16384
        private const val RING_MASK = (RING_SIZE - 1).toLong()

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun halfWidthFor(inputRate: Int, outputRate: Int, tapsPerSide: Int = DEFAULT_TAPS_PER_SIDE): Int =
            max(1, (tapsPerSide * max(1.0, inputRate.toDouble() / outputRate)).roundToInt())

        /** True if a stream at [rate] can be taken to [modelRate] and back. */
        fun supports(rate: Int, modelRate: Int): Boolean {
            if (rate !in 8_000..192_000 || modelRate <= 0) return false
            val common = gcd(rate, modelRate)
            return rate / common <= MAX_PHASES && modelRate / common <= MAX_PHASES
        }
    }
}
