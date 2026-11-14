package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNode
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Importing and exporting the graphic EQ as a text file. A file can be
 * anything the user happened to pick, so most of this is about refusing the
 * wrong ones whole rather than half-reading them.
 */
class GraphicEqFileTest {

    private fun nodes(vararg n: Pair<Double, Double>) =
        GraphicEqNodeList().apply { n.forEach { add(GraphicEqNode(it.first, it.second)) } }

    private fun ok(text: String) = (GraphicEqFile.parse(text) as? GraphicEqFile.Parsed.Ok)?.nodes
        ?: throw AssertionError("refused: $text")

    private fun refused(text: String): GraphicEqFile.Parsed.Error =
        GraphicEqFile.parse(text) as? GraphicEqFile.Parsed.Error
            ?: throw AssertionError("accepted: $text")

    @Test
    fun `export then import gives the same nodes`() {
        val list = nodes(20.0 to -1.5, 105.25 to 6.123456, 1000.0 to 0.0, 19871.0 to -12.75)
        val text = GraphicEqFile.format(list)
        val back = ok(text)
        assertEquals(list.map { it.freq to it.gain }, back.map { it.freq to it.gain })
    }

    @Test
    fun `the exported line is the one AutoEq writes`() {
        val text = GraphicEqFile.format(nodes(20.0 to -1.5, 21.0 to -1.4))
        assertEquals("GraphicEQ: 20 -1.5; 21 -1.4\n", text)
    }

    @Test
    fun `an AutoEq file with other lines around the curve imports`() {
        val text = "\uFEFF# from AutoEq\r\nPreamp: -6.2 dB\r\nGraphicEQ: 20 -0.6; 21\t-0.6; 22 -0.5\r\n"
        val back = ok(text)
        assertEquals(listOf(20.0 to -0.6, 21.0 to -0.6, 22.0 to -0.5), back.map { it.freq to it.gain })
    }

    @Test
    fun `nodes out of order come back sorted`() {
        val back = ok("graphiceq: 1000 2; 20 1; 300 0;")
        assertEquals(listOf(20.0, 300.0, 1000.0), back.map { it.freq })
    }

    @Test
    fun `a file with no curve in it is refused`() {
        assertEquals(GraphicEqFile.Reason.NO_GRAPHIC_EQ_LINE, refused("").reason)
        assertEquals(GraphicEqFile.Reason.NO_GRAPHIC_EQ_LINE, refused("Preamp: -3 dB\nFilter 1: ON PK Fc 100 Hz Gain 3 dB Q 1").reason)
        assertEquals(GraphicEqFile.Reason.NO_NODES, refused("GraphicEQ:").reason)
        assertEquals(GraphicEqFile.Reason.NO_NODES, refused("GraphicEQ: ; ;").reason)
    }

    @Test
    fun `one bad entry refuses the whole file and names it`() {
        for (bad in listOf("abc 1", "20", "20 1 3", "20,5 1", "NaN 1", "20 Infinity", "20 -")) {
            val e = refused("GraphicEQ: 10 0; $bad; 30 1")
            assertEquals(bad, GraphicEqFile.Reason.BAD_ENTRY, e.reason)
            assertEquals(bad, e.entry)
        }
    }

    @Test
    fun `values no curve in the app can hold are refused`() {
        for (bad in listOf("-20 1", "-0.5 0", "24000.5 1", "30000 1", "1000 48.1", "1000 -60")) {
            val e = refused("GraphicEQ: 100 0; $bad")
            assertEquals(bad, GraphicEqFile.Reason.OUT_OF_RANGE, e.reason)
            assertEquals(bad, e.entry)
        }
        // The edges themselves are fine
        ok("GraphicEQ: 0 -48; 24000 48")
    }

    @Test
    fun `a Match EQ curve and a 0 Hz node survive export and import`() {
        // Match EQ at its widest limits: 30 dB cut, then lowered by a 12 dB
        // boost plus headroom. The node editor also takes 0 Hz.
        val list = nodes(0.0 to -3.0, 20.0 to -42.2, 1000.0 to -12.2, 8000.0 to -0.2, 24000.0 to -30.0)
        val back = ok(GraphicEqFile.format(list))
        assertEquals(list.map { it.freq to it.gain }, back.map { it.freq to it.gain })
    }

    @Test
    fun `binary junk is refused, not thrown`() {
        val junk = String(ByteArray(4096) { (it * 37).toByte() }, Charsets.ISO_8859_1)
        refused(junk)
        refused("GraphicEQ: " + junk)
    }

    @Test
    fun `reading stops at the size limit`() {
        val small = "GraphicEQ: 20 1".toByteArray()
        assertEquals("GraphicEQ: 20 1", GraphicEqFile.read(small.inputStream()))
        val exact = ByteArray(GraphicEqFile.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(GraphicEqFile.MAX_BYTES, GraphicEqFile.read(exact.inputStream())?.length)
        val big = ByteArray(GraphicEqFile.MAX_BYTES + 1)
        assertNull(GraphicEqFile.read(big.inputStream()))
        assertTrue(GraphicEqFile.parse("x".repeat(GraphicEqFile.MAX_BYTES + 1)) is GraphicEqFile.Parsed.Error)
    }
}
