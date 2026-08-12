package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Browses GitHub repositories for convolver IRs and DDC profiles and downloads
 * them into a library folder. Everything uses the unauthenticated API, which
 * allows 60 requests an hour - plenty for casual browsing, and the error
 * message says so when the limit is hit.
 */
object GithubLibraryDownloader {

    data class Source(val repo: String, val branch: String, val label: String, val builtIn: Boolean = false)
    data class RemoteFile(val path: String, val name: String)

    /** One community pack carries both the DDC and the IRS collections. */
    fun defaultSources() = listOf(
        Source("programminghoch10/ViPER4AndroidRepackaged", "main",
            "ViPER4Android community pack", builtIn = true)
    )

    // ------------------------------------------------------------ user sources

    fun customSources(ctx: Context): MutableList<Source> {
        val store = ctx.getSharedPreferences(Constants.PREF_FILELIBRARY, Context.MODE_MULTI_PROCESS)
        val legacy = ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_MULTI_PROCESS)
        if (!store.contains("filelib_sources") && legacy.contains("filelib_sources")) {
            store.edit().putString("filelib_sources", legacy.getString("filelib_sources", null)).apply()
            legacy.edit().remove("filelib_sources").apply()
        }
        val raw = store.getString("filelib_sources", null) ?: return mutableListOf()
        val list = mutableListOf<Source>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Source(o.getString("repo"), o.getString("branch"), o.getString("label")))
            }
        }
        return list
    }

    fun saveCustomSources(ctx: Context, sources: List<Source>) {
        val arr = JSONArray()
        sources.forEach {
            arr.put(JSONObject().put("repo", it.repo).put("branch", it.branch).put("label", it.label))
        }
        ctx.getSharedPreferences(Constants.PREF_FILELIBRARY, Context.MODE_MULTI_PROCESS)
            .edit().putString("filelib_sources", arr.toString()).apply()
    }

    /**
     * Accepts "user/repo", "github.com/user/repo" or a full URL, optionally
     * with "/tree/branch". Returns null if it doesn't look like a repository.
     */
    fun parseRepoInput(input: String): Pair<String, String?>? {
        var s = input.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").removePrefix("github.com/")
            .trim('/')
        if (s.isEmpty()) return null
        var branch: String? = null
        val treeIdx = s.indexOf("/tree/")
        if (treeIdx > 0) {
            branch = s.substring(treeIdx + 6).substringBefore('/')
            s = s.substring(0, treeIdx)
        }
        val parts = s.split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return "${parts[0]}/${parts[1]}" to branch
    }

    // ------------------------------------------------------------- API calls

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = conn.responseCode
            if (code == 403) throw RateLimitException()
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    class RateLimitException :
        Exception("GitHub's hourly request limit was reached. Try again in a little while.")

    fun defaultBranch(repo: String): String =
        runCatching { JSONObject(get("https://api.github.com/repos/$repo")).getString("default_branch") }
            .getOrDefault("main")

    /**
     * Lists every file in the repository matching the wanted extensions,
     * de-duplicated by file name (the first occurrence wins).
     */
    fun listFiles(repo: String, branch: String, extensions: List<String>): List<RemoteFile> {
        val json = JSONObject(get("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1"))
        val tree = json.optJSONArray("tree") ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<RemoteFile>()
        for (i in 0 until tree.length()) {
            val entry = tree.getJSONObject(i)
            if (entry.optString("type") != "blob") continue
            val path = entry.getString("path")
            val lower = path.lowercase()
            if (extensions.none { lower.endsWith(it) }) continue
            val name = path.substringAfterLast('/')
            if (!seen.add(name.lowercase())) continue
            out.add(RemoteFile(path, name))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun searchRepositories(query: String): List<Source> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(get("https://api.github.com/search/repositories?q=$q&per_page=15"))
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Source>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            out.add(Source(o.getString("full_name"),
                o.optString("default_branch", "main"),
                o.getString("full_name")))
        }
        return out
    }

    /** Downloads one file; returns false if it already exists (dedupe). */
    fun download(repo: String, branch: String, file: RemoteFile, targetDir: File): Boolean {
        val existing = targetDir.list()?.map { it.lowercase() } ?: emptyList()
        if (existing.contains(file.name.lowercase())) return false
        val encodedPath = file.path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val conn = URL("https://raw.githubusercontent.com/$repo/$branch/$encodedPath")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            targetDir.mkdirs()
            File(targetDir, file.name).outputStream().use { out ->
                conn.inputStream.use { it.copyTo(out) }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }
}
