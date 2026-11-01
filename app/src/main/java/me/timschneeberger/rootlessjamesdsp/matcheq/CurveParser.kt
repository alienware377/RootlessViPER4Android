package me.timschneeberger.rootlessjamesdsp.matcheq

import kotlin.math.log2

/**
 * Reads a frequency response from the text formats people actually have:
 *
 *  - AutoEq CSV with a header ("frequency,raw,..."): the named column is used,
 *  - plain two-column exports, separated by commas, semicolons, tabs or spaces
 *    (graph tools and squig.link pages export these),
 *  - REW text exports, whose header lines start with "*",
 *  - EqualizerAPO / AutoEq GraphicEQ text ("GraphicEQ: 20 -1.2; 21 -1.1; ...").
 *    That is a correction rather than a measurement, so it is reported as such
 *    and only used to estimate a measurement.
 *
 * Any line whose first value is not a number is skipped, which covers headers
 * and comments. A decimal comma is accepted where it cannot be confused with
 * the separator (with semicolons or tabs).
 */
object CurveParser {

    enum class Problem { EMPTY, TOO_FEW_POINTS, TOO_NARROW, OUT_OF_RANGE }

    sealed class Result {
        data class Ok(val curve: FrequencyCurve, val isGraphicEq: Boolean) : Result()
        data class Failed(val problem: Problem) : Result()
    }

    const val MIN_POINTS = 10
    const val MAX_ABS_DB = 200.0

    /**
     * @param preferredColumns header names to look for, first match wins. When the
     *   file has a header but none of these, the first numeric column after the
     *   frequency is used.
     */
    fun parse(text: String, preferredColumns: List<String> = listOf("raw")): Result {
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmed.isEmpty()) return Result.Failed(Problem.EMPTY)
        if (trimmed.startsWith("GraphicEQ:", ignoreCase = true)) return parseGraphicEq(trimmed)

        val f = ArrayList<Double>()
        val g = ArrayList<Double>()
        var column = 1
        var headerSeen = false

        for (raw in trimmed.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("*") || line.startsWith("//"))
                continue
            val sep = separatorOf(line)
            val tokens = split(line, sep)
            if (tokens.isEmpty()) continue
            val first = number(tokens[0], sep)
            if (first == null) {
                // A header. Only the first one counts, and only if it names columns.
                if (!headerSeen) {
                    headerSeen = true
                    val names = tokens.map { it.trim().trim('"').lowercase() }
                    val idx = preferredColumns.asSequence()
                        .map { p -> names.indexOf(p.lowercase()) }
                        .firstOrNull { it > 0 }
                    if (idx != null) column = idx
                }
                continue
            }
            if (tokens.size <= column) continue
            val value = number(tokens[column], sep) ?: continue
            f.add(first)
            g.add(value)
        }
        return clean(f, g, false)
    }

    private fun parseGraphicEq(text: String): Result {
        val f = ArrayList<Double>()
        val g = ArrayList<Double>()
        text.substringAfter(':').replace('\n', ' ').split(';').forEach { pair ->
            val parts = pair.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val a = parts[0].toDoubleOrNull()
                val b = parts[1].toDoubleOrNull()
                if (a != null && b != null) { f.add(a); g.add(b) }
            }
        }
        return clean(f, g, true)
    }

    /** Sorts, drops unusable points and averages repeated frequencies. */
    private fun clean(f: List<Double>, g: List<Double>, isGraphicEq: Boolean): Result {
        val pairs = f.indices
            .filter { f[it] > 0 && f[it].isFinite() && g[it].isFinite() }
            .map { f[it] to g[it] }
            .sortedBy { it.first }
        if (pairs.isEmpty()) return Result.Failed(Problem.EMPTY)
        if (pairs.any { kotlin.math.abs(it.second) > MAX_ABS_DB }) return Result.Failed(Problem.OUT_OF_RANGE)

        val outF = ArrayList<Double>()
        val outG = ArrayList<Double>()
        var i = 0
        while (i < pairs.size) {
            var j = i
            var sum = 0.0
            while (j < pairs.size && pairs[j].first == pairs[i].first) { sum += pairs[j].second; j++ }
            outF.add(pairs[i].first)
            outG.add(sum / (j - i))
            i = j
        }
        if (outF.size < MIN_POINTS) return Result.Failed(Problem.TOO_FEW_POINTS)
        if (log2(outF.last() / outF.first()) < 1.0) return Result.Failed(Problem.TOO_NARROW)
        return Result.Ok(FrequencyCurve(outF.toDoubleArray(), outG.toDoubleArray()), isGraphicEq)
    }

    private fun separatorOf(line: String): Char = when {
        line.contains('\t') -> '\t'
        line.contains(';') -> ';'
        line.contains(',') -> ','
        else -> ' '
    }

    private fun split(line: String, sep: Char): List<String> =
        if (sep == ' ') line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        else line.split(sep).map { it.trim() }

    private fun number(token: String, sep: Char): Double? {
        val t = token.trim().trim('"')
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it }
        // Decimal comma, only where comma is not the separator.
        if (sep != ',' && t.count { it == ',' } == 1 && !t.contains('.'))
            return t.replace(',', '.').toDoubleOrNull()
        return null
    }
}
