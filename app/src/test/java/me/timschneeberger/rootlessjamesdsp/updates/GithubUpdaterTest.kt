package me.timschneeberger.rootlessjamesdsp.updates

import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of the GitHub updater that decide what is offered, without the network. */
class GithubUpdaterTest {

    private fun asset(name: String) =
        """{"name":"$name","browser_download_url":"https://example.invalid/$name"}"""

    private val list = """[
      {"tag_name":"v4.6.0","draft":true,"prerelease":false,"body":"","assets":[]},
      {"tag_name":"v4.5.1","draft":false,"prerelease":true,"body":"","assets":[]},
      {"tag_name":"rubberband-v9.0.0","draft":false,"prerelease":false,"body":"","assets":[]},
      {"tag_name":"v4.4.0","draft":false,"prerelease":false,"body":"old","assets":[]},
      {"tag_name":"v4.5.0","draft":false,"prerelease":false,"body":"## New\n- thing",
       "html_url":"https://example.invalid/v4.5.0","assets":[
         ${asset("RootfulViPER4Android-engine-module-v4.5.0.zip")},
         ${asset("ViPER4Android-v4.5.0-rootful-fdroid-universal-release.apk")},
         ${asset("ViPER4Android-v4.5.0-rootless-fdroid-arm64-v8a-release.apk")},
         ${asset("ViPER4Android-v4.5.0-rootless-fdroid-universal-release.apk")}]},
      {"tag_name":"v4.4.2","draft":false,"prerelease":false,"body":"","assets":[]}
    ]"""

    @Test
    fun onlyNewerPublishedAppReleasesNewestFirst() {
        val r = GithubUpdater.parseReleases(list, intArrayOf(4, 4, 0))
        // Drafts, pre-releases, add-ons and the installed version are left out.
        assertEquals(listOf("v4.5.0", "v4.4.2"), r.map { it.tag })
        assertEquals("4.5.0", r[0].label)
    }

    @Test
    fun upToDateOffersNothing() {
        assertTrue(GithubUpdater.parseReleases(list, intArrayOf(4, 5, 0)).isEmpty())
        assertTrue(GithubUpdater.parseReleases(list, intArrayOf(5, 0, 0)).isEmpty())
    }

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(GithubUpdater.compare(intArrayOf(4, 10, 0), intArrayOf(4, 9, 9)) > 0)
        assertEquals(0, GithubUpdater.compare(GithubUpdater.parse("v4.4.0")!!, GithubUpdater.parse("4.4.0-123")!!))
    }

    @Test
    fun picksTheUniversalFileForThisBuild() {
        val r = GithubUpdater.parseReleases(list, intArrayOf(4, 4, 0))[0]
        val apk = GithubUpdater.apkFor(r)!!.first
        // The unit tests run once per flavour, and each build must be offered
        // its own file: a rooted install updated to the rootless app would
        // lose root mode without a word.
        val expected = if (isRoot()) "ViPER4Android-v4.5.0-rootful-fdroid-universal-release.apk"
            else "ViPER4Android-v4.5.0-rootless-fdroid-universal-release.apk"
        assertEquals(expected, apk)
    }

    @Test
    fun downloadedFileNamedForAnOlderVersionIsNotOffered() {
        assertFalse(UpdateInstaller.isNewer("ViPER4Android-v0.0.1-rootless-fdroid-universal-release.apk"))
        assertTrue(UpdateInstaller.isNewer("ViPER4Android-v99.0.0-rootless-fdroid-universal-release.apk"))
    }
}
