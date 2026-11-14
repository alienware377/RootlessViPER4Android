/*
 * Ported from android-realtime-voice-isolation by André Naumann
 * (https://github.com/sk2andy/android-realtime-voice-isolation), MIT licence,
 * by way of the evoeram fork of RootlessJamesDSP (GPLv3), which made it a stage of the rootless
 * playback loop. Copyright (c) 2026 André Naumann. See THIRD_PARTY_NOTICES.md for the full notice.
 */
package me.timschneeberger.rootlessjamesdsp.voiceisolation

import timber.log.Timber
import kotlin.math.log10

/**
 * The voice isolation stage for one stream at one sample rate: two channels, each with its own
 * model session, framing and mixer.
 *
 * Everything is built in the constructor, which loads the model and so belongs on a background
 * thread; the audio thread only ever calls [processFloat] or [processShort]. A model that cannot
 * be loaded throws from the constructor, and the caller carries on without the stage.
 *
 * The left channel runs on a worker thread while the caller does the right one: two inferences
 * one after the other per hop would double the time a block takes. The hand-over is a lock and a
 * counter rather than an executor, which would allocate a task per block on the audio thread.
 */
internal class VoiceIsolationProcessor(
    val sampleRate: Int,
    createBackend: () -> EnhancerBackend,
) : AutoCloseable {
    @Volatile
    var settings: VoiceIsolationSettings = VoiceIsolationSettings()

    /** Set when a block failed; the stage then passes audio through untouched. */
    @Volatile
    var failure: Throwable? = null
        private set

    /** Called once, on the audio thread, when [failure] is first set. */
    @Volatile
    var onFailure: ((Throwable) -> Unit)? = null

    private val channels: Array<Channel>

    init {
        val built = ArrayList<Channel>(2)
        try {
            repeat(2) { built += Channel(sampleRate, createBackend()) }
        } catch (e: Throwable) {
            built.forEach { it.close() }
            throw e
        }
        channels = built.toTypedArray()
    }

    private val hopBudgetMs: Float =
        channels[0].backend.hopSize * 1000f / channels[0].backend.modelSampleRate

    /** Delay the stage adds, in milliseconds. */
    val latencyMs: Float get() = channels[0].framing.latencyMs

    private var scratch = FloatArray(SCRATCH_SAMPLES)
    private var outputPeak = 0f

    private val workerLock = Object()
    private var blockSeq = 0L
    private var doneSeq = 0L
    private var workerFailure: Throwable? = null
    private var workerClosed = false
    private var taskBuffer: FloatArray? = null
    private var taskFrames = 0
    private var taskOffset = 0
    private var taskSettings: VoiceIsolationSettings? = null

    private val worker = Thread({ workerLoop() }, "voice-isolation-left").apply {
        isDaemon = true
        start()
    }

    private fun workerLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            // Not fatal: the channel still runs, just at normal priority.
        }
        var lastSeq = 0L
        while (true) {
            val buffer: FloatArray
            val frames: Int
            val offset: Int
            val blockSettings: VoiceIsolationSettings
            synchronized(workerLock) {
                while (!workerClosed && blockSeq == lastSeq) {
                    try {
                        workerLock.wait()
                    } catch (e: InterruptedException) {
                        workerClosed = true
                    }
                }
                if (workerClosed) return
                lastSeq = blockSeq
                buffer = taskBuffer ?: return
                frames = taskFrames
                offset = taskOffset
                blockSettings = taskSettings ?: return
            }
            var failed: Throwable? = null
            try {
                channels[0].framing.process(buffer, 0, frames, offset, blockSettings)
            } catch (e: Throwable) {
                failed = e
            }
            // Always report done, so a failure here cannot leave the audio thread waiting.
            synchronized(workerLock) {
                if (failed != null) workerFailure = failed
                doneSeq = lastSeq
                workerLock.notifyAll()
            }
        }
    }

    /** Processes [frames] interleaved stereo frames of [buffer] from [offset], in place. */
    fun processFloat(buffer: FloatArray, offset: Int, frames: Int) {
        if (failure != null || frames <= 0) return
        synchronized(this) {
            if (failure != null) return
            runBlock(buffer, offset, frames)
        }
    }

    /** As [processFloat], for 16-bit samples. */
    fun processShort(buffer: ShortArray, offset: Int, frames: Int) {
        if (failure != null || frames <= 0) return
        synchronized(this) {
            if (failure != null) return
            val samples = frames * 2
            // The capture block size is set once per session, so this grows at most once.
            if (scratch.size < samples) scratch = FloatArray(samples)
            PcmConverter.toFloatRange(buffer, scratch, offset, samples)
            if (runBlock(scratch, 0, frames))
                PcmConverter.toShortRange(scratch, buffer, offset, samples)
        }
    }

    private fun runBlock(buffer: FloatArray, offset: Int, frames: Int): Boolean {
        val blockSettings = settings
        val seq: Long
        synchronized(workerLock) {
            if (workerClosed) return false
            taskBuffer = buffer
            taskFrames = frames
            taskOffset = offset
            taskSettings = blockSettings
            seq = ++blockSeq
            workerLock.notifyAll()
        }
        var failed: Throwable? = null
        try {
            channels[1].framing.process(buffer, 1, frames, offset, blockSettings)
        } catch (e: Throwable) {
            failed = e
        }
        var interrupted = false
        synchronized(workerLock) {
            // Wait for the left channel even if the right one failed, or the next block would
            // overwrite the task while the worker is still inside the model.
            while (workerFailure == null && doneSeq < seq && !workerClosed) {
                try {
                    workerLock.wait()
                } catch (e: InterruptedException) {
                    // The capture thread is being stopped: give up on this block.
                    Thread.currentThread().interrupt()
                    interrupted = true
                    break
                }
            }
            if (failed == null) failed = workerFailure
            workerFailure = null
            taskBuffer = null
            taskSettings = null
        }
        if (failed != null) {
            fail(failed)
            return false
        }
        if (interrupted) return false

        var peak = outputPeak
        for (index in offset until offset + frames * 2) {
            val sample = if (buffer[index] < 0f) -buffer[index] else buffer[index]
            if (sample > peak) peak = sample
        }
        outputPeak = peak
        return true
    }

    private fun fail(e: Throwable) {
        if (failure != null) return
        failure = e
        Timber.e(e, "Voice isolation stopped after a processing failure")
        onFailure?.invoke(e)
    }

    fun inferenceMs(): Float = channels.maxOf { it.backend.inferenceMs() }

    fun underruns(): Long = channels.sumOf { it.framing.underruns }

    fun speechDetected(): Boolean = channels.any { it.mixer.speechDetected }

    /** One line for the log: how close inference runs to its real-time budget, and what it does. */
    fun stats(): String {
        val inference = inferenceMs()
        val peak = outputPeak
        outputPeak = 0f
        return "voice isolation: inference %.2f ms of %.2f ms, latency %.1f ms, speech %s, out %.0f dBFS, late %d".format(
            inference, hopBudgetMs, latencyMs, speechDetected(),
            if (peak > 0f) 20f * log10(peak) else -120f, underruns(),
        )
    }

    override fun close() {
        synchronized(workerLock) {
            workerClosed = true
            workerLock.notifyAll()
        }
        synchronized(this) {
            worker.join(WORKER_JOIN_MS)
            if (worker.isAlive) worker.interrupt()
            channels.forEach { it.close() }
        }
    }

    private class Channel(sampleRate: Int, val backend: EnhancerBackend) : AutoCloseable {
        private val isolated = FloatArray(backend.hopSize)
        val mixer = IsolationIntensityMixer(backend.hopSize, backend.modelSampleRate)
        val framing = HopPipeline(sampleRate, backend.modelSampleRate, backend.hopSize) { dry, voice, keep, settings ->
            backend.process(dry, isolated)
            mixer.mix(dry, isolated, voice, keep, settings)
            mixer.detectedDelayHops
        }

        override fun close() = backend.close()
    }

    companion object {
        /** Two channels of the largest capture block the settings allow. */
        private const val SCRATCH_SAMPLES = 16384
        private const val WORKER_JOIN_MS = 500L
    }
}
