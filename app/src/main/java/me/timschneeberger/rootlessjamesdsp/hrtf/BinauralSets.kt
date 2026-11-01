package me.timschneeberger.rootlessjamesdsp.hrtf

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Where the Binaural upmix gets its head set from, and the small prepared
 * copy of it that is kept so a .sofa file is only read once.
 *
 * Nothing here runs unless the card is switched on: the engine push asks for
 * a set only then, and the card only shows what is already known.
 */
object BinauralSets {
    const val SOURCE_BUILTIN = "0"
    const val SOURCE_FILE = "1"
    const val BUILTIN_ASSET = "hrtf/mit_kemar_normal_pinna.sofa"
    /** Bump when HrtfPrep changes what it makes, so old copies are redone. */
    private const val PREP_VERSION = 1

    class Source(val key: String, val builtIn: Boolean, val path: String?)

    /** A set ready for one engine rate. */
    class ForRate(val hrir: FloatArray, val taps: Int, val fs: Int, val cOff: Int) {
        val hash: Int get() = hrir.contentHashCode() * 31 + cOff * 7 + fs
    }

    private val memory = ConcurrentHashMap<String, PreparedHrtfSet>()
    private val failures = ConcurrentHashMap<String, String>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "binaural-sets").apply { priority = Thread.MIN_PRIORITY }
    }

    /** Null when the file choice points at nothing. */
    fun source(context: Context, kind: String, file: String): Source? {
        if (kind != SOURCE_FILE)
            return Source("builtin-${BuildConfig.VERSION_CODE}", true, null)
        if (file.isBlank()) return null
        val f = File(FileLibraryPreference.createFullPathCompat(context, file))
        if (!f.isFile) return null
        val id = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        return Source("file-" + sha1(id), false, f.absolutePath)
    }

    private fun cacheKey(src: Source, evenOut: Boolean) =
        "${src.key}-p$PREP_VERSION-e${if (evenOut) 1 else 0}"

    private fun cacheFile(context: Context, key: String) =
        File(File(context.filesDir, "hrtf"), "$key.bin")

    /** The prepared set if it is in memory or on disk, without reading the .sofa. */
    fun cached(context: Context, src: Source, evenOut: Boolean): PreparedHrtfSet? {
        val key = cacheKey(src, evenOut)
        memory[key]?.let { return it }
        val f = cacheFile(context, key)
        if (!f.isFile) return null
        val set = try { f.inputStream().buffered().use { HrtfSetFile.read(it) } } catch (e: IOException) { null }
        if (set == null) {
            f.delete()
            return null
        }
        memory[key] = set
        return set
    }

    /** Why the last attempt at this set failed, in plain words, or null. */
    fun failure(src: Source, evenOut: Boolean): String? = failures[cacheKey(src, evenOut)]

    fun isWorking(src: Source, evenOut: Boolean): Boolean = cacheKey(src, evenOut) in running

    /**
     * Reads and prepares the set in the background, unless that is already
     * happening or has already failed for this exact file. Says
     * [Constants.ACTION_BINAURAL_SET_READY] when it is done either way.
     */
    fun request(context: Context, src: Source, evenOut: Boolean) {
        val key = cacheKey(src, evenOut)
        if (memory.containsKey(key) || failures.containsKey(key) || !running.add(key)) return
        val app = context.applicationContext
        worker.execute {
            try {
                val pfd = open(app, src)
                when (val o = SofaParserClient.parse(app, pfd)) {
                    is SofaParserClient.Outcome.Ok -> {
                        val set = HrtfPrep.prepare(o.set, evenOut)
                        val f = cacheFile(app, key)
                        f.parentFile?.mkdirs()
                        // Copies made by an older version of the preparation
                        // are no longer needed.
                        f.parentFile?.listFiles()?.filter {
                            it.name.startsWith(src.key) && !it.name.startsWith("${src.key}-p$PREP_VERSION-")
                        }?.forEach { it.delete() }
                        val tmp = File(f.parentFile, f.name + ".tmp")
                        tmp.outputStream().buffered().use { HrtfSetFile.write(set, it) }
                        tmp.renameTo(f)
                        memory[key] = set
                        Timber.i("Binaural set ready: ${set.title} (${o.set.measurements} directions, ${set.fs} Hz)")
                    }
                    is SofaParserClient.Outcome.Failed -> {
                        Timber.w("Binaural set failed: ${o.why} ${o.detail}")
                        failures[key] = describe(app, o.why)
                    }
                }
            } catch (e: HrtfPrep.BadSetException) {
                Timber.w(e)
                failures[key] = app.getString(R.string.binaural_error_unusable)
            } catch (e: Exception) {
                Timber.e(e)
                failures[key] = app.getString(R.string.binaural_error_unreadable)
            } finally {
                running.remove(key)
                app.sendLocalBroadcast(Intent(Constants.ACTION_BINAURAL_SET_READY))
            }
        }
    }

    /** Lets a file that failed be tried again, after the user picks it anew. */
    fun forgetFailures() = failures.clear()

    private fun open(context: Context, src: Source): ParcelFileDescriptor {
        if (!src.builtIn)
            return ParcelFileDescriptor.open(File(src.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        // The built-in set is inside the APK and may be compressed there, so it
        // goes through a pipe rather than as a descriptor of the APK itself.
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                context.assets.open(BUILTIN_ASSET).use { inp ->
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> inp.copyTo(out) }
                }
            } catch (e: IOException) {
                Timber.w(e, "Writing the built-in set to the reader")
                try { write.close() } catch (_: IOException) {}
            }
        }.start()
        return read
    }

    private fun describe(context: Context, why: SofaParserClient.Failure): String = context.getString(
        when (why) {
            SofaParserClient.Failure.NOT_A_SOFA_FILE -> R.string.binaural_error_unreadable
            SofaParserClient.Failure.NOT_A_HEAD_SET -> R.string.binaural_error_not_hrir
            SofaParserClient.Failure.TOO_BIG -> R.string.binaural_error_too_big
            SofaParserClient.Failure.OUT_OF_RANGE -> R.string.binaural_error_unusable
            SofaParserClient.Failure.CRASHED -> R.string.binaural_error_unreadable
            SofaParserClient.Failure.TIMED_OUT,
            SofaParserClient.Failure.NO_HELPER -> R.string.binaural_error_helper
        })

    /** The set at the engine's rate. */
    fun forRate(set: PreparedHrtfSet, rate: Int): ForRate? {
        val from = set.fs.roundToInt()
        if (rate == from) return ForRate(set.hrir, set.taps, rate, set.cOff)
        val out = try {
            JdspImpResToolbox.ResampleResponses(set.hrir, BinauralSpeakers.COUNT * 2, set.taps, from, rate)
        } catch (e: UnsatisfiedLinkError) {
            null
        } ?: return null
        val taps = out.size / (BinauralSpeakers.COUNT * 2)
        if (taps < 16 || taps > 1024) return null
        val cOff = (set.cOff * rate.toDouble() / from).roundToInt().coerceIn(0, taps - 1)
        return ForRate(out, taps, rate, cOff)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
