package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presets are the only place where the ported intensity mapping survives; the settings screen
 * reads and writes them through [VoiceIsolationPreset.matching]. These tests pin that the stored
 * endpoints are representable, unique and round-trip through the mix controls.
 */
class VoiceIsolationPresetTest {
    @Test
    fun storageValuesAreUniqueAndUseTheDocumentedKeys() {
        assertEquals(
            listOf("podcast", "film", "gaming", "maximum", "custom"),
            VoiceIsolationPreset.entries.map { it.storageValue },
        )
        assertEquals(VoiceIsolationPreset.entries.size, VoiceIsolationPreset.entries.distinctBy { it.storageValue }.size)
    }

    @Test
    fun unknownOrMissingPreferenceFallsBackToTheFilmMix() {
        assertSame(VoiceIsolationPreset.Film, VoiceIsolationPreset.fromStorageValue("film"))
        assertSame(VoiceIsolationPreset.Film, VoiceIsolationPreset.fromStorageValue(null))
        assertSame(VoiceIsolationPreset.Film, VoiceIsolationPreset.fromStorageValue("karaoke"))
    }

    @Test
    fun everyPresetEndpointsRoundTripThroughTheSliderMatching() {
        for (preset in VoiceIsolationPreset.entries - VoiceIsolationPreset.Custom) {
            assertSame(
                "$preset no longer matches itself",
                preset,
                VoiceIsolationPreset.matching(preset.reductionPct, preset.boostDb),
            )
        }
    }

    @Test
    fun anyMixThatIsNotAPresetReportsCustom() {
        assertSame(VoiceIsolationPreset.Custom, VoiceIsolationPreset.matching(70f, 1f))
        assertSame(VoiceIsolationPreset.Custom, VoiceIsolationPreset.matching(0f, 0f))
        assertSame(VoiceIsolationPreset.Custom, VoiceIsolationPreset.matching(100f, 0f))
        // the custom entry itself must not shadow a real preset
        assertSame(VoiceIsolationPreset.Custom, VoiceIsolationPreset.matching(65f, 0f))
    }

    @Test
    fun matchingIgnoresRoundingNoiseFromTheSliders() {
        assertSame(VoiceIsolationPreset.Podcast, VoiceIsolationPreset.matching(85.04f, 2.03f))
    }

    @Test
    fun presetEndpointsStayInsideTheRangesTheMixerAndTheUiAccept() {
        for (preset in VoiceIsolationPreset.entries) {
            val reduction = preset.reductionPct / 100f
            val settings = VoiceIsolationSettings(reduction, preset.voiceGain).normalized()

            assertEquals("$preset reduction escapes 0..100", reduction, settings.backgroundReduction, 1e-5f)
            assertEquals("$preset voice gain gets clamped away", preset.voiceGain, settings.voiceGain, 1e-5f)
            assertEquals("$preset boost escapes the slider range", preset.boostDb, VoiceIsolationSettings.gainToBoostDb(preset.voiceGain), 0.01f)
            assertTrue(
                "$preset boost escapes the 6 dB ceiling",
                preset.boostDb in 0f..VoiceIsolationSettings.MAXIMUM_BOOST_DB,
            )
        }
    }
}
