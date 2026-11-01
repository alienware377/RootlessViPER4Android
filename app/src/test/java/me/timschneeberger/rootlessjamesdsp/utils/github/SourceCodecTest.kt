package me.timschneeberger.rootlessjamesdsp.utils.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list of sources the user added, as stored and as merged from an older preset. */
class SourceCodecTest {

    @Test
    fun `sources saved by the old version still load`() {
        val old = """[{"repo":"u/r","branch":"master","label":"u/r"},{"repo":"bad"},{"repo":"x/y","branch":"v4a+2.7","label":"x/y"}]"""
        val list = SourceCodec.decode(old)
        assertEquals(listOf(Source("u/r", "master", "u/r"), Source("x/y", "v4a+2.7", "x/y")), list)
    }

    @Test
    fun `folders round trip and built in sources are never written`() {
        val list = listOf(
            Source("a/b", "main", "a/b/results", "results"),
            Source("p/q", "main", "pack", builtIn = true))
        assertEquals(listOf(list[0]), SourceCodec.decode(SourceCodec.encode(list)))
        assertTrue(SourceCodec.decode("not json").isEmpty())
        assertTrue(SourceCodec.decode(null).isEmpty())
    }

    @Test
    fun `a preset's sources are merged into the user's, never replacing them`() {
        val mine = listOf(Source("a/b", "main", "a/b"), Source("c/d", "dev", "c/d"))
        val preset = listOf(Source("A/B", "main", "other label"), Source("e/f", "main", "e/f"))
        assertEquals(listOf("a/b", "c/d", "e/f"), SourceCodec.merge(mine, preset).map { it.repo })
        assertEquals("a/b", SourceCodec.merge(mine, preset)[0].label)
        // Same repository, different folder: a separate source
        assertEquals(3, SourceCodec.merge(mine, listOf(Source("a/b", "main", "a/b/x", "x"))).size)
    }
}
