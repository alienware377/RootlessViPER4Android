package me.timschneeberger.rootlessjamesdsp.utils

import android.graphics.Color
import me.timschneeberger.rootlessjamesdsp.R

/**
 * Vivid per-effect icon colors for the ViPER4Android classic theme, replacing
 * the single-tint icons with the colorful column the classic app implied.
 * Hues are grouped by family: dynamics warm, spatial cool, tone-shapers green
 * and gold, scripting slate, so the column reads organised rather than random.
 */
object V4aIconColors {

    private val map = mapOf(
        // Dynamics - warm reds and oranges
        R.xml.dsp_output_control_preferences to "#FF5252",
        R.xml.dsp_compander_preferences to "#FF8A33",
        R.xml.dsp_fetcomp_preferences to "#FFB300",
        R.xml.dsp_agc_preferences to "#FF7043",
        R.xml.dsp_vdynbass_preferences to "#F4511E",
        // Bass and tone - greens and golds
        R.xml.dsp_bass_preferences to "#9CCC65",
        R.xml.dsp_bassex_preferences to "#7CB342",
        R.xml.dsp_viperbass_preferences to "#00C853",
        R.xml.dsp_clarity_preferences to "#FFD54F",
        R.xml.dsp_tube_preferences to "#D4A373",
        R.xml.dsp_cure_preferences to "#66BB6A",
        // Equalizers - teals and cyans
        R.xml.dsp_equalizer_preferences to "#26C6DA",
        R.xml.dsp_graphiceq_preferences to "#00ACC1",
        R.xml.dsp_parametriceq_preferences to "#4DD0E1",
        R.xml.dsp_ddc_preferences to "#00BFA5",
        R.xml.dsp_convolver_preferences to "#1DE9B6",
        // Spatial - blues and indigos
        R.xml.dsp_stereowide_preferences to "#42A5F5",
        R.xml.dsp_crossfeed_preferences to "#5C6BC0",
        R.xml.dsp_fieldsurround_preferences to "#29B6F6",
        R.xml.dsp_diffsurround_preferences to "#7986CB",
        R.xml.dsp_hpsurround_preferences to "#4FC3F7",
        R.xml.dsp_speakeropt_preferences to "#64B5F6",
        // Time and pitch - purples and pinks
        R.xml.dsp_reverb_preferences to "#AB47BC",
        R.xml.dsp_vreverb_preferences to "#D24BFF",
        R.xml.dsp_echo_preferences to "#EC407A",
        R.xml.dsp_pitchshift_preferences to "#F06292",
        R.xml.dsp_spectrumext_preferences to "#FF5CE1",
        // Scripting - slate blues
        R.xml.dsp_liveprog_preferences to "#90A4AE",
        R.xml.dsp_liveprog2_preferences to "#9FA8DA",
        R.xml.dsp_liveprog3_preferences to "#80CBC4",
        R.xml.dsp_liveprog4_preferences to "#BCAAA4",
    )

    /** Fallback cycle for any screen not mapped explicitly. */
    private val fallback = listOf(
        "#FF5252", "#FFB300", "#00C853", "#26C6DA", "#42A5F5", "#AB47BC", "#EC407A"
    )

    fun forXml(xmlRes: Int): Int {
        map[xmlRes]?.let { return Color.parseColor(it) }
        return Color.parseColor(fallback[Math.floorMod(xmlRes, fallback.size)])
    }
}
