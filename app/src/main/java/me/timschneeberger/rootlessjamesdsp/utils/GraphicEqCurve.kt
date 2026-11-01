package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.ln

/**
 * Reading a graphic EQ string and looking up its gain at any frequency.
 *
 * Lifted out of JamesDspBaseEngine unchanged, so the engine's merge of the
 * graphic and parametric EQs and global mode's curve builder share one
 * reading of the same text. Two copies would drift, and the difference would
 * only show up as global mode sounding slightly unlike capture mode.
 */
object GraphicEqCurve {

    /** "GraphicEQ: f1 g1; f2 g2; ..." to (frequency, gain dB) nodes, sorted. */
    fun parse(geqBands: String): List<Pair<Double, Double>> {
        val geqNodes = mutableListOf<Pair<Double, Double>>()
        val content = geqBands.replace("GraphicEQ:", "").trim()
        content.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { s ->
            val parts = s.split(" ").filter { it.isNotBlank() }
            val freq = parts.getOrNull(0)?.toDoubleOrNull()
            val gain = parts.getOrNull(1)?.toDoubleOrNull()
            if (freq != null && gain != null) {
                geqNodes.add(Pair(freq, gain))
            }
        }
        geqNodes.sortBy { it.first }
        return geqNodes
    }

    /**
     * Gain at [freq]: straight lines between nodes on a log frequency axis,
     * holding the end values outside them. Zero with no nodes at all.
     */
    fun interpolate(nodes: List<Pair<Double, Double>>, freq: Double): Double {
        if (nodes.isEmpty()) return 0.0
        if (freq <= nodes.first().first) return nodes.first().second
        if (freq >= nodes.last().first) return nodes.last().second

        // Find surrounding nodes and do log-linear interpolation
        for (i in 0 until nodes.size - 1) {
            val (f0, g0) = nodes[i]
            val (f1, g1) = nodes[i + 1]
            if (freq in f0..f1) {
                if (f1 <= f0) return g0
                val logF = ln(freq)
                val logF0 = ln(f0)
                val logF1 = ln(f1)
                val t = (logF - logF0) / (logF1 - logF0)
                return g0 + t * (g1 - g0)
            }
        }
        return 0.0
    }
}
