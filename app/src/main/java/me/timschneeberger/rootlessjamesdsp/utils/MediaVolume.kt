package me.timschneeberger.rootlessjamesdsp.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * The media volume in dB, 0 at full volume, for the loudness correction.
 *
 * Android says what attenuation an index means on a given output
 * (AudioManager.getStreamVolumeDb). Where that answer cannot be right, the
 * index is turned into dB with the curve Android itself uses for media by
 * default.
 */
object MediaVolume {
    /** What a muted stream counts as. The engine goes no lower either. */
    const val MUTED_DB = -100.0

    /**
     * AOSP's default media volume curve (DEFAULT_MEDIA_VOLUME_CURVE in
     * default_volume_tables.xml): points of percent of the index range and dB.
     */
    private val defaultCurve = arrayOf(
        1 to -58.0,
        20 to -40.0,
        60 to -17.0,
        100 to 0.0,
    )

    /**
     * [index] on a stream that runs from [min] to [max], in dB, the way
     * AudioPolicy's VolumeCurve turns an index into a level: scaled to the
     * curve's own steps with integer division, then a straight line between
     * the two points either side. An index below the curve's first point is
     * muted.
     */
    fun indexToDb(index: Int, min: Int, max: Int): Double {
        if (max <= min) return 0.0
        val i = index.coerceIn(min, max)
        val first = defaultCurve.first().first
        val last = defaultCurve.last().first
        val steps = 1 + last - first
        val idx = (steps * (i - min)) / (max - min)
        if (idx < first) return MUTED_DB
        if (idx >= last) return defaultCurve.last().second
        var p = 1
        while (defaultCurve[p].first < idx) p++
        val (i0, db0) = defaultCurve[p - 1]
        val (i1, db1) = defaultCurve[p]
        return db0 + (db1 - db0) * (idx - i0).toDouble() / (i1 - i0).toDouble()
    }

    /**
     * What the platform reported for this index, or null when it cannot be
     * the level that is heard. A Bluetooth output with absolute volume
     * reports 0 dB at every index, because the headphones do the attenuation
     * themselves, yet they do get quieter.
     */
    fun usablePlatformDb(reported: Float, index: Int, max: Int): Double? {
        if (reported.isNaN()) return null
        if (reported == Float.NEGATIVE_INFINITY) return MUTED_DB
        if (reported.isInfinite()) return null
        if (index < max && reported >= -0.01f) return null
        return reported.toDouble().coerceIn(MUTED_DB, 0.0)
    }

    /**
     * [index] of [max] in dB on Android [sdk]. getStreamMinVolume and
     * getStreamVolumeDb ([min] and [platformDb]) only exist from Android 9;
     * before that they are never called and the default curve is all there is.
     */
    fun levelDb(sdk: Int, index: Int, max: Int, min: () -> Int, platformDb: () -> Float): Double {
        if (sdk < Build.VERSION_CODES.P)
            return indexToDb(index, 0, max)
        return usablePlatformDb(platformDb(), index, max) ?: indexToDb(index, min(), max)
    }

    /** The current media volume in dB, 0 when it cannot be read. */
    fun currentDb(context: Context): Double {
        val am = context.getSystemService<AudioManager>() ?: return 0.0
        val stream = AudioManager.STREAM_MUSIC
        return try {
            val index = am.getStreamVolume(stream)
            levelDb(Build.VERSION.SDK_INT, index, am.getStreamMaxVolume(stream),
                { am.getStreamMinVolume(stream) },
                { am.getStreamVolumeDb(stream, index, outputType(am)) })
        } catch (e: Exception) {
            Timber.w(e, "currentDb: could not read the media volume")
            0.0
        } catch (e: LinkageError) {
            // A system missing a method it should have; better the volume
            // untracked than the app gone.
            Timber.w(e, "currentDb: could not read the media volume")
            0.0
        }
    }

    /** The kind of output media plays on now, as getStreamVolumeDb wants it. */
    private fun outputType(am: AudioManager): Int {
        sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(media).firstOrNull()?.type
        }.valueOrNull()?.let { return it }
        // Older versions cannot say; a connected headset is the likeliest.
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return headsetTypes.firstOrNull { it in outputs } ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private val headsetTypes = listOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
    )

    /**
     * Calls [onChange] with the media volume in dB whenever it changes, and on
     * [start]. Android announces volume changes with a broadcast it does not
     * document, which every volume tool relies on; a change of output is
     * caught too, since the same index can mean a different level there.
     */
    class Tracker(private val context: Context, private val onChange: (Double) -> Unit) {
        private var last = Double.NaN
        private var registered = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                if (intent.action == VOLUME_CHANGED_ACTION && stream != AudioManager.STREAM_MUSIC)
                    return
                report()
            }
        }

        /** Reads the volume again and passes it on if it moved. */
        fun report() {
            val db = currentDb(context)
            if (db != last) {
                last = db
                onChange(db)
            }
        }

        fun start() {
            if (!registered) {
                val filter = IntentFilter().apply {
                    addAction(VOLUME_CHANGED_ACTION)
                    addAction(STREAM_DEVICES_CHANGED_ACTION)
                    addAction(AudioManager.ACTION_HEADSET_PLUG)
                }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
            last = Double.NaN
            report()
        }

        fun stop() {
            if (!registered) return
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Tracker.stop: receiver was not registered")
            }
            registered = false
        }

        private companion object {
            const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
            const val STREAM_DEVICES_CHANGED_ACTION = "android.media.STREAM_DEVICES_CHANGED_ACTION"
            const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        }
    }
}
