package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAutoScrollTest {

    private val top = 0f
    private val bottom = 1000f
    private val zone = 120f
    private val max = 2000f

    private fun v(y: Float) = EdgeAutoScroll.velocity(y, top, bottom, zone, max)

    @Test
    fun `the middle of the list does not scroll`() {
        assertEquals(0f, v(500f), 0f)
        assertEquals(0f, v(top + zone), 0f)
        assertEquals(0f, v(bottom - zone), 0f)
    }

    @Test
    fun `top zone scrolls up and bottom zone scrolls down`() {
        assertTrue(v(10f) < 0f)
        assertTrue(v(990f) > 0f)
    }

    @Test
    fun `speed rises the deeper the finger is in a zone`() {
        val shallow = v(bottom - zone + 20f)
        val middle = v(bottom - zone / 2f)
        val deep = v(bottom - 5f)
        assertTrue(shallow in 0f..middle)
        assertTrue(middle < deep)
        assertTrue(deep <= max)
        assertEquals(max / 4f, middle, 0.01f)
        assertEquals(-max / 4f, v(top + zone / 2f), 0.01f)
    }

    @Test
    fun `a finger past the edge gets full speed and no more`() {
        assertEquals(max, v(bottom), 0.01f)
        assertEquals(max, v(bottom + 300f), 0.01f)
        assertEquals(-max, v(top - 300f), 0.01f)
    }

    @Test
    fun `the bottom edge is where it is told, not the end of the view`() {
        // List is 1000 tall, but the bottom 150 sits under the bar.
        val usableBottom = 850f
        assertTrue(EdgeAutoScroll.velocity(800f, top, usableBottom, zone, max) > 0f)
        assertEquals(0f, EdgeAutoScroll.velocity(700f, top, usableBottom, zone, max), 0f)
    }

    @Test
    fun `nothing scrolls without a finger or a sensible list`() {
        assertEquals(0f, v(Float.NaN), 0f)
        assertEquals(0f, EdgeAutoScroll.velocity(10f, 500f, 500f, zone, max), 0f)
        assertEquals(0f, EdgeAutoScroll.velocity(10f, top, bottom, 0f, max), 0f)
    }

    @Test
    fun `zone is a share of the list but never too small or too big`() {
        assertEquals(240f, EdgeAutoScroll.zoneSize(2000f, 100f), 0.01f)
        assertEquals(100f, EdgeAutoScroll.zoneSize(500f, 100f), 0.01f)
        // A tiny list: two zones may not meet in the middle.
        assertEquals(60f, EdgeAutoScroll.zoneSize(180f, 100f), 0.01f)
        assertEquals(0f, EdgeAutoScroll.zoneSize(0f, 100f), 0f)
    }
}
