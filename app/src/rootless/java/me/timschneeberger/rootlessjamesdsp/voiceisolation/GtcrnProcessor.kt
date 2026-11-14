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
 * The input and cache tensors wrap direct buffers made once, so a hop allocates nothing but what
 * ONNX Runtime returns. The model gives the voice only; [IsolationIntensityMixer] makes the
 * background from it.
 */
internal class GtcrnProcessor(model: ByteArray) : EnhancerBackend {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val stft = StftProcessor(GtcrnModel.FFT_SIZE, GtcrnModel.HOP_SIZE)

    private val mixBuffer = directFloatBuffer(GtcrnModel.SPECTRUM_SIZE)
    private val convolutionCache = directFloatBuffer(CONVOLUTION_CACHE_SIZE)
    private val attentionCache = directFloatBuffer(ATTENTION_CACHE_SIZE)
    private val recurrentCache = directFloatBuffer(RECURRENT_CACHE_SIZE)

    private val mixTensor: OnnxTensor
    private val convolutionTensor: OnnxTensor
    private val attentionTensor: OnnxTensor
    private val recurrentTensor: OnnxTensor
    private val inputs: Map<String, OnnxTensor>

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
            mixTensor = OnnxTensor.createTensor(environment, mixBuffer, MIX_SHAPE)
            convolutionTensor = OnnxTensor.createTensor(environment, convolutionCache, CONVOLUTION_SHAPE)
            attentionTensor = OnnxTensor.createTensor(environment, attentionCache, ATTENTION_SHAPE)
            recurrentTensor = OnnxTensor.createTensor(environment, recurrentCache, RECURRENT_SHAPE)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
        inputs = mapOf(
            "mix" to mixTensor,
            "conv_cache" to convolutionTensor,
            "tra_cache" to attentionTensor,
            "inter_cache" to recurrentTensor,
        )
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
        session.run(inputs).use { result ->
            copyTensor(result, "enh", interleavedSpectrum)
            copyTensor(result, "conv_cache_out", convolutionCache)
            copyTensor(result, "tra_cache_out", attentionCache)
            copyTensor(result, "inter_cache_out", recurrentCache)
        }
        trackInferenceTime(startedAt)

        for (bin in 0 until GtcrnModel.BIN_COUNT) {
            enhancedSpectrum[bin] = interleavedSpectrum[bin * 2]
            enhancedSpectrum[bin + GtcrnModel.BIN_COUNT] = interleavedSpectrum[bin * 2 + 1]
        }
        stft.synthesize(enhancedSpectrum, output)
        limit(output)
    }

    override fun inferenceMs(): Float = smoothedInferenceMs

    override fun reset() {
        clearBuffer(convolutionCache)
        clearBuffer(attentionCache)
        clearBuffer(recurrentCache)
        stft.reset()
        smoothedInferenceMs = 0f
    }

    override fun close() {
        mixTensor.close()
        convolutionTensor.close()
        attentionTensor.close()
        recurrentTensor.close()
        session.close()
    }

    private fun trackInferenceTime(startedAtNanos: Long) {
        val inferenceMs = (System.nanoTime() - startedAtNanos) / 1_000_000f
        smoothedInferenceMs = if (smoothedInferenceMs == 0f) inferenceMs
            else smoothedInferenceMs * 0.9f + inferenceMs * 0.1f
    }

    private fun output(result: OrtSession.Result, name: String): FloatBuffer {
        val tensor = result.get(name).orElseThrow { IllegalStateException("The model has no output $name") }
        return (tensor as OnnxTensor).floatBuffer.also { it.rewind() }
    }

    private fun copyTensor(result: OrtSession.Result, name: String, destination: FloatArray) {
        output(result, name).get(destination)
    }

    private fun copyTensor(result: OrtSession.Result, name: String, destination: FloatBuffer) {
        destination.clear()
        destination.put(output(result, name))
        destination.rewind()
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
        private const val CONVOLUTION_CACHE_SIZE = 2 * 1 * 16 * 16 * 33
        private const val ATTENTION_CACHE_SIZE = 2 * 3 * 1 * 1 * 16
        private const val RECURRENT_CACHE_SIZE = 2 * 1 * 33 * 16

        private val MIX_SHAPE = longArrayOf(1, GtcrnModel.BIN_COUNT.toLong(), 1, 2)
        private val CONVOLUTION_SHAPE = longArrayOf(2, 1, 16, 16, 33)
        private val ATTENTION_SHAPE = longArrayOf(2, 3, 1, 1, 16)
        private val RECURRENT_SHAPE = longArrayOf(2, 1, 33, 16)
    }
}
