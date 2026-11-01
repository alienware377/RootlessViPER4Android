package me.timschneeberger.rootlessjamesdsp.matcheq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqTargetsTest {

    private val all = AutoEqTargets.all

    @Test
    fun `labels and ids are unique and there is exactly one flat target`() {
        assertEquals(all.size, all.map { it.label }.toSet().size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(1, all.count { it.file == null })
        assertEquals(AutoEqTargets.FLAT_ID, all.single { it.file == null }.id)
    }

    @Test
    fun `every rig has exactly one recommended target, and it suits that rig`() {
        val rigs = all.flatMap { it.recommended }.toSet()
        assertTrue(rigs.size > 40)
        for (rig in rigs) {
            val rec = all.filter { it.recommendedFor(rig) }
            assertEquals("$rig", 1, rec.size)
            assertTrue("$rig", rec[0].suits(rig))
        }
    }

    @Test
    fun `AutoEq's own choices for two well known rigs`() {
        val over = AutoEqTargets.recommended(Rig("oratory1990", "over-ear", null))!!
        assertEquals("Harman over-ear 2018 without bass.csv", over.file)
        assertEquals(6.0, over.bassBoost, 0.0)
        val iem = AutoEqTargets.recommended(Rig("crinacle", "in-ear", "711"))!!
        assertEquals("AutoEq in-ear.csv", iem.file)
        assertEquals(8.0, iem.bassBoost, 0.0)
    }

    @Test
    fun `the offered order puts the recommended target first and hides other rigs unless asked`() {
        val rig = Rig("Rtings", "over-ear", "HMS II.3")
        val short = AutoEqTargets.ordered(rig, includeIncompatible = false)
        assertEquals(AutoEqTargets.recommended(rig), short.first())
        assertTrue(short.all { it.suits(rig) })
        assertTrue(short.any { it.file == null })
        val long = AutoEqTargets.ordered(rig, includeIncompatible = true)
        assertEquals(all.size, long.size)
        assertFalse(long.subList(short.size, long.size).any { it.suits(rig) })
    }

    @Test
    fun `an imported measurement of unknown rig is offered every target`() {
        assertEquals(all.size, AutoEqTargets.ordered(null, includeIncompatible = false).size)
    }

    @Test
    fun `rigs are read from result paths`() {
        assertEquals(Rig("oratory1990", "earbud", null), Rig.fromResultPath("oratory1990/earbud/X"))
        assertEquals(Rig("crinacle", "in-ear", "711"), Rig.fromResultPath("crinacle/711 in-ear/X"))
        assertEquals(null, Rig.fromResultPath("crinacle/sideways/X"))
    }
}
