/*
 * Based on the hop framing of the evoeram fork of RootlessJamesDSP
 * (https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP), GPLv3.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.ceil

/**
 * Runs one channel of the stream through the model and back, at a fixed delay.
 *
 * The capture loop hands over blocks whose size has nothing to do with the model's hop. Each block
 * is brought down to the model rate, cut into hops (a partial hop waits for the next block), and
 * every finished hop goes through [stage], which returns the scaled voice and the share of dry
 * signal to keep for each of its samples. The voice is brought back up to the device rate; the dry
 * part is read from a history of the full-band input at exactly the delay the voice took to get
 * there, so the two line up sample for sample and nothing above the model's 8 kHz is lost.
 * Nothing is limited here: a boosted voice can go over full scale, and the engine's limiter, which
 * runs next, deals with it, so loud masters at neutral settings come out untouched.
 *
 * The delay is the same for every block size: a block's own input is always processed before its
 * output is read, and the output is read [leadFrames] behind the input, which is just over one hop
 * at the device rate (the longest a sample can wait for its hop to fill). Nothing is held back to
 * absorb jitter because nothing here is asynchronous. In total that is [latencyFrames], about 34 ms
 * at 44.1 and 48 kHz.
 *
 * Long blocks are processed in slices of [MAX_SLICE] frames, so the rings below never grow, and
 * nothing is allocated once the pipeline is built.
 */
internal class HopPipeline(
    val sampleRate: Int,
    private val modelSampleRate: Int,
    private val hopSize: Int,
    private val stage: Stage,
) {
    fun interface Stage {
        /**
         * Processes one hop of [dry] model-rate audio into [voice] and [keep] (one value per
         * sample, see [IsolationIntensityMixer]) and returns how many hops the voice trails [dry].
         */
        fun process(dry: FloatArray, voice: FloatArray, keep: FloatArray, settings: VoiceIsolationSettings): Int
    }

    private val decimator = SincResampler(sampleRate, modelSampleRate)

    /** Delays below are counted in 1/[delayUnits] of a frame; one model sample is [delayPerModelSample] of them. */
    private val delayUnits = modelSampleRate / SincResampler.gcd(modelSampleRate, sampleRate)
    private val delayPerModelSample = sampleRate / SincResampler.gcd(modelSampleRate, sampleRate)

    /**
     * The voice path's delay for a voice that trails its dry hop by [hops]: the decimator's delay,
     * plus the interpolator's and the hops', measured at the device rate. At 44.1 kHz that is not a
     * whole number of frames.
     */
    private fun voiceDelayUnits(hops: Int): Long =
        decimator.delay.toLong() * delayUnits +
            (SincResampler.halfWidthFor(modelSampleRate, sampleRate) + hopSize.toLong() * hops) * delayPerModelSample

    /**
     * The interpolator is moved by the fraction of a frame left over in the usual case of a voice
     * one hop late, so that the dry part, read from whole frames, lines up with it exactly.
     */
    private val interpolator = SincResampler(modelSampleRate, sampleRate,
        phaseOffset = (voiceDelayUnits(1) % delayUnits).toInt())
    private val dry = FloatArray(hopSize)
    private val voice = FloatArray(hopSize)
    private val keep = FloatArray(hopSize)
    private var dryFilled = 0

    /** Full-band input, indexed by frames taken since the last reset. */
    private val history = FloatArray(HISTORY_SIZE)
    private var inputFrames = 0L

    /** The voice at the device rate, indexed like the output. */
    private val voiceRing = FloatArray(VOICE_SIZE)
    private var voiceFrames = 0L

    /** Per model-rate sample of voice: the dry share; per hop: the voice's delay in hops. */
    private val keepRing = FloatArray(KEEP_SIZE)
    private val hopDelays = IntArray(HOP_DELAY_SIZE)
    private var mixedSamples = 0L

    private var outputFrames = 0L

    /** Frames by which the output is read behind the input: one hop at the device rate, rounded up. */
    val leadFrames: Int = ceil(hopSize.toDouble() * sampleRate / modelSampleRate).toInt() + 2

    /**
     * The voice path's delay in whole frames for each number of hops the voice can be late by.
     * Exact for one hop, the usual case; otherwise the nearest frame (within half of one).
     */
    private val voiceDelays = LongArray(IsolationIntensityMixer.MAX_DELAY_HOPS + 1) { hops ->
        val units = voiceDelayUnits(hops) - interpolator.phaseOffset
        (units + delayUnits / 2) / delayUnits
    }

    @Volatile
    private var lastDelayHops = 1

    /** Complete hops handed to [stage]; used to show that no audio is dropped. */
    var processedHops = 0L
        private set

    /** Output frames that were not ready when read; zero unless the framing is broken. */
    var underruns = 0L
        private set

    /** Frames from a sample going in to it coming out. */
    val latencyFrames: Long
        get() = leadFrames + voiceDelays[lastDelayHops.coerceIn(0, voiceDelays.lastIndex)]

    val latencyMs: Float
        get() = latencyFrames * 1000f / sampleRate

    init {
        require(sampleRate > 0 && modelSampleRate > 0 && hopSize > 0)
        prime()
    }

    // Both resamplers need a full kernel of history before their first output. Zeros pushed up
    // front make every later hop complete and fix the delay at exactly SincResampler.delay.
    private fun prime() {
        val zeros = FloatArray(maxOf(decimator.primingInputSamples, interpolator.primingInputSamples))
        decimator.push(zeros, 0, 1, decimator.primingInputSamples)
        interpolator.push(zeros, 0, 1, interpolator.primingInputSamples)
    }

    /**
     * Takes [frames] samples of one channel from [buffer] (interleaved stereo, starting at
     * [offset] + [channel]) and writes the same number back in their place.
     */
    fun process(buffer: FloatArray, channel: Int, frames: Int, offset: Int, settings: VoiceIsolationSettings) {
        var done = 0
        while (done < frames) {
            val count = minOf(frames - done, MAX_SLICE)
            processSlice(buffer, offset + channel + 2 * done, count, settings)
            done += count
        }
    }

    private fun processSlice(buffer: FloatArray, first: Int, frames: Int, settings: VoiceIsolationSettings) {
        // A NaN or a wild value would end up in the model's recurrent state and stay there.
        var index = first
        for (frame in 0 until frames) {
            val sample = buffer[index]
            val safe = if (sample.isNaN()) 0f else sample.coerceIn(-INPUT_LIMIT, INPUT_LIMIT)
            buffer[index] = safe
            history[((inputFrames + frame) and HISTORY_MASK).toInt()] = safe
            index += 2
        }
        inputFrames += frames
        decimator.push(buffer, first, 2, frames)

        while (true) {
            dryFilled += decimator.poll(dry, dryFilled, 1, hopSize - dryFilled)
            if (dryFilled < hopSize) break
            dryFilled = 0

            val delayHops = stage.process(dry, voice, keep, settings)
                .coerceIn(0, IsolationIntensityMixer.MAX_DELAY_HOPS)
            lastDelayHops = delayHops
            for (sample in 0 until hopSize) keepRing[((mixedSamples + sample) and KEEP_MASK).toInt()] = keep[sample]
            hopDelays[((mixedSamples / hopSize) and HOP_DELAY_MASK).toInt()] = delayHops
            interpolator.push(voice, 0, 1, hopSize)
            mixedSamples += hopSize
            processedHops++
        }
        drainInterpolator()

        index = first
        for (frame in 0 until frames) {
            val position = outputFrames - leadFrames
            var out = 0f
            if (position >= 0) {
                if (position < voiceFrames) {
                    // The model-rate sample this output is centred on gives its dry share and delay.
                    val mixed = position * modelSampleRate / sampleRate - interpolator.delay
                    val keepDry: Float
                    val delay: Long
                    if (mixed >= 0) {
                        keepDry = keepRing[(mixed and KEEP_MASK).toInt()]
                        delay = voiceDelays[hopDelays[((mixed / hopSize) and HOP_DELAY_MASK).toInt()]]
                    } else {
                        keepDry = 1f
                        delay = voiceDelays[lastDelayHops]
                    }
                    val source = position - delay
                    val full = if (source >= 0 && source > inputFrames - HISTORY_SIZE)
                        history[(source and HISTORY_MASK).toInt()] else 0f
                    out = voiceRing[(position and VOICE_MASK).toInt()] + keepDry * full
                } else {
                    underruns++
                }
            }
            buffer[index] = out
            outputFrames++
            index += 2
        }
    }

    private fun drainInterpolator() {
        val oldestUnread = outputFrames - leadFrames
        while (true) {
            val free = VOICE_SIZE - (voiceFrames - oldestUnread)
            val tail = (voiceFrames and VOICE_MASK).toInt()
            val requested = minOf(free, (VOICE_SIZE - tail).toLong()).toInt()
            if (requested <= 0) return
            val produced = interpolator.poll(voiceRing, tail, 1, requested)
            voiceFrames += produced
            if (produced < requested) return
        }
    }

    fun reset() {
        decimator.reset()
        interpolator.reset()
        history.fill(0f)
        voiceRing.fill(0f)
        keepRing.fill(0f)
        hopDelays.fill(0)
        dryFilled = 0
        inputFrames = 0
        voiceFrames = 0
        mixedSamples = 0
        outputFrames = 0
        processedHops = 0
        underruns = 0
        lastDelayHops = 1
        prime()
    }

    companion object {
        /** Longest slice processed in one pass; the rings below are sized from it. */
        const val MAX_SLICE = 4096
        private const val INPUT_LIMIT = 8f
        private const val HISTORY_SIZE = 16384
        private const val HISTORY_MASK = (HISTORY_SIZE - 1).toLong()
        private const val VOICE_SIZE = 16384
        private const val VOICE_MASK = (VOICE_SIZE - 1).toLong()
        private const val KEEP_SIZE = 8192
        private const val KEEP_MASK = (KEEP_SIZE - 1).toLong()
        private const val HOP_DELAY_SIZE = 64
        private const val HOP_DELAY_MASK = (HOP_DELAY_SIZE - 1).toLong()
    }
}
