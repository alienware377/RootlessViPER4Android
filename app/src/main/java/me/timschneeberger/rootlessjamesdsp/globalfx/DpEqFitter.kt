package me.timschneeberger.rootlessjamesdsp.globalfx

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

/** One band of Android's DynamicsProcessing equaliser: everything up to [cutoffHz] gets [gainDb]. */
data class DpEqBand(val cutoffHz: Float, val gainDb: Float)

/** A fitted curve: the bands to send and how far they stray from the target. */
data class DpEqFit(val bands: List<DpEqBand>, val endBins: IntArray, val maxErrorDb: Double) {
    override fun equals(other: Any?) =
        other is DpEqFit && bands == other.bands && endBins.contentEquals(other.endBins) && maxErrorDb == other.maxErrorDb
    override fun hashCode() = bands.hashCode() * 31 + endBins.contentHashCode()
}

/**
 * Fits a target curve onto Android's DynamicsProcessing equaliser.
 *
 * That equaliser is not a set of filters. It is an FFT gain table: the sound is
 * cut into blocks of N samples, and each band sets one constant gain for a run
 * of FFT bins, from the bin after the previous band's last one up to
 * binStop = int(0.5 + cutoff * N / fs) (AOSP DPFrequency.cpp,
 * ChannelBuffer::computeBinStartStop). So the best it can ever do is a
 * staircase on the bin grid, and the job here is to choose where the up to 128
 * steps go.
 *
 * Method: start with one step per bin, then keep merging the neighbouring pair
 * whose merged step would stray least from the target (half its max minus
 * min), until the band limit is reached. Each step's gain is the middle of its
 * range, which is the smallest possible worst error for a constant. Each
 * cutoff is placed a quarter bin past its last bin, so AOSP's rounding lands
 * on that exact bin with room to spare for float error, and the last band is
 * pushed to 192 kHz so it covers every bin up to Nyquist: bins past the last
 * band are never written and would miss the input gain, which rides on the
 * same table.
 */
object DpEqFitter {
    /** The most bands the AIDL effect HAL accepts per stage (aidl/DynamicsProcessing.cpp). */
    const val MAX_BANDS = 128
    /** The highest cutoff the AIDL effect HAL accepts, used as "everything above". */
    const val TOP_CUTOFF_HZ = 192000f
    /** Band gain limits. AOSP accepts -200..200 dB; nothing sensible needs more than this. */
    const val MIN_GAIN_DB = -60.0
    const val MAX_GAIN_DB = 40.0

    /** AOSP's block size: frame length in samples, rounded up to a power of two, 8..16384. */
    fun blockSize(frameMs: Float, sampleRate: Int): Int {
        val desired = (frameMs * sampleRate / 1000.0f).toInt()
        if (desired < 8) return 8
        var n = Integer.highestOneBit(desired)
        if (n != desired) n = n shl 1
        return min(n, 16384)
    }

    /** Centre frequency of each bin the equaliser works on, 0 to Nyquist. */
    fun binFrequencies(blockSize: Int, sampleRate: Int): DoubleArray =
        DoubleArray(blockSize / 2 + 1) { it.toDouble() * sampleRate / blockSize }

    /** The cutoff that makes AOSP's rounding end a band exactly on [endBin]. */
    fun cutoffForEndBin(endBin: Int, blockSize: Int, sampleRate: Int): Float =
        ((endBin + 0.25) * sampleRate / blockSize).toFloat()

    /** AOSP's own rounding, ChannelBuffer::computeBinStartStop. */
    fun aospBinStop(cutoffHz: Float, blockSize: Int, sampleRate: Int): Int =
        (0.5 + cutoffHz * blockSize.toFloat() / sampleRate.toFloat()).toInt()

    fun fit(target: DoubleArray, blockSize: Int, sampleRate: Int, maxBands: Int = MAX_BANDS): DpEqFit {
        val bins = blockSize / 2 + 1
        require(target.size == bins) { "target has ${target.size} values, expected $bins" }
        require(maxBands >= 1)
        val t = DoubleArray(bins) {
            val v = target[it]
            if (v.isFinite()) v.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) else 0.0
        }

        // Segments as a doubly linked list over their first bin.
        val next = IntArray(bins) { if (it + 1 < bins) it + 1 else -1 }
        val prev = IntArray(bins) { it - 1 }
        val end = IntArray(bins) { it }
        val lo = t.copyOf()
        val hi = t.copyOf()
        val version = IntArray(bins)
        val alive = BooleanArray(bins) { true }
        var count = bins

        // (cost, left segment, its version, right segment's version) per pair
        class Pair(val cost: Double, val left: Int, val lv: Int, val right: Int, val rv: Int)
        val heap = PriorityQueue<Pair>(bins, compareBy<Pair> { it.cost }.thenBy { it.left })
        fun push(l: Int) {
            val r = next[l]
            if (r < 0) return
            val cost = (max(hi[l], hi[r]) - min(lo[l], lo[r])) / 2.0
            heap.add(Pair(cost, l, version[l], r, version[r]))
        }
        for (i in 0 until bins - 1) push(i)

        while (count > maxBands && heap.isNotEmpty()) {
            val p = heap.poll()!!
            val l = p.left
            val r = p.right
            if (!alive[l] || !alive[r] || next[l] != r || version[l] != p.lv || version[r] != p.rv) continue
            // Merge r into l
            lo[l] = min(lo[l], lo[r])
            hi[l] = max(hi[l], hi[r])
            end[l] = end[r]
            next[l] = next[r]
            if (next[r] >= 0) prev[next[r]] = l
            alive[r] = false
            version[l]++
            count--
            if (prev[l] >= 0) push(prev[l])
            push(l)
        }

        val out = ArrayList<DpEqBand>(count)
        val ends = IntArray(count)
        var worst = 0.0
        var seg = 0
        var k = 0
        while (seg >= 0) {
            val gain = (lo[seg] + hi[seg]) / 2.0
            worst = max(worst, (hi[seg] - lo[seg]) / 2.0)
            val last = next[seg] < 0
            val cutoff = if (last) TOP_CUTOFF_HZ else cutoffForEndBin(end[seg], blockSize, sampleRate)
            out.add(DpEqBand(cutoff, gain.toFloat()))
            ends[k++] = end[seg]
            seg = next[seg]
        }
        return DpEqFit(out, ends, worst)
    }

    /**
     * Replays bands through AOSP's bin mapping: the gain each bin really gets.
     * Bins no band reaches stay at 0 dB, as they do on the device.
     */
    fun realisedGains(bands: List<DpEqBand>, blockSize: Int, sampleRate: Int): DoubleArray {
        val bins = blockSize / 2 + 1
        val out = DoubleArray(bins)
        var start = 0
        for (b in bands) {
            val stop = aospBinStop(b.cutoffHz, blockSize, sampleRate)
            var k = start
            while (k <= stop && k < bins) { out[k] = b.gainDb.toDouble(); k++ }
            start = stop + 1
        }
        return out
    }
}
