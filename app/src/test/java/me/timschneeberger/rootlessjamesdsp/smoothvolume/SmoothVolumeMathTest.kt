package me.timschneeberger.rootlessjamesdsp.smoothvolume

import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeMath.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothVolumeMathTest {
    private val max = 15
    private val curve = SmoothVolumeMath.defaultCurve(max)

    // A curve with 4 dB between neighbouring indices and full volume at 0 dB,
    // easy to check by hand.
    private val even = SmoothVolumeMath.Curve { i -> if (i <= 0) Double.NEGATIVE_INFINITY else (i - max) * 4.0 }

    private fun eff(l: Level, c: SmoothVolumeMath.Curve = even) = SmoothVolumeMath.effectiveDb(l, c)

    @Test
    fun `switching on holds the index the phone was at with the gain at 0 dB`() {
        assertEquals(Level(9, 0.0), SmoothVolumeMath.seed(9, max))
        assertEquals(Level(15, 0.0), SmoothVolumeMath.seed(20, max))
    }

    @Test
    fun `switching on while muted holds the lowest index and unmuting comes back at the new index`() {
        val muted = SmoothVolumeMath.seed(0, max)
        assertEquals(Level(1, 0.0), muted)
        // Turned up to 7 through the system: index 7 at 0 dB, as if it had
        // never been held.
        assertEquals(Level(7, 0.0), SmoothVolumeMath.fold(muted, 0, 7, max, even))
    }

    @Test
    fun `starting again picks up the level it had`() {
        // Held at 10 and turned down 13.5 dB, then processing stopped.
        val before = SmoothVolumeMath.step(Level(10, 0.0), -13.5, max, even)
        val again = SmoothVolumeMath.reseed(10, max, even, eff(before))
        assertEquals(before, again)
        // Above the index it is at again: that index goes up, as it had.
        val raised = SmoothVolumeMath.step(Level(10, 0.0), 6.0, max, even)
        assertEquals(raised, SmoothVolumeMath.reseed(10, max, even, eff(raised)))
    }

    @Test
    fun `starting again without a usable level starts at the index`() {
        assertEquals(Level(10, 0.0), SmoothVolumeMath.reseed(10, max, even, null))
        assertEquals(Level(10, 0.0), SmoothVolumeMath.reseed(10, max, even, Double.NaN))
        assertEquals(Level(10, 0.0), SmoothVolumeMath.reseed(10, max, even, Double.NEGATIVE_INFINITY))
        assertEquals(Level(1, 0.0), SmoothVolumeMath.reseed(0, max, even, -50.0))
        // Never below the bottom of the gain or above full volume.
        assertEquals(Level(10, SmoothVolumeMath.MIN_GAIN_DB), SmoothVolumeMath.reseed(10, max, even, -500.0))
        assertEquals(Level(15, 0.0), SmoothVolumeMath.reseed(10, max, even, 12.0))
    }

    @Test
    fun `a held key raises the index as it goes, each raise one lag after its level`() {
        // Rootless: 250 ms from the engine to the speaker, longer than the
        // 100 ms between steps of a key held for a while. Every step needs
        // the next index up.
        val lag = 250L
        val q = SmoothVolumeMath.RaiseQueue()
        var written = 5
        val wantedAt = mutableMapOf<Long, Int>()
        var t = 0L
        var hold = 5
        while (t <= 2000) {
            q.takeDue(t)?.let { written = maxOf(written, it) }
            if (t <= 1500 && t % 100 == 0L) {
                hold++
                wantedAt[t] = hold
                q.add(t + lag, hold)
            }
            // What is written is never ahead of what the engine was told a
            // lag ago, and never further behind than that.
            val sent = wantedAt.filterKeys { it <= t - lag }.values.maxOrNull() ?: 5
            assertEquals("at $t ms", sent, written)
            t += 10
        }
        // The raises happened while the key was held, not all at the end.
        assertEquals(hold, written)
        assertEquals(null, q.nextDueMs())
    }

    @Test
    fun `a raise already waiting is not queued again`() {
        val q = SmoothVolumeMath.RaiseQueue()
        q.add(100, 8)
        q.add(150, 8)
        q.add(160, 7)
        assertEquals(100L, q.nextDueMs())
        assertEquals(null, q.takeDue(99))
        assertEquals(8, q.takeDue(500))
        assertEquals(null, q.nextDueMs())
        q.add(600, 9)
        q.clear()
        assertEquals(null, q.takeDue(10_000))
    }

    @Test
    fun `a raise waits for every engine to have its gain`() {
        val c = SmoothVolumeMath.ConfirmedRaises()
        c.add(3, 6)
        c.add(4, 6)
        c.add(5, 7)
        assertEquals(null, c.takeConfirmed(2))
        assertEquals(6, c.takeConfirmed(4))
        assertFalse(c.isEmpty())
        assertEquals(7, c.takeConfirmed(Long.MAX_VALUE))
        assertTrue(c.isEmpty())
        c.add(6, 8)
        c.clear()
        assertEquals(null, c.takeConfirmed(Long.MAX_VALUE))
    }

    @Test
    fun `a sync that holds up the gain holds up the raise`() {
        // A key held from 0 to 1500 ms, a step every 100 ms, each needing the
        // next index up. The engine applies each gain 10 ms after it is sent,
        // except while another sync (a model load, say) keeps it busy from
        // 300 to 900 ms; whatever was sent meanwhile arrives at 900 ms.
        val lag = 250L
        fun applyTime(sentMs: Long) = if (sentMs + 10 in 300L..900L) 900L else sentMs + 10
        val waiting = SmoothVolumeMath.ConfirmedRaises()
        val raises = SmoothVolumeMath.RaiseQueue()
        val appliedAt = mutableMapOf<Long, Long>()
        val indexOf = mutableMapOf<Long, Int>()
        var serial = 0L
        var confirmed = 0L
        var written = 5
        var hold = 5
        var t = 0L
        while (t <= 2500) {
            // The engine confirms the newest gain it has applied by now.
            val newest = appliedAt.filterValues { it <= t }.keys.maxOrNull() ?: 0L
            if (newest > confirmed) {
                confirmed = newest
                waiting.takeConfirmed(confirmed)?.let { raises.add(t + lag, it) }
            }
            raises.takeDue(t)?.let { written = maxOf(written, it) }
            if (t <= 1500 && t % 100 == 0L) {
                hold++
                serial++
                appliedAt[serial] = applyTime(t)
                indexOf[serial] = hold
                waiting.add(serial, hold)
            }
            // Never an index whose gain the engine has not had for a lag.
            val safe = appliedAt.filterValues { it <= t - lag }.keys.maxOfOrNull { indexOf.getValue(it) } ?: 5
            assertTrue("at $t ms wrote $written, safe was $safe", written <= safe)
            t += 10
        }
        assertEquals(hold, written)
    }

    @Test
    fun `a step moves the effective level by half a dB and keeps the index`() {
        val start = Level(10, 0.0)
        val down = SmoothVolumeMath.step(start, -0.5, max, even)
        assertEquals(10, down.holdIndex)
        assertEquals(-0.5, down.gainDb, 1e-9)
        assertEquals(eff(start) - 0.5, eff(down), 1e-9)
        val back = SmoothVolumeMath.step(down, 0.5, max, even)
        assertEquals(start, back)
    }

    @Test
    fun `there are two hundred steps from the held index to the bottom`() {
        var l = Level(10, 0.0)
        var n = 0
        while (true) {
            val next = SmoothVolumeMath.step(l, -0.5, max, even)
            if (next == l) break
            l = next; n++
        }
        assertEquals(200, n)
        assertEquals(SmoothVolumeMath.MIN_GAIN_DB, l.gainDb, 1e-9)
        assertEquals(10, l.holdIndex)
    }

    @Test
    fun `a step off the grid lands on the next grid point in that direction`() {
        assertEquals(-27.0, SmoothVolumeMath.stepOnGrid(-27.3, 0.5), 1e-9)
        assertEquals(-27.5, SmoothVolumeMath.stepOnGrid(-27.3, -0.5), 1e-9)
        assertEquals(-26.5, SmoothVolumeMath.stepOnGrid(-27.0, 0.5), 1e-9)
        assertEquals(-28.0, SmoothVolumeMath.stepOnGrid(-27.0, -1.0), 1e-9)
        // A held key's bigger steps still land on the grid: the part step
        // to it first, then whole ones.
        assertEquals(-23.5, SmoothVolumeMath.stepOnGrid(-27.3, 4.0), 1e-9)
    }

    @Test
    fun `above 0 dB of gain the held index goes up as little as it can`() {
        // Index 10 is -20 dB here; one step up needs index 11 (-16 dB) under it.
        val up = SmoothVolumeMath.step(Level(10, 0.0), 0.5, max, even)
        assertEquals(11, up.holdIndex)
        assertEquals(-3.5, up.gainDb, 1e-9)
        assertEquals(-19.5, eff(up), 1e-9)
        assertTrue(up.gainDb <= 0.0)
    }

    @Test
    fun `the gain never boosts and the top is full system volume`() {
        var l = Level(13, -2.0)
        repeat(100) { l = SmoothVolumeMath.step(l, 8.0, max, even) }
        assertEquals(Level(15, 0.0), l)
        assertEquals(l, SmoothVolumeMath.step(l, 0.5, max, even))
    }

    @Test
    fun `steps down never lower the held index`() {
        var l = Level(12, 0.0)
        repeat(50) { l = SmoothVolumeMath.step(l, -8.0, max, even) }
        assertEquals(12, l.holdIndex)
        assertEquals(SmoothVolumeMath.MIN_GAIN_DB, l.gainDb, 1e-9)
    }

    @Test
    fun `gains are clamped and a NaN keeps what was there`() {
        assertEquals(0.0, SmoothVolumeMath.clampGain(6.0), 0.0)
        assertEquals(SmoothVolumeMath.MIN_GAIN_DB, SmoothVolumeMath.clampGain(-500.0), 0.0)
        assertEquals(-7.5, SmoothVolumeMath.clampGain(Double.NaN, -7.5), 0.0)
        assertEquals(SmoothVolumeMath.MIN_GAIN_DB, SmoothVolumeMath.clampGain(Double.NEGATIVE_INFINITY), 0.0)
        val l = Level(10, -3.0)
        assertEquals(l, SmoothVolumeMath.moveTo(l, Double.NaN, max, even))
        assertEquals(l, SmoothVolumeMath.step(l, Double.NaN, max, even))
    }

    @Test
    fun `moving the slider places the level and raises the index only above 0 dB of gain`() {
        val l = Level(10, 0.0)
        assertEquals(Level(10, -12.5), SmoothVolumeMath.moveTo(l, -32.5, max, even))
        assertEquals(Level(13, -1.0), SmoothVolumeMath.moveTo(l, -9.0, max, even))
        assertEquals(Level(10, SmoothVolumeMath.MIN_GAIN_DB), SmoothVolumeMath.moveTo(l, -500.0, max, even))
    }

    @Test
    fun `a system volume change elsewhere is kept as a change of level`() {
        val l = Level(10, -6.0)
        // The panel's slider went down two indices: 8 dB quieter, index held.
        val down = SmoothVolumeMath.fold(l, 10, 8, max, even)
        assertEquals(10, down.holdIndex)
        assertEquals(eff(l) - 8.0, eff(down), 1e-9)
        // Up three: 12 dB louder, more than the gain has left, so the index rises.
        val up = SmoothVolumeMath.fold(l, 10, 13, max, even)
        assertEquals(eff(l) + 12.0, eff(up), 1e-9)
        assertEquals(12, up.holdIndex)
        assertTrue(up.gainDb <= 0.0)
    }

    @Test
    fun `muting and unmuting through the system volume comes back to the same level`() {
        val l = Level(10, -6.0)
        val muted = SmoothVolumeMath.fold(l, 10, 0, max, even)
        assertTrue(eff(muted) < eff(l))
        val unmuted = SmoothVolumeMath.fold(muted, 0, 10, max, even)
        assertEquals(eff(l), eff(unmuted), 1e-9)
        assertEquals(10, unmuted.holdIndex)
    }

    @Test
    fun `levels map to the nearest system index and back`() {
        for (i in 1..max) {
            assertEquals(i, SmoothVolumeMath.nearestIndex(curve.db(i), max, curve))
            assertEquals(i, SmoothVolumeMath.nearestIndex(curve.db(i) + 0.4, max, curve))
        }
        // Holding an index with the gain at 0 dB is that index exactly.
        val l = SmoothVolumeMath.seed(7, max)
        assertEquals(7, SmoothVolumeMath.nearestIndex(eff(l, curve), max, curve))
        // Far below anything index 1 reaches counts as silent.
        assertEquals(0, SmoothVolumeMath.nearestIndex(curve.db(1) - 80.0, max, curve))
    }

    @Test
    fun `the default curve follows the AOSP breakpoints`() {
        val c100 = SmoothVolumeMath.defaultCurve(100)
        assertEquals(-58.0, c100.db(1), 1e-9)
        assertEquals(-40.0, c100.db(20), 1e-9)
        assertEquals(-17.0, c100.db(60), 1e-9)
        assertEquals(0.0, c100.db(100), 1e-9)
        assertEquals(-28.5, c100.db(40), 1e-9)
        assertTrue(c100.db(0) == Double.NEGATIVE_INFINITY)
        for (i in 2..max) assertTrue(curve.db(i) > curve.db(i - 1))
    }

    @Test
    fun `a curve read from the phone is only trusted when it rises`() {
        assertTrue(SmoothVolumeMath.isUsableCurve(doubleArrayOf(Double.NEGATIVE_INFINITY, -50.0, -30.0, -10.0, 0.0)))
        // Absolute Bluetooth volume: 0 dB everywhere.
        assertFalse(SmoothVolumeMath.isUsableCurve(DoubleArray(16) { 0.0 }))
        assertFalse(SmoothVolumeMath.isUsableCurve(doubleArrayOf(0.0, -50.0, -60.0, -10.0, 0.0)))
        assertFalse(SmoothVolumeMath.isUsableCurve(doubleArrayOf(0.0, -50.0, Double.NaN, -10.0, 0.0)))
        assertFalse(SmoothVolumeMath.isUsableCurve(doubleArrayOf(0.0, -3.0, -2.0, -1.0, 0.0)))
    }

    @Test
    fun `a held key speeds up the longer it is held`() {
        var last = 0.0
        for (ms in longArrayOf(0, 600, 1600, 3100, 6000)) {
            val (step, next) = SmoothVolumeMath.rampFor(ms)
            assertTrue(step > last)
            assertTrue(next in 100..300)
            assertEquals(0.0, step % SmoothVolumeMath.STEP_DB, 1e-12)
            last = step
        }
    }

    @Test
    fun `levels are shown with one decimal only when needed`() {
        assertEquals("-12.5 dB", SmoothVolumeMath.formatDb(-12.5))
        assertEquals("-12 dB", SmoothVolumeMath.formatDb(-12.0))
        assertEquals("0 dB", SmoothVolumeMath.formatDb(-0.01))
        assertEquals("-27.3 dB", SmoothVolumeMath.formatDb(-27.3))
    }
}
