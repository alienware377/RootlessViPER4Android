package me.timschneeberger.rootlessjamesdsp.utils.github

import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The user's download sources, as they move out of the old preset namespace. */
class LibrarySourcesTest {
    private lateinit var dir: File
    private lateinit var ctx: FakePrefsContext

    private val a = Source("a/b", "main", "a/b")
    private val c = Source("c/d", "dev", "c/d")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sources").toFile()
        ctx = FakePrefsContext(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** What an older version, or a preset it saved, leaves in dsp_filelibrary. */
    private fun leaveLegacy(vararg sources: Source) {
        ctx.getSharedPreferences(Constants.PREF_FILELIBRARY, 0).edit()
            .putString("filelib_sources", SourceCodec.encode(sources.toList())).commit()
    }

    @Test
    fun `revert to defaults keeps sources an older version left among the effect settings`() {
        leaveLegacy(a, c)
        with(ContextExtensions) { ctx.restoreDspSettings(silent = true) }
        assertEquals(listOf(a, c), LibrarySources.load(ctx))
    }

    @Test
    fun `a removed source does not come back from an older preset`() {
        leaveLegacy(a, c)
        assertEquals(listOf(a, c), LibrarySources.load(ctx))
        LibrarySources.remove(ctx, a)
        // Loading a preset an older version saved copies its list back in.
        leaveLegacy(a, c)
        LibrarySources.absorbLegacy(ctx)
        assertEquals(listOf(c), LibrarySources.load(ctx))
    }

    @Test
    fun `adding a removed source again by hand keeps it`() {
        leaveLegacy(a)
        LibrarySources.load(ctx)
        LibrarySources.remove(ctx, a)
        LibrarySources.add(ctx, a)
        LibrarySources.remove(ctx, c)
        leaveLegacy(a, c)
        LibrarySources.absorbLegacy(ctx)
        assertEquals(listOf(a), LibrarySources.load(ctx))
    }
}
