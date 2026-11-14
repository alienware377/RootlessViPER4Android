package me.timschneeberger.rootlessjamesdsp.smoothvolume

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The arithmetic behind smooth volume, kept free of Android so it can be unit
 * tested.
 *
 * A level is two things: the system media volume index that is held, and the
 * engine gain under it. What the listener hears is the sum, the held index's
 * own dB (from the phone's volume curve) plus the gain, called the effective
 * level here and shown in dB below full system volume.
 *
 * Steps move the effective level by exactly [STEP_DB] on a fixed grid. The gain
 * takes up whatever is left over from the curve, so the grid holds on any
 * phone, whatever its curve looks like. The gain only ever attenuates: when a
 * step up would need it above 0 dB, the held index moves up instead, as little
 * as it can, and the gain makes up the difference. Steps down never lower the
 * held index; the gain covers [MIN_GAIN_DB] below it, about two hundred steps.
 */
object SmoothVolumeMath {
    const val STEP_DB = 0.5
    const val MIN_GAIN_DB = -100.0
    const val MAX_GAIN_DB = 0.0

    /** The held system index and the engine gain under it. */
    data class Level(val holdIndex: Int, val gainDb: Double)

    /** A phone's media volume curve: index to dB, 0 dB at full volume. */
    fun interface Curve {
        fun db(index: Int): Double
    }

    /** NaN keeps [fallback]; everything else is held to the gain's range. */
    fun clampGain(db: Double, fallback: Double = MAX_GAIN_DB): Double =
        if (db.isNaN()) fallback else db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    fun effectiveDb(level: Level, curve: Curve): Double = curve.db(level.holdIndex) + level.gainDb

    /**
     * Where smooth volume starts: the index the phone was at, held, with the
     * gain at 0 dB, so switching on changes nothing that can be heard. Muted
     * (index 0) counts as index 1, the way [fold] sees it, and stays muted
     * until the system volume is turned up again; unmuting to an index then
     * comes back at that index.
     */
    fun seed(index: Int, maxIndex: Int): Level =
        Level(index.coerceIn(1, maxIndex.coerceAtLeast(1)), MAX_GAIN_DB)

    /**
     * Where smooth volume starts again after it stopped holding for a while
     * (processing stopped, or a root playback ended): the level it had,
     * [rememberedDb], under the index the phone is at. The caller only passes
     * a level when nothing has changed the system volume since, so starting
     * again sounds the way it did when it stopped. Without one, or muted, it
     * is [seed].
     */
    fun reseed(index: Int, maxIndex: Int, curve: Curve, rememberedDb: Double?): Level {
        val base = seed(index, maxIndex)
        if (index <= 0 || rememberedDb == null || !rememberedDb.isFinite()) return base
        return moveTo(base, rememberedDb, maxIndex, curve)
    }

    /**
     * The next grid point from [db] in the direction of [deltaDb], then the
     * rest of the delta in whole steps. A level that sits off the grid (just
     * after switching on, say) lands on it with the first step instead of
     * keeping its offset forever.
     */
    fun stepOnGrid(db: Double, deltaDb: Double): Double {
        if (deltaDb == 0.0 || deltaDb.isNaN()) return db
        val steps = (abs(deltaDb) / STEP_DB).let { if (it < 1.0) 1.0 else floor(it + 1e-9) }
        val q = db / STEP_DB
        return if (deltaDb > 0) {
            val next = (floor(q + 1e-9) + 1.0) * STEP_DB
            next + (steps - 1.0) * STEP_DB
        } else {
            val next = (ceil(q - 1e-9) - 1.0) * STEP_DB
            next - (steps - 1.0) * STEP_DB
        }
    }

    /**
     * Moves the effective level by [deltaDb] (in steps, see [stepOnGrid]),
     * between the bottom of the gain's range under the held index and full
     * system volume. Raises the held index when the gain would have to boost.
     */
    fun step(level: Level, deltaDb: Double, maxIndex: Int, curve: Curve): Level {
        val now = effectiveDb(level, curve)
        val floorDb = curve.db(level.holdIndex) + MIN_GAIN_DB
        val wanted = stepOnGrid(now, deltaDb).coerceIn(floorDb, curve.db(maxIndex))
        return place(level, wanted, maxIndex, curve)
    }

    /**
     * Puts the effective level at [db] directly, for the card's slider, within
     * the same limits as [step].
     */
    fun moveTo(level: Level, db: Double, maxIndex: Int, curve: Curve): Level {
        if (db.isNaN()) return level
        val floorDb = curve.db(level.holdIndex) + MIN_GAIN_DB
        return place(level, db.coerceIn(floorDb, curve.db(maxIndex)), maxIndex, curve)
    }

    /**
     * Someone else moved the system volume from [fromIndex] to [toIndex]: a
     * volume key while nothing was playing, the volume panel's slider, another
     * app. The change is kept, as a change of the effective level by the same
     * number of dB, and the held index goes back to where it was (or up, if
     * the new level is above what the gain can reach under it).
     */
    fun fold(level: Level, fromIndex: Int, toIndex: Int, maxIndex: Int, curve: Curve): Level {
        if (fromIndex == toIndex) return level
        // Muted counts as index 1. As minus infinity, the level would hit the
        // bottom and lose how far below the rest it went, and unmuting would
        // then add the whole way back up on top of that.
        fun at(i: Int) = curve.db(i.coerceIn(1, maxIndex))
        val delta = at(toIndex) - at(fromIndex)
        val floorDb = curve.db(level.holdIndex) + MIN_GAIN_DB
        val wanted = (effectiveDb(level, curve) + delta).let { if (it.isNaN()) floorDb else it }
            .coerceIn(floorDb, curve.db(maxIndex))
        return place(level, wanted, maxIndex, curve)
    }

    /** The held index and gain for an effective level, raising the index only if it must. */
    private fun place(level: Level, wantedDb: Double, maxIndex: Int, curve: Curve): Level {
        var hold = level.holdIndex
        while (hold < maxIndex && curve.db(hold) < wantedDb - 1e-9) hold++
        return Level(hold, clampGain(wantedDb - curve.db(hold), level.gainDb))
    }

    /**
     * The system index that sounds closest to an effective level, for showing
     * where a level sits on the scale people already know. Index 0 only for
     * a level below everything index 1 can reach.
     */
    fun nearestIndex(db: Double, maxIndex: Int, curve: Curve): Int {
        if (maxIndex <= 0) return 0
        var best = 1
        var bestDistance = Double.MAX_VALUE
        for (i in 1..maxIndex) {
            val d = abs(curve.db(i) - db)
            if (d < bestDistance) { best = i; bestDistance = d }
        }
        return if (db < curve.db(1) + MIN_GAIN_DB / 2) 0 else best
    }

    /**
     * Android's default media volume curve, for when the phone will not say
     * what its own is (before Android 9, or a Bluetooth output with absolute
     * volume, where every index reads 0 dB). Breakpoints of
     * DEFAULT_MEDIA_VOLUME_CURVE in AOSP's audio policy, by percent of the
     * range: -58 dB at 1 %, -40 at 20, -17 at 60, 0 at 100. Index 0 is muted.
     */
    fun defaultCurve(maxIndex: Int): Curve = Curve { index ->
        if (index <= 0 || maxIndex <= 0) Double.NEGATIVE_INFINITY
        else {
            val pct = 100.0 * index.coerceAtMost(maxIndex) / maxIndex
            val x = doubleArrayOf(1.0, 20.0, 60.0, 100.0)
            val y = doubleArrayOf(-58.0, -40.0, -17.0, 0.0)
            when {
                pct <= x[0] -> y[0]
                else -> {
                    var k = 1
                    while (k < x.size - 1 && pct > x[k]) k++
                    y[k - 1] + (pct - x[k - 1]) / (x[k] - x[k - 1]) * (y[k] - y[k - 1])
                }
            }
        }
    }

    /**
     * A curve read from the phone is only used when it can be trusted: finite
     * from index 1 up, rising all the way, and spanning more than a few dB.
     * Absolute Bluetooth volume reads 0 dB everywhere, and some builds return
     * nonsense; either way the default curve is the better guess.
     */
    fun isUsableCurve(dbByIndex: DoubleArray): Boolean {
        if (dbByIndex.size < 3) return false
        for (i in 1 until dbByIndex.size) {
            if (!dbByIndex[i].isFinite()) return false
            if (i > 1 && dbByIndex[i] <= dbByIndex[i - 1]) return false
        }
        return dbByIndex.last() - dbByIndex[1] >= 10.0
    }

    /** A curve from a table read once, with index 0 always muted. */
    fun tableCurve(dbByIndex: DoubleArray): Curve = Curve { index ->
        if (index <= 0) Double.NEGATIVE_INFINITY
        else dbByIndex[index.coerceAtMost(dbByIndex.size - 1)]
    }

    /**
     * How far one tick of a held volume key goes, and how long until the next:
     * single steps for the first half second, then faster, so a held key still
     * crosses the whole range in a few seconds. The timings are the ones
     * evoeram's fork uses (see [SmoothVolumeController]).
     */
    fun rampFor(heldMs: Long): Pair<Double, Long> = when {
        heldMs < 500 -> STEP_DB to 300L
        heldMs < 1500 -> STEP_DB * 2 to 200L
        heldMs < 3000 -> STEP_DB * 4 to 150L
        heldMs < 5000 -> STEP_DB * 8 to 120L
        else -> STEP_DB * 16 to 100L
    }

    /**
     * Raises of the held index that are waiting for the engine. A raise is only
     * safe once the engine's attenuation for the new level is being heard, so
     * each one is due [lagMs] after the level that needed it, and the index
     * written at any moment is the one the level had that long ago. Each raise
     * keeps its own due time: a held key moves the level more often than the
     * lag in rootless mode, and pushing one raise back on every step would put
     * them all off until the key is let go, then do them at once.
     */
    class RaiseQueue {
        private val pending = ArrayDeque<Pair<Long, Int>>()

        /** Queues a raise to [index], due at [dueMs]; nothing if one at least as high is queued. */
        fun add(dueMs: Long, index: Int) {
            if ((pending.lastOrNull()?.second ?: Int.MIN_VALUE) >= index) return
            pending.addLast(dueMs to index)
        }

        /** The highest index due by [nowMs], or null; what it returns is gone from the queue. */
        fun takeDue(nowMs: Long): Int? {
            var best: Int? = null
            while (pending.isNotEmpty() && pending.first().first <= nowMs)
                best = maxOf(best ?: Int.MIN_VALUE, pending.removeFirst().second)
            return best
        }

        /** When the next raise is due, or null when none is waiting. */
        fun nextDueMs(): Long? = pending.firstOrNull()?.first

        fun clear() = pending.clear()
    }

    /** "-12.5 dB", "0 dB": one decimal only when it is not whole. */
    fun formatDb(db: Double): String {
        val r = Math.round(db * 10.0) / 10.0
        val text = if (r == Math.rint(r)) String.format(Locale.ROOT, "%.0f", r)
            else String.format(Locale.ROOT, "%.1f", r)
        return "${if (text == "-0") "0" else text} dB"
    }
}
