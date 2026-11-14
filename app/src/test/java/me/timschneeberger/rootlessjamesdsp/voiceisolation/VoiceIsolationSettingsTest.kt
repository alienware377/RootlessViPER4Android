package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preferences screen stores a percentage and a dB boost; the mixer works on a 0..1 reduction
 * and a linear gain. These tests pin that boundary and the clamping that protects the audio path
 * from a corrupted preference value.
 */
class VoiceIsolationSettingsTest {
    @Test
    fun defaultsMatchThePortedFilmMix() {
        val settings = VoiceIsolationSettings()

        assertEquals(VoiceIsolationSettings.DEFAULT_REDUCTION, settings.backgroundReduction, 0f)
        assertEquals(1f, settings.voiceGain, 0f)
        assertTrue(!settings.autoMode)
    }

    @Test
    fun valuesAlreadyInRangeArePassedThroughUnchanged() {
        val settings = VoiceIsolationSettings(0.4f, 1.6f, autoMode = true)

        assertSame(settings, settings.normalized())
    }

    @Test
    fun outOfRangeValuesAreClampedIntoTheMixingRange() {
        assertEquals(0f, VoiceIsolationSettings(-0.5f, 0.2f).normalized().backgroundReduction, 1e-6f)
        // the mixer never attenuates the voice below unity
        assertEquals(VoiceIsolationSettings.MINIMUM_VOICE_GAIN, VoiceIsolationSettings(-0.5f, 0.2f).normalized().voiceGain, 1e-6f)
        assertEquals(1f, VoiceIsolationSettings(4f, 9f).normalized().backgroundReduction, 1e-6f)
        assertEquals(VoiceIsolationSettings.MAXIMUM_VOICE_GAIN, VoiceIsolationSettings(4f, 9f).normalized().voiceGain, 1e-6f)
    }

    @Test
    fun nonFiniteValuesFallBackToThePortedDefaults() {
        val settings = VoiceIsolationSettings(Float.NaN, Float.POSITIVE_INFINITY).normalized()

        assertEquals(VoiceIsolationSettings.DEFAULT_REDUCTION, settings.backgroundReduction, 0f)
        assertEquals(1f, settings.voiceGain, 0f)
    }

    @Test
    fun voiceReductionDefaultsOffAndIsClamped() {
        assertEquals(0f, VoiceIsolationSettings().voiceReduction, 0f)
        assertEquals(0f, VoiceIsolationSettings(voiceReduction = -1f).normalized().voiceReduction, 0f)
        assertEquals(1f, VoiceIsolationSettings(voiceReduction = 2f).normalized().voiceReduction, 0f)
        assertEquals(0f, VoiceIsolationSettings(voiceReduction = Float.NaN).normalized().voiceReduction, 0f)
    }

    @Test
    fun boostDbConvertsToTheDocumentedLinearGain() {
        assertEquals(1f, VoiceIsolationSettings.boostDbToGain(0f), 1e-6f)
        assertEquals(1.41254f, VoiceIsolationSettings.boostDbToGain(3f), 1e-4f)
        assertEquals(1.99526f, VoiceIsolationSettings.boostDbToGain(6f), 1e-4f)
    }

    @Test
    fun boostDbIsClampedToTheSixDecibelCeilingAndFloor() {
        assertEquals(1f, VoiceIsolationSettings.boostDbToGain(-12f), 1e-6f)
        assertEquals(VoiceIsolationSettings.boostDbToGain(6f), VoiceIsolationSettings.boostDbToGain(30f), 1e-6f)
        assertEquals(1f, VoiceIsolationSettings.boostDbToGain(Float.NaN), 0f)
    }

    @Test
    fun everyBoostThatTheUiCanProduceSurvivesNormalization() {
        var boostDb = 0f
        while (boostDb <= VoiceIsolationSettings.MAXIMUM_BOOST_DB) {
            val settings = VoiceIsolationSettings(
                VoiceIsolationSettings.DEFAULT_REDUCTION,
                VoiceIsolationSettings.boostDbToGain(boostDb),
            ).normalized()
            assertEquals(VoiceIsolationSettings.boostDbToGain(boostDb), settings.voiceGain, 1e-6f)
            boostDb += 0.5f
        }
    }

    @Test
    fun gainConvertsBackToTheSameBoost() {
        for (boostDb in listOf(0f, 1f, 2.5f, 4f, 6f)) {
            assertEquals(boostDb, VoiceIsolationSettings.gainToBoostDb(VoiceIsolationSettings.boostDbToGain(boostDb)), 1e-4f)
        }
        assertEquals(0f, VoiceIsolationSettings.gainToBoostDb(0f), 0f)
        assertEquals(VoiceIsolationSettings.MAXIMUM_BOOST_DB, VoiceIsolationSettings.gainToBoostDb(4f), 1e-4f)
    }
}
