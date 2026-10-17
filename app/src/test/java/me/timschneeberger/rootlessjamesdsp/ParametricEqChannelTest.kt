package me.timschneeberger.rootlessjamesdsp

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-channel bands, through both formats they have to survive.
 *
 * The engine side of this is covered by tools/percheq_harness.c on real
 * hardware. What that cannot reach is the two text formats, and those are
 * where a mistake is quiet: a dropped channel does not crash, it just applies
 * a correction meant for one ear to both, which sounds like the feature not
 * working rather than like a bug.
 *
 * The backwards-compatibility cases matter most. Everyone upgrading has
 * presets written before this existed, and anyone sharing one with a user on
 * an older build needs it to still load there.
 */
class ParametricEqChannelTest {

    private fun bands(vararg b: ParametricEqBand) =
        ParametricEqBandList().apply { b.forEach { add(it) } }

    @Test
    fun `a band on both ears serializes exactly as it used to`() {
        // Four fields, no fifth. An older build reads this and is none the
        // wiser, which is the whole point of leaving it off.
        val list = bands(ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING))
        assertEquals("PEQ: 1000 3 1.41 0; ", list.serialize())
    }

    @Test
    fun `a one-eared band carries its channel`() {
        val list = bands(
            ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT),
            ParametricEqBand(2000.0, -2.0, 0.71, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT)
        )
        val out = list.serialize()
        assertTrue(out, out.contains("1000 3 1.41 0 1;"))
        assertTrue(out, out.contains("2000 -2 0.71 0 2;"))
    }

    @Test
    fun `the internal format round-trips`() {
        val original = bands(
            ParametricEqBand(100.0, 5.0, 0.71, ParametricEqFilterType.LOW_SHELF, ParametricEqChannel.LEFT),
            ParametricEqBand(1000.0, -3.0, 1.41, ParametricEqFilterType.PEAKING),
            ParametricEqBand(8000.0, 2.0, 0.5, ParametricEqFilterType.HIGH_SHELF, ParametricEqChannel.RIGHT)
        )
        val restored = ParametricEqBandList().apply { deserialize(original.serialize()) }
        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals("band $i", original[i].frequency, restored[i].frequency, 0.001)
            assertEquals("band $i", original[i].gain, restored[i].gain, 0.001)
            assertEquals("band $i", original[i].q, restored[i].q, 0.001)
            assertEquals("band $i", original[i].filterType, restored[i].filterType)
            assertEquals("band $i", original[i].channel, restored[i].channel)
        }
    }

    @Test
    fun `a preset saved before this feature existed loads as both ears`() {
        val restored = ParametricEqBandList().apply {
            deserialize("PEQ: 1000 3 1.41 0; 4000 -2 0.71 1; ")
        }
        assertEquals(2, restored.size)
        assertEquals(ParametricEqChannel.BOTH, restored[0].channel)
        assertEquals(ParametricEqChannel.BOTH, restored[1].channel)
    }

    @Test
    fun `an ordinary preset exports without any Channel line`() {
        // Other tools read these files. A preset that uses no per-channel
        // bands has to look exactly like one written before the feature, or
        // every export suddenly grows a line it does not need.
        val apo = bands(
            ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING)
        ).toApoString()
        assertTrue(apo, !apo.contains("Channel:"))
        assertTrue(apo, apo.contains("Filter 1: ON PK Fc 1000 Hz Gain 3 dB Q 1.41"))
    }

    @Test
    fun `channel lines are written only where the channel changes`() {
        val apo = bands(
            ParametricEqBand(100.0, 1.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT),
            ParametricEqBand(200.0, 2.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT),
            ParametricEqBand(300.0, 3.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT)
        ).toApoString()
        // Two bands on the left, so one "Channel: L" and not two.
        assertEquals(apo, 1, apo.lines().count { it.trim() == "Channel: L" })
        assertEquals(apo, 1, apo.lines().count { it.trim() == "Channel: R" })
        // Filters stay numbered from one, continuously, which is what the
        // format expects: the channel lines are not filters.
        assertTrue(apo, apo.contains("Filter 1:"))
        assertTrue(apo, apo.contains("Filter 2:"))
        assertTrue(apo, apo.contains("Filter 3:"))
    }

    @Test
    fun `a Channel line applies to every filter after it`() {
        val list = ParametricEqBandList()
        list.fromApoString(
            """
            Preamp: -3 dB
            Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
            Channel: L
            Filter 2: ON PK Fc 200 Hz Gain 2.0 dB Q 1.0
            Filter 3: ON PK Fc 300 Hz Gain 3.0 dB Q 1.0
            Channel: R
            Filter 4: ON PK Fc 400 Hz Gain 4.0 dB Q 1.0
            Channel: ALL
            Filter 5: ON PK Fc 500 Hz Gain 5.0 dB Q 1.0
            """.trimIndent()
        )
        assertEquals(5, list.size)
        assertEquals(ParametricEqChannel.BOTH, list[0].channel)
        assertEquals(ParametricEqChannel.LEFT, list[1].channel)
        // The one that would be missed by a reader that forgot the line
        // persists past a single filter.
        assertEquals(ParametricEqChannel.LEFT, list[2].channel)
        assertEquals(ParametricEqChannel.RIGHT, list[3].channel)
        assertEquals(ParametricEqChannel.BOTH, list[4].channel)
    }

    @Test
    fun `the APO format round-trips through both directions`() {
        val original = bands(
            ParametricEqBand(100.0, 5.0, 0.71, ParametricEqFilterType.LOW_SHELF, ParametricEqChannel.LEFT),
            ParametricEqBand(1000.0, -3.0, 1.41, ParametricEqFilterType.PEAKING),
            ParametricEqBand(8000.0, 2.0, 0.5, ParametricEqFilterType.HIGH_SHELF, ParametricEqChannel.RIGHT)
        )
        val restored = ParametricEqBandList()
        restored.fromApoString(original.toApoString(preampDb = -2.0))
        assertEquals(original.size, restored.size)
        for (i in original.indices)
            assertEquals("band $i channel", original[i].channel, restored[i].channel)
    }

    @Test
    fun `an unknown channel name falls back to both ears rather than guessing`() {
        val list = ParametricEqBandList()
        list.fromApoString(
            """
            Channel: SURROUND_LEFT
            Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
            """.trimIndent()
        )
        assertEquals(1, list.size)
        // Guessing LEFT from the name would put a correction on one ear that
        // was never meant for it.
        assertEquals(ParametricEqChannel.BOTH, list[0].channel)
    }

    @Test
    fun `channel is part of what makes two bands different`() {
        val left = ParametricEqBand(1000.0, 3.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT)
        val right = ParametricEqBand(1000.0, 3.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT)
        val same = ParametricEqBand(1000.0, 3.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT)
        // Without this the editor's change detection would treat moving a band
        // to the other ear as no change at all.
        assertNotEquals(left, right)
        assertEquals(left, same)
        assertNotEquals(left.hashCode().toLong(), right.hashCode().toLong())
    }

    @Test
    fun `an unparseable fifth field does not lose the band`() {
        val list = ParametricEqBandList()
        list.deserialize("PEQ: 1000 3 1.41 0 banana; ")
        assertEquals(1, list.size)
        assertEquals(ParametricEqChannel.BOTH, list[0].channel)
    }
}
