/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * The model it runs is GTCRN by Rong Xiaobin (https://github.com/Xiaobin-Rong/gtcrn), MIT licence,
 * Copyright (c) 2024 Rong Xiaobin. See THIRD_PARTY_NOTICES.md for both notices.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Streaming GTCRN: an STFT frame in, the enhanced voice spectrum out, with the model's
 * convolution, attention and recurrent caches carried from one frame to the next.
 *
 * Every tensor, inputs and outputs alike, wraps a direct buffer made once, and the outputs are
 * handed to ONNX Runtime pinned, so a hop costs no native tensor and no copy of the caches. There
 * are two sets of caches used in turn: a hop reads one and the model writes the next state into
 * the other. The model gives the voice only; [IsolationIntensityMixer] makes the background from it.
 */
internal class GtcrnProcessor(model: ByteArray) : EnhancerBackend {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val stft = StftProcessor(GtcrnModel.FFT_SIZE, GtcrnModel.HOP_SIZE)

    private val mixBuffer = directFloatBuffer(GtcrnModel.SPECTRUM_SIZE)
    private val enhancedBuffer = directFloatBuffer(GtcrnModel.SPECTRUM_SIZE)
    private val cacheBuffers = Array(2) { CACHE_SHAPES.map { directFloatBuffer(it.fold(1L, Long::times).toInt()) } }

    private val tensors = ArrayList<OnnxTensor>()

    /** Inputs and pinned outputs for a hop that reads cache set 0 and for one that reads set 1. */
    private val inputs: Array<Map<String, OnnxTensor>>
    private val outputs: Array<Map<String, OnnxTensor>>
    private var cacheSet = 0

    private val inputSpectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
    private val enhancedSpectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)
    private val interleavedSpectrum = FloatArray(GtcrnModel.SPECTRUM_SIZE)

    private var smoothedInferenceMs = 0f

    override val modelSampleRate: Int = GtcrnModel.SAMPLE_RATE
    override val hopSize: Int = GtcrnModel.HOP_SIZE

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = options.use { environment.createSession(model, it) }
        try {
            val mix = tensor(mixBuffer, MIX_SHAPE)
            val enhanced = tensor(enhancedBuffer, MIX_SHAPE)
            val caches = Array(2) { set -> CACHE_SHAPES.mapIndexed { index, shape -> tensor(cacheBuffers[set][index], shape) } }
            inputs = Array(2) { set ->
                mapOf("mix" to mix) + CACHE_NAMES.indices.associate { CACHE_NAMES[it] to caches[set][it] }
            }
            outputs = Array(2) { set ->
                mapOf("enh" to enhanced) + CACHE_NAMES.indices.associate { CACHE_NAMES[it] + "_out" to caches[1 - set][it] }
            }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun process(input: FloatArray, output: FloatArray) {
        require(input.size == hopSize && output.size == hopSize)
        stft.analyze(input, inputSpectrum)

        mixBuffer.clear()
        for (bin in 0 until GtcrnModel.BIN_COUNT) {
            mixBuffer.put(inputSpectrum[bin])
            mixBuffer.put(inputSpectrum[bin + GtcrnModel.BIN_COUNT])
        }
        mixBuffer.rewind()

        val startedAt = System.nanoTime()
        // The result only lists the pinned tensors, so closing it frees nothing of ours.
        session.run(inputs[cacheSet], outputs[cacheSet]).close()
        cacheSet = 1 - cacheSet
        trackInferenceTime(startedAt)

        enhancedBuffer.rewind()
        enhancedBuffer.get(interleavedSpectrum)
        enhancedBuffer.rewind()
        for (bin in 0 until GtcrnModel.BIN_COUNT) {
            enhancedSpectrum[bin] = interleavedSpectrum[bin * 2]
            enhancedSpectrum[bin + GtcrnModel.BIN_COUNT] = interleavedSpectrum[bin * 2 + 1]
        }
        stft.synthesize(enhancedSpectrum, output)
        limit(output)
    }

    override fun inferenceMs(): Float = smoothedInferenceMs

    override fun reset() {
        cacheBuffers.forEach { set -> set.forEach(::clearBuffer) }
        cacheSet = 0
        stft.reset()
        smoothedInferenceMs = 0f
    }

    override fun close() {
        tensors.forEach { it.close() }
        tensors.clear()
        session.close()
    }

    private fun tensor(buffer: FloatBuffer, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, shape).also { tensors += it }

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

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun clearBuffer(buffer: FloatBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0f)
        buffer.rewind()
    }

    companion object {
        private val MIX_SHAPE = longArrayOf(1, GtcrnModel.BIN_COUNT.toLong(), 1, 2)

        /** The model's caches; each has an input of this name and an output named with "_out". */
        private val CACHE_NAMES = arrayOf("conv_cache", "tra_cache", "inter_cache")
        private val CACHE_SHAPES = listOf(
            longArrayOf(2, 1, 16, 16, 33),
            longArrayOf(2, 3, 1, 1, 16),
            longArrayOf(2, 1, 33, 16),
        )
    }
}
