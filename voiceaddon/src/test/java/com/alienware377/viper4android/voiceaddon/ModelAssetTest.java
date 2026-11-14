package com.alienware377.viper4android.voiceaddon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

public class ModelAssetTest {
    /** stream/onnx_models/gtcrn.onnx from Xiaobin-Rong/gtcrn at 3862c44, unmodified. */
    static final String MODEL_SHA256 = "f648b02f2d7ff96ebcb0eec2219688a08ed12fe7e3d50f248605a90eba8cad17";

    static byte[] model() throws Exception {
        // Gradle runs unit tests from the module directory, some IDE setups from the project root.
        for (String path : new String[] {"src/main/assets/gtcrn.onnx", "voiceaddon/src/main/assets/gtcrn.onnx"}) {
            File file = new File(path);
            if (file.isFile()) return Files.readAllBytes(file.toPath());
        }
        throw new AssertionError("gtcrn.onnx not found");
    }

    static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** The app refuses any other file (GtcrnModel.ASSET_SHA256 there), so the two must not drift apart. */
    @Test
    public void theShippedModelIsTheOneTheAppChecksFor() throws Exception {
        byte[] bytes = model();
        assertEquals(352_084, bytes.length);
        assertEquals(MODEL_SHA256, sha256(bytes));
    }
}
