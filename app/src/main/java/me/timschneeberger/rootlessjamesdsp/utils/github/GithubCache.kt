package me.timschneeberger.rootlessjamesdsp.utils.github

import java.io.File
import java.security.MessageDigest

/**
 * A small file cache for GitHub replies, in the app's cache folder.
 *
 * Without a token GitHub allows 60 browsing requests an hour and 10
 * searches a minute per IP address, and on mobile data many people share
 * one address. Conditional requests do not help (a 304 still counts when
 * unauthenticated, measured), so the only way to save quota is to not ask.
 *
 * Deliberately not SharedPreferences: those travel inside presets and
 * backups, and a cache has no business in either.
 */
class GithubCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 5L shl 20,
) {
    class Hit(val text: String, val savedAtMs: Long)

    fun get(key: String): Hit? {
        val f = fileFor(key)
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        val nl = raw.indexOf('\n')
        if (nl < 0) return null
        val saved = raw.substring(0, nl).toLongOrNull() ?: return null
        // Reading counts as use, so the eviction below drops the stalest first
        runCatching { f.setLastModified(now()) }
        return Hit(raw.substring(nl + 1), saved)
    }

    fun isFresh(hit: Hit, ttlMs: Long) = ttlMs == FOREVER || now() - hit.savedAtMs in 0 until ttlMs

    fun put(key: String, text: String) {
        runCatching {
            dir.mkdirs()
            val f = fileFor(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText("${now()}\n$text")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            f.setLastModified(now())
        }
        trim()
    }

    fun remove(key: String) { fileFor(key).delete() }

    /** When the given bucket ("core" or "search") may be used again, or 0. */
    fun limitedUntil(bucket: String): Long {
        val until = get(LIMIT_PREFIX + bucket)?.text?.trim()?.toLongOrNull() ?: return 0
        return if (until > now()) until else 0
    }

    fun setLimited(bucket: String, untilMs: Long) = put(LIMIT_PREFIX + bucket, untilMs.toString())

    /** Drops the least recently used entries once the folder is over its size limit. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(EXT) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fileFor(key: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, hex + EXT)
    }

    companion object {
        const val FOREVER = -1L
        private const val EXT = ".c"
        private const val LIMIT_PREFIX = "ratelimit:"
    }
}
