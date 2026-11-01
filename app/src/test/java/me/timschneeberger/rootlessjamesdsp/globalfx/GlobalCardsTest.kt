package me.timschneeberger.rootlessjamesdsp.globalfx

import me.timschneeberger.rootlessjamesdsp.utils.EffectCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlobalCardsTest {

    @Test
    fun `every card is classified for global mode`() {
        val missing = EffectCards.cardKeyByNamespace.keys - GlobalCards.support.keys
        assertTrue("not classified: $missing", missing.isEmpty())
        val stale = GlobalCards.support.keys - EffectCards.cardKeyByNamespace.keys
        assertTrue("classified but not a card: $stale", stale.isEmpty())
    }

    @Test
    fun `only the cards global mode can map are offered`() {
        val usable = GlobalCards.support.filterValues { it != GlobalCards.Support.NONE }.keys
        assertEquals(8, usable.size)
    }

    private fun cardFile(name: String): File {
        // Unit tests run from the module directory under Gradle and from the
        // project root in some IDE setups.
        return listOf("src/main/res/xml/$name.xml", "app/src/main/res/xml/$name.xml")
            .map(::File).first { it.isFile }
    }

    @Test
    fun `every greyed row exists on its card`() {
        for ((ns, keys) in GlobalCards.ignoredRows) {
            val xmlName = GlobalCards.cardXml[ns]
            assertTrue("no card file listed for $ns", xmlName != null)
            val xml = cardFile(xmlName!!).readText()
            for (k in keys)
                assertTrue("$k is not on $xmlName", xml.contains("@string/$k\""))
        }
    }
}
