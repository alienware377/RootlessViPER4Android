package me.timschneeberger.rootlessjamesdsp.api

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.matcheq.AutoEqIndex
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the few AutoEq repository files Match EQ needs: the results index,
 * one headphone's result CSV, and target curves.
 *
 * Nothing from AutoEq is bundled with the app. The measurements belong to the
 * people who made them and come with no licence of their own, so the app
 * fetches only the file the user picks, straight from the AutoEq repository,
 * the same way the AutoEQ profile search already fetches profiles.
 *
 * Files are read at the AutoEq commit the AutoEQ search service is built from
 * (AutoEqPackages' version.json), so a name and source picked in the search are
 * guaranteed to be in the index. If that cannot be found, the main branch is
 * used. Everything is cached under the app's cache folder per commit.
 *
 * All calls block: run them off the main thread.
 */
class AutoEqDataClient(context: Context) {

    class NotFoundException : Exception("not found")

    private val cacheRoot = File(context.cacheDir, "matcheq")

    /** The AutoEq commit to read from. Remembered for a week. */
    fun commit(): String {
        val f = File(cacheRoot, "commit.txt")
        val cached = runCatching { f.readText().trim() }.getOrNull()?.takeIf { COMMIT_RE.matches(it) }
        if (cached != null && System.currentTimeMillis() - f.lastModified() < WEEK_MS) return cached
        return runCatching {
            val json = JSONArray(get(VERSION_URL))
            json.getJSONObject(0).getString("commit").also {
                require(COMMIT_RE.matches(it))
                cacheRoot.mkdirs()
                f.writeText(it)
            }
        }.onFailure { Timber.w(it, "AutoEq version lookup failed") }
            .getOrNull() ?: cached ?: FALLBACK_REF
    }

    fun index(commit: String): List<AutoEqIndex.Entry> =
        AutoEqIndex.parse(cached(commit, "results/INDEX.md"))

    /** A headphone's result CSV. Throws [NotFoundException] when none was published. */
    fun resultCsv(commit: String, entry: AutoEqIndex.Entry): String =
        cached(commit, entry.csvPath())

    /** A target curve CSV from targets/. */
    fun targetCsv(commit: String, file: String): String =
        cached(commit, "targets/" + AutoEqIndex.encodeSegment(file))

    private fun cached(commit: String, path: String): String {
        val dir = File(cacheRoot, commit)
        // Readable but short, with a hash so two long names cannot collide.
        val safe = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        val local = File(dir, safe + "_" + Integer.toHexString(path.hashCode()))
        if (local.isFile && local.length() > 0) return local.readText()
        val text = get("$RAW_BASE/$commit/$path")
        dir.mkdirs()
        // Written aside and moved into place, so a half-written file is never read back.
        runCatching {
            val tmp = File(dir, local.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(local)) tmp.delete()
        }
        return text
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            val code = conn.responseCode
            if (code == 404) throw NotFoundException()
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } catch (e: FileNotFoundException) {
            throw NotFoundException()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val RAW_BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq"
        private const val VERSION_URL = "https://raw.githubusercontent.com/timschneeb/AutoEqPackages/main/version.json"
        private const val FALLBACK_REF = "master"
        private const val WEEK_MS = 7L * 24 * 3600 * 1000
        private val COMMIT_RE = Regex("[0-9a-f]{40}")
    }
}
