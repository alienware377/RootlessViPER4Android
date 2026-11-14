package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The rootless capture loop hands the module a live PCM16 buffer, so the 1/32768 scaling and the
 * region helpers used by the in-place path must be exact. Ported convention from Voice Isolation
 * for Android: truncating quantization, no dither, clamped at the float headroom ceiling.
 */
class PcmConverterTest {
    @Test
    fun shortRangeScalesByExactlyOneOver32768() {
        val input = shortArrayOf(0, 1, -1, 16384, -16384, 32767, Short.MIN_VALUE)
        val output = FloatArray(input.size)

        PcmConverter.toFloat(input, output)

        assertEquals(floatArrayOf(0f, 1f / 32768f, -1f / 32768f, 0.5f, -0.5f, 32767f / 32768f, -1f).toList(), output.toList())
    }

    @Test
    fun everyShortValueSurvivesARoundTrip() {
        val original = ShortArray(65536) { (it - 32768).toShort() }
        val floats = FloatArray(original.size)
        val restored = ShortArray(original.size)

        PcmConverter.toFloat(original, floats)
        PcmConverter.toShort(floats, restored)

        assertEquals(original.toList(), restored.toList())
    }

    @Test
    fun floatsBeyondFullScaleAreClampedNotWrapped() {
        val input = floatArrayOf(1f, 2f, 0.9999695f, -1f, -2f, 0f)
        val output = ShortArray(input.size)

        PcmConverter.toShort(input, output)

        assertEquals(32767, output[0].toInt())
        assertEquals(32767, output[1].toInt())
        assertEquals(32767, output[2].toInt())
        assertEquals(-32768, output[3].toInt())
        assertEquals(-32768, output[4].toInt())
        assertEquals(0, output[5].toInt())
    }

    @Test
    fun gainIsAppliedBeforeClamping() {
        val output = ShortArray(3)

        PcmConverter.toShort(floatArrayOf(1f, 1f, -1f), output, gain = 0.5f)

        assertEquals(16384, output[0].toInt())
        assertEquals(16384, output[1].toInt())
        assertEquals(-16384, output[2].toInt())
    }

    @Test
    fun regionHelpersMoveABlockIntoAndBackOutOfALiveBuffer() {
        // the in-place capture path converts a slice of the track buffer into a scratch array that
        // always starts at zero, and writes the processed scratch back into the same slice
        val shorts = shortArrayOf(100, 200, 300, 400, 500, 600)
        val scratch = FloatArray(shorts.size) { -1f }

        PcmConverter.toFloatRange(shorts, scratch, offset = 2, count = 3)

        assertEquals(300f / 32768f, scratch[0], 0f)
        assertEquals(400f / 32768f, scratch[1], 0f)
        assertEquals(500f / 32768f, scratch[2], 0f)
        assertEquals(-1f, scratch[3], 0f)

        val restored = shortArrayOf(1, 2, 3, 4, 5, 6)
        PcmConverter.toShortRange(floatArrayOf(0.25f, -0.25f), restored, offset = 1, count = 2)

        assertEquals(1, restored[0].toInt())
        assertEquals(8192, restored[1].toInt())
        assertEquals(-8192, restored[2].toInt())
        assertEquals(4, restored[3].toInt())
    }

    @Test
    fun roundedConversionMatchesTheEnginesSixteenBitOutput() {
        val output = ShortArray(9) { 7 }
        PcmConverter.toShortRounded(
            floatArrayOf(0.25f, -0.25f, 1.5f / 32768f, 2.5f / 32768f, -0.6f / 32768f, 1.2f, -3f, Float.NaN),
            output, count = 8,
        )

        // nearest step, halves to even, and clamped rather than wrapped
        assertEquals(listOf(8192, -8192, 2, 2, -1, 32767, -32768, 0, 7), output.map { it.toInt() })
    }

    @Test
    fun wholeBufferConversionWritesOnlyTheRequestedCount() {
        val shorts = shortArrayOf(100, 200, 300, 400)
        val floats = FloatArray(shorts.size) { -1f }

        PcmConverter.toFloat(shorts, floats, count = 2)

        assertEquals(100f / 32768f, floats[0], 0f)
        assertEquals(200f / 32768f, floats[1], 0f)
        assertEquals(-1f, floats[2], 0f)
    }

    @Test
    fun regionHelpersRefuseToReadOrWritePastTheBuffer() {
        val shorts = shortArrayOf(1, 2, 3, 4)
        val floats = FloatArray(4)

        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.toFloatRange(shorts, floats, offset = 2, count = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.toFloatRange(shorts, floats, offset = -1, count = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.toShortRange(floats, shorts, offset = 3, count = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.toShortRange(FloatArray(2), shorts, offset = 0, count = 3)
        }
    }

    @Test
    fun countIsLimitedToTheSmallerBuffer() {
        val shorts = shortArrayOf(1, 2, 3, 4)
        val floats = FloatArray(2)

        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.toFloat(shorts, floats, count = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.toShort(FloatArray(5), shorts)
        }
    }
}
