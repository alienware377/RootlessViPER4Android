package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVolumeTest {

    @Test
    fun `full volume is 0 dB and index 0 is muted`() {
        assertEquals(0.0, MediaVolume.indexToDb(15, 0, 15), 0.0)
        assertEquals(MediaVolume.MUTED_DB, MediaVolume.indexToDb(0, 0, 15), 0.0)
    }

    @Test
    fun `a 100 step stream lands on the curve's own points`() {
        assertEquals(-58.0, MediaVolume.indexToDb(1, 0, 100), 1e-9)
        assertEquals(-40.0, MediaVolume.indexToDb(20, 0, 100), 1e-9)
        assertEquals(-17.0, MediaVolume.indexToDb(60, 0, 100), 1e-9)
        assertEquals(-28.5, MediaVolume.indexToDb(40, 0, 100), 1e-9)
    }

    @Test
    fun `a 15 step stream is scaled with AudioPolicy's integer division`() {
        // 100 * 7 / 15 = 46, between 20 (-40 dB) and 60 (-17 dB)
        assertEquals(-40.0 + 23.0 * 26.0 / 40.0, MediaVolume.indexToDb(7, 0, 15), 1e-9)
        // 100 * 1 / 15 = 6, between 1 (-58 dB) and 20 (-40 dB)
        assertEquals(-58.0 + 18.0 * 5.0 / 19.0, MediaVolume.indexToDb(1, 0, 15), 1e-9)
    }

    @Test
    fun `louder index never means a lower level`() {
        for (max in listOf(15, 25, 30, 150)) {
            var last = Double.NEGATIVE_INFINITY
            for (i in 0..max) {
                val db = MediaVolume.indexToDb(i, 0, max)
                assertTrue("$i of $max: $db after $last", db >= last)
                last = db
            }
        }
    }

    @Test
    fun `out of range input is held`() {
        assertEquals(0.0, MediaVolume.indexToDb(99, 0, 15), 0.0)
        assertEquals(MediaVolume.MUTED_DB, MediaVolume.indexToDb(-4, 0, 15), 0.0)
        assertEquals(0.0, MediaVolume.indexToDb(3, 5, 5), 0.0)
    }

    @Test
    fun `the platform's answer is used unless it cannot be the level heard`() {
        assertEquals(-12.5, MediaVolume.usablePlatformDb(-12.5f, 9, 15)!!, 1e-6)
        assertEquals(0.0, MediaVolume.usablePlatformDb(0f, 15, 15)!!, 0.0)
        assertEquals(MediaVolume.MUTED_DB, MediaVolume.usablePlatformDb(Float.NEGATIVE_INFINITY, 0, 15)!!, 0.0)
        assertEquals(MediaVolume.MUTED_DB, MediaVolume.usablePlatformDb(-758f, 0, 15)!!, 0.0)
        // Absolute volume over Bluetooth: 0 dB below full volume is not what is heard.
        assertNull(MediaVolume.usablePlatformDb(0f, 7, 15))
        assertNull(MediaVolume.usablePlatformDb(Float.NaN, 7, 15))
        assertNull(MediaVolume.usablePlatformDb(Float.POSITIVE_INFINITY, 7, 15))
    }

    @Test
    fun `before Android 9 the methods it added are never called`() {
        // Calling either on Android 8 is a NoSuchMethodError.
        val missing = { throw NoSuchMethodError("not on this version") }
        for (sdk in listOf(26, 27))
            assertEquals(MediaVolume.indexToDb(7, 0, 15), MediaVolume.levelDb(sdk, 7, 15, missing, missing), 0.0)
    }

    @Test
    fun `from Android 9 the platform's answer comes first`() {
        assertEquals(-21.5, MediaVolume.levelDb(28, 7, 15, { 0 }, { -21.5f }), 1e-6)
        // Absolute volume: the curve, from the stream's own minimum.
        assertEquals(MediaVolume.indexToDb(7, 1, 15), MediaVolume.levelDb(34, 7, 15, { 1 }, { 0f }), 0.0)
    }
}
