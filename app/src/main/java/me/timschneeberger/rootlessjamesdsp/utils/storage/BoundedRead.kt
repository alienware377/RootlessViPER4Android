package me.timschneeberger.rootlessjamesdsp.utils.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BoundedRead {
    /**
     * Reads [input] to the end, unless it holds more than [maxBytes]: then it
     * stops one byte past the limit and returns null. A file picked by mistake
     * (a song, a video) or one streamed from a slow cloud provider is turned
     * down after [maxBytes], instead of being read in full and then refused,
     * or running the app out of memory first.
     */
    fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val want = minOf(buf.size.toLong(), maxBytes + 1L - total).toInt()
            if (want <= 0) return null
            val n = input.read(buf, 0, want)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
