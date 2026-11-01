package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainOrderTest {

    // An order the processing order screen saved before the shaper existed:
    // every id it listed then, here with DDC and the maximiser moved about.
    private val oldSaved = listOf(0, 1, 2, 3, 30, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35, 36)

    private val newer = setOf(ChainOrder.SHAPER, ChainOrder.BINAURAL)

    @Test
    fun `a saved order without the shaper gets it right after the DDC`() {
        val out = ChainOrder.complete(oldSaved)
        assertEquals(oldSaved.size + 2, out.size)
        assertEquals(out.indexOf(ChainOrder.DDC) + 1, out.indexOf(ChainOrder.SHAPER))
        // Everything else keeps the order the user gave it.
        assertEquals(oldSaved, out.filter { it !in newer })
    }

    @Test
    fun `a saved order without binaural gets it right after the stereo widening`() {
        val out = ChainOrder.complete(oldSaved)
        assertEquals(out.indexOf(ChainOrder.STEREOWIDE) + 1, out.indexOf(ChainOrder.BINAURAL))
    }

    @Test
    fun `the stereo widening is followed wherever the user moved it`() {
        assertEquals(listOf(19, 40, 39, 12, 30), ChainOrder.complete(listOf(19, 39, 12, 30)))
    }

    @Test
    fun `an order saved with the shaper but before binaural keeps the shaper where it is`() {
        val withShaper = listOf(12, 0, 39, 19, 20, 30)
        assertEquals(listOf(12, 0, 39, 19, 40, 20, 30), ChainOrder.complete(withShaper))
    }

    @Test
    fun `the DDC is followed wherever the user moved it`() {
        val moved = listOf(12, 0, 1, 30)
        // Binaural has no widening to follow here, so it goes ahead of the maximiser.
        assertEquals(listOf(12, 39, 0, 1, 40, 30), ChainOrder.complete(moved))
    }

    @Test
    fun `an order that already has both is left exactly as it is`() {
        val mine = listOf(39, 30, 40, 12, 0)
        assertEquals(mine, ChainOrder.complete(mine))
    }

    @Test
    fun `without the DDC it still lands ahead of the maximiser`() {
        assertEquals(listOf(9, 0, 39, 40, 30, 3), ChainOrder.complete(listOf(9, 0, 30, 3)))
    }

    @Test
    fun `with neither anchor nothing is invented and the engine appends it`() {
        val partial = listOf(9, 0, 24, 11, 3)
        assertEquals(partial, ChainOrder.complete(partial))
    }

    @Test
    fun `an empty order still means the default chain`() {
        assertEquals(emptyList<Int>(), ChainOrder.complete(emptyList()))
    }

    @Test
    fun `duplicates from an old list are passed through for the engine to drop`() {
        assertEquals(listOf(12, 39, 12, 40, 30), ChainOrder.complete(listOf(12, 12, 30)))
    }
}
