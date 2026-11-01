package me.timschneeberger.rootlessjamesdsp.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmPipeReaderTest {

    /** Hands out at most [chunk] bytes per read, like a pipe that is only partly filled. */
    private class Trickle(bytes: ByteArray, private val chunk: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        var closed = false
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, chunk))
        override fun close() { closed = true }
    }

    private fun floats(vararg v: Float, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(order)
        v.forEach { b.putFloat(it) }
        return b.array()
    }

    private fun shorts(vararg v: Short, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val b = ByteBuffer.allocate(v.size * 2).order(order)
        v.forEach { b.putShort(it) }
        return b.array()
    }

    @Test
    fun `float samples survive reads that split them`() {
        val samples = floatArrayOf(0f, 0.5f, -0.25f, 1f, -1f, 0.125f)
        for (chunk in listOf(1, 3, 5, 7, 100)) {
            val reader = PcmPipeReader(Trickle(floats(*samples), chunk), ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(samples.size)
            assertEquals(samples.size, reader.read(out))
            assertArrayEquals("chunk $chunk", samples, out, 0f)
        }
    }

    @Test
    fun `16-bit samples survive reads that split them`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)
        for (chunk in listOf(1, 3, 100)) {
            val reader = PcmPipeReader(Trickle(shorts(*samples), chunk), ByteOrder.LITTLE_ENDIAN)
            val out = ShortArray(samples.size)
            assertEquals(samples.size, reader.read(out))
            assertArrayEquals("chunk $chunk", samples, out)
        }
    }

    @Test
    fun `consecutive buffers continue where the last one stopped`() {
        val reader = PcmPipeReader(Trickle(floats(1f, 2f, 3f, 4f, 5f, 6f), 5), ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(2)
        reader.read(out); assertArrayEquals(floatArrayOf(1f, 2f), out, 0f)
        reader.read(out); assertArrayEquals(floatArrayOf(3f, 4f), out, 0f)
        reader.read(out); assertArrayEquals(floatArrayOf(5f, 6f), out, 0f)
    }

    @Test
    fun `the byte order is honoured`() {
        val reader = PcmPipeReader(Trickle(floats(0.75f, order = ByteOrder.BIG_ENDIAN), 2), ByteOrder.BIG_ENDIAN)
        val out = FloatArray(1)
        reader.read(out)
        assertEquals(0.75f, out[0], 0f)
    }

    @Test
    fun `the default order is the device's own`() {
        val native = ByteOrder.nativeOrder()
        val reader = PcmPipeReader(Trickle(shorts(4321, order = native), 1))
        val out = ShortArray(1)
        reader.read(out)
        assertEquals(4321.toShort(), out[0])
    }

    @Test
    fun `a pipe closed mid-buffer ends the capture`() {
        val reader = PcmPipeReader(Trickle(floats(1f, 2f, 3f).copyOf(10), 4), ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(3)
        var threw = false
        try { reader.read(out) } catch (e: EOFException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `a pipe closed between buffers ends the capture`() {
        val reader = PcmPipeReader(Trickle(floats(1f, 2f), 64), ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(2)
        reader.read(out)
        var threw = false
        try { reader.read(out) } catch (e: EOFException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `switching buffer sizes keeps working`() {
        val reader = PcmPipeReader(Trickle(shorts(1, 2, 3, 4, 5, 6, 7), 3), ByteOrder.LITTLE_ENDIAN)
        val small = ShortArray(2)
        val big = ShortArray(5)
        reader.read(small); assertArrayEquals(shortArrayOf(1, 2), small)
        reader.read(big); assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), big)
    }

    @Test
    fun `close closes the stream`() {
        val stream = Trickle(ByteArray(0), 1)
        PcmPipeReader(stream).close()
        assertTrue(stream.closed)
    }
}
