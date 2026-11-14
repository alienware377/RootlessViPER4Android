package com.alienware377.viper4android.voiceaddon;

/**
 * The one class RootlessViPER4Android looks up in this package.
 *
 * The app reaches it through the add-on's own class loader, so it cannot see any class of the
 * app's and the app cannot see the session class here. Everything that crosses is a platform
 * type: a byte array in, and back an object the app only knows as a
 * {@code java.util.function.BiConsumer<float[], float[]>} (one hop of the model) and an
 * {@link AutoCloseable} (frees the session).
 *
 * The hop: 514 floats in, the 257 bins of one 512-point frame at 16 kHz as interleaved real and
 * imaginary pairs, and 514 floats of the voice out in the same layout. That is the model's own
 * tensor layout; the transform around it stays in the app.
 *
 * Anything that changes this contract raises {@link #API_VERSION}.
 */
public final class VoiceAddon {
    /**
     * The contract above. The app reads it from the manifest before it loads any code from here,
     * checks this field once it has, and passes its own number to {@link #open}.
     */
    public static final int API_VERSION = BuildConfig.API_VERSION;

    private VoiceAddon() {
    }

    /**
     * Opens one model session. Slow (tens of milliseconds), so the app calls it off the audio
     * thread, once per channel.
     *
     * @param model the GTCRN model, which the app reads from this package's assets and checks
     * @param callerApiVersion the app's {@link #API_VERSION}
     */
    public static Object open(byte[] model, int callerApiVersion) throws Exception {
        if (callerApiVersion != API_VERSION) {
            throw new IllegalArgumentException("The app uses add-on interface " + callerApiVersion
                    + " and this add-on " + API_VERSION);
        }
        return new GtcrnSession(model);
    }
}
