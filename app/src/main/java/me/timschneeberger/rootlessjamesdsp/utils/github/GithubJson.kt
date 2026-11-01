package me.timschneeberger.rootlessjamesdsp.utils.github

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter

/**
 * Reads GitHub's replies as a stream and writes the compact form the cache
 * keeps. The compact form uses the same field names as GitHub, so one
 * reader handles both.
 *
 * Streaming matters for trees: a big repository's recursive tree is tens of
 * megabytes of JSON (AutoEq: 15.2 MB, 52581 entries), and reading that into
 * one string and a DOM is an out-of-memory crash on a small phone. Only the
 * matching files are ever kept.
 */
object GithubJson {

    /** Every extension a library can use; listings keep all of them so one cache entry serves both libraries. */
    val PROFILE_EXTENSIONS = listOf(".vdc", ".irs", ".wav", ".flac")

    /** Stop reading a tree after this many entries; GitHub's own limit is 100k. */
    const val MAX_TREE_ENTRIES = 100_000

    /** Stop reading a tree after this many bytes. */
    const val MAX_TREE_BYTES = 20L shl 20

    // ------------------------------------------------------------- search

    fun readSearch(r: Reader): SearchPage = JsonReader(r).use { j ->
        var total = 0
        var incomplete = false
        val items = mutableListOf<RepoInfo>()
        j.beginObject()
        while (j.hasNext()) {
            when (j.nextName()) {
                "total_count" -> total = j.nextInt()
                "incomplete_results" -> incomplete = j.nextBoolean()
                "items" -> {
                    j.beginArray()
                    while (j.hasNext()) readRepo(j)?.let(items::add)
                    j.endArray()
                }
                else -> j.skipValue()
            }
        }
        j.endObject()
        SearchPage(total, incomplete, items)
    }

    fun readRepo(r: Reader): RepoInfo =
        JsonReader(r).use { readRepo(it) } ?: throw IllegalStateException("No repository in reply")

    private fun readRepo(j: JsonReader): RepoInfo? {
        var name: String? = null
        var branch: String? = null
        var desc: String? = null
        var stars = 0
        var archived = false
        var fork = false
        var pushed: String? = null
        var size = 0L
        j.beginObject()
        while (j.hasNext()) {
            when (j.nextName()) {
                "full_name" -> name = str(j)
                "default_branch" -> branch = str(j)
                "description" -> desc = str(j)
                "stargazers_count" -> stars = num(j).toInt()
                "archived" -> archived = bool(j)
                "fork" -> fork = bool(j)
                "pushed_at" -> pushed = str(j)
                "size" -> size = num(j)
                else -> j.skipValue()
            }
        }
        j.endObject()
        return RepoInfo(name ?: return null, branch ?: "main", desc, stars, archived, fork, pushed, size)
    }

    fun writeSearch(p: SearchPage): String = write { w ->
        w.beginObject()
        w.name("total_count").value(p.total)
        w.name("incomplete_results").value(p.incomplete)
        w.name("items").beginArray()
        p.items.forEach { writeRepo(w, it) }
        w.endArray()
        w.endObject()
    }

    fun writeRepo(r: RepoInfo): String = write { writeRepo(it, r) }

    private fun writeRepo(w: JsonWriter, r: RepoInfo) {
        w.beginObject()
        w.name("full_name").value(r.fullName)
        w.name("default_branch").value(r.defaultBranch)
        w.name("description").value(r.description)
        w.name("stargazers_count").value(r.stars)
        w.name("archived").value(r.archived)
        w.name("fork").value(r.fork)
        w.name("pushed_at").value(r.pushedAt)
        w.name("size").value(r.sizeKb)
        w.endObject()
    }

    // --------------------------------------------------------------- trees

    /**
     * Reads a git tree, keeping blobs whose name ends in one of [extensions]
     * and the names of top-level folders. Empty files are left out and
     * counted. Nothing is de-duplicated: two files with the same name in
     * different folders are different files.
     *
     * @param prefix the folder the tree was listed from, put back in front of
     *        each path so paths are always from the repository root
     */
    fun readTree(
        r: Reader,
        extensions: Collection<String> = PROFILE_EXTENSIONS,
        prefix: String = "",
        maxEntries: Int = MAX_TREE_ENTRIES,
        keepNestedFolders: Boolean = false,
    ): TreeListing {
        val files = mutableListOf<RemoteFile>()
        val folders = mutableListOf<String>()
        var truncated = false
        var empty = 0
        val lowerExt = extensions.map { it.lowercase() }
        val pre = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        try {
            JsonReader(r).use { j ->
                j.beginObject()
                while (j.hasNext()) {
                    when (j.nextName()) {
                        "truncated" -> truncated = truncated || bool(j)
                        "empty_dropped" -> empty += num(j).toInt()
                        "tree" -> {
                            var count = 0
                            j.beginArray()
                            while (j.hasNext()) {
                                if (++count > maxEntries) { truncated = true; return@use }
                                var path: String? = null
                                var type: String? = null
                                var sha: String? = null
                                var size = -1L
                                j.beginObject()
                                while (j.hasNext()) {
                                    when (j.nextName()) {
                                        "path" -> path = str(j)
                                        "type" -> type = str(j)
                                        "sha" -> sha = str(j)
                                        "size" -> size = num(j)
                                        else -> j.skipValue()
                                    }
                                }
                                j.endObject()
                                if (path == null) continue
                                when (type) {
                                    "tree" -> if (keepNestedFolders || !path.contains('/')) folders += pre + path
                                    "blob" -> {
                                        val lower = path.lowercase()
                                        if (lowerExt.none { lower.endsWith(it) }) continue
                                        if (size == 0L) { empty++; continue }
                                        if (sha == null || size < 0) continue
                                        files += RemoteFile(pre + path, size, sha)
                                    }
                                }
                            }
                            j.endArray()
                        }
                        else -> j.skipValue()
                    }
                }
                j.endObject()
            }
        } catch (_: SizeLimitExceeded) {
            truncated = true
        }
        return TreeListing(
            files.sortedWith(compareBy({ it.name.lowercase() }, { it.path.lowercase() })),
            folders.sortedBy { it.lowercase() },
            truncated,
            empty,
        )
    }

    fun writeTree(t: TreeListing): String = write { w ->
        w.beginObject()
        w.name("truncated").value(t.truncated)
        w.name("empty_dropped").value(t.emptyDropped)
        w.name("tree").beginArray()
        t.folders.forEach { f ->
            w.beginObject().name("path").value(f).name("type").value("tree").endObject()
        }
        t.files.forEach { f ->
            w.beginObject()
                .name("path").value(f.path).name("type").value("blob")
                .name("sha").value(f.sha).name("size").value(f.size)
                .endObject()
        }
        w.endArray()
        w.endObject()
    }

    /** Reads the compact form back; paths and folders in it are already from the root. */
    fun readCachedTree(text: String, extensions: Collection<String>): TreeListing =
        readTree(StringReader(text), extensions, keepNestedFolders = true)

    // -------------------------------------------------------- suggestions

    /**
     * The suggested sources list kept in this app's own repository:
     * {"sources":[{"repo":"a/b","branch":"x","kinds":["vdc","irs"],"note":"..."}]}
     */
    fun readSuggestions(r: Reader): List<Suggestion> = JsonReader(r).use { j ->
        val out = mutableListOf<Suggestion>()
        j.beginObject()
        while (j.hasNext()) {
            if (j.nextName() != "sources") { j.skipValue(); continue }
            j.beginArray()
            while (j.hasNext()) {
                var repo: String? = null
                var branch: String? = null
                var note: String? = null
                val kinds = mutableListOf<String>()
                j.beginObject()
                while (j.hasNext()) when (j.nextName()) {
                    "repo" -> repo = str(j)
                    "branch" -> branch = str(j)
                    "note" -> note = str(j)
                    "kinds" -> { j.beginArray(); while (j.hasNext()) str(j)?.let(kinds::add); j.endArray() }
                    else -> j.skipValue()
                }
                j.endObject()
                if (repo != null && RepoInput.parse(repo)?.repo == repo) out += Suggestion(repo, branch, kinds, note)
            }
            j.endArray()
        }
        j.endObject()
        out
    }

    // ------------------------------------------------------------ helpers

    private fun str(j: JsonReader): String? =
        if (j.peek() == JsonToken.NULL) { j.nextNull(); null } else j.nextString()

    private fun num(j: JsonReader): Long =
        if (j.peek() == JsonToken.NULL) { j.nextNull(); 0L } else j.nextLong()

    private fun bool(j: JsonReader): Boolean =
        if (j.peek() == JsonToken.NULL) { j.nextNull(); false } else j.nextBoolean()

    private fun write(block: (JsonWriter) -> Unit): String {
        val sw = StringWriter()
        JsonWriter(sw).use(block)
        return sw.toString()
    }
}

/** Thrown by [LimitedInputStream] once more than its limit has been read. */
class SizeLimitExceeded : RuntimeException("Reply too large")

/** Refuses to read past [limit] bytes, so a huge reply cannot fill the memory or the data plan. */
class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0 && ++count > limit) throw SizeLimitExceeded()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > limit) throw SizeLimitExceeded()
        }
        return n
    }
}
