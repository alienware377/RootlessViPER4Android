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
    const val STEREOWIDE = 19
    const val MAXIMIZER = 30
    const val TAPE = 36
    const val VINYL = 37
    const val BALANCE = 38
    const val SHAPER = 39
    const val BINAURAL = 40
    const val LUFS = 41

    /**
     * The engine's default chain (jdspDefaultChain in jdspController.c), as
     * effect ids. This is what runs when no order has been saved, so it is also
     * what the processing order screen shows then. ChainOrderTest reads the C
     * array and fails if the two drift apart.
     */
    val DEFAULT: List<Int> = listOf(
        0, 1, 2, 3,          // tube, compander, pitch shift, FET compressor
        33,                  // impact
        4, 5, 6, 7, 8,       // differential surround, bass boost, ViPER bass x3
        34, 35,              // low end, exciter
        9, 10, 31,           // equaliser, graphic EQ, dynamic EQ
        11, 12, SHAPER,      // convolver, DDC, spectral shaper
        13, 14, 15, 16,      // liveprog slots
        17, 18,              // crossfeed, cure
        32, STEREOWIDE, BINAURAL,
        20, 21, 22, 23, 24,  // field surround, headphone surround, spectrum ext, clarity, AGC
        25, 26, 27, 28, 29,  // speaker opt, reverb, ViPER reverb, echo, multiband distortion
        TAPE, VINYL, LUFS, BALANCE,
        MAXIMIZER,
    )

    /** Newer effect id to the id it follows in the default chain. */
    private val placeAfter = linkedMapOf(
        SHAPER to DDC,
        // After the stereo widening, so it spreads the image the user set up,
        // and before the surround effects and reverbs that then act on it.
        BINAURAL to STEREOWIDE,
        // Saved orders from older builds lack these two; in the default chain
        // they sit just ahead of the maximiser, after tape. Vinyl first, so
        // balance can follow it.
        VINYL to TAPE,
        BALANCE to VINYL,
        // Also after vinyl, so it lands between vinyl and balance: it sets its
        // gain for everything that shapes the sound except the listener's own
        // trim, and stays ahead of the maximiser.
        LUFS to VINYL,
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
