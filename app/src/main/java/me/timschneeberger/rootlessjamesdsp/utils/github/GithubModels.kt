package me.timschneeberger.rootlessjamesdsp.utils.github

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A repository the library can download from.
 *
 * [folder] narrows it to one folder (empty for the whole repository), which
 * is how repositories too big to list in one go stay usable.
 */
data class Source(
    val repo: String,
    val branch: String,
    val label: String,
    val folder: String = "",
    val builtIn: Boolean = false,
) {
    /** Two entries for the same repository and folder are the same source. */
    val key get() = "${repo.lowercase()}:${folder.trim('/')}"
}

/** One downloadable file from a repository tree. [path] is always from the repository root. */
data class RemoteFile(val path: String, val size: Long, val sha: String) {
    val name get() = path.substringAfterLast('/')
    val folder get() = path.substringBeforeLast('/', "")

    /**
     * Git LFS keeps a pointer of about 130 bytes in the repository instead
     * of the file. Its real size is only known once the pointer is read, so
     * the picker says so. (Every file under [LFS_POINTER_MAX] is checked for
     * a pointer after download; this hint is narrower because DDC files of
     * 600 bytes are normal.)
     */
    val maybeLfs get() = size in 100 until LFS_HINT_MAX

    companion object {
        /** The Git LFS spec caps a pointer file below this. */
        const val LFS_POINTER_MAX = 1024L
        private const val LFS_HINT_MAX = 200L
    }
}

data class RepoInfo(
    val fullName: String,
    val defaultBranch: String,
    val description: String?,
    val stars: Int,
    val archived: Boolean,
    val fork: Boolean,
    val pushedAt: String?,
    val sizeKb: Long,
)

data class SearchPage(val total: Int, val incomplete: Boolean, val items: List<RepoInfo>)

/**
 * What a tree listing found. [truncated] means the list is not complete
 * (GitHub cut it short, or it was too big to read safely), so [files] must
 * not be offered as everything the repository holds.
 */
data class TreeListing(
    val files: List<RemoteFile>,
    val folders: List<String>,
    val truncated: Boolean,
    val emptyDropped: Int,
)

/** A repository named on the app's own suggested sources list. */
data class Suggestion(val repo: String, val branch: String?, val kinds: List<String>, val note: String?)

/** What the user typed or pasted: a repository, and whatever followed "/tree/". */
data class RepoRef(val repo: String, val treeRest: String?)

object RepoInput {
    private val OWNER = Regex("^[A-Za-z0-9-]+$")
    private val NAME = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * Accepts "user/repo", "github.com/user/repo", a full URL, an SSH clone
     * address, and links into a branch or folder ("/tree/...") or a file
     * ("/blob/..."). Returns null for anything that is not a GitHub repository.
     *
     * What follows "/tree/" is kept whole: a branch name may itself contain
     * slashes, so only GitHub can say where the branch ends and the folder
     * starts. See [candidates].
     */
    fun parse(input: String): RepoRef? {
        var s = input.trim()
        if (s.startsWith("git@github.com:", ignoreCase = true)) s = s.substring(15)
        s = s.replace(Regex("^(?i)(https?://)?(www\\.)?"), "")
        if (s.startsWith("github.com/", ignoreCase = true)) s = s.substring(11)
        s = s.substringBefore('#').substringBefore('?').trim().trim('/')
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val name = parts[1].removeSuffix(".git")
        if (!OWNER.matches(owner) || !NAME.matches(name) || name == "." || name == "..") return null

        var rest: String? = null
        if (parts.size >= 4 && (parts[2] == "tree" || parts[2] == "blob")) {
            var segs = parts.drop(3).map(::decode)
            // A file link: the folder it sits in is what is wanted
            if (parts[2] == "blob" && segs.size > 1) segs = segs.dropLast(1)
            rest = segs.joinToString("/").ifEmpty { null }
        }
        return RepoRef("$owner/$name", rest)
    }

    /**
     * Every way to split "feature/x/some/folder" into a branch and a folder,
     * the shortest branch first, at most [max] of them. Each one costs a
     * request to try, so the list is kept short.
     */
    fun candidates(rest: String, max: Int = 4): List<Pair<String, String>> {
        val seg = rest.split('/').filter { it.isNotEmpty() }
        return (1..minOf(seg.size, max)).map { k ->
            seg.take(k).joinToString("/") to seg.drop(k).joinToString("/")
        }
    }

    /** Percent-decoding that leaves '+' alone: "v4a+2.7" is a real branch name. */
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** Encodes each path segment for a URL, keeping the slashes between them. */
    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

object SearchQuery {
    const val TOPIC = "topic:viper4android"

    /**
     * Free text on its own finds almost nothing useful (measured: "viper ddc"
     * gave two repositories, neither holding a single profile), while the
     * topic gives a short list of the right ones. So an empty search is the
     * topic, and the topic narrows typed words unless the user turns it off.
     * Forks are included so they can be shown with a badge.
     */
    fun build(text: String, topicOnly: Boolean): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val parts = mutableListOf<String>()
        if (t.isNotEmpty()) parts += t
        if ((topicOnly || t.isEmpty()) && !t.contains("topic:", ignoreCase = true)) parts += TOPIC
        if (!t.contains("fork:", ignoreCase = true)) parts += "fork:true"
        return parts.joinToString(" ")
    }

    /** Star order suits the topic list; typed words are better served by GitHub's best match. */
    fun sortByStars(text: String) = text.isBlank()
}
