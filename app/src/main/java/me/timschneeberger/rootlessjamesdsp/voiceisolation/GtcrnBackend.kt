/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * The model it runs is GTCRN by Rong Xiaobin (https://github.com/Xiaobin-Rong/gtcrn), MIT licence,
 * Copyright (c) 2024 Rong Xiaobin. See THIRD_PARTY_NOTICES.md for both notices.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import java.util.function.BiConsumer
import kotlin.math.abs

/**
 * GTCRN as the stage sees it: a hop of 16 kHz audio in, a hop of the voice out.
 *
 * The transform lives here; the model itself runs in the voice isolation add-on, behind [hop],
 * which takes one frame of spectrum in the model's own layout (bins as interleaved real and
 * imaginary pairs) and writes the voice's spectrum back in the same layout. [hop] and [session]
 * are the same object from the add-on, known to the app only by these platform interfaces, so a
 * hop is one interface call with no reflection and nothing allocated on this side.
 */
internal class GtcrnBackend(
    private val hop: BiConsumer<FloatArray, FloatArray>,
    private val session: AutoCloseable,
) : EnhancerBackend {
    private val stft = StftProcessor(GtcrnModel.FFT_SIZE, GtcrnModel.HOP_SIZE)

    private val inputSpectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
    private val enhancedSpectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
    private val mixFrame = FloatArray(GtcrnModel.SPECTRUM_SIZE)
    private val enhancedFrame = FloatArray(GtcrnModel.SPECTRUM_SIZE)

    private var smoothedInferenceMs = 0f

    override val modelSampleRate: Int = GtcrnModel.SAMPLE_RATE
    override val hopSize: Int = GtcrnModel.HOP_SIZE

    override fun process(input: FloatArray, output: FloatArray) {
        require(input.size == hopSize && output.size == hopSize)
        stft.analyze(input, inputSpectrum)
        for (bin in 0 until GtcrnModel.BIN_COUNT) {
            mixFrame[bin * 2] = inputSpectrum[bin]
            mixFrame[bin * 2 + 1] = inputSpectrum[bin + GtcrnModel.BIN_COUNT]
        }

        val startedAt = System.nanoTime()
        hop.accept(mixFrame, enhancedFrame)
        trackInferenceTime(startedAt)

        for (bin in 0 until GtcrnModel.BIN_COUNT) {
            enhancedSpectrum[bin] = enhancedFrame[bin * 2]
            enhancedSpectrum[bin + GtcrnModel.BIN_COUNT] = enhancedFrame[bin * 2 + 1]
        }
        stft.synthesize(enhancedSpectrum, output)
        limit(output)
    }

    override fun inferenceMs(): Float = smoothedInferenceMs

    override fun close() = session.close()

    private fun trackInferenceTime(startedAtNanos: Long) {
        val inferenceMs = (System.nanoTime() - startedAtNanos) / 1_000_000f
        smoothedInferenceMs = if (smoothedInferenceMs == 0f) inferenceMs
            else smoothedInferenceMs * 0.9f + inferenceMs * 0.1f
    }

    /** The model can overshoot on a sudden onset; keep its voice under full scale. */
    private fun limit(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = maxOf(peak, abs(sample))
        if (peak > 0.98f) {
            val scale = 0.98f / peak
            for (index in frame.indices) frame[index] *= scale
        }
    }
}
