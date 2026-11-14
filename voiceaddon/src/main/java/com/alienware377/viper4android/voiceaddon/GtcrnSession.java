/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * as carried by the evoeram fork of RootlessJamesDSP. Copyright (c) 2026 André Naumann.
 * The model it runs is GTCRN by Rong Xiaobin (https://github.com/Xiaobin-Rong/gtcrn), MIT licence,
 * Copyright (c) 2024 Rong Xiaobin. See THIRD_PARTY_NOTICES.md for both notices.
 */
package com.alienware377.viper4android.voiceaddon;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Streaming GTCRN: one frame of spectrum in, the voice's spectrum out, with the model's
 * convolution, attention and recurrent caches carried from one frame to the next.
 *
 * Every tensor, inputs and outputs alike, wraps a direct buffer made once, and the outputs are
 * handed to ONNX Runtime pinned, so a hop costs no native tensor and no copy of the caches. There
 * are two sets of caches used in turn, because one tensor cannot be an input and an output of the
 * same run: a hop reads one set and the model writes the next state into the other.
 *
 * Not thread safe; the app gives each channel its own session.
 */
final class GtcrnSession implements BiConsumer<float[], float[]>, AutoCloseable {
    static final int BIN_COUNT = 257;
    static final int FRAME_FLOATS = BIN_COUNT * 2;

    private static final long[] MIX_SHAPE = {1, BIN_COUNT, 1, 2};

    /** The model's caches; each has an input of this name and an output named with "_out". */
    private static final String[] CACHE_NAMES = {"conv_cache", "tra_cache", "inter_cache"};
    private static final long[][] CACHE_SHAPES = {
            {2, 1, 16, 16, 33},
            {2, 3, 1, 1, 16},
            {2, 1, 33, 16},
    };

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    private final FloatBuffer mixBuffer = directFloatBuffer(FRAME_FLOATS);
    private final FloatBuffer enhancedBuffer = directFloatBuffer(FRAME_FLOATS);
    private final FloatBuffer[][] cacheBuffers = new FloatBuffer[2][CACHE_NAMES.length];

    private final List<OnnxTensor> tensors = new ArrayList<>();

    /** Inputs and pinned outputs for a hop that reads cache set 0, and for one that reads set 1. */
    private final List<Map<String, OnnxTensor>> inputs = new ArrayList<>(2);
    private final List<Map<String, OnnxTensor>> outputs = new ArrayList<>(2);
    private int cacheSet;
    private boolean closed;

    GtcrnSession(byte[] model) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(model, options);
        }
        try {
            OnnxTensor mix = tensor(mixBuffer, MIX_SHAPE);
            OnnxTensor enhanced = tensor(enhancedBuffer, MIX_SHAPE);
            OnnxTensor[][] caches = new OnnxTensor[2][CACHE_NAMES.length];
            for (int set = 0; set < 2; set++) {
                for (int index = 0; index < CACHE_NAMES.length; index++) {
                    cacheBuffers[set][index] = directFloatBuffer(elements(CACHE_SHAPES[index]));
                    caches[set][index] = tensor(cacheBuffers[set][index], CACHE_SHAPES[index]);
                }
            }
            for (int set = 0; set < 2; set++) {
                Map<String, OnnxTensor> in = new HashMap<>();
                Map<String, OnnxTensor> out = new HashMap<>();
                in.put("mix", mix);
                out.put("enh", enhanced);
                for (int index = 0; index < CACHE_NAMES.length; index++) {
                    in.put(CACHE_NAMES[index], caches[set][index]);
                    out.put(CACHE_NAMES[index] + "_out", caches[1 - set][index]);
                }
                inputs.add(in);
                outputs.add(out);
            }
        } catch (OrtException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Runs one hop: {@link #FRAME_FLOATS} floats of [mix] in, the voice into [enhanced]. */
    @Override
    public void accept(float[] mix, float[] enhanced) {
        mixBuffer.clear();
        mixBuffer.put(mix, 0, FRAME_FLOATS);
        mixBuffer.rewind();
        try {
            // The result only lists the pinned tensors, so closing it frees nothing of ours.
            session.run(inputs.get(cacheSet), outputs.get(cacheSet)).close();
        } catch (OrtException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        cacheSet = 1 - cacheSet;
        enhancedBuffer.rewind();
        enhancedBuffer.get(enhanced, 0, FRAME_FLOATS);
        enhancedBuffer.rewind();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (OnnxTensor tensor : tensors) tensor.close();
        tensors.clear();
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private OnnxTensor tensor(FloatBuffer buffer, long[] shape) throws OrtException {
        OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer, shape);
        tensors.add(tensor);
        return tensor;
    }

    private static int elements(long[] shape) {
        long count = 1;
        for (long dimension : shape) count *= dimension;
        return (int) count;
    }

    private static FloatBuffer directFloatBuffer(int size) {
        // Zeroed on allocation, which is the state the caches start from.
        return ByteBuffer.allocateDirect(size * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
