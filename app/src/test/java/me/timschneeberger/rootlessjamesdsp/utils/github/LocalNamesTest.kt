package me.timschneeberger.rootlessjamesdsp.utils.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNamesTest {

    private fun f(path: String, size: Long = 5000) = RemoteFile(path, size, "0".repeat(40))

    @Test
    fun `same name in two folders keeps both, the second tagged with its folder`() {
        val plans = LocalNames.plan(
            listOf(f("oratory1990/HD 650.wav"), f("crinacle/HD 650.wav")), emptyMap(), "u/AutoEq")
        assertEquals("HD 650.wav", plans[0].localName)
        assertEquals("HD 650 (crinacle).wav", plans[1].localName)
        assertFalse(plans[0].renamed)
        assertTrue(plans[1].renamed)
    }

    @Test
    fun `names differing only in case still clash on the phone`() {
        val plans = LocalNames.plan(listOf(f("DDC/SONY XBA-H3.vdc", 912), f("DDC/Sony XBA-H3.vdc", 866)), emptyMap(), "u/r")
        assertEquals("SONY XBA-H3.vdc", plans[0].localName)
        assertEquals("Sony XBA-H3 (DDC).vdc", plans[1].localName)
    }

    @Test
    fun `a file already in the library with the same size is skipped`() {
        val plans = LocalNames.plan(listOf(f("IRS/Hall.irs", 5000)), mapOf("hall.irs" to 5000L), "u/r")
        assertTrue(plans[0].alreadyHave)
        assertNull(plans[0].localName)
    }

    @Test
    fun `a different file under a name the library already uses gets the folder tag`() {
        // The old code skipped it as "already downloaded"
        val plans = LocalNames.plan(listOf(f("IRS/Hall.irs", 7000)), mapOf("hall.irs" to 5000L), "u/r")
        assertEquals("Hall (IRS).irs", plans[0].localName)
    }

    @Test
    fun `a tagged copy downloaded earlier is recognised`() {
        val plans = LocalNames.plan(listOf(f("IRS/Hall.irs", 7000)),
            mapOf("hall.irs" to 5000L, "hall (irs).irs" to 7000L), "u/r")
        assertTrue(plans[0].alreadyHave)
    }

    @Test
    fun `files at the top of the repository are tagged with its name`() {
        val plans = LocalNames.plan(listOf(f("Hall.irs", 7000)), mapOf("hall.irs" to 5000L), "someone/Reverbs")
        assertEquals("Hall (Reverbs).irs", plans[0].localName)
    }

    @Test
    fun `third clash counts up`() {
        val plans = LocalNames.plan(listOf(f("a/x.irs", 1000), f("b/a/x.irs", 2000), f("c/a/x.irs", 3000)), emptyMap(), "u/r")
        assertEquals(listOf("x.irs", "x (a).irs", "x (a 2).irs"), plans.map { it.localName })
    }

    @Test
    fun `characters shared storage refuses are replaced`() {
        assertEquals("a_b_c_.irs", LocalNames.sanitize("a:b?c*.irs"))
        assertEquals("No ext (tag)", LocalNames.withTag("No ext", "tag"))
    }

    @Test
    fun `small DDC files are not mistaken for git lfs pointers`() {
        assertFalse(f("DDC/AKG K240.vdc", 908).maybeLfs)
        assertTrue(f("a.wav", 130).maybeLfs)
    }
}
