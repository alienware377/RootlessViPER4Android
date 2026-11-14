package me.timschneeberger.rootlessjamesdsp.smoothvolume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessOutputLagTest {
    @Test
    fun `never shorter than the lag that was assumed before`() {
        assertEquals(RootlessOutputLag.FLOOR_MS, RootlessOutputLag.lagMs(0.0, 0.0))
        // The smallest buffer on a wired output.
        assertEquals(RootlessOutputLag.FLOOR_MS, RootlessOutputLag.lagMs(5.8, 40.0))
    }

    @Test
    fun `the largest buffer on Bluetooth waits for all of it`() {
        // 16384 samples is 8192 stereo frames, 186 ms a block at 44.1 kHz; the
        // track holds one block and the headphones add about 200 ms.
        val block = 8192 * 1000.0 / 44_100
        val queue = block + 200.0
        val lag = RootlessOutputLag.lagMs(block, queue)
        assertTrue("$lag ms", lag >= block + queue)
        assertTrue("$lag ms", lag > RootlessOutputLag.FLOOR_MS)
    }

    @Test
    fun `what is still ahead of the speaker is read from the output timestamp`() {
        // 48 kHz: 48000 frames written, frame 38400 heard 100 ms ago, so 4800
        // more have been heard since and 4800 (100 ms) are still to come.
        val now = 5_000_000_000L
        assertEquals(100.0, RootlessOutputLag.queuedMs(48_000, 38_400, now - 100_000_000L, now, 48_000)!!, 1e-6)
        // A timestamp taken just now.
        assertEquals(200.0, RootlessOutputLag.queuedMs(48_000, 38_400, now, now, 48_000)!!, 1e-6)
    }

    @Test
    fun `a reading that cannot be right is left out`() {
        val now = 5_000_000_000L
        // Heard more than was written: the track's count started again.
        assertNull(RootlessOutputLag.queuedMs(1_000, 38_400, now, now, 48_000))
        // Seconds of audio queued: a count from before a restart.
        assertNull(RootlessOutputLag.queuedMs(480_000, 0, now, now, 48_000))
        assertNull(RootlessOutputLag.queuedMs(48_000, 38_400, now, now, 0))
    }

    @Test
    fun `a longer reading counts at once and a shorter one only slowly`() {
        assertEquals(300.0, RootlessOutputLag.follow(100.0, 300.0), 0.0)
        val after = RootlessOutputLag.follow(300.0, 100.0)
        assertTrue("$after", after < 300.0 && after > 280.0)
        // Many short readings in a row bring it down in the end.
        var q = 300.0
        repeat(200) { q = RootlessOutputLag.follow(q, 100.0) }
        assertEquals(100.0, q, 1.0)
    }
}
