package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.BiConsumer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The app's half of GTCRN: the transform around the add-on's hop, and the layout it hands over. */
class GtcrnBackendTest {
    private val hop = GtcrnModel.HOP_SIZE

    private class Session(private val model: (FloatArray, FloatArray) -> Unit) :
        BiConsumer<FloatArray, FloatArray>, AutoCloseable {
        val frames = ArrayList<FloatArray>()
        var closed = false
        override fun accept(mix: FloatArray, enhanced: FloatArray) {
            frames += mix.copyOf()
            model(mix, enhanced)
        }
        override fun close() { closed = true }
    }

    private fun backend(session: Session) = GtcrnBackend(session, session)

    @Test
    fun aModelThatChangesNothingGivesTheHopBefore() {
        val session = Session { mix, enhanced -> mix.copyInto(enhanced) }
        val backend = backend(session)
        val input = FloatArray(hop * 8) { 0.5f * sin(2.0 * PI * 440.0 * it / GtcrnModel.SAMPLE_RATE).toFloat() }
        val output = FloatArray(hop)
        var worst = 0f
        for (frame in 0 until 8) {
            backend.process(input.copyOfRange(frame * hop, (frame + 1) * hop), output)
            if (frame > 0) for (sample in 0 until hop)
                worst = maxOf(worst, abs(input[(frame - 1) * hop + sample] - output[sample]))
        }
        assertTrue("reconstruction error $worst", worst < 1e-5f)
        assertEquals(8, session.frames.size)
    }

    @Test
    fun theFrameIsHandedOverAsInterleavedPairs() {
        val session = Session { _, enhanced -> enhanced.fill(0f) }
        val backend = backend(session)
        val output = FloatArray(hop)
        // A constant: after the window most of it is in bin 0, which has no imaginary part.
        repeat(2) { backend.process(FloatArray(hop) { 0.25f }, output) }

        val frame = session.frames.last()
        assertEquals(GtcrnModel.SPECTRUM_SIZE, frame.size)
        assertTrue("bin 0 real ${frame[0]}", frame[0] > 1f)
        assertEquals("bin 0 imaginary", 0f, frame[1], 1e-6f)
        assertTrue("bin 1 real ${frame[2]}", abs(frame[2]) > 0.1f)
        assertEquals("Nyquist imaginary", 0f, frame[GtcrnModel.SPECTRUM_SIZE - 1], 1e-6f)
    }

    @Test
    fun aLoudVoiceIsKeptUnderFullScale() {
        val backend = backend(Session { mix, enhanced -> for (i in mix.indices) enhanced[i] = mix[i] * 4f })
        val output = FloatArray(hop)
        repeat(4) { backend.process(FloatArray(hop) { 0.5f * sin(2.0 * PI * it / 64).toFloat() }, output) }
        assertTrue(output.all { abs(it) <= 0.98f + 1e-6f })
    }

    @Test
    fun closingTheBackendClosesTheSession() {
        val session = Session { _, _ -> }
        backend(session).close()
        assertTrue(session.closed)
    }
}
