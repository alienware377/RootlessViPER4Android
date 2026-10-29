package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupFoldsTest {

    // Two cards before any heading, then the V4A heading with two cards, then
    // a custom group with two cards.
    private val order = listOf(
        "card_bass", "card_clarity",
        "group_v4a", "card_agc", "card_eq",
        "group_custom_1", "card_ddc", "card_reverb",
    )
    private val headers = setOf("group_v4a", "group_custom_1")

    @Test
    fun `a group owns the cards up to the next heading`() {
        assertEquals(listOf("card_agc", "card_eq"), GroupFolds.membersOf(order, headers, "group_v4a"))
        assertEquals(listOf("card_ddc", "card_reverb"), GroupFolds.membersOf(order, headers, "group_custom_1"))
        assertEquals(emptyList<String>(), GroupFolds.membersOf(order, headers, "group_missing"))
    }

    @Test
    fun `folding a group hides only its own cards`() {
        assertEquals(
            setOf("card_ddc", "card_reverb"),
            GroupFolds.foldedAway(order, headers, setOf("group_custom_1"))
        )
        assertEquals(emptySet<String>(), GroupFolds.foldedAway(order, headers, emptySet()))
    }

    @Test
    fun `a fold under a heading that is not on show hides nothing`() {
        val folded = setOf("group_v4a", "group_custom_1")
        // V4A-only mode: no heading may fold
        assertEquals(emptySet<String>(), GroupFolds.foldedAway(order, headers, folded) { false })
        // Classic layout: only the V4A heading is gone
        assertEquals(
            setOf("card_ddc", "card_reverb"),
            GroupFolds.foldedAway(order, headers, folded) { it != "group_v4a" }
        )
    }

    @Test
    fun `a fold left behind by a deleted group hides nothing`() {
        val headersAfterDelete = setOf("group_v4a")
        val orderAfterDelete = order - "group_custom_1"
        assertEquals(
            emptySet<String>(),
            GroupFolds.foldedAway(orderAfterDelete, headersAfterDelete, setOf("group_custom_1"))
        )
    }

    @Test
    fun `deleting a group clears its fold and keeps the others`() {
        val folded = setOf("group_v4a", "group_custom_1")
        assertEquals(setOf("group_v4a"), GroupFolds.forget(folded, "group_custom_1"))
        assertEquals(folded, GroupFolds.forget(folded, "group_custom_9"))
    }

    @Test
    fun `toggling flips one group only`() {
        assertEquals(setOf("group_v4a", "group_custom_1"), GroupFolds.toggled(setOf("group_v4a"), "group_custom_1"))
        assertEquals(setOf("group_v4a"), GroupFolds.toggled(setOf("group_v4a", "group_custom_1"), "group_custom_1"))
    }

    @Test
    fun `a preset opens only the groups holding a card it switched on`() {
        val folded = setOf("group_v4a", "group_custom_1")
        assertEquals(
            setOf("group_v4a"),
            GroupFolds.unfoldFor(order, headers, folded, setOf("card_reverb", "card_bass"))
        )
        // Cards outside every folded group change nothing
        assertEquals(folded, GroupFolds.unfoldFor(order, headers, folded, setOf("card_clarity")))
        assertEquals(folded, GroupFolds.unfoldFor(order, headers, folded, emptySet()))
    }
}
