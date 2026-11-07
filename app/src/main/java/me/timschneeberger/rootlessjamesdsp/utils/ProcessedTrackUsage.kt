package me.timschneeberger.rootlessjamesdsp.utils

import android.media.AudioAttributes

/**
 * Decides what kind of audio the processed output track says it is.
 *
 * Android Auto, and other features that send phone audio somewhere else
 * (some screen mirroring and "play phone audio on this device" features), do
 * not show up as an output device. They register a rule that grabs every track
 * marked as media and sends it on. The rule compares the track's usage
 * exactly, so a track marked "unknown" is skipped and plays on the phone
 * speaker instead, while the music app's own track (which this app silences)
 * is the only thing sent to the car.
 *
 * Marking the track as media lets those rules pick it up. On every ordinary
 * output (speaker, wired, USB, Bluetooth) Android puts unknown and media in
 * the same group, so the track goes to the same device at the same volume as
 * before. The old type is kept behind a Troubleshooting switch in case some
 * device behaves differently.
 */
object ProcessedTrackUsage {
    /** First Android version whose spatial audio can pick up a media track (12L). */
    const val SPATIALIZER_SDK = 32

    /** The usage to put on the processed output track. */
    fun usage(legacyUnknownType: Boolean): Int =
        if (legacyUnknownType) AudioAttributes.USAGE_UNKNOWN
        else AudioAttributes.USAGE_MEDIA

    /**
     * Whether the track has to opt out of the system's spatial audio.
     *
     * Android only spatializes media and game tracks. The old unknown track
     * was never spatialized, so a media track opts out to keep headphones
     * sounding exactly as they did. Spatial audio would also run on top of
     * the effects this app already applies.
     */
    fun optOutOfSpatializer(sdkInt: Int, usage: Int): Boolean =
        sdkInt >= SPATIALIZER_SDK &&
                (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME)
}
