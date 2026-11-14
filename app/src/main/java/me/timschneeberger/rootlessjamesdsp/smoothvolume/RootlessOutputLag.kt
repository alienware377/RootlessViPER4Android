package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlin.math.ceil

/**
 * How long rootless processing takes from a gain the engine has applied to the
 * speaker playing it, which is how long smooth volume waits before it raises
 * the held system index (see [SmoothVolumeController]).
 *
 * The processing service measures it. A gain applied now is first used on the
 * next block the service processes, so that is one block; then that block
 * waits behind whatever the output track says is still ahead of the speaker
 * once it is written: the track's own buffer, the mixer and the device's
 * latency, which on Bluetooth is often a couple of hundred milliseconds by
 * itself. The engine's walk to the new gain and a margin come on top. It is
 * never less than the fixed 250 ms that was used before it was measured.
 */
object RootlessOutputLag {
    const val FLOOR_MS = 250L
    /** The engine walks to a new gain with a 25 ms time constant; after three it is there. */
    private const val RAMP_MS = 75.0
    private const val MARGIN_MS = 40.0
    /** Longer than any real output; a reading past it comes from a track that just started again. */
    private const val MAX_QUEUE_MS = 2000.0
    /** How much of the way a lower reading moves the queue estimate, per reading. */
    private const val FALL = 0.05
    private const val CHECK_INTERVAL_NS = 200_000_000L
    /** A track's position settles after play(); readings from just after it are left out. */
    private const val SETTLE_NS = 500_000_000L

    @Volatile
    private var blockMs = 0.0
    @Volatile
    private var queueMs = 0.0

    fun lagMs(): Long = lagMs(blockMs, queueMs)

    fun lagMs(blockMs: Double, queueMs: Double): Long =
        maxOf(FLOOR_MS, ceil(blockMs + queueMs + RAMP_MS + MARGIN_MS).toLong())

    /**
     * The audio still ahead of the speaker, in ms, from an output timestamp:
     * [framesWritten] in all, frame [presentedFrame] heard at [presentedAtNs].
     * Null when the reading makes no sense.
     */
    fun queuedMs(framesWritten: Long, presentedFrame: Long, presentedAtNs: Long, nowNs: Long, sampleRate: Int): Double? {
        if (sampleRate <= 0) return null
        val playedSince = (nowNs - presentedAtNs) / 1e9 * sampleRate
        val ms = (framesWritten - presentedFrame - playedSince) * 1000.0 / sampleRate
        return ms.takeIf { it in 0.0..MAX_QUEUE_MS }
    }

    /**
     * A longer reading counts at once and a shorter one only slowly, so one
     * quick reading never shortens the wait while the output is really slower.
     */
    fun follow(current: Double, reading: Double): Double =
        if (reading >= current) reading else current + (reading - current) * FALL

    /** Measures one output track, from when it is made until it is released. Audio thread only. */
    class Probe(private val sampleRate: Int, blockFrames: Int) {
        private val timestamp = AudioTimestamp()
        private var written = 0L
        private var lastCheckNs = 0L
        private var settledAtNs = 0L

        init {
            blockMs = blockFrames * 1000.0 / sampleRate
            queueMs = 0.0
        }

        /** Call just before the track is started again: it counts its frames from there. */
        fun restarted() {
            written = 0
            settledAtNs = System.nanoTime() + SETTLE_NS
        }

        /** Call after [frames] stereo frames were written to [track]. */
        fun wrote(track: AudioTrack, frames: Int) {
            written += frames
            val now = System.nanoTime()
            if (now - lastCheckNs < CHECK_INTERVAL_NS || now < settledAtNs) return
            lastCheckNs = now
            if (!track.getTimestamp(timestamp)) return
            val reading = queuedMs(written, timestamp.framePosition, timestamp.nanoTime, now, sampleRate) ?: return
            queueMs = follow(queueMs, reading)
        }
    }
}
