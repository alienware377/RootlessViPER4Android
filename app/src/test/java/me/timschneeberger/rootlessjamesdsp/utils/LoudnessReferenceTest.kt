package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessReferenceTest {

    private val noMedia: () -> Double = { throw AssertionError("the media volume should not be read") }

    @Test
    fun `a reference never set is taken from the volume on switching on`() {
        assertFalse(LoudnessReference.isChosen(null, null, null))
        assertEquals(-17f, LoudnessReference.onSwitchOn(false, -17.0))
    }

    @Test
    fun `a reference the card took itself is taken again`() {
        assertFalse(LoudnessReference.isChosen(-17f, 0f, -17f))
        assertFalse(LoudnessReference.isChosen(-17f, null, -17f))
        assertEquals(-30f, LoudnessReference.onSwitchOn(false, -30.0))
    }

    @Test
    fun `a reference set by hand is left alone`() {
        // Moved after the card took it
        assertTrue(LoudnessReference.isChosen(-20f, 0f, -17f))
        assertTrue(LoudnessReference.isChosen(-17f, 3f, -17f))
        // Set with the button or a slider, which drops the copy
        assertTrue(LoudnessReference.isChosen(-17f, 0f, null))
        assertTrue(LoudnessReference.isChosen(0f, 0f, null))
        // Settings from before the copy existed: anything stored was set by hand
        assertTrue(LoudnessReference.isChosen(-25f, null, null))
        assertTrue(LoudnessReference.isChosen(null, -6f, null))
        assertNull(LoudnessReference.onSwitchOn(true, -17.0))
    }

    @Test
    fun `the listening volume set on the card counts when the media volume is not followed`() {
        assertEquals(-25.0, LoudnessReference.listeningDb(false, -25.0, -31.5, noMedia), 0.0)
        assertEquals(-25f, LoudnessReference.onSwitchOn(false, LoudnessReference.listeningDb(false, -25.0, null, noMedia)))
    }

    @Test
    fun `smooth volume's level counts while it holds the media volume`() {
        assertEquals(-31.5, LoudnessReference.listeningDb(true, 0.0, -31.5, noMedia), 0.0)
        assertEquals(-31.5f, LoudnessReference.onSwitchOn(false, LoudnessReference.listeningDb(true, 0.0, -31.5, noMedia)))
    }

    @Test
    fun `otherwise the media volume counts`() {
        assertEquals(-17.0, LoudnessReference.listeningDb(true, -40.0, null) { -17.0 }, 0.0)
    }

    @Test
    fun `a muted volume leaves the reference alone`() {
        assertNull(LoudnessReference.onSwitchOn(false, MediaVolume.MUTED_DB))
        assertNull(LoudnessReference.onSwitchOn(false, Double.NaN))
    }

    @Test
    fun `the reference is held to the card's range`() {
        assertEquals(-60f, LoudnessReference.onSwitchOn(false, -72.0))
        assertEquals(0f, LoudnessReference.onSwitchOn(false, 0.0))
    }

    @Test
    fun `switching on at the volume taken changes nothing`() {
        val freqs = LoudnessCurve.logSpaced(10.0, 20000.0, 64)
        for (volume in listOf(-5.0, -17.0, -23.4, -45.0)) {
            val ref = LoudnessReference.onSwitchOn(false, volume)!!.toDouble()
            for (mode in listOf(LoudnessCurve.MODE_CLASSIC, LoudnessCurve.MODE_ISO)) {
                val s = LoudnessCurve.Settings(mode = mode, refLevel = ref, refOffset = 0.0)
                // The engine is sent the volume as a float too.
                val heard = LoudnessCurve.responseDb(s, volume.toFloat().toDouble(), freqs)
                for (i in freqs.indices)
                    assertEquals("mode $mode at $volume dB, ${freqs[i]} Hz", 0.0, heard[i], 1e-3)
            }
        }
    }
}
