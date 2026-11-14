package com.alienware377.viper4android.voiceaddon;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Runs the shipped model on the desktop build of ONNX Runtime, the same version and Java API as
 * the Android one in the add-on.
 */
public class GtcrnSessionTest {
    private static final long[] MIX_SHAPE = {1, GtcrnSession.BIN_COUNT, 1, 2};
    private static final String[] CACHE_NAMES = {"conv_cache", "tra_cache", "inter_cache"};
    private static final long[][] CACHE_SHAPES = {{2, 1, 16, 16, 33}, {2, 3, 1, 1, 16}, {2, 1, 33, 16}};

    @BeforeClass
    public static void runtimeLoads() {
        try {
            OrtEnvironment.getEnvironment();
        } catch (Throwable e) {
            // No desktop runtime for this machine's platform; the Android build is not affected.
            Assume.assumeNoException(e);
        }
    }

    private static float[][] noiseFrames(int count) {
        Random random = new Random(7);
        float[][] frames = new float[count][GtcrnSession.FRAME_FLOATS];
        for (float[] frame : frames) {
            for (int i = 0; i < frame.length; i++) frame[i] = (float) random.nextGaussian();
        }
        return frames;
    }

    /**
     * The plain way to run it: fresh tensors every hop, each cache output fed back as the next
     * hop's input. What the session does with pinned outputs and two cache sets must match it.
     */
    private static float[][] plainRun(byte[] model, float[][] frames) throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        float[][] caches = new float[CACHE_NAMES.length][];
        for (int i = 0; i < caches.length; i++) {
            long count = 1;
            for (long dimension : CACHE_SHAPES[i]) count *= dimension;
            caches[i] = new float[(int) count];
        }
        float[][] voice = new float[frames.length][GtcrnSession.FRAME_FLOATS];
        try (OrtSession session = environment.createSession(model, new OrtSession.SessionOptions())) {
            for (int hop = 0; hop < frames.length; hop++) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("mix", OnnxTensor.createTensor(environment, FloatBuffer.wrap(frames[hop]), MIX_SHAPE));
                for (int i = 0; i < caches.length; i++) {
                    inputs.put(CACHE_NAMES[i],
                            OnnxTensor.createTensor(environment, FloatBuffer.wrap(caches[i]), CACHE_SHAPES[i]));
                }
                try (OrtSession.Result result = session.run(inputs)) {
                    ((OnnxTensor) result.get("enh").get()).getFloatBuffer().get(voice[hop]);
                    for (int i = 0; i < caches.length; i++) {
                        ((OnnxTensor) result.get(CACHE_NAMES[i] + "_out").get()).getFloatBuffer().get(caches[i]);
                    }
                } finally {
                    for (OnnxTensor tensor : inputs.values()) tensor.close();
                }
            }
        }
        return voice;
    }

    @Test
    public void pinnedRunsMatchPlainRuns() throws Exception {
        byte[] model = ModelAssetTest.model();
        float[][] frames = noiseFrames(80);
        float[][] expected = plainRun(model, frames);

        float[] out = new float[GtcrnSession.FRAME_FLOATS];
        float energy = 0f;
        try (GtcrnSession session = new GtcrnSession(model)) {
            for (int hop = 0; hop < frames.length; hop++) {
                session.accept(frames[hop], out);
                assertArrayEquals("hop " + hop, expected[hop], out, 1e-5f);
                for (float value : out) {
                    assertTrue(Float.isFinite(value));
                    energy += value * value;
                }
            }
        }
        // The caches carry state: a model that ignored them would still give finite output.
        assertTrue("the model gave nothing back", energy > 0f);
    }

    @Test
    public void openHandsBackOnlyPlatformTypes() throws Exception {
        Object handle = VoiceAddon.open(ModelAssetTest.model(), VoiceAddon.API_VERSION);
        try {
            assertTrue(handle instanceof BiConsumer);
            assertTrue(handle instanceof AutoCloseable);
            @SuppressWarnings("unchecked")
            BiConsumer<float[], float[]> hop = (BiConsumer<float[], float[]>) handle;
            float[] out = new float[GtcrnSession.FRAME_FLOATS];
            hop.accept(new float[GtcrnSession.FRAME_FLOATS], out);
            for (float value : out) assertTrue(Float.isFinite(value));
        } finally {
            ((AutoCloseable) handle).close();
        }
    }

    @Test
    public void openRefusesAnAppOnAnotherInterface() throws Exception {
        byte[] model = ModelAssetTest.model();
        for (int other : new int[] {VoiceAddon.API_VERSION - 1, VoiceAddon.API_VERSION + 1}) {
            try {
                VoiceAddon.open(model, other);
                fail("opened for interface " + other);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains(Integer.toString(other)));
            }
        }
    }

    @Test
    public void closingTwiceIsHarmless() throws Exception {
        GtcrnSession session = new GtcrnSession(ModelAssetTest.model());
        session.close();
        session.close();
        assertEquals(514, GtcrnSession.FRAME_FLOATS);
    }
}
