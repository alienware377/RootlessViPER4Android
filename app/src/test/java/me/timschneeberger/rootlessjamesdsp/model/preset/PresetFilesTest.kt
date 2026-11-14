package me.timschneeberger.rootlessjamesdsp.model.preset

import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which settings files a preset, a device profile and a reset of the effects take. */
class PresetFilesTest {

    @Test
    fun `effect namespaces travel in presets`() {
        assertTrue(Preset.isSoundFile("dsp_balance.xml"))
        assertTrue(Preset.isSoundFile(Constants.PREF_EQ + ".xml"))
        assertTrue(Preset.isKnownEntry("dsp_balance.xml"))
        assertTrue(Preset.isKnownEntry("effect_layout.xml"))
        assertTrue(Preset.isKnownEntry(Preset.FILE_LIVEPROG))
    }

    @Test
    fun `the loudness, LUFS and voice isolation settings travel in presets`() {
        for (ns in listOf(Constants.PREF_LOUDNESS, Constants.PREF_LUFS, Constants.PREF_VOICEISOLATION)) {
            assertTrue(Preset.isSoundFile("$ns.xml"))
            assertTrue(Preset.isKnownEntry("$ns.xml"))
        }
    }

    @Test
    fun `smooth volume stays out of presets, profiles and resets`() {
        val file = Constants.PREF_SMOOTH_VOLUME + ".xml"
        assertFalse(Preset.isSoundFile(file))
        assertFalse(Preset.isKnownEntry(file))
    }

    @Test
    fun `other files are not sound settings`() {
        assertFalse(Preset.isSoundFile("application.xml"))
        assertFalse(Preset.isSoundFile("smooth_volume_state.xml"))
        assertFalse(Preset.isSoundFile("dsp_balance.bak"))
    }
}
