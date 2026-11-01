package me.timschneeberger.rootlessjamesdsp.matcheq

import me.timschneeberger.rootlessjamesdsp.matcheq.CurveParser.Problem
import me.timschneeberger.rootlessjamesdsp.matcheq.CurveParser.Result
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveParserTest {

    private val freqs = listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)

    private fun ok(text: String, cols: List<String> = listOf("raw")): Result.Ok {
        val r = CurveParser.parse(text, cols)
        assertTrue("expected a curve, got $r", r is Result.Ok)
        return r as Result.Ok
    }

    private fun failed(text: String): Problem = (CurveParser.parse(text) as Result.Failed).problem

    @Test
    fun `plain comma separated pairs`() {
        val c = ok(freqs.joinToString("\n") { "$it,${it / 1000.0}" }).curve
        assertEquals(10, c.size)
        assertEquals(20.0, c.freq[0], 0.0)
        assertEquals(20.0, c.gain[9], 1e-12)
    }

    @Test
    fun `semicolons with a decimal comma`() {
        val c = ok(freqs.joinToString("\r\n") { "$it;-1,5" }).curve
        c.gain.forEach { assertEquals(-1.5, it, 1e-12) }
    }

    @Test
    fun `tabs and spaces`() {
        assertEquals(10, ok(freqs.joinToString("\n") { "$it\t2.0" }).curve.size)
        assertEquals(10, ok(freqs.joinToString("\n") { "  $it    2.0  " }).curve.size)
    }

    @Test
    fun `REW export with star header lines and a phase column`() {
        val text = "* Measurement data measured by REW\n* Source: thing\n* Freq(Hz) SPL(dB) Phase(degrees)\n" +
            freqs.joinToString("\n") { "$it.000 7${it % 10}.5 -12.0" }
        val c = ok(text).curve
        assertEquals(10, c.size)
        assertEquals(70.5, c.gain[0], 1e-12)
    }

    @Test
    fun `AutoEq CSV uses the named column, not just the second one`() {
        val text = "frequency,smoothed,raw,target\n" + freqs.joinToString("\n") { "$it,1.0,2.0,3.0" }
        ok(text).curve.gain.forEach { assertEquals(2.0, it, 0.0) }
        // a target file read with the target preference picks raw first, then target
        val noRaw = "frequency,smoothed,target\n" + freqs.joinToString("\n") { "$it,1.0,3.0" }
        ok(noRaw, listOf("raw", "target")).curve.gain.forEach { assertEquals(3.0, it, 0.0) }
    }

    @Test
    fun `comments, BOM and blank lines are skipped`() {
        val text = "\uFEFF# exported\n\n// note\nfrequency,raw\n" + freqs.joinToString("\n") { "$it,0.5" } + "\n\n"
        assertEquals(10, ok(text).curve.size)
    }

    @Test
    fun `unsorted input is sorted and repeated frequencies averaged`() {
        val text = (freqs.reversed().map { "$it,1" } + "1000,3").joinToString("\n")
        val c = ok(text).curve
        assertArrayEquals(freqs.map { it.toDouble() }.toDoubleArray(), c.freq, 0.0)
        assertEquals(2.0, c.gain[5], 1e-12)
    }

    @Test
    fun `GraphicEQ text is recognised as a correction`() {
        val r = ok("GraphicEQ: " + freqs.joinToString("; ") { "$it -1.5" })
        assertTrue(r.isGraphicEq)
        assertFalse(ok(freqs.joinToString("\n") { "$it,1" }).isGraphicEq)
    }

    @Test
    fun `garbage is turned away with a reason`() {
        assertEquals(Problem.EMPTY, failed(""))
        assertEquals(Problem.EMPTY, failed("hello\nworld"))
        assertEquals(Problem.TOO_FEW_POINTS, failed("100,1\n200,2\n300,3"))
        assertEquals(Problem.TOO_NARROW, failed((0 until 20).joinToString("\n") { "${1000 + it},1" }))
        assertEquals(Problem.OUT_OF_RANGE, failed(freqs.joinToString("\n") { "$it,500" }))
        assertEquals(Problem.EMPTY, failed(freqs.joinToString("\n") { "-$it,1" }))
    }
}
