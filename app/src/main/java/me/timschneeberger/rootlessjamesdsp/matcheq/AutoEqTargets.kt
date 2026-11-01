/*
 * Match EQ: which AutoEq target suits which measuring rig.
 *
 * The table below is ported from AutoEq's dbtools/create_webapp_data.py at
 * commit 7ae0f56d53074872b028649617a22bbb4232feb7, by Jaakko Pasanen
 * (https://github.com/jaakkopasanen/AutoEq, MIT licence, Copyright (c)
 * 2018-2022 Jaakko Pasanen). The full notice is in THIRD_PARTY_NOTICES.md.
 * Only the file NAMES and the bass shelf amounts are here. The target curves
 * themselves are not bundled: the app downloads the one the user picks from
 * the AutoEq repository when it is needed.
 */
package me.timschneeberger.rootlessjamesdsp.matcheq

/** A measuring setup: who measured, what kind of headphone, and on which rig (if they used several). */
data class Rig(val source: String, val form: String, val rig: String?) {
    companion object {
        val FORMS = listOf("over-ear", "in-ear", "earbud")

        /**
         * Reads a rig from an AutoEq result path such as
         * "crinacle/711 in-ear/Name" or "oratory1990/over-ear/Name".
         */
        fun fromResultPath(path: String): Rig? {
            val parts = path.split('/')
            if (parts.size < 2) return null
            val group = parts[1]
            val form = FORMS.firstOrNull { group == it || group.endsWith(" $it") } ?: return null
            val rig = group.removeSuffix(form).trim().ifEmpty { null }
            return Rig(parts[0], form, rig)
        }
    }
}

data class AutoEqTarget(
    val label: String,
    /** File under targets/ in the AutoEq repository, or null for the built-in flat target. */
    val file: String?,
    /** Default bass shelf in dB, the amount AutoEq's own results use. */
    val bassBoost: Double,
    val compatible: List<Rig>,
    val recommended: List<Rig>,
) {
    val id: String get() = file?.removeSuffix(".csv") ?: AutoEqTargets.FLAT_ID

    /** A target without any compatible rigs is a generic one that suits every rig. */
    fun suits(rig: Rig?): Boolean = rig == null || compatible.isEmpty() || rig in compatible
    fun recommendedFor(rig: Rig?): Boolean = rig != null && rig in recommended
}

object AutoEqTargets {
    const val FLAT_ID = "flat"

    /** Shelf AutoEq adds for the bass boost: a low shelf at 105 Hz, Q 0.7. */
    const val BASS_FC = 105.0
    const val BASS_Q = 0.7
    /** Shelf AutoEq adds for a treble change: a high shelf at 10 kHz, Q 0.7. */
    const val TREBLE_FC = 10000.0
    const val TREBLE_Q = 0.7

    fun byId(id: String?): AutoEqTarget? = all.firstOrNull { it.id == id }

    /** The target AutoEq itself uses for results measured on [rig], if any. */
    fun recommended(rig: Rig?): AutoEqTarget? = all.firstOrNull { it.recommendedFor(rig) }

    /**
     * Targets in the order to offer them: the recommended one first, then the
     * others that suit the rig, then (only when asked) the rest.
     */
    fun ordered(rig: Rig?, includeIncompatible: Boolean): List<AutoEqTarget> {
        val rec = all.filter { it.recommendedFor(rig) }
        val ok = all.filter { it.suits(rig) && it !in rec }
        val rest = if (includeIncompatible) all.filter { !it.suits(rig) } else emptyList()
        return rec + ok + rest
    }

    val all: List<AutoEqTarget> = listOf(
        AutoEqTarget(
            label = "AutoEq in-ear",
            file = "AutoEq in-ear.csv",
            bassBoost = 8.0,
            compatible = listOf(
                Rig("Auriculares Argentina", "in-ear", null),
                Rig("Bakkwatan", "in-ear", null),
                Rig("crinacle", "in-ear", "711"),
                Rig("DHRME", "in-ear", null),
                Rig("Fahryst", "in-ear", null),
                Rig("Filk", "in-ear", null),
                Rig("freeryder05", "in-ear", null),
                Rig("Harpo", "in-ear", null),
                Rig("Hi End Portable", "in-ear", null),
                Rig("HypetheSonics", "in-ear", "GRAS RA0045"),
                Rig("Jaytiss", "in-ear", null),
                Rig("Kazi", "in-ear", null),
                Rig("Kazi", "earbud", null),
                Rig("kr0mka", "in-ear", null),
                Rig("kr0mka", "earbud", null),
                Rig("oratory1990", "in-ear", null),
                Rig("oratory1990", "earbud", null),
                Rig("Regan Cipher", "in-ear", null),
                Rig("Regan Cipher", "earbud", null),
                Rig("RikudouGoku", "in-ear", null),
                Rig("Super Review", "in-ear", null),
                Rig("Super Review", "earbud", null),
                Rig("Ted's Squig Hoard", "in-ear", null),
                Rig("ToneDeafMonk", "in-ear", null),
            ),
            recommended = listOf(
                Rig("Auriculares Argentina", "in-ear", null),
                Rig("Bakkwatan", "in-ear", null),
                Rig("crinacle", "in-ear", "711"),
                Rig("DHRME", "in-ear", null),
                Rig("Fahryst", "in-ear", null),
                Rig("Filk", "in-ear", null),
                Rig("freeryder05", "in-ear", null),
                Rig("Harpo", "in-ear", null),
                Rig("Hi End Portable", "in-ear", null),
                Rig("HypetheSonics", "in-ear", "GRAS RA0045"),
                Rig("Jaytiss", "in-ear", null),
                Rig("Kazi", "in-ear", null),
                Rig("Kazi", "earbud", null),
                Rig("kr0mka", "in-ear", null),
                Rig("kr0mka", "earbud", null),
                Rig("oratory1990", "in-ear", null),
                Rig("oratory1990", "earbud", null),
                Rig("Regan Cipher", "in-ear", null),
                Rig("Regan Cipher", "earbud", null),
                Rig("RikudouGoku", "in-ear", null),
                Rig("Super Review", "in-ear", null),
                Rig("Super Review", "earbud", null),
                Rig("Ted's Squig Hoard", "in-ear", null),
                Rig("ToneDeafMonk", "in-ear", null),
            ),
        ),
        AutoEqTarget(
            label = "crinacle EARS + 711 Harman over-ear 2018",
            file = "crinacle EARS + 711 Harman over-ear 2018 without bass.csv",
            bassBoost = 6.0,
            compatible = listOf(
                Rig("crinacle", "over-ear", "EARS + 711"),
            ),
            recommended = listOf(
                Rig("crinacle", "over-ear", "EARS + 711"),
            ),
        ),
        AutoEqTarget(
            label = "Diffuse Field 5128 (-1 dB/oct)",
            file = "Diffuse field 5128 -1dB per octave.csv",
            bassBoost = 0.0,
            compatible = emptyList(),
            recommended = emptyList(),
        ),
        AutoEqTarget(
            label = "Flat",
            file = null,
            bassBoost = 0.0,
            compatible = emptyList(),
            recommended = emptyList(),
        ),
        AutoEqTarget(
            label = "Harman in-ear 2019",
            file = "Harman in-ear 2019 without bass.csv",
            bassBoost = 9.5,
            compatible = listOf(
                Rig("Auriculares Argentina", "in-ear", null),
                Rig("Bakkwatan", "in-ear", null),
                Rig("crinacle", "in-ear", "711"),
                Rig("DHRME", "in-ear", null),
                Rig("Fahryst", "in-ear", null),
                Rig("Filk", "in-ear", null),
                Rig("freeryder05", "in-ear", null),
                Rig("Harpo", "in-ear", null),
                Rig("Hi End Portable", "in-ear", null),
                Rig("HypetheSonics", "in-ear", "GRAS RA0045"),
                Rig("Jaytiss", "in-ear", null),
                Rig("Kazi", "in-ear", null),
                Rig("Kazi", "earbud", null),
                Rig("kr0mka", "in-ear", null),
                Rig("oratory1990", "in-ear", null),
                Rig("oratory1990", "earbud", null),
                Rig("Regan Cipher", "in-ear", null),
                Rig("Regan Cipher", "earbud", null),
                Rig("RikudouGoku", "in-ear", null),
                Rig("Super Review", "in-ear", null),
                Rig("Super Review", "earbud", null),
                Rig("Ted's Squig Hoard", "in-ear", null),
                Rig("ToneDeafMonk", "in-ear", null),
            ),
            recommended = emptyList(),
        ),
        AutoEqTarget(
            label = "Harman over-ear 2018",
            file = "Harman over-ear 2018 without bass.csv",
            bassBoost = 6.0,
            compatible = listOf(
                Rig("Auriculares Argentina", "over-ear", null),
                Rig("crinacle", "over-ear", "GRAS 43AG-7"),
                Rig("Filk", "over-ear", null),
                Rig("kr0mka", "over-ear", null),
                Rig("Kuulokenurkka", "over-ear", null),
                Rig("oratory1990", "over-ear", null),
                Rig("Regan Cipher", "over-ear", null),
                Rig("RikudouGoku", "over-ear", null),
                Rig("Super Review", "over-ear", null),
            ),
            recommended = listOf(
                Rig("Auriculares Argentina", "over-ear", null),
                Rig("crinacle", "over-ear", "GRAS 43AG-7"),
                Rig("Filk", "over-ear", null),
                Rig("kr0mka", "over-ear", null),
                Rig("Kuulokenurkka", "over-ear", null),
                Rig("oratory1990", "over-ear", null),
                Rig("Regan Cipher", "over-ear", null),
                Rig("RikudouGoku", "over-ear", null),
                Rig("Super Review", "over-ear", null),
            ),
        ),
        AutoEqTarget(
            label = "HMS II.3 AutoEq in-ear",
            file = "HMS II.3 AutoEq in-ear.csv",
            bassBoost = 8.0,
            compatible = listOf(
                Rig("Headphone.com Legacy", "in-ear", null),
                Rig("Headphone.com Legacy", "earbud", null),
                Rig("Innerfidelity", "in-ear", null),
                Rig("Innerfidelity", "earbud", null),
                Rig("Rtings", "in-ear", "HMS II.3"),
                Rig("Rtings", "earbud", "HMS II.3"),
            ),
            recommended = listOf(
                Rig("Headphone.com Legacy", "in-ear", null),
                Rig("Headphone.com Legacy", "earbud", null),
                Rig("Innerfidelity", "in-ear", null),
                Rig("Innerfidelity", "earbud", null),
                Rig("Rtings", "in-ear", "HMS II.3"),
                Rig("Rtings", "earbud", "HMS II.3"),
            ),
        ),
        AutoEqTarget(
            label = "HMS II.3 Harman in-ear 2019",
            file = "HMS II.3 Harman in-ear 2019 without bass.csv",
            bassBoost = 9.5,
            compatible = listOf(
                Rig("Headphone.com Legacy", "in-ear", null),
                Rig("Headphone.com Legacy", "earbud", null),
                Rig("Innerfidelity", "in-ear", null),
                Rig("Innerfidelity", "earbud", null),
                Rig("Rtings", "in-ear", "HMS II.3"),
                Rig("Rtings", "earbud", "HMS II.3"),
            ),
            recommended = emptyList(),
        ),
        AutoEqTarget(
            label = "HMS II.3 Harman over-ear 2018",
            file = "HMS II.3 Harman over-ear 2018 without bass.csv",
            bassBoost = 6.0,
            compatible = listOf(
                Rig("Headphone.com Legacy", "over-ear", null),
                Rig("Innerfidelity", "over-ear", null),
                Rig("Rtings", "over-ear", "HMS II.3"),
            ),
            recommended = listOf(
                Rig("Headphone.com Legacy", "over-ear", null),
                Rig("Innerfidelity", "over-ear", null),
                Rig("Rtings", "over-ear", "HMS II.3"),
            ),
        ),
        AutoEqTarget(
            label = "JM-1 with Harman filters",
            file = "JM-1 with Harman treble filter.csv",
            bassBoost = 6.5,
            compatible = listOf(
                Rig("crinacle", "in-ear", "Bruel & Kjaer 4620"),
                Rig("HypetheSonics", "in-ear", "Bruel & Kjaer 5128"),
                Rig("Rtings", "in-ear", "Bruel & Kjaer 5128"),
            ),
            recommended = listOf(
                Rig("crinacle", "in-ear", "Bruel & Kjaer 4620"),
                Rig("HypetheSonics", "in-ear", "Bruel & Kjaer 5128"),
                Rig("Rtings", "in-ear", "Bruel & Kjaer 5128"),
            ),
        ),
        AutoEqTarget(
            label = "LMG 5128 0.6 without bass",
            file = "LMG 5128 0.6 without bass.csv",
            bassBoost = 6.0,
            compatible = listOf(
                Rig("HypetheSonics", "over-ear", null),
                Rig("HypetheSonics", "earbud", null),
                Rig("Rtings", "over-ear", "Bruel & Kjaer 5128"),
                Rig("Rtings", "earbud", "Bruel & Kjaer 5128"),
            ),
            recommended = listOf(
                Rig("HypetheSonics", "over-ear", null),
                Rig("HypetheSonics", "earbud", null),
                Rig("Rtings", "over-ear", "Bruel & Kjaer 5128"),
                Rig("Rtings", "earbud", "Bruel & Kjaer 5128"),
            ),
        ),
    )
}
