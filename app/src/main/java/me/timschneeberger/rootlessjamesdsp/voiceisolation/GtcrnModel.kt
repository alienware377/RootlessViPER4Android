package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * The shape of the GTCRN speech model: 16 kHz audio in hops of 256 samples, analysed with a
 * 512-point sqrt-Hann STFT, 257 bins in and out.
 *
 * Kept apart from the ONNX code, which only the rootless build carries, so that the framing around
 * the model builds and is tested in every flavour.
 */
internal object GtcrnModel {
    const val SAMPLE_RATE = 16_000
    const val FFT_SIZE = 512
    const val HOP_SIZE = 256
    const val BIN_COUNT = FFT_SIZE / 2 + 1
    const val SPECTRUM_SIZE = BIN_COUNT * 2

    /** stream/onnx_models/gtcrn.onnx from Xiaobin-Rong/gtcrn, unmodified. */
    const val ASSET = "gtcrn.onnx"
    const val ASSET_SHA256 = "f648b02f2d7ff96ebcb0eec2219688a08ed12fe7e3d50f248605a90eba8cad17"
}
