package me.timschneeberger.rootlessjamesdsp.utils.github

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Lets the UI stop a download, including one blocked in a read. */
class CancelToken {
    @Volatile var isCancelled = false
        private set
    @Volatile private var current: HttpResponse? = null

    fun cancel() {
        isCancelled = true
        current?.close()
    }

    internal fun attach(r: HttpResponse?) {
        current = r
        if (r != null && isCancelled) r.close()
    }

    internal fun check() { if (isCancelled) throw GithubException.Cancelled() }
}

/**
 * Downloads one file from a repository and proves it arrived whole.
 *
 * The download is pinned to the commit the user browsed, so the file is the
 * one that was listed and odd branch names never reach a URL. It is written
 * to "name.part" first and only renamed once its size and git blob hash
 * match the tree; anything else deletes the part file. The parked version
 * wrote straight to the final name, so a dropped connection left a broken
 * file that every later run skipped as "already downloaded".
 *
 * Files kept in Git LFS show up in the tree as a ~130 byte pointer. When
 * the verified download is such a pointer, the real file is fetched from
 * GitHub's LFS host and checked against the pointer's SHA-256 and size.
 */
class ProfileDownloader(private val http: HttpTransport) {

    /**
     * @param onBytes called with the number of new bytes as they arrive
     * @throws GithubException on any failure; no partial file is left behind
     */
    fun download(
        repo: String, commitSha: String, file: RemoteFile, target: File,
        cancel: CancelToken = CancelToken(), onBytes: (Long) -> Unit = {},
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        try {
            val url = "$RAW/$repo/$commitSha/${RepoInput.encodePath(file.path)}"
            // Git names a file by SHA-1 over "blob <size>", a zero byte, and then the
            // contents. The size is known from the tree, so the header goes in
            // first; a file of any other length cannot produce the same hash.
            val blob = fetch(url, part, cancel, file.size, "blob ${file.size}\u0000", onBytes) { code, headers ->
                GithubErrors.classify(code, headers, "raw", System.currentTimeMillis())
            }
            if (blob.size != file.size || hex(blob.sha1) != file.sha.lowercase())
                throw GithubException.Corrupt()

            val pointer = if (blob.size < RemoteFile.LFS_POINTER_MAX) LfsPointer.parse(part.readBytes()) else null
            if (pointer == null) {
                moveInto(part, target)
                return
            }
            val media = "$MEDIA/$repo/$commitSha/${RepoInput.encodePath(file.path)}"
            val real = fetch(media, part, cancel, pointer.size, null, onBytes) { code, _ ->
                GithubException.LfsUnavailable(code)
            }
            if (real.size != pointer.size || hex(real.sha256) != pointer.oid) throw GithubException.Corrupt()
            moveInto(part, target)
        } catch (e: Throwable) {
            part.delete()
            if (cancel.isCancelled) throw GithubException.Cancelled()
            throw GithubErrors.wrap(e)
        }
    }

    private class Fetched(val size: Long, val sha1: ByteArray, val sha256: ByteArray)

    /**
     * Streams [url] into [into], hashing as it goes. Stops as soon as more
     * than [expected] bytes arrive. [sha1Prefix] is hashed before the body.
     */
    private fun fetch(
        url: String, into: File, cancel: CancelToken, expected: Long, sha1Prefix: String?,
        onBytes: (Long) -> Unit, error: (Int, Map<String, String>) -> GithubException,
    ): Fetched {
        cancel.check()
        val resp = http.get(url, null)
        cancel.attach(resp)
        try {
            if (resp.code != 200) throw error(resp.code, resp.headers)
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1Prefix?.let { sha1.update(it.toByteArray(Charsets.US_ASCII)) }
            val sha256 = MessageDigest.getInstance("SHA-256")
            var n = 0L
            into.outputStream().use { out ->
                val buf = ByteArray(64 shl 10)
                val input: InputStream = resp.body
                while (true) {
                    cancel.check()
                    val r = input.read(buf)
                    if (r < 0) break
                    n += r
                    // Larger than promised: no point fetching the rest
                    if (n > expected) throw GithubException.Corrupt()
                    out.write(buf, 0, r)
                    sha1.update(buf, 0, r)
                    sha256.update(buf, 0, r)
                    onBytes(r.toLong())
                }
            }
            cancel.check()
            return Fetched(n, sha1.digest(), sha256.digest())
        } finally {
            cancel.attach(null)
            resp.close()
        }
    }

    private fun moveInto(part: File, target: File) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    companion object {
        const val RAW = "https://raw.githubusercontent.com"
        const val MEDIA = "https://media.githubusercontent.com/media"

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/** A Git LFS pointer file: "version https://git-lfs.github.com/spec/v1", "oid sha256:...", "size n". */
data class LfsPointer(val oid: String, val size: Long) {
    companion object {
        private const val HEADER = "version https://git-lfs.github.com/spec/v1"

        fun parse(bytes: ByteArray): LfsPointer? {
            if (bytes.size >= RemoteFile.LFS_POINTER_MAX) return null
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
            if (!text.startsWith(HEADER)) return null
            var oid: String? = null
            var size: Long? = null
            text.lineSequence().forEach { line ->
                if (line.startsWith("oid sha256:")) oid = line.removePrefix("oid sha256:").trim().lowercase()
                if (line.startsWith("size ")) size = line.removePrefix("size ").trim().toLongOrNull()
            }
            val o = oid ?: return null
            val s = size ?: return null
            if (!Regex("^[0-9a-f]{64}$").matches(o) || s < 0) return null
            return LfsPointer(o, s)
        }
    }
}
