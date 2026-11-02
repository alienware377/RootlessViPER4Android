package me.timschneeberger.rootlessjamesdsp.hrtf

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

class BinauralSetsTest {

    private val v = BinauralSets.PREP_VERSION
    private lateinit var realResample: (FloatArray, Int, Int, Int, Int) -> FloatArray?
    private var calls = 0

    @Before
    fun fakeResampler() {
        realResample = BinauralSets.resample
        // Stands in for libsamplerate: the same output length rule
        // (round(frames * to / from)), contents do not matter here
        BinauralSets.resample = { h, count, frames, from, to ->
            calls++
            val out = (frames * to.toDouble() / from).roundToInt()
            FloatArray(count * out) { i -> h[(i / out) * frames + minOf(frames - 1, i % out * frames / out)] }
        }
    }

    @After
    fun restore() {
        BinauralSets.resample = realResample
    }

    /** A set shaped like the prepared built-in one: 235 taps at 44.1 kHz. */
    private fun set(fs: Float = 44100f, taps: Int = 235, seed: Float = 1f) = PreparedHrtfSet(
        fs, taps, 20,
        FloatArray(BinauralSpeakers.COUNT * 2 * taps) { if (it % taps == 20) seed else 0.01f },
        FloatArray(BinauralSpeakers.COUNT * 2), true, "t", "", "")

    @Test
    fun `a set is resampled once per rate, not on every settings push`() {
        val s = set()
        val key = "test-once-p$v-e1"
        val a = BinauralSets.forRate(key, s, 48000)
        assertNotNull(a)
        assertEquals(1, calls)
        // The same set at the same rate, as every slider move sends it
        repeat(50) { assertSame(a, BinauralSets.forRate(key, s, 48000)) }
        assertEquals(1, calls)
        assertEquals(256, a!!.taps)
        // Another rate is its own entry, and the first one is still kept
        assertEquals(512, BinauralSets.forRate(key, s, 96000)!!.taps)
        assertEquals(2, calls)
        assertSame(a, BinauralSets.forRate(key, s, 48000))
        assertEquals(2, calls)
        // A set at its own rate needs no resampling at all
        assertEquals(235, BinauralSets.forRate(key, s, 44100)!!.taps)
        assertEquals(2, calls)
    }

    @Test
    fun `a new set under the same key is not served from the old one`() {
        val key = "test-swap-p$v-e1"
        val first = BinauralSets.forRate(key, set(seed = 1f), 48000)!!
        val second = BinauralSets.forRate(key, set(seed = 0.5f), 48000)!!
        assertEquals(2, calls)
        assertTrue(first.hash != second.hash)
    }

    @Test
    fun `rates the set cannot serve are refused before resampling, and the limit is exact`() {
        val s = set()
        // round(235 * r / 44100) <= 1024 holds up to 192257 Hz
        assertEquals(192257, BinauralSets.maxRate(s))
        val key = "test-rate-p$v-e1"
        assertEquals(1023, BinauralSets.forRate(key, s, 192000)!!.taps)
        assertEquals(1024, BinauralSets.forRate(key, s, 192257)!!.taps)
        val before = calls
        assertNull(BinauralSets.forRate(key, s, 192258))
        assertNull(BinauralSets.forRate(key, s, 352800))
        assertNull(BinauralSets.forRate(key, s, 384000))
        assertEquals(before, calls)
        // A set made at 48 kHz (256 taps) reaches 192 kHz too
        assertEquals(192093, BinauralSets.maxRate(set(48000f, 256)))
    }

    @Test
    fun `stale prepared copies are removed, current ones kept`() {
        val dir = Files.createTempDirectory("hrtf").toFile()
        try {
            val names = listOf(
                "builtin-40-p$v-e1.bin", "builtin-40-p$v-e0.bin",   // this version: kept
                "builtin-39-p$v-e1.bin", "builtin-400-p$v-e1.bin",  // other app versions: removed
                "builtin-40-p${v + 50}-e1.bin",                      // other preparation: removed
                "file-0123456789abcdef-p$v-e1.bin",                  // a user's file: kept
            )
            names.forEach { File(dir, it).writeText("x") }
            BinauralSets.removeStaleCopies(dir, BinauralSets.Source("builtin-40", true, null))
            assertEquals(
                setOf("builtin-40-p$v-e1.bin", "builtin-40-p$v-e0.bin", "file-0123456789abcdef-p$v-e1.bin"),
                dir.list()!!.toSet())

            // A user's file only clears its own older preparations
            File(dir, "file-0123456789abcdef-p${v + 50}-e0.bin").writeText("x")
            File(dir, "builtin-41-p$v-e1.bin").writeText("x")
            BinauralSets.removeStaleCopies(dir, BinauralSets.Source("file-0123456789abcdef", false, "/x.sofa"))
            assertEquals(
                setOf("builtin-40-p$v-e1.bin", "builtin-40-p$v-e0.bin", "builtin-41-p$v-e1.bin",
                    "file-0123456789abcdef-p$v-e1.bin"),
                dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }
}
