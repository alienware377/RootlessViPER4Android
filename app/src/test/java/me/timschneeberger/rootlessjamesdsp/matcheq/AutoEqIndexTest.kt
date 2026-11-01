package me.timschneeberger.rootlessjamesdsp.matcheq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lines written the way results/INDEX.md writes them. The names are invented
 * but have the awkward shapes the real list has: " by " inside a name,
 * brackets and parentheses, "&" and "+" in folder names.
 */
class AutoEqIndexTest {

    private val text = """
        # Index
        This is a list of all equalization profiles.

        - [Acme One](./oratory1990/over-ear/Acme%20One) by oratory1990
        - [Nada by Somebody Buds (ANC on, warm)](./Regan%20Cipher/in-ear/Nada%20by%20Somebody%20Buds%20(ANC%20on,%20warm)) by Regan Cipher
        - [Acme One](./crinacle/GRAS%2043AG-7%20over-ear/Acme%20One) by crinacle on GRAS 43AG-7
        - [Zed [v2]](./Rtings/Bruel%20&%20Kjaer%205128%20in-ear/Zed%20[v2]) by Rtings on Bruel & Kjaer 5128
        - [Pair](./crinacle/EARS%20+%20711%20over-ear/Pair) by crinacle on EARS + 711
    """.trimIndent()

    private val entries = AutoEqIndex.parse(text)

    @Test
    fun `every result line is read and nothing else`() {
        assertEquals(5, entries.size)
    }

    @Test
    fun `a name containing by and brackets keeps its path and source`() {
        val e = AutoEqIndex.find(entries, "Nada by Somebody Buds (ANC on, warm)", "Regan Cipher")!!
        assertEquals("Regan Cipher/in-ear/Nada by Somebody Buds (ANC on, warm)", e.path)
        assertEquals(Rig("Regan Cipher", "in-ear", null), e.rig)
        val z = AutoEqIndex.find(entries, "Zed [v2]", "Rtings on Bruel & Kjaer 5128")!!
        assertEquals(Rig("Rtings", "in-ear", "Bruel & Kjaer 5128"), z.rig)
    }

    @Test
    fun `the same name from two sources is told apart by source`() {
        assertEquals("oratory1990/over-ear/Acme One", AutoEqIndex.find(entries, "Acme One", "oratory1990")!!.path)
        val c = AutoEqIndex.find(entries, "Acme One", "crinacle on GRAS 43AG-7")!!
        assertEquals(Rig("crinacle", "over-ear", "GRAS 43AG-7"), c.rig)
        assertNull(AutoEqIndex.find(entries, "Acme One", "nobody"))
    }

    @Test
    fun `plus signs stay plus signs`() {
        val e = AutoEqIndex.find(entries, "Pair", "crinacle on EARS + 711")!!
        assertEquals(Rig("crinacle", "over-ear", "EARS + 711"), e.rig)
        assertEquals("results/crinacle/EARS%20%2B%20711%20over-ear/Pair/Pair.csv", e.csvPath())
    }

    @Test
    fun `download paths encode everything that is not plain`() {
        val e = AutoEqIndex.find(entries, "Nada by Somebody Buds (ANC on, warm)", null)
        assertNotNull(e)
        val p = e!!.csvPath()
        assertTrue(p, p.startsWith("results/Regan%20Cipher/in-ear/Nada%20by%20Somebody%20Buds%20%28ANC%20on%2C%20warm%29/"))
        assertTrue(p, p.endsWith("%28ANC%20on%2C%20warm%29.csv"))
        assertEquals("caf%C3%A9", AutoEqIndex.encodeSegment("caf\u00e9"))
        assertEquals("caf\u00e9 +", AutoEqIndex.percentDecode("caf%C3%A9%20+"))
    }
}
