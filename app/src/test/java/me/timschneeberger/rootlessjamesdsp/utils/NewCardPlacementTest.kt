package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NewCardPlacementTest {

    // The cards and the one built-in heading in the order fragment_dsp.xml
    // declares them, which is the default layout.
    private val declared = listOf(
        "card_output_control", "card_compressor", "card_bass", "card_bassex",
        "card_stereowide", "card_binaural", "card_crossfeed", "card_reverb",
        "card_geq", "card_peq",
        "card_liveprog", "card_liveprog2", "card_liveprog3", "card_liveprog4",
        "card_pitchshift", "card_echo", "card_mbd", "card_maxr", "card_dyneq",
        "card_shaper", "card_imaging", "card_transient", "card_lowend",
        "card_exciter", "card_tape", "card_vinyl", "card_balance",
        "group_v4a",
        "card_agc", "card_fetcomp", "card_ddc", "card_spectrumext", "card_eq",
        "card_convolver", "card_fieldsurround", "card_diffsurround",
        "card_hpsurround", "card_vreverb", "card_vdynbass", "card_tube",
        "card_viperbass", "card_clarity", "card_cure", "card_speakeropt",
    )

    private val gBass = "group_custom_1"
    private val gDyn = "group_custom_2"
    private val gEq = "group_custom_3"
    private val gStereo = "group_custom_4"
    private val gFx = "group_custom_5"
    private val headers = setOf("group_v4a", gBass, gDyn, gEq, gStereo, gFx)

    /**
     * A layout rearranged into the user's own groups and saved before the
     * spectral shaper and the binaural upmix existed.
     */
    private val savedBeforeShaper = listOf(
        "card_output_control",
        gBass, "card_bass", "card_bassex", "card_lowend",
        gDyn, "card_compressor", "card_mbd", "card_maxr", "card_dyneq", "card_transient",
        gEq, "card_geq", "card_peq",
        gStereo, "card_stereowide", "card_crossfeed", "card_imaging",
        gFx, "card_reverb", "card_echo", "card_pitchshift", "card_exciter",
        "card_tape", "card_vinyl", "card_balance",
        "card_liveprog", "card_liveprog2", "card_liveprog3", "card_liveprog4",
        "group_v4a",
        "card_agc", "card_fetcomp", "card_ddc", "card_spectrumext", "card_eq",
        "card_convolver", "card_fieldsurround", "card_diffsurround",
        "card_hpsurround", "card_vreverb", "card_vdynbass", "card_tube",
        "card_viperbass", "card_clarity", "card_cure", "card_speakeropt",
    )

    /** The same layout saved once the shaper existed, but not the upmix. */
    private val savedBeforeBinaural = savedBeforeShaper.toMutableList().apply {
        add(indexOf("card_dyneq") + 1, "card_shaper")
    }

    /** Everything that exists on screen once the user-made headings are built. */
    private val present = declared + listOf(gBass, gDyn, gEq, gStereo, gFx)

    private fun merge(saved: List<String>, hidden: Set<String> = emptySet()) =
        NewCardPlacement.merge(saved, declared, present, headers, hidden)

    /** The heading a card sits under, or null above every heading. */
    private fun groupOf(order: List<String>, key: String): String? =
        order.subList(0, order.indexOf(key)).lastOrNull { it in headers }

    /** What the layout did before: the card's declared position number. */
    private fun oldMerge(saved: List<String>, natural: List<String>): List<String> {
        val result = ArrayList(saved.filter { natural.contains(it) })
        natural.forEachIndexed { index, key ->
            if (!result.contains(key)) result.add(index.coerceAtMost(result.size), key)
        }
        return result
    }

    @Test
    fun `the old placement put both cards in unrelated groups`() {
        // The natural order the old code saw on a fresh start: the declared
        // cards, then the user-made headings, built last.
        val old = oldMerge(savedBeforeShaper, present)
        assertEquals(gBass, groupOf(old, "card_binaural"))
        assertEquals("card_lowend", old[old.indexOf("card_binaural") - 1])
        assertEquals(gStereo, groupOf(old, "card_shaper"))
        assertEquals("card_imaging", old[old.indexOf("card_shaper") - 1])
    }

    @Test
    fun `new cards follow their declared neighbour into its group`() {
        val merged = merge(savedBeforeShaper)
        assertEquals("card_stereowide", merged[merged.indexOf("card_binaural") - 1])
        assertEquals(gStereo, groupOf(merged, "card_binaural"))
        assertEquals("card_dyneq", merged[merged.indexOf("card_shaper") - 1])
        assertEquals(gDyn, groupOf(merged, "card_shaper"))
    }

    @Test
    fun `the LUFS normalizer joins vinyl and balance in the user's group`() {
        val withLufs = declared.toMutableList().apply { add(indexOf("card_balance"), "card_lufs") }
        val merged = NewCardPlacement.merge(savedBeforeShaper, withLufs, present + "card_lufs", headers)
        assertEquals("card_vinyl", merged[merged.indexOf("card_lufs") - 1])
        assertEquals("card_balance", merged[merged.indexOf("card_lufs") + 1])
        assertEquals(gFx, groupOf(merged, "card_lufs"))
    }

    @Test
    fun `a layout saved before the upmix only gains the upmix`() {
        val merged = merge(savedBeforeBinaural)
        assertEquals("card_stereowide", merged[merged.indexOf("card_binaural") - 1])
        assertEquals(savedBeforeBinaural, merged - "card_binaural")
    }

    @Test
    fun `everything the user arranged keeps its order`() {
        for (saved in listOf(savedBeforeShaper, savedBeforeBinaural)) {
            val merged = merge(saved)
            assertEquals(saved, merged.filter { it in saved })
            assertEquals(present.toSet(), merged.toSet())
            assertEquals(merged.size, merged.toSet().size)
        }
    }

    @Test
    fun `an untouched order gets the default layout`() {
        val untouched = declared - "card_binaural" - "card_shaper"
        assertEquals(declared, NewCardPlacement.merge(untouched, declared, declared, setOf("group_v4a")))
    }

    @Test
    fun `a neighbour put away in a hidden group is passed over`() {
        // The user made a group to hide cards in and put the stereo widener
        // there. The upmix should not vanish with it; the crossfeed is the
        // next neighbour and is still on show.
        val saved = savedBeforeShaper - "card_stereowide" + listOf("group_custom_9", "card_stereowide")
        val withBin = setOf("group_custom_9") + headers
        val merged = NewCardPlacement.merge(
            saved, declared, present + "group_custom_9", withBin, hidden = setOf("group_custom_9"),
        )
        assertEquals("card_crossfeed", merged[merged.indexOf("card_binaural") + 1])
        assertEquals(gStereo, groupOf(merged, "card_binaural"))
    }

    @Test
    fun `with every neighbour in a hidden group the card stays with them`() {
        // The whole stereo group is hidden, so both neighbours are put away
        // and the card goes with them rather than into some unrelated group.
        val merged = merge(savedBeforeShaper, hidden = setOf(gStereo))
        assertEquals("card_stereowide", merged[merged.indexOf("card_binaural") - 1])
    }

    @Test
    fun `a card the user hid still counts as a neighbour`() {
        val merged = merge(savedBeforeShaper, hidden = setOf("card_stereowide"))
        assertEquals("card_stereowide", merged[merged.indexOf("card_binaural") - 1])
    }

    @Test
    fun `a run of new cards keeps its own order`() {
        val saved = savedBeforeShaper - "card_crossfeed"
        val merged = merge(saved)
        val at = merged.indexOf("card_stereowide")
        assertEquals(listOf("card_stereowide", "card_binaural", "card_crossfeed"), merged.subList(at, at + 3))
    }

    @Test
    fun `the first card of a group goes before the one declared after it`() {
        // card_agc is the first V4A card, so it has no neighbour before it.
        val saved = savedBeforeShaper - "card_agc"
        val merged = merge(saved)
        assertEquals("card_fetcomp", merged[merged.indexOf("card_agc") + 1])
        assertEquals("group_v4a", groupOf(merged, "card_agc"))
    }

    @Test
    fun `neighbours never come from another declared group`() {
        // card_balance is the last card before the V4A heading and card_agc
        // the first after it. With every top card but balance gone, a new
        // V4A card must not attach to balance.
        val tiny = listOf("card_balance", "group_v4a", "card_agc", "card_fetcomp")
        val merged = NewCardPlacement.merge(
            saved = listOf("card_balance", "group_v4a"),
            declared = tiny, present = tiny, headers = setOf("group_v4a"),
        )
        assertEquals(listOf("card_balance", "group_v4a", "card_agc", "card_fetcomp"), merged)
    }

    @Test
    fun `with no neighbour placed a card goes to the end of its declared group`() {
        val tiny = listOf("card_a", "group_v4a", "card_b", "card_c")
        val custom = "group_custom_7"
        val merged = NewCardPlacement.merge(
            saved = listOf("card_a", "group_v4a", custom),
            declared = tiny,
            present = tiny + custom,
            headers = setOf("group_v4a", custom),
        )
        assertEquals(listOf("card_a", "group_v4a", "card_b", "card_c", custom), merged)
    }

    @Test
    fun `a top card with no neighbour goes above every heading`() {
        val tiny = listOf("card_a", "card_b", "group_v4a", "card_c")
        val merged = NewCardPlacement.merge(
            saved = listOf("group_v4a", "card_c"),
            declared = tiny, present = tiny, headers = setOf("group_v4a"),
        )
        assertEquals(listOf("card_a", "card_b", "group_v4a", "card_c"), merged)
    }

    @Test
    fun `a missing built-in heading goes last so it takes no cards`() {
        val tiny = listOf("card_a", "group_v4a", "card_b")
        val merged = NewCardPlacement.merge(
            saved = listOf("card_b", "card_a"),
            declared = tiny, present = tiny, headers = setOf("group_v4a"),
        )
        assertEquals(listOf("card_b", "card_a", "group_v4a"), merged)

        // Its declared neighbour sits above a card here. Slotted in after
        // that neighbour, the heading would take that card into its group.
        val merged2 = NewCardPlacement.merge(
            saved = listOf("card_a", "card_b"),
            declared = tiny, present = tiny, headers = setOf("group_v4a"),
        )
        assertEquals(listOf("card_a", "card_b", "group_v4a"), merged2)
    }

    @Test
    fun `gone cards and repeats are dropped and unknown headings go last`() {
        val tiny = listOf("card_a", "card_b")
        val custom = "group_custom_8"
        val merged = NewCardPlacement.merge(
            saved = listOf("card_b", "card_gone", "card_b", "card_a"),
            declared = tiny, present = tiny + custom, headers = setOf(custom),
        )
        assertEquals(listOf("card_b", "card_a", custom), merged)
    }

    @Test
    fun `restoring the default order puts every built-in card back where it is declared`() {
        val present = savedBeforeShaper + listOf("card_shaper", "card_binaural")
        val restored = NewCardPlacement.defaultOrder(declared, present)
        assertEquals(declared, restored.filter { it in declared })
    }

    @Test
    fun `restoring the default order keeps the user's own headings, after everything else`() {
        val present = savedBeforeShaper + listOf("card_shaper", "card_binaural")
        val restored = NewCardPlacement.defaultOrder(declared, present)
        assertEquals(declared + listOf(gBass, gDyn, gEq, gStereo, gFx), restored)
    }

    @Test
    fun `restoring the default order names only what is on screen, once each`() {
        val present = listOf("card_eq", gFx, "card_bass", "card_eq", "card_output_control")
        val restored = NewCardPlacement.defaultOrder(declared, present)
        assertEquals(listOf("card_output_control", "card_bass", "card_eq", gFx), restored)
    }
}
