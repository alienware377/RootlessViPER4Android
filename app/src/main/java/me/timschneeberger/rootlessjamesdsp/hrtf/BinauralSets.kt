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
import kotlin.math.floor
import kotlin.math.min
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
    internal const val PREP_VERSION = 1
    private const val BUILTIN_PREFIX = "builtin-"
    /** What the engine takes per response (BinauralUpmixLoadHrir). */
    private const val MIN_TAPS = 16
    private const val MAX_TAPS = 1024

    class Source(val key: String, val builtIn: Boolean, val path: String?)

    /** A set ready for one engine rate. */
    class ForRate(val hrir: FloatArray, val taps: Int, val fs: Int, val cOff: Int) {
        val hash: Int = hrir.contentHashCode() * 31 + cOff * 7 + fs
    }

    /** A set brought to one rate, and the prepared set it was made from. */
    private class Ready(val from: PreparedHrtfSet, val set: ForRate)

    /**
     * Why the set in use could not be brought to the engine's rate: the
     * rate, and the highest rate the set can serve. Kept until the next
     * push that gets past choosing a set.
     */
    class RateProblem(val rate: Int, val maxRate: Int)

    private val memory = ConcurrentHashMap<String, PreparedHrtfSet>()
    private val failures = ConcurrentHashMap<String, String>()
    private val rateProblems = ConcurrentHashMap<String, RateProblem>()
    /** Sets already brought to a rate, newest last, by cache key and rate. */
    private val ready = object : LinkedHashMap<String, Ready>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ready>?) = size > READY_KEEP
    }
    private const val READY_KEEP = 4
    /** The resampler, swapped out by the unit tests, which cannot load the native library. */
    internal var resample: (FloatArray, Int, Int, Int, Int) -> FloatArray? = { h, count, taps, from, to ->
        try {
            JdspImpResToolbox.ResampleResponses(h, count, taps, from, to)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "binaural-sets").apply { priority = Thread.MIN_PRIORITY }
    }

    /** Null when the file choice points at nothing. */
    fun source(context: Context, kind: String, file: String): Source? {
        if (kind != SOURCE_FILE)
            return Source("$BUILTIN_PREFIX${BuildConfig.VERSION_CODE}", true, null)
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
                        f.parentFile?.let { removeStaleCopies(it, src) }
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

    /**
     * Deletes prepared copies in [dir] that nothing will read again: those
     * of [src] made by an older version of the preparation and, for the
     * built-in set, those made for other app versions. Each app version
     * makes its own copy of the built-in set (its key holds the version
     * code), and before this every one of them stayed behind for good.
     */
    internal fun removeStaleCopies(dir: File, src: Source) {
        val current = "${src.key}-p$PREP_VERSION-"
        dir.listFiles()?.filter { f ->
            val n = f.name
            !n.startsWith(current) &&
                (n.startsWith("${src.key}-") || (src.builtIn && n.startsWith(BUILTIN_PREFIX)))
        }?.forEach { it.delete() }
    }

    /** What the card shows when the set cannot be used at the engine's rate, or null. */
    fun rateProblem(src: Source, evenOut: Boolean): RateProblem? = rateProblems[cacheKey(src, evenOut)]

    /**
     * Forgets any rate problem, for a push that turns the effect off before
     * it gets to choosing a rate. Tells the card if that changes what it shows.
     */
    fun clearRateProblems(context: Context) {
        if (rateProblems.isEmpty()) return
        rateProblems.clear()
        context.sendLocalBroadcast(Intent(Constants.ACTION_BINAURAL_STATUS_CHANGED))
    }

    /**
     * The set at the engine's rate. Remembers the result, so a settings
     * change while the card is on does not resample the set all over again,
     * and remembers when the rate is one the set cannot serve, for the card.
     */
    fun forRate(context: Context, src: Source, evenOut: Boolean, set: PreparedHrtfSet, rate: Int): ForRate? {
        val key = cacheKey(src, evenOut)
        val out = forRate(key, set, rate)
        val problem = if (out == null) RateProblem(rate, maxRate(set)) else null
        val before = rateProblems[key]
        if (problem == null) rateProblems.remove(key) else rateProblems[key] = problem
        if (before?.rate != problem?.rate || before?.maxRate != problem?.maxRate)
            context.sendLocalBroadcast(Intent(Constants.ACTION_BINAURAL_STATUS_CHANGED))
        return out
    }

    /** [forRate] without the card's bookkeeping; [key] names the set and its preparation. */
    internal fun forRate(key: String, set: PreparedHrtfSet, rate: Int): ForRate? {
        val slot = "$key@$rate"
        synchronized(ready) {
            ready[slot]?.takeIf { it.from === set }?.let { return it.set }
        }
        val made = resampleTo(set, rate) ?: return null
        synchronized(ready) { ready[slot] = Ready(set, made) }
        return made
    }

    private fun resampleTo(set: PreparedHrtfSet, rate: Int): ForRate? {
        val from = set.fs.roundToInt()
        if (rate == from)
            return if (set.taps in MIN_TAPS..MAX_TAPS) ForRate(set.hrir, set.taps, rate, set.cOff) else null
        if (rate > maxRate(set)) return null
        val out = resample(set.hrir, BinauralSpeakers.COUNT * 2, set.taps, from, rate) ?: return null
        val taps = out.size / (BinauralSpeakers.COUNT * 2)
        if (taps < MIN_TAPS || taps > MAX_TAPS) return null
        val cOff = (set.cOff * rate.toDouble() / from).roundToInt().coerceIn(0, taps - 1)
        return ForRate(out, taps, rate, cOff)
    }

    /**
     * The highest rate [set] can be brought to: its responses get longer
     * with the rate, and the engine takes at most [MAX_TAPS] taps. The
     * resampler makes round(taps * to / from) of them.
     */
    internal fun maxRate(set: PreparedHrtfSet): Int {
        val from = set.fs.roundToInt()
        return min(384000.0, floor((MAX_TAPS + 0.5) * from / set.taps)).toInt()
            .let { if ((set.taps * it.toDouble() / from + 0.5).toInt() > MAX_TAPS) it - 1 else it }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
