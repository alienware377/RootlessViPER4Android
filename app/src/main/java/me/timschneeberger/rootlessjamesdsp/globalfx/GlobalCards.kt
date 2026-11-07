package me.timschneeberger.rootlessjamesdsp.globalfx

import me.timschneeberger.rootlessjamesdsp.utils.Constants

/**
 * Which cards still work in global mode, and which of their rows do not.
 *
 * Global mode cannot run this app's engine at all; it can only set Android's
 * own DynamicsProcessing effect, which has an equaliser, a compressor and a
 * limiter. So a card either maps onto those (FULL), maps with some rows left
 * over (PARTIAL), or has nothing to map onto (NONE) and is greyed out with its
 * settings kept for when capture mode comes back.
 *
 * Every namespace in EffectCards must appear here. GlobalCardsTest enforces
 * that, so a card added later forces someone to decide, instead of sitting
 * enabled on screen while doing nothing.
 */
object GlobalCards {
    enum class Support { FULL, PARTIAL, NONE }

    val support: Map<String, Support> = mapOf(
        Constants.PREF_EQ to Support.FULL,
        Constants.PREF_GEQ to Support.FULL,
        Constants.PREF_PEQ to Support.FULL,
        Constants.PREF_FETCOMP to Support.FULL,
        Constants.PREF_BASS to Support.PARTIAL,
        Constants.PREF_OUTPUT to Support.PARTIAL,
        Constants.PREF_MAXIMIZER to Support.PARTIAL,
        Constants.PREF_BALANCE to Support.PARTIAL,

        Constants.PREF_COMPANDER to Support.NONE,
        Constants.PREF_BASSEX to Support.NONE,
        Constants.PREF_VDYNBASS to Support.NONE,
        Constants.PREF_DIFFSURROUND to Support.NONE,
        Constants.PREF_CLARITY to Support.NONE,
        Constants.PREF_FIELDSURROUND to Support.NONE,
        Constants.PREF_HPSURROUND to Support.NONE,
        Constants.PREF_CURE to Support.NONE,
        Constants.PREF_VIPERBASS to Support.NONE,
        Constants.PREF_VREVERB to Support.NONE,
        Constants.PREF_SPEAKEROPT to Support.NONE,
        Constants.PREF_PITCHSHIFT to Support.NONE,
        Constants.PREF_ECHODELAY to Support.NONE,
        Constants.PREF_MULTIBANDDIST to Support.NONE,
        Constants.PREF_DYNAMICEQ to Support.NONE,
        Constants.PREF_SHAPER to Support.NONE,
        Constants.PREF_BINAURAL to Support.NONE,
        Constants.PREF_IMAGING to Support.NONE,
        Constants.PREF_TRANSIENT to Support.NONE,
        Constants.PREF_LOWEND to Support.NONE,
        Constants.PREF_EXCITER to Support.NONE,
        Constants.PREF_TAPE to Support.NONE,
        Constants.PREF_VINYL to Support.NONE,
        Constants.PREF_AGC to Support.NONE,
        Constants.PREF_DDC to Support.NONE,
        Constants.PREF_CONVOLVER to Support.NONE,
        Constants.PREF_LIVEPROG to Support.NONE,
        Constants.PREF_LIVEPROG2 to Support.NONE,
        Constants.PREF_LIVEPROG3 to Support.NONE,
        Constants.PREF_LIVEPROG4 to Support.NONE,
        Constants.PREF_TUBE to Support.NONE,
        Constants.PREF_SPECTRUMEXT to Support.NONE,
        Constants.PREF_STEREOWIDE to Support.NONE,
        Constants.PREF_CROSSFEED to Support.NONE,
        Constants.PREF_REVERB to Support.NONE,
    )

    /**
     * Rows that do nothing in global mode, by string resource NAME (not value),
     * so they can be checked against the card XML without Android.
     */
    val ignoredRows: Map<String, List<String>> = mapOf(
        // Android's equaliser is its own filter, so the engine's filter choice has no say.
        Constants.PREF_EQ to listOf("key_eq_filter_type"),
        // An FFT gain table has no phase to choose.
        Constants.PREF_GEQ to listOf("key_geq_linear_phase"),
        Constants.PREF_PEQ to listOf("key_geq_linear_phase"),
        Constants.PREF_MAXIMIZER to listOf(
            "key_maxr_mode", "key_maxr_clip_shape", "key_maxr_character", "key_maxr_transient",
            "key_maxr_stereo_link", "key_maxr_true_peak", "key_maxr_oversample"),
        Constants.PREF_BALANCE to listOf("key_balance_mono", "key_balance_swap"),
    )

    /** The card XML each namespace with ignored rows lives in, for the test. */
    val cardXml: Map<String, String> = mapOf(
        Constants.PREF_EQ to "dsp_equalizer_preferences",
        Constants.PREF_GEQ to "dsp_graphiceq_preferences",
        Constants.PREF_PEQ to "dsp_parametriceq_preferences",
        Constants.PREF_MAXIMIZER to "dsp_maximizer_preferences",
        Constants.PREF_BALANCE to "dsp_balance_preferences",
    )

    fun supportOf(namespace: String?): Support = support[namespace] ?: Support.NONE
}
