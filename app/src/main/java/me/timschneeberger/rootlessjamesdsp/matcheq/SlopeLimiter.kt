/*
 * Match EQ: slope limiter.
 *
 * A Kotlin port of the slope limiting in AutoEq's FrequencyResponse.equalize
 * by Jaakko Pasanen (https://github.com/jaakkopasanen/AutoEq, MIT licence,
 * Copyright (c) 2018-2022 Jaakko Pasanen). The full notice is in
 * THIRD_PARTY_NOTICES.md. Peak finding and prominence follow the published
 * definitions that SciPy's find_peaks and peak_prominences implement.
 */
package me.timschneeberger.rootlessjamesdsp.matcheq

import kotlin.math.ln

/**
 * Stops a correction rising faster than a set number of dB per octave on its
 * way into a boost.
 *
 * A measurement often has deep, narrow notches. Inverting one gives a narrow,
 * tall boost that rings, eats headroom and usually corrects something that
 * moves every time the headphones are put on. Limiting how steeply the
 * correction may climb fills such a notch only part of the way, while a broad
 * dip, whose sides are gentle anyway, is filled as before.
 *
 * The curve is walked from the left and from the right and the lower of the
 * two results is kept. A climb is only limited where it leads into a peak of
 * the correction, and dips that sit below both neighbouring dips are left
 * alone so the limiter never props up the bottom of a cut.
 */
object SlopeLimiter {

    const val DEFAULT_MAX_SLOPE = 18.0

    /** Limited copy of [y] (dB) on frequencies [x]. */
    fun limit(x: DoubleArray, y: DoubleArray, maxSlope: Double = DEFAULT_MAX_SLOPE): DoubleArray {
        val peaks = findPeaks(y, 1.0)
        val dips = findPeaks(DoubleArray(y.size) { -y[it] }, 1.0)
        if (peaks.isEmpty() && dips.isEmpty()) return y.copyOf()

        val free = protectionMask(y, peaks, dips)
        val rtlStart = findRtlStart(y, peaks, dips)
        val ltr = limitedLtr(x, y, maxSlope, 0, peaks, free)
        val rtl = limitedRtl(x, y, maxSlope, rtlStart, peaks, free)
        return DoubleArray(y.size) { minOf(ltr[it], rtl[it]) }
    }

    /**
     * Indices of local maxima whose topographic prominence is at least
     * [minProminence]. A flat top counts once, at its middle; the first and last
     * samples are never peaks.
     */
    fun findPeaks(y: DoubleArray, minProminence: Double): IntArray {
        val out = ArrayList<Int>()
        for (p in localMaxima(y)) {
            if (prominence(y, p) >= minProminence) out.add(p)
        }
        return out.toIntArray()
    }

    fun localMaxima(y: DoubleArray): IntArray {
        val out = ArrayList<Int>()
        var i = 1
        val iMax = y.size - 1
        while (i < iMax) {
            if (y[i - 1] < y[i]) {
                var ahead = i + 1
                while (ahead < iMax && y[ahead] == y[i]) ahead++
                if (y[ahead] < y[i]) {
                    out.add((i + ahead - 1) / 2)
                    i = ahead
                }
            }
            i++
        }
        return out.toIntArray()
    }

    /**
     * How far a peak stands above the higher of the two lowest points reached
     * on each side before meeting higher ground (or the end of the data).
     */
    fun prominence(y: DoubleArray, peak: Int): Double {
        val h = y[peak]
        var leftMin = h
        var i = peak
        while (i >= 0 && y[i] <= h) {
            if (y[i] < leftMin) leftMin = y[i]
            i--
        }
        var rightMin = h
        i = peak
        while (i <= y.size - 1 && y[i] <= h) {
            if (y[i] < rightMin) rightMin = y[i]
            i++
        }
        return h - maxOf(leftMin, rightMin)
    }

    /** Zones around dips that are lower than the dips either side of them. */
    fun protectionMask(y: DoubleArray, peaks: IntArray, dips: IntArray): BooleanArray {
        val mask = BooleanArray(y.size)
        val dipInds: IntArray
        val dipLevels: DoubleArray
        if (peaks.isNotEmpty() && (dips.isEmpty() || peaks.last() > dips.last())) {
            // The last peak comes after the last dip: add a dip at the lowest
            // point after that peak.
            var arg = peaks.last()
            for (k in peaks.last() until y.size) if (y[k] < y[arg]) arg = k
            dipInds = dips + arg
            dipLevels = DoubleArray(dipInds.size) { y[dipInds[it]] }
        } else {
            // Otherwise close with the overall minimum, placed at the very end.
            dipInds = dips + (y.size - 1)
            dipLevels = DoubleArray(dipInds.size) { y[dipInds[it]] }
            dipLevels[dipLevels.size - 1] = y.minOrNull() ?: 0.0
        }
        if (dipInds.size < 3) return mask

        for (i in 1 until dipInds.size - 1) {
            val dip = dipInds[i]
            val targetLeft = dipLevels[i - 1]
            val targetRight = dipLevels[i + 1]
            var lastLeft = -1
            for (k in 0 until dip) if (y[k] >= targetLeft) lastLeft = k
            if (lastLeft < 0) continue
            val leftInd = lastLeft + 1
            var firstRight = -1
            for (k in dip until y.size) if (y[k] >= targetRight) { firstRight = k; break }
            if (firstRight < 0) continue
            val rightInd = firstRight - 1
            for (k in leftInd..rightInd) mask[k] = true
        }
        return mask
    }

    /** Where the right-to-left walk starts. Nothing to its right is limited. */
    fun findRtlStart(y: DoubleArray, peaks: IntArray, dips: IntArray): Int {
        if (peaks.isNotEmpty() && (dips.isEmpty() || peaks.last() > dips.last())) {
            val level = if (dips.isNotEmpty()) y[dips.last()] else maxOf(y.first(), y.last())
            for (k in peaks.last() until y.size) if (y[k] <= level) return k
            return y.size - 1
        }
        return dips.last()
    }

    fun limitedLtr(
        x: DoubleArray, y: DoubleArray, maxSlope: Double, startIndex: Int,
        peaks: IntArray?, free: BooleanArray?,
    ): DoubleArray {
        val n = x.size
        val limited = DoubleArray(n)
        val clipped = BooleanArray(n)
        var regionStart = -1
        for (i in 0 until n) {
            if (i <= startIndex) {
                limited[i] = y[i]
                continue
            }
            val octaves = ln(x[i] / x[i - 1]) / ln(2.0)
            val slope = (y[i] - limited[i - 1]) / octaves
            if (slope > maxSlope && (free == null || !free[i])) {
                if (!clipped[i - 1]) regionStart = i
                clipped[i] = true
                limited[i] = limited[i - 1] + maxSlope * octaves
            } else {
                limited[i] = y[i]
                if (clipped[i - 1]) {
                    // A clipped run just ended. Keep it only if it climbed
                    // towards a real peak; otherwise it was limiting the way
                    // out of a cut, which is not what this is for.
                    if (peaks != null && peaks.none { it >= regionStart && it < i }) {
                        for (k in regionStart until i) {
                            limited[k] = y[k]
                            clipped[k] = false
                        }
                    }
                }
            }
        }
        return limited
    }

    fun limitedRtl(
        x: DoubleArray, y: DoubleArray, maxSlope: Double, startIndex: Int,
        peaks: IntArray?, free: BooleanArray?,
    ): DoubleArray {
        val n = x.size
        // Same walk on the mirrored curve. The frequencies are deliberately not
        // mirrored, exactly as AutoEq does: on a log grid every step is the
        // same number of octaves either way.
        val flipped = y.reversedArray()
        val fPeaks = peaks?.map { n - it - 1 }?.toIntArray()
        val fFree = free?.reversedArray()
        return limitedLtr(x, flipped, maxSlope, n - startIndex - 1, fPeaks, fFree).reversedArray()
    }
}
