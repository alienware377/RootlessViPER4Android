package me.timschneeberger.rootlessjamesdsp.utils

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedTrackUsageTest {

    @Test
    fun `the processed track plays as media by default`() {
        // Android Auto's rule only takes media tracks, so anything else stays
        // on the phone speaker while the car gets the silenced original.
        assertEquals(AudioAttributes.USAGE_MEDIA, ProcessedTrackUsage.usage(legacyUnknownType = false))
    }

    @Test
    fun `the old unknown type is still there when asked for`() {
        assertEquals(AudioAttributes.USAGE_UNKNOWN, ProcessedTrackUsage.usage(legacyUnknownType = true))
    }

    @Test
    fun `a media track opts out of spatial audio from Android 12L on`() {
        val media = ProcessedTrackUsage.usage(legacyUnknownType = false)
        assertTrue(ProcessedTrackUsage.optOutOfSpatializer(32, media))
        assertTrue(ProcessedTrackUsage.optOutOfSpatializer(35, media))
        assertTrue(ProcessedTrackUsage.optOutOfSpatializer(32, AudioAttributes.USAGE_GAME))
    }

    @Test
    fun `older Android and the unknown type need no opt out`() {
        val media = ProcessedTrackUsage.usage(legacyUnknownType = false)
        val unknown = ProcessedTrackUsage.usage(legacyUnknownType = true)
        // The call does not exist before 12L.
        assertFalse(ProcessedTrackUsage.optOutOfSpatializer(31, media))
        assertFalse(ProcessedTrackUsage.optOutOfSpatializer(29, media))
        // Spatial audio never takes an unknown track, so nothing changes there.
        assertFalse(ProcessedTrackUsage.optOutOfSpatializer(35, unknown))
    }
}
