package me.timschneeberger.rootlessjamesdsp.utils

/**
 * Where a card the saved layout has never seen goes.
 *
 * A saved layout is just the card order the user left behind. An update that
 * adds an effect brings a card that order does not name, and it has to be
 * slotted in somewhere without moving anything the user put in place.
 *
 * Its position number in the declared layout is no use for that: once the
 * user has moved cards around, the same number points into a different group
 * altogether. What does carry over is its neighbours. A new card goes right
 * after the card declared just before it in the same declared group, wherever
 * the user has since put that card, so it ends up beside the effect it was
 * designed to sit next to and in the group the user filed that one under.
 *
 * Kept free of Android types so it can be checked in a plain unit test.
 */
object NewCardPlacement {

    /**
     * The saved order with every card it is missing put in place.
     *
     * @param saved the order the user saved, possibly naming cards that are
     *   gone and missing ones that are new
     * @param declared every built-in card and heading in the order the screen
     *   declares them, the default layout
     * @param present the cards and headings that exist right now, in any order;
     *   these are the only ones the result names
     * @param headers every heading key, built-in and user-made
     * @param hidden what the user hid, cards and headings alike
     */
    fun merge(
        saved: List<String>,
        declared: List<String>,
        present: Collection<String>,
        headers: Set<String>,
        hidden: Set<String> = emptySet(),
    ): List<String> {
        val presentSet = present.toHashSet()
        val result = ArrayList<String>()
        saved.forEach { key -> if (key in presentSet && key !in result) result.add(key) }

        // In declared order, so a run of new cards keeps its own order: each
        // one finds the one before it already placed.
        declared.forEachIndexed { index, key ->
            if (key !in presentSet || key in result) return@forEachIndexed
            result.add(slotFor(index, declared, result, headers, hidden), key)
        }

        // Anything left is neither saved nor declared, such as a group the
        // user made that a preset forgot to list. The end leaves the rest alone.
        present.forEach { key -> if (key !in result) result.add(key) }
        return result
    }

    private fun slotFor(
        index: Int,
        declared: List<String>,
        result: List<String>,
        headers: Set<String>,
        hidden: Set<String>,
    ): Int {
        // A built-in heading the saved order lacks. Put anywhere else, it
        // would take the cards below it out of the group the user put them in.
        if (declared[index] in headers) return result.size

        // The declared group: from the heading above it to the next one.
        var start = index
        while (start > 0 && declared[start - 1] !in headers) start--
        var end = index + 1
        while (end < declared.size && declared[end] !in headers) end++
        val heading = declared.getOrNull(start - 1)

        // The nearest placed neighbour before it, and the nearest after it.
        val before = (index - 1 downTo start)
            .map { result.indexOf(declared[it]) }.firstOrNull { it >= 0 }
        val after = (index + 1 until end)
            .map { result.indexOf(declared[it]) }.firstOrNull { it >= 0 }
        // The one before wins. But a group the user hid as a whole is a place
        // to put cards away, so if that neighbour was put there and the other
        // one is on show, the card goes with the one on show instead.
        if (before != null && after != null &&
            inHiddenGroup(before, result, headers, hidden) &&
            !inHiddenGroup(after, result, headers, hidden)
        ) return after
        if (before != null) return before + 1
        if (after != null) return after

        // No neighbour placed at all: the end of its own declared group, if
        // that heading is there.
        if (heading != null) {
            val at = result.indexOf(heading)
            if (at >= 0) return endOfGroup(at, result, headers)
        }
        // A card from the top of the screen, which has no heading: the end of
        // the cards that sit above every heading.
        if (heading == null) {
            val firstHeading = result.indexOfFirst { it in headers }
            return if (firstHeading < 0) result.size else firstHeading
        }
        return result.size
    }

    /** True when the card at [at] sits in a group whose heading the user hid. */
    private fun inHiddenGroup(
        at: Int,
        order: List<String>,
        headers: Set<String>,
        hidden: Set<String>,
    ): Boolean {
        for (i in at - 1 downTo 0) {
            if (order[i] in headers) return order[i] in hidden
        }
        return false
    }

    /** The slot just after the last card under the heading at [at]. */
    private fun endOfGroup(at: Int, order: List<String>, headers: Set<String>): Int {
        var i = at + 1
        while (i < order.size && order[i] !in headers) i++
        return i
    }

    /**
     * The order "Restore the default effect order" puts the screen back in.
     *
     * Every built-in card and heading goes back where it is declared. A
     * heading the user made is kept, since the reset leaves their groups
     * alone, but goes after everything else: a heading owns the cards below
     * it, so one left near the top would swallow a built-in group.
     *
     * @param declared every built-in card and heading in declared order
     * @param present what the screen holds right now, in its current order
     */
    fun defaultOrder(declared: List<String>, present: List<String>): List<String> {
        val presentSet = present.toHashSet()
        val declaredSet = declared.toHashSet()
        val builtIn = declared.filter { it in presentSet }.distinct()
        val userMade = present.filter { it !in declaredSet }.distinct()
        return builtIn + userMade
    }
}
