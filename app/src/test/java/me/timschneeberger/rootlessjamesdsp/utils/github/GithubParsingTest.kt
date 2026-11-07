package me.timschneeberger.rootlessjamesdsp.utils.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.StringReader

/** Link parsing, search queries, error mapping and reply parsing, on recorded GitHub replies. */
class GithubParsingTest {

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/github/$name")!!.readBytes().toString(Charsets.UTF_8)

    // ------------------------------------------------------------ links

    @Test
    fun `plain names and links parse to the repository`() {
        for (s in listOf("user/repo", "github.com/user/repo", "https://github.com/user/repo",
            "https://www.github.com/user/repo/", "http://github.com/user/repo.git",
            "git@github.com:user/repo.git", "  github.com/user/repo?tab=readme#top ")) {
            assertEquals(s, RepoRef("user/repo", null), RepoInput.parse(s))
        }
    }

    @Test
    fun `other hosts and half links are refused`() {
        for (s in listOf("", "user", "github.com/user", "gitlab.com/user/repo",
            "https://example.com/user/repo", "user name/repo", "user/re po")) {
            assertNull(s, RepoInput.parse(s))
        }
    }

    @Test
    fun `everything after tree is kept so a slash branch survives`() {
        assertEquals(RepoRef("u/r", "feature/x/some/folder"),
            RepoInput.parse("https://github.com/u/r/tree/feature/x/some/folder"))
        // Every split point is a candidate, not only the first segment
        assertEquals(
            listOf("feature" to "x/some/folder", "feature/x" to "some/folder",
                "feature/x/some" to "folder", "feature/x/some/folder" to ""),
            RepoInput.candidates("feature/x/some/folder"))
    }

    @Test
    fun `a plus in a branch is not turned into a space`() {
        assertEquals(RepoRef("jadilson12/Viper4Android-presets", "v4a+2.7"),
            RepoInput.parse("https://github.com/jadilson12/Viper4Android-presets/tree/v4a+2.7"))
        assertEquals(RepoRef("u/r", "v4a+2.7/DDC files"),
            RepoInput.parse("github.com/u/r/tree/v4a%2B2.7/DDC%20files"))
    }

    @Test
    fun `a file link points at the folder holding it`() {
        assertEquals(RepoRef("u/r", "main/IRS"), RepoInput.parse("github.com/u/r/blob/main/IRS/hall.irs"))
    }

    @Test
    fun `path segments are encoded one by one`() {
        assertEquals("a%20b/c%2Bd/%23e", RepoInput.encodePath("a b/c+d/#e"))
        assertEquals("v4a%2B2.7", RepoInput.encodePath("v4a+2.7"))
    }

    @Test
    fun `an empty search is the viper4android topic and forks are included`() {
        assertEquals("topic:viper4android fork:true", SearchQuery.build("", true))
        assertEquals("topic:viper4android fork:true", SearchQuery.build("  ", false))
        assertEquals("harman topic:viper4android fork:true", SearchQuery.build(" harman ", true))
        assertEquals("harman fork:true", SearchQuery.build("harman", false))
        assertEquals("topic:ddc fork:false", SearchQuery.build("topic:ddc fork:false", true))
        assertTrue(SearchQuery.sortByStars(""))
        assertFalse(SearchQuery.sortByStars("harman"))
    }

    // ----------------------------------------------------------- errors

    private val now = 1_000_000_000_000L

    @Test
    fun `a 403 is only a rate limit when GitHub says so`() {
        val limited = GithubErrors.classify(403, mapOf(
            "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1000000600",
            "x-ratelimit-resource" to "search"), GithubErrors.CORE, now)
        assertTrue(limited is GithubException.RateLimited)
        limited as GithubException.RateLimited
        assertEquals("search", limited.bucket)
        assertEquals(1_000_000_600_000L, limited.resetAtMs)

        assertTrue(GithubErrors.classify(403, mapOf("x-ratelimit-remaining" to "41"), GithubErrors.CORE, now)
            is GithubException.Refused)
        assertTrue(GithubErrors.classify(403, emptyMap(), GithubErrors.CORE, now) is GithubException.Refused)
    }

    @Test
    fun `secondary limits and 429 are rate limits too`() {
        val retry = GithubErrors.classify(403, mapOf("retry-after" to "30"), GithubErrors.CORE, now)
        assertEquals(now + 30_000, (retry as GithubException.RateLimited).resetAtMs)
        val bare = GithubErrors.classify(429, emptyMap(), GithubErrors.SEARCH, now)
        assertEquals("search", (bare as GithubException.RateLimited).bucket)
        assertTrue(bare.resetAtMs > now)
    }

    @Test
    fun `other codes get their own messages`() {
        assertTrue(GithubErrors.classify(404, emptyMap(), "core", now) is GithubException.NotFound)
        assertTrue(GithubErrors.classify(409, emptyMap(), "core", now) is GithubException.EmptyRepo)
        assertTrue(GithubErrors.classify(451, emptyMap(), "core", now) is GithubException.Blocked)
        assertEquals(502, (GithubErrors.classify(502, emptyMap(), "core", now) as GithubException.Http).code)
        assertTrue(GithubErrors.wrap(java.net.SocketTimeoutException()) is GithubException.Offline)
    }

    // ----------------------------------------------------------- search

    @Test
    fun `search reply is read with badges and branches`() {
        val page = GithubJson.readSearch(StringReader(fixture("search_topic.json")))
        assertEquals(15, page.total)
        assertEquals(5, page.items.size)
        val wst = page.items.first { it.fullName == "WSTxda/ViperFX-RE-Releases" }
        assertTrue(wst.archived)
        assertFalse(wst.fork)
        assertEquals("v4a+2.7", page.items.first { it.fullName == "jadilson12/Viper4Android-presets" }.defaultBranch)
        assertEquals("master", page.items.first { it.fullName == "timschneeb/DDCToolbox" }.defaultBranch)

        // The cached compact form reads back the same
        assertEquals(page, GithubJson.readSearch(StringReader(GithubJson.writeSearch(page))))
    }

    // ------------------------------------------------------------- trees

    @Test
    fun `tree keeps every matching file and drops empty ones`() {
        val t = GithubJson.readTree(StringReader(fixture("tree_jadilson12.json")))
        assertFalse(t.truncated)
        assertEquals(5, t.emptyDropped)
        assertTrue(t.files.none { it.size == 0L })
        assertTrue(t.files.none { it.path.endsWith(".md") || it.path.endsWith(".zip") })
        // Same name in different folders: both kept
        val byName = t.files.groupBy { it.name.lowercase() }
        assertTrue(byName.values.count { it.size > 1 } >= 3)
        // Names outside ASCII come through untouched
        assertTrue(t.files.any { f -> f.path.any { it.code > 127 } })
        assertTrue(t.folders.isNotEmpty())
        assertTrue(t.folders.none { it.contains('/') })
    }

    @Test
    fun `extension filter and folder prefix apply`() {
        val t = GithubJson.readTree(StringReader(fixture("tree_jadilson12.json")), listOf(".vdc"), "sub/dir")
        assertTrue(t.files.isNotEmpty())
        assertTrue(t.files.all { it.path.startsWith("sub/dir/") && it.path.lowercase().endsWith(".vdc") })
    }

    @Test
    fun `cached tree reads back with nested folders and counts`() {
        val t = GithubJson.readTree(StringReader(fixture("tree_jadilson12.json")))
        val back = GithubJson.readCachedTree(GithubJson.writeTree(t.copy(folders = listOf("a", "a/b"))), GithubJson.PROFILE_EXTENSIONS)
        assertEquals(t.files, back.files)
        assertEquals(listOf("a", "a/b"), back.folders)
        assertEquals(t.emptyDropped, back.emptyDropped)
    }

    @Test
    fun `root folders of a big repository`() {
        val t = GithubJson.readTree(StringReader(fixture("tree_autoeq_root.json")))
        assertTrue(t.folders.containsAll(listOf("measurements", "results", "targets")))
        assertTrue(t.files.isEmpty())
    }

    /** A synthetic tree shaped like AutoEq's: GitHub's own truncated flag, at the very end. */
    private fun bigTree(entries: Int, truncated: Boolean): String {
        val sb = StringBuilder("{\"sha\":\"x\",\"tree\":[")
        for (i in 0 until entries) {
            if (i > 0) sb.append(',')
            sb.append("{\"path\":\"results/src$i/Headphone $i.wav\",\"mode\":\"100644\",\"type\":\"blob\",")
                .append("\"sha\":\"%040x\",\"size\":${100_000 + i},\"url\":\"https://api.github.com/x\"}".format(i))
        }
        return sb.append("],\"truncated\":$truncated}").toString()
    }

    @Test
    fun `github's truncated flag is honoured`() {
        val t = GithubJson.readTree(StringReader(bigTree(2000, truncated = true)))
        assertTrue(t.truncated)
        assertFalse(GithubJson.readTree(StringReader(bigTree(2000, truncated = false))).truncated)
    }

    @Test
    fun `entry and byte caps stop a huge tree without reading it all`() {
        val capped = GithubJson.readTree(StringReader(bigTree(500, truncated = false)), maxEntries = 100)
        assertTrue(capped.truncated)
        assertEquals(100, capped.files.size)

        val body = bigTree(20_000, truncated = false).toByteArray()
        val limited = LimitedInputStream(ByteArrayInputStream(body), 64 shl 10)
        val t = GithubJson.readTree(InputStreamReader(limited))
        assertTrue(t.truncated)
        assertTrue(limited.count < body.size)
    }

    // ------------------------------------------------------- suggestions

    @Test
    fun `the suggested sources file in this repository parses`() {
        val f = java.io.File("../suggested-sources.json").takeIf { it.exists() }
            ?: java.io.File("suggested-sources.json")
        val list = GithubJson.readSuggestions(f.reader())
        assertTrue(list.size >= 3)
        assertTrue(list.all { RepoInput.parse(it.repo)?.repo == it.repo })
        assertEquals("v4a+2.7", list.first { it.repo == "jadilson12/Viper4Android-presets" }.branch)
        assertTrue(list.all { s -> s.kinds.all { it == "vdc" || it == "irs" } })
    }
}
