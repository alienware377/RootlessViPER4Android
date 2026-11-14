package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * A streaming speech enhancer that takes and returns one hop of mono audio at a time.
 *
 * The voice it returns is the one from the hop before the hop it was given, because the spectrum
 * analysed from hop t is synthesised as audio t-1. [IsolationIntensityMixer] finds that delay
 * itself and holds the dry signal back to match.
 */
internal interface EnhancerBackend : AutoCloseable {
    /** Sample rate the model takes and returns. */
    val modelSampleRate: Int

    /** Samples at [modelSampleRate] in one [process] call. */
    val hopSize: Int

    /** Processes one hop; [output] receives [hopSize] samples of the voice estimate. */
    fun process(input: FloatArray, output: FloatArray)

    /** Smoothed wall-clock time of one inference, in milliseconds. */
    fun inferenceMs(): Float

    /** Drops the recurrent state and filter history, as if freshly made. */
    fun reset()

    override fun close()
}
