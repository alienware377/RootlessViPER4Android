package me.timschneeberger.rootlessjamesdsp.utils

/**
 * Which effect cards a folded group puts out of sight.
 *
 * A group is not stored as a list of cards. It is a heading in the card order,
 * and it owns every card after it up to the next heading. Folding is kept as
 * the set of heading keys that are folded, next to the order in the layout
 * prefs. Keys never change when a group is renamed, so a rename keeps its
 * fold without any extra work, while a deleted group has to be taken out.
 *
 * Kept free of Android types so it can be checked in a plain unit test.
 */
object GroupFolds {

    /** The cards under [header]: everything after it until the next heading. */
    fun membersOf(order: List<String>, headers: Set<String>, header: String): List<String> {
        val start = order.indexOf(header)
        if (start < 0) return emptyList()
        val members = ArrayList<String>()
        for (i in start + 1 until order.size) {
            if (order[i] in headers) break
            members.add(order[i])
        }
        return members
    }

    /**
     * Every card that a folded group hides.
     *
     * [canFold] says whether a heading's fold counts right now. A heading that
     * is not on screen (V4A-only mode hides them all) must not fold anything,
     * or its cards would be gone with nothing left to tap to bring them back.
     */
    fun foldedAway(
        order: List<String>,
        headers: Set<String>,
        folded: Set<String>,
        canFold: (String) -> Boolean = { true },
    ): Set<String> {
        val away = HashSet<String>()
        folded.filter { it in headers && canFold(it) }.forEach { header ->
            away.addAll(membersOf(order, headers, header))
        }
        return away
    }

    /**
     * The folded set after opening every group that holds one of [cards].
     *
     * Used when a preset switches effects on: a card that was just turned on
     * should be in sight, the same way a preset may unhide one.
     */
    fun unfoldFor(
        order: List<String>,
        headers: Set<String>,
        folded: Set<String>,
        cards: Set<String>,
    ): Set<String> = folded.filterTo(HashSet()) { header ->
        membersOf(order, headers, header).none { it in cards }
    }

    /** The folded set with [header] flipped. */
    fun toggled(folded: Set<String>, header: String): Set<String> =
        if (header in folded) folded - header else folded + header

    /** The folded set once [header] no longer exists. */
    fun forget(folded: Set<String>, header: String): Set<String> = folded - header
}
