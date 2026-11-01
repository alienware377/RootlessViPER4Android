package me.timschneeberger.rootlessjamesdsp.capture

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads raw PCM that the Shizuku capture helper writes into a pipe, and hands
 * it out as whole float or 16-bit sample buffers, the same shapes AudioRecord
 * fills for the screen capture method.
 *
 * A pipe hands out whatever is there, so one read can return part of a
 * sample or part of a buffer. Every read here waits until the whole buffer
 * is filled. When the helper closes its end (stopped, crashed, Shizuku gone)
 * the read throws EOFException, which the caller treats as the capture ending.
 *
 * The helper writes in the device's native byte order, so that is the default.
 */
class PcmPipeReader(
    private val input: InputStream,
    private val order: ByteOrder = ByteOrder.nativeOrder(),
) {
    private var bytes = ByteArray(0)
    private var view: ByteBuffer = ByteBuffer.wrap(bytes).order(order)

    private fun ensure(size: Int) {
        if (bytes.size < size) {
            bytes = ByteArray(size)
            view = ByteBuffer.wrap(bytes).order(order)
        }
    }

    private fun fill(size: Int) {
        ensure(size)
        var done = 0
        while (done < size) {
            val n = input.read(bytes, done, size - done)
            if (n < 0)
                throw EOFException("capture pipe closed after $done of $size bytes")
            done += n
        }
    }

    /** Fills all of [out] with float samples. Returns the number of samples. */
    fun read(out: FloatArray): Int {
        val size = out.size * Float.SIZE_BYTES
        fill(size)
        view.clear()
        view.limit(size)
        view.asFloatBuffer().get(out, 0, out.size)
        return out.size
    }

    /** Fills all of [out] with 16-bit samples. Returns the number of samples. */
    fun read(out: ShortArray): Int {
        val size = out.size * Short.SIZE_BYTES
        fill(size)
        view.clear()
        view.limit(size)
        view.asShortBuffer().get(out, 0, out.size)
        return out.size
    }

    fun close() {
        try { input.close() } catch (_: Exception) {}
    }
}
