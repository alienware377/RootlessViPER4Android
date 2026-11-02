package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** Reading a picked file with a size limit. */
class BoundedReadTest {

    /** [size] bytes of a repeating pattern, counting how many were taken. */
    private class Source(private val size: Long) : InputStream() {
        var taken = 0L
        override fun read(): Int = if (taken >= size) -1 else ((taken++ % 251).toInt())
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (taken >= size) return -1
            val n = minOf(len.toLong(), size - taken).toInt()
            for (i in 0 until n) b[off + i] = ((taken + i) % 251).toByte()
            taken += n
            return n
        }
    }

    @Test
    fun `a file over the limit is turned down without reading the rest of it`() {
        val limit = 4 * 1024 * 1024
        val src = Source(40L * 1024 * 1024)
        assertNull(BoundedRead.readAtMost(src, limit))
        assertTrue("read ${src.taken} bytes of a 40 MB file", src.taken <= limit + 1L)
    }

    @Test
    fun `a file within the limit comes back whole`() {
        val src = Source(100_000)
        val got = BoundedRead.readAtMost(src, 100_000)!!
        assertArrayEquals(ByteArray(100_000) { (it % 251).toByte() }, got)
        assertNull(BoundedRead.readAtMost(Source(100_001), 100_000))
    }
}
