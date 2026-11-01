package me.timschneeberger.rootlessjamesdsp.matcheq

import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text Match EQ hands over has to load into the graphic EQ as it is. */
class MatchEqOutputTest {

    @Test
    fun `output loads into the graphic EQ with 127 nodes topping out at -0_2 dB`() {
        val r = MatchEqSolver.solve(Synthetic.headphone(), Synthetic.target())
        val nodes = GraphicEqNodeList()
        nodes.deserialize(r.graphicEqString())
        assertEquals(127, nodes.size)
        assertEquals(-0.2, nodes.maxOf { it.gain }, 1e-9)
        assertEquals(20.0, nodes.first().freq, 0.0)
        assertEquals(19871.0, nodes.last().freq, 0.0)
        assertTrue(r.graphicEqString().startsWith("GraphicEQ: 20 "))
    }

}
