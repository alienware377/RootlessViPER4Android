package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.max
import kotlin.math.min

/**
 * How fast the effect list scrolls while a dragged card is held near its top
 * or bottom edge in the layout editor.
 *
 * Kept free of Android types so the numbers can be checked in a plain unit
 * test. All values are pixels (or pixels per second) and the y position is in
 * the coordinates of the visible part of the list.
 */
object EdgeAutoScroll {

    /** Share of the visible list each edge zone takes up. */
    const val ZONE_FRACTION = 0.12f

    /**
     * Height of one edge zone.
     *
     * A share of the visible list, but never less than [minZone] so there is
     * always room for a fingertip on a short screen, and never more than a
     * third of the list so the two zones can not meet in the middle.
     */
    fun zoneSize(visibleHeight: Float, minZone: Float, fraction: Float = ZONE_FRACTION): Float {
        if (visibleHeight <= 0f) return 0f
        return min(max(visibleHeight * fraction, minZone), visibleHeight / 3f)
    }

    /**
     * Scroll speed for a finger at [y], in pixels per second.
     *
     * Negative scrolls up, positive scrolls down, zero means the finger is
     * outside both zones. The speed starts at nothing on the inner edge of a
     * zone and rises smoothly to [maxSpeed] at the outer edge; a finger past
     * the edge (over the bar at the bottom, say) gets the full speed.
     *
     * @param top top of the usable part of the list
     * @param bottom bottom of the usable part of the list, which is above
     *        anything pinned over the end of it
     */
    fun velocity(y: Float, top: Float, bottom: Float, zone: Float, maxSpeed: Float): Float {
        if (y.isNaN() || bottom <= top || zone <= 0f || maxSpeed <= 0f) return 0f
        val upDepth = (top + zone - y) / zone
        if (upDepth > 0f) return -maxSpeed * ease(upDepth)
        val downDepth = (y - (bottom - zone)) / zone
        if (downDepth > 0f) return maxSpeed * ease(downDepth)
        return 0f
    }

    /** Quadratic ramp: gentle at the start of a zone, quick near the edge. */
    private fun ease(depth: Float): Float {
        val t = depth.coerceIn(0f, 1f)
        return t * t
    }
}
