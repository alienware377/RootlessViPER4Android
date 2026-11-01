package me.timschneeberger.rootlessjamesdsp.utils.github

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The cache, the rate-limit handling and the verified download, against a
 * fake GitHub serving recorded replies. No test touches the network.
 */
class GithubClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000_000_000L

    private class Reply(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    /** Serves a reply per URL substring and counts the requests made. */
    private inner class FakeGithub : HttpTransport {
        val routes = LinkedHashMap<String, () -> Reply>()
        val calls = mutableListOf<String>()
        var offline = false
        override fun get(url: String, accept: String?): HttpResponse {
            calls += url
            if (offline) throw IOException("no network")
            val r = routes.entries.firstOrNull { url.contains(it.key) }?.value?.invoke()
                ?: Reply(404, "{\"message\":\"Not Found\"}".toByteArray())
            return HttpResponse(r.code, r.headers, ByteArrayInputStream(r.body))
        }
        fun json(key: String, text: String) { routes[key] = { Reply(200, text.toByteArray()) } }
    }

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/github/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val sha = "d3258669f1bb9e33ebed33b839a80a10d91c6236"
    private val fake = FakeGithub()
    private val cache by lazy { GithubCache(tmp.newFolder("cache"), { clock }) }
    private val client by lazy { GithubClient(fake, cache) { clock } }

    // ------------------------------------------------------------ caching

    @Test
    fun `a second search within a day asks nothing`() {
        fake.json("/search/repositories", fixture("search_topic.json"))
        val first = client.search("", true, 1)
        assertEquals(5, first.value.items.size)
        clock += 23 * 3_600_000L
        val second = client.search("", true, 1)
        assertEquals(first.value, second.value)
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls[0].contains("q=topic%3Aviper4android+fork%3Atrue"))
        assertTrue(fake.calls[0].contains("sort=stars"))

        clock += 2 * 3_600_000L
        client.search("", true, 1)
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `opening a repository costs two requests the first time and none after`() {
        fake.json("/commits/v4a%2B2.7", sha)
        fake.json("/git/trees/$sha", fixture("tree_jadilson12.json"))
        val head = client.headSha("jadilson12/Viper4Android-presets", "v4a+2.7")
        val files = client.listFiles("jadilson12/Viper4Android-presets", head.value, "", listOf(".vdc"))
        assertTrue(files.value.files.all { it.path.endsWith(".vdc") })
        assertEquals(2, fake.calls.size)

        client.headSha("jadilson12/Viper4Android-presets", "v4a+2.7")
        // The same commit's tree serves the other library too
        val irs = client.listFiles("jadilson12/Viper4Android-presets", head.value, "", listOf(".irs", ".wav", ".flac"))
        assertTrue(irs.value.files.isNotEmpty())
        assertEquals(2, fake.calls.size)

        // The branch is looked up again after six hours; the tree is not, the commit has not moved
        clock += 7 * 3_600_000L
        client.listFiles("jadilson12/Viper4Android-presets",
            client.headSha("jadilson12/Viper4Android-presets", "v4a+2.7").value, "", listOf(".vdc"))
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `a missing branch is said so and nothing guesses main`() {
        fake.routes["/commits/"] = { Reply(422, "{\"message\":\"No commit found for SHA: nope\"}".toByteArray()) }
        try {
            client.headSha("u/r", "nope")
            fail()
        } catch (e: GithubException.NoSuchBranch) {
            assertEquals("nope", e.branch)
        }
    }

    @Test
    fun `a tree link is split at the first branch that exists`() {
        fake.routes["/commits/feature/x"] = { Reply(200, sha.toByteArray()) }
        fake.routes["/commits/"] = { Reply(422, "{}".toByteArray()) }
        val (branch, folder, head) = client.resolve(RepoRef("u/r", "feature/x/some/folder"))
        assertEquals("feature/x", branch)
        assertEquals("some/folder", folder)
        assertEquals(sha, head.value)
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `a plain link uses the repository's real default branch`() {
        fake.json("/repos/u/r/commits/master", sha)
        fake.json("/repos/u/r", "{\"full_name\":\"u/r\",\"default_branch\":\"master\"}")
        val (branch, folder, _) = client.resolve(RepoRef("u/r", null))
        assertEquals("master", branch)
        assertEquals("", folder)
    }

    @Test
    fun `a failed lookup throws instead of handing back a guess`() {
        fake.offline = true
        try {
            client.resolve(RepoRef("u/r", null))
            fail()
        } catch (e: GithubException.Offline) {
        }
    }

    @Test
    fun `a missing repository is not called a network failure`() {
        try {
            client.headSha("nobody/nothing", "main")
            fail()
        } catch (e: GithubException.NotFound) {
        }
    }

    @Test
    fun `a missing folder in a tree link says so`() {
        try {
            client.listFiles("u/r", sha, "gone/away", listOf(".irs"))
            fail()
        } catch (e: GithubException.NoSuchFolder) {
            assertEquals("gone/away", e.folder)
        }
        assertTrue(fake.calls[0].endsWith("/git/trees/$sha:gone/away?recursive=1"))
    }

    // -------------------------------------------------------- rate limits

    private val limitedHeaders get() = mapOf(
        "x-ratelimit-remaining" to "0",
        "x-ratelimit-reset" to ((clock + 1_800_000) / 1000).toString(),
        "x-ratelimit-resource" to "core")

    @Test
    fun `once limited nothing is asked until the reset and saved data is used`() {
        fake.json("/commits/main", sha)
        client.headSha("u/r", "main")
        clock += 7 * 3_600_000L
        fake.routes["/commits/main"] = { Reply(403, "{}".toByteArray(), limitedHeaders) }
        val reset = clock + 1_800_000

        val stale = client.headSha("u/r", "main")
        assertEquals(sha, stale.value)
        assertEquals(reset / 1000 * 1000, stale.limitedUntil)
        assertEquals(2, fake.calls.size)

        // Nothing saved for this one: told when to come back, still without asking
        try {
            client.headSha("u/other", "main")
            fail()
        } catch (e: GithubException.RateLimited) {
            assertEquals("core", e.bucket)
        }
        assertEquals(2, fake.calls.size)

        // Search is a separate allowance and still works
        fake.json("/search/repositories", fixture("search_topic.json"))
        client.search("", true, 1)
        assertEquals(3, fake.calls.size)

        // After the reset GitHub is asked again
        clock = reset + 1
        fake.json("/commits/main", sha)
        assertEquals(0L, client.headSha("u/other", "main").limitedUntil)
        assertEquals(4, fake.calls.size)
    }

    @Test
    fun `offline with saved data serves it and says so`() {
        fake.json("/commits/main", sha)
        client.headSha("u/r", "main")
        clock += 7 * 3_600_000L
        fake.offline = true
        val r = client.headSha("u/r", "main")
        assertEquals(sha, r.value)
        assertTrue(r.stale)
    }

    @Test
    fun `a repository with no matching files is remembered`() {
        assertNull(client.knownMatches("u/r", "vdc"))
        client.recordMatches("u/r", "vdc", 0)
        assertEquals(0, client.knownMatches("U/R", "vdc"))
        assertNull(client.knownMatches("u/r", "irs"))
        clock += 8 * 24 * 3_600_000L
        assertNull(client.knownMatches("u/r", "vdc"))
    }

    @Test
    fun `a truncated tree is cached as too big without its partial list`() {
        val big = StringBuilder("{\"tree\":[")
        for (i in 0 until 50) big.append(if (i > 0) "," else "")
            .append("{\"path\":\"r/$i.wav\",\"type\":\"blob\",\"sha\":\"%040x\",\"size\":5000}".format(i))
        fake.json("/git/trees/", big.append("],\"truncated\":true}").toString())
        val t = client.listFiles("big/repo", sha, "", listOf(".wav")).value
        assertTrue(t.truncated)
        assertTrue(t.files.isEmpty())
        assertTrue(client.listFiles("big/repo", sha, "", listOf(".wav")).value.truncated)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `the cache drops the least recently used entries when full`() {
        val small = GithubCache(tmp.newFolder("small"), { clock }, maxBytes = 3000)
        for (i in 0 until 10) {
            clock += 1000
            small.put("k$i", "x".repeat(500))
        }
        assertNull(small.get("k0"))
        assertTrue(small.get("k9") != null)
    }

    // ------------------------------------------------------------ download

    private fun gitSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray())
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @Test
    fun `a good download lands under its name with no part file left`() {
        fake.routes["raw.githubusercontent.com/u/r/$sha/IRS/My%20Hall.irs"] = { Reply(200, content) }
        val dir = tmp.newFolder("lib")
        var seen = 0L
        ProfileDownloader(fake).download("u/r", sha, RemoteFile("IRS/My Hall.irs", content.size.toLong(), gitSha(content)),
            java.io.File(dir, "My Hall.irs")) { seen += it }
        assertArrayEquals(content, java.io.File(dir, "My Hall.irs").readBytes())
        assertEquals(content.size.toLong(), seen)
        assertEquals(listOf("My Hall.irs"), dir.list()!!.toList())
    }

    @Test
    fun `a cut-off download leaves nothing behind`() {
        fake.routes["raw.githubusercontent.com"] = { Reply(200, content.copyOf(100_000)) }
        val dir = tmp.newFolder("lib")
        try {
            ProfileDownloader(fake).download("u/r", sha, RemoteFile("a.irs", content.size.toLong(), gitSha(content)),
                java.io.File(dir, "a.irs"))
            fail()
        } catch (e: GithubException.Corrupt) {
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `a download of the right size but wrong contents is refused`() {
        val wrong = content.clone().also { it[1234] = (it[1234] + 1).toByte() }
        fake.routes["raw.githubusercontent.com"] = { Reply(200, wrong) }
        val dir = tmp.newFolder("lib")
        try {
            ProfileDownloader(fake).download("u/r", sha, RemoteFile("a.irs", content.size.toLong(), gitSha(content)),
                java.io.File(dir, "a.irs"))
            fail()
        } catch (e: GithubException.Corrupt) {
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `a git lfs pointer is swapped for the real file and checked`() {
        // Recorded from a public repository that keeps .wav files in Git LFS
        val pointer = fixture("lfs_pointer.txt").toByteArray()
        assertEquals("8e7d8cbe9162d3ce2eaf6ae4dfed4dd93dca53a9", gitSha(pointer))
        val p = LfsPointer.parse(pointer)!!
        assertEquals(34392L, p.size)

        // A stand-in for the real file with the pointer's size, and a pointer naming it
        val real = ByteArray(34392) { (it % 7).toByte() }
        val oid = MessageDigest.getInstance("SHA-256").digest(real).joinToString("") { "%02x".format(it) }
        val ptr = "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize 34392\n".toByteArray()
        fake.routes["raw.githubusercontent.com/u/r/$sha/audio/010.wav"] = { Reply(200, ptr) }
        fake.routes["media.githubusercontent.com/media/u/r/$sha/audio/010.wav"] = { Reply(200, real) }
        val dir = tmp.newFolder("lib")
        ProfileDownloader(fake).download("u/r", sha, RemoteFile("audio/010.wav", ptr.size.toLong(), gitSha(ptr)),
            java.io.File(dir, "010.wav"))
        assertArrayEquals(real, java.io.File(dir, "010.wav").readBytes())
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun `an lfs file its owner cannot serve gives the lfs message`() {
        val ptr = fixture("lfs_pointer.txt").toByteArray()
        fake.routes["raw.githubusercontent.com"] = { Reply(200, ptr) }
        fake.routes["media.githubusercontent.com"] = { Reply(403, ByteArray(0)) }
        val dir = tmp.newFolder("lib")
        try {
            ProfileDownloader(fake).download("u/r", sha, RemoteFile("a.wav", ptr.size.toLong(), gitSha(ptr)),
                java.io.File(dir, "a.wav"))
            fail()
        } catch (e: GithubException.LfsUnavailable) {
            assertEquals(403, e.code)
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `cancelling stops a download mid file`() {
        val token = CancelToken()
        // A body that cancels the batch once part of it has been read
        val body = object : InputStream() {
            var n = 0
            override fun read(): Int {
                if (++n == 50_000) token.cancel()
                return if (n > content.size) -1 else content[n - 1].toInt() and 0xff
            }
        }
        val transport = HttpTransport { _, _ -> HttpResponse(200, emptyMap(), body) }
        val dir = tmp.newFolder("lib")
        try {
            ProfileDownloader(transport).download("u/r", sha, RemoteFile("a.irs", content.size.toLong(), gitSha(content)),
                java.io.File(dir, "a.irs"), token)
            fail()
        } catch (e: GithubException.Cancelled) {
        }
        assertEquals(0, dir.list()!!.size)
        assertFalse(body.n >= content.size)
    }
}
