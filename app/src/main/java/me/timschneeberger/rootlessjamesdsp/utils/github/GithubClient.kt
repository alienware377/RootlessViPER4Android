package me.timschneeberger.rootlessjamesdsp.utils.github

import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.net.URLEncoder

/**
 * Everything the library asks GitHub, behind the cache.
 *
 * Request budget without a token: a search is one request from the search
 * bucket; opening a repository is one request for the branch's commit and
 * one for its tree, and both are cached, so opening it again costs nothing.
 * Search results are never checked one by one in the background, since that
 * alone would use a quarter of the hourly allowance.
 */
class GithubClient(
    private val http: HttpTransport,
    private val cache: GithubCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * A value and where it came from. [limitedUntil] > 0 means GitHub's limit
     * is used up and this is saved data; [stale] means saved data was used
     * because GitHub could not be reached.
     */
    class Result<T>(val value: T, val limitedUntil: Long = 0, val stale: Boolean = false) {
        fun <R> map(f: (T) -> R) = Result(f(value), limitedUntil, stale)
    }

    // ------------------------------------------------------------- search

    fun search(text: String, topicOnly: Boolean, page: Int, force: Boolean = false): Result<SearchPage> {
        val q = SearchQuery.build(text, topicOnly)
        val sort = if (SearchQuery.sortByStars(text)) "&sort=stars&order=desc" else ""
        val url = "$API/search/repositories?q=${URLEncoder.encode(q, "UTF-8")}$sort&per_page=$PAGE_SIZE&page=$page"
        return cached("search:$q:$sort:$page", DAY, GithubErrors.SEARCH, force,
            parse = { GithubJson.readSearch(StringReader(it)) }) {
            request(url, GithubErrors.SEARCH) { body ->
                GithubJson.readSearch(InputStreamReader(body, Charsets.UTF_8)).let { it to GithubJson.writeSearch(it) }
            }
        }
    }

    /** Whether another page of results exists; the API stops at 1000 results. */
    fun hasMore(page: SearchPage, pageNo: Int) = pageNo * PAGE_SIZE < minOf(page.total, 1000)

    // ------------------------------------------------------- repositories

    fun repoInfo(repo: String, force: Boolean = false): Result<RepoInfo> =
        cached("repo:${repo.lowercase()}", DAY, GithubErrors.CORE, force,
            parse = { GithubJson.readRepo(StringReader(it)) }) {
            request("$API/repos/$repo", GithubErrors.CORE) { body ->
                GithubJson.readRepo(InputStreamReader(body, Charsets.UTF_8)).let { it to GithubJson.writeRepo(it) }
            }
        }

    /**
     * The commit a branch points at. Asked for as a bare SHA, so the reply is
     * 40 bytes rather than the whole commit. GitHub answers 422 for a branch
     * that does not exist and 404 for a repository that does not.
     */
    fun headSha(repo: String, branch: String, force: Boolean = false): Result<String> =
        cached("head:${repo.lowercase()}:$branch", HEAD_TTL, GithubErrors.CORE, force, parse = { it }) {
            try {
                request("$API/repos/$repo/commits/${RepoInput.encodePath(branch)}", GithubErrors.CORE,
                    accept = "application/vnd.github.sha") { body ->
                    val sha = body.readBytes().toString(Charsets.UTF_8).trim()
                    if (!SHA.matches(sha)) throw GithubException.BadReply(IllegalStateException("Not a SHA"))
                    sha to sha
                }
            } catch (e: GithubException.Http) {
                if (e.code == 422) throw GithubException.NoSuchBranch(branch) else throw e
            }
        }

    /**
     * Works out what a pasted link points at: the branch, the folder in it,
     * and the commit. With no branch in the link the default branch is used;
     * with "/tree/a/b/c" each split is tried, shortest branch first.
     * Throws rather than guessing, so a failed lookup is never saved.
     */
    fun resolve(ref: RepoRef): Triple<String, String, Result<String>> {
        if (ref.treeRest == null) {
            val branch = repoInfo(ref.repo).value.defaultBranch
            return Triple(branch, "", headSha(ref.repo, branch))
        }
        var lastMiss: GithubException? = null
        for ((branch, folder) in RepoInput.candidates(ref.treeRest)) {
            try {
                return Triple(branch, folder, headSha(ref.repo, branch))
            } catch (e: GithubException.NoSuchBranch) {
                lastMiss = e
            }
        }
        throw lastMiss ?: GithubException.NoSuchBranch(ref.treeRest)
    }

    // -------------------------------------------------------------- trees

    /**
     * Lists the profile files under [folder] at commit [sha]. A commit's tree
     * never changes, so this is cached for good (until the cache is full).
     * A truncated result is cached too: it means "pick a folder".
     */
    fun listFiles(repo: String, sha: String, folder: String, extensions: Collection<String>): Result<TreeListing> {
        val f = folder.trim('/')
        val treeish = if (f.isEmpty()) sha else "$sha:${RepoInput.encodePath(f)}"
        val result = cached("tree:${repo.lowercase()}@$sha:$f", GithubCache.FOREVER, GithubErrors.CORE, false,
            parse = { GithubJson.readCachedTree(it, GithubJson.PROFILE_EXTENSIONS) }) {
            try {
                request("$API/repos/$repo/git/trees/$treeish?recursive=1", GithubErrors.CORE) { body ->
                    val reader = InputStreamReader(LimitedInputStream(body, GithubJson.MAX_TREE_BYTES), Charsets.UTF_8)
                    val t = GithubJson.readTree(reader, GithubJson.PROFILE_EXTENSIONS, f)
                    // A cut-short list is not worth keeping: only its "too big" verdict is
                    val kept = if (t.truncated) t.copy(files = emptyList()) else t
                    kept to GithubJson.writeTree(kept)
                }
            } catch (e: GithubException.NotFound) {
                if (f.isNotEmpty()) throw GithubException.NoSuchFolder(f) else throw e
            } catch (e: GithubException.Http) {
                if (e.code == 422 && f.isNotEmpty()) throw GithubException.NoSuchFolder(f) else throw e
            }
        }
        return result.map { it.only(extensions) }
    }

    /** The folders directly inside [folder]: one small request, cached for good. */
    fun listFolders(repo: String, sha: String, folder: String): Result<List<String>> {
        val f = folder.trim('/')
        val treeish = if (f.isEmpty()) sha else "$sha:${RepoInput.encodePath(f)}"
        return cached("dirs:${repo.lowercase()}@$sha:$f", GithubCache.FOREVER, GithubErrors.CORE, false,
            parse = { GithubJson.readCachedTree(it, emptyList()) }) {
            request("$API/repos/$repo/git/trees/$treeish", GithubErrors.CORE) { body ->
                val reader = InputStreamReader(LimitedInputStream(body, GithubJson.MAX_TREE_BYTES), Charsets.UTF_8)
                val t = GithubJson.readTree(reader, emptyList(), f)
                t to GithubJson.writeTree(t)
            }
        }.map { it.folders }
    }

    // ------------------------------------------- what earlier listings found

    /** Remembers how many usable files a repository had, so search can say "no matching files" without asking. */
    fun recordMatches(repo: String, kind: String, count: Int) =
        cache.put("matches:$kind:${repo.lowercase()}", count.toString())

    fun knownMatches(repo: String, kind: String): Int? =
        cache.get("matches:$kind:${repo.lowercase()}")
            ?.takeIf { cache.isFresh(it, WEEK) }?.text?.trim()?.toIntOrNull()

    // -------------------------------------------------------- suggestions

    /** The suggested sources list from this app's own repository; empty if it cannot be had. */
    fun suggestions(force: Boolean = false): List<Suggestion> = runCatching {
        cached("suggested", WEEK, "raw", force, parse = { GithubJson.readSuggestions(StringReader(it)) }) {
            request(SUGGESTIONS_URL, "raw") { body ->
                val text = LimitedInputStream(body, 256 shl 10).readBytes().toString(Charsets.UTF_8)
                GithubJson.readSuggestions(StringReader(text)) to text
            }
        }.value
    }.getOrDefault(emptyList())

    // ----------------------------------------------------------- plumbing

    private fun <T> request(url: String, bucket: String, accept: String? = JSON, read: (InputStream) -> T): T {
        val resp = try { http.get(url, accept) } catch (e: Exception) { throw GithubErrors.wrap(e) }
        resp.use {
            if (it.code !in 200..299) {
                val err = GithubErrors.classify(it.code, it.headers, bucket, now())
                if (err is GithubException.RateLimited) cache.setLimited(err.bucket, err.resetAtMs)
                throw err
            }
            try {
                return read(it.body)
            } catch (e: GithubException) {
                throw e
            } catch (e: SizeLimitExceeded) {
                throw GithubException.BadReply(e)
            } catch (e: Exception) {
                throw GithubErrors.wrap(e)
            }
        }
    }

    /**
     * Serves fresh saved data without asking; while the bucket is limited,
     * serves saved data of any age (or says when to try again) without
     * asking; otherwise asks and saves the answer.
     */
    private fun <T> cached(
        key: String, ttl: Long, bucket: String, force: Boolean,
        parse: (String) -> T, fetch: () -> Pair<T, String>,
    ): Result<T> {
        val hit = cache.get(key)
        val saved = hit?.let { runCatching { parse(it.text) }.getOrNull() }
        if (saved != null && !force && cache.isFresh(hit, ttl)) return Result(saved)

        val until = cache.limitedUntil(bucket)
        if (until > 0) {
            if (saved != null) return Result(saved, limitedUntil = until)
            throw GithubException.RateLimited(bucket, until)
        }
        return try {
            val (value, text) = fetch()
            cache.put(key, text)
            Result(value)
        } catch (e: GithubException.RateLimited) {
            if (saved != null) Result(saved, limitedUntil = e.resetAtMs) else throw e
        } catch (e: GithubException.Offline) {
            if (saved != null) Result(saved, stale = true) else throw e
        }
    }

    companion object {
        const val API = "https://api.github.com"
        private const val JSON = "application/vnd.github+json"
        const val PAGE_SIZE = 30
        const val SUGGESTIONS_URL =
            "https://raw.githubusercontent.com/alienware377/RootlessViPER4Android/HEAD/suggested-sources.json"

        private const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val HEAD_TTL = 6 * HOUR
        const val WEEK = 7 * DAY
        private val SHA = Regex("^[0-9a-f]{40}$")
    }
}
