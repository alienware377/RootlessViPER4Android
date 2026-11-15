package me.timschneeberger.rootlessjamesdsp.utils

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.github.FakePrefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LoudnessReferenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noMedia: () -> Double = { throw AssertionError("the media volume should not be read") }

    private lateinit var prefs: SharedPreferences

    // The real key names, so a renamed key in keys.xml fails here too.
    private val keys: LoudnessReference.Keys by lazy {
        val xml = listOf("src/main/res/values/keys.xml", "app/src/main/res/values/keys.xml")
            .map(::File).first { it.isFile }.readText()
        fun key(name: String) = Regex("""<string name="$name"[^>]*>([^<]+)</string>""")
            .find(xml)?.groupValues?.get(1) ?: throw AssertionError("$name missing from keys.xml")
        LoudnessReference.Keys(
            key("key_loudness_enable"),
            key("key_loudness_follow_volume"),
            key("key_loudness_volume"),
            key("key_loudness_reference_level"),
            key("key_loudness_reference_offset"),
        )
    }

    @Before
    fun setUp() {
        prefs = FakePrefsContext(tmp.newFolder()).getSharedPreferences(Constants.PREF_LOUDNESS, 0)
    }

    private fun level() = if (prefs.contains(keys.level)) prefs.getFloat(keys.level, 0f) else null
    private fun offset() = if (prefs.contains(keys.offset)) prefs.getFloat(keys.offset, 0f) else null

    private fun switchOn(mediaDb: Double, smoothDb: Double? = null) =
        LoudnessReference.captureOnSwitchOn(prefs, keys, smoothDb) { mediaDb }

    @Test
    fun `a reference never set is taken from the volume on switching on`() {
        assertFalse(LoudnessReference.isSet(null, null))
        assertEquals(-17f, LoudnessReference.onSwitchOn(false, -17.0))

        assertEquals(-17f, switchOn(-17.0))
        assertEquals(-17f, level())
        assertEquals(0f, offset())
        assertTrue(LoudnessReference.isSet(prefs, keys))
    }

    @Test
    fun `the reference taken at the first switch-on stays`() {
        switchOn(-17.0)
        // Switched off, turned down, switched on again to compare: the
        // correction for 18 dB down must still be there.
        assertNull(switchOn(-35.0))
        assertEquals(-17f, level())
    }

    @Test
    fun `a reference set by hand is left alone`() {
        assertTrue(LoudnessReference.isSet(-25f, null))
        assertTrue(LoudnessReference.isSet(null, -6f))
        assertTrue(LoudnessReference.isSet(0f, 0f))
        assertNull(LoudnessReference.onSwitchOn(true, -17.0))

        prefs.edit().putFloat(keys.level, -25f).commit()
        assertNull(switchOn(-17.0))
        assertEquals(-25f, level())
        assertNull(offset())

        // An offset alone counts too: it was moved by hand.
        prefs.edit().clear().putFloat(keys.offset, 4f).commit()
        assertNull(switchOn(-17.0))
        assertNull(level())
        assertEquals(4f, offset())
    }

    @Test
    fun `the listening volume set on the card counts when the media volume is not followed`() {
        assertEquals(-25.0, LoudnessReference.listeningDb(false, -25.0, -31.5, noMedia), 0.0)

        prefs.edit().putBoolean(keys.follow, false).putFloat(keys.volume, -25f).commit()
        assertEquals(-25f, LoudnessReference.captureOnSwitchOn(prefs, keys, -31.5, noMedia))
        assertEquals(-25f, level())
    }

    @Test
    fun `the listening volume defaults to 0 dB when not followed and never moved`() {
        prefs.edit().putBoolean(keys.follow, false).commit()
        assertEquals(0.0, LoudnessReference.listeningDb(prefs, keys, null, noMedia), 0.0)
    }

    @Test
    fun `smooth volume's level counts while it holds the media volume`() {
        assertEquals(-31.5, LoudnessReference.listeningDb(true, 0.0, -31.5, noMedia), 0.0)

        // Following is the default, and the card's own listening volume is
        // not what the engine is sent then.
        prefs.edit().putFloat(keys.volume, -40f).commit()
        assertEquals(-31.5f, LoudnessReference.captureOnSwitchOn(prefs, keys, -31.5, noMedia))
        assertEquals(-31.5f, level())
    }

    @Test
    fun `otherwise the media volume counts`() {
        assertEquals(-17.0, LoudnessReference.listeningDb(true, -40.0, null) { -17.0 }, 0.0)
        prefs.edit().putFloat(keys.volume, -40f).commit()
        assertEquals(-17.0, LoudnessReference.listeningDb(prefs, keys, null) { -17.0 }, 0.0)
    }

    @Test
    fun `a muted volume leaves the reference alone`() {
        assertNull(LoudnessReference.onSwitchOn(false, MediaVolume.MUTED_DB))
        assertNull(LoudnessReference.onSwitchOn(false, Double.NaN))

        assertNull(switchOn(MediaVolume.MUTED_DB))
        assertNull(level())
        assertNull(offset())
        // and is taken at the next switch-on instead
        assertEquals(-20f, switchOn(-20.0))
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
