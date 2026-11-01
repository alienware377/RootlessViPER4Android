package me.timschneeberger.rootlessjamesdsp.utils

/**
 * Puts effects that are newer than a saved processing order where they belong.
 *
 * The order is saved as a list of effect ids, so a list saved before an effect
 * existed cannot mention it. The engine still runs such an effect, by adding it
 * at the very end of the chain, which is after the maximiser. That is safe for
 * most effects but wrong for one meant to tidy the sound before it is spread
 * about and limited. Effects listed here are instead slotted in right after the
 * effect they follow in the default chain, wherever the user has moved that one.
 *
 * Both the engine push and the processing order screen go through this, so the
 * screen shows the order that is actually running.
 */
object ChainOrder {
    /** JdspEffectId values, as in jdsp_header.h. */
    const val DDC = 12
    const val MAXIMIZER = 30
    const val SHAPER = 39

    /** Newer effect id to the id it follows in the default chain. */
    private val placeAfter = linkedMapOf(
        SHAPER to DDC,
    )

    /**
     * Returns [saved] with every effect from [placeAfter] that it lacks put in
     * after its anchor. With the anchor missing too, it goes in just ahead of
     * the maximiser, and with neither it is left for the engine to append. An
     * empty list stays empty, since that means "use the default chain".
     */
    fun complete(saved: List<Int>): List<Int> {
        if (saved.isEmpty()) return saved
        val out = saved.toMutableList()
        for ((id, anchor) in placeAfter) {
            if (id in out) continue
            val at = out.indexOf(anchor)
            if (at >= 0) {
                out.add(at + 1, id)
                continue
            }
            val maxr = out.indexOf(MAXIMIZER)
            if (maxr >= 0) out.add(maxr, id)
        }
        return out
    }
}
