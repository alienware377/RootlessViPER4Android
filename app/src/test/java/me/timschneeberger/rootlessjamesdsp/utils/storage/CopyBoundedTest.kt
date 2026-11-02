package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Copying a file into the library with a size limit. */
class CopyBoundedTest {
    private lateinit var dir: File

    @Before
    fun setUp() { dir = Files.createTempDirectory("copy").toFile() }

    @After
    fun tearDown() { dir.deleteRecursively() }

    /** [size] zero bytes, counting how many were taken. */
    private class Source(private val size: Long) : InputStream() {
        var taken = 0L
        override fun read(): Int = if (taken >= size) -1 else { taken++; 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (taken >= size) return -1
            val n = minOf(len.toLong(), size - taken).toInt()
            b.fill(0, off, off + n)
            taken += n
            return n
        }
    }

    @Test
    fun `a head set over the limit is not copied, and nothing is left behind`() {
        val limit = 64L * 1024
        val src = Source(10 * limit)
        val dest = File(dir, "big.sofa")
        assertEquals(StorageUtils.Copy.TOO_BIG, StorageUtils.copyBounded(src, dest, limit))
        assertFalse("partial copy left in the library", dest.exists())
        assertTrue("read ${src.taken} bytes past a limit of $limit", src.taken <= limit + 4096)
    }

    @Test
    fun `a file within the limit is copied whole`() {
        val dest = File(dir, "small.sofa")
        assertEquals(StorageUtils.Copy.OK, StorageUtils.copyBounded(Source(50_000), dest, 50_000))
        assertEquals(50_000L, dest.length())
        val any = File(dir, "any.wav")
        assertEquals(StorageUtils.Copy.OK, StorageUtils.copyBounded(Source(70_000), any, Long.MAX_VALUE))
        assertEquals(70_000L, any.length())
    }
}
