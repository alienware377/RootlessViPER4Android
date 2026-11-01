package me.timschneeberger.rootlessjamesdsp.matcheq

/**
 * Finds a headphone's folder in the AutoEq repository from the name and source
 * the AutoEQ search returns.
 *
 * The repository's results/INDEX.md has one line per result:
 *
 *     - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
 *
 * The text after "by" is exactly the source the search service reports, so the
 * pair is enough to find the folder without any other service. The path is
 * percent-encoded and has no spaces, which is what makes the line safe to split
 * even when the name itself contains " by ", brackets or parentheses.
 */
object AutoEqIndex {

    data class Entry(val name: String, val path: String, val source: String) {
        val rig: Rig? get() = Rig.fromResultPath(path)

        /** results/<path>/<name>.csv, percent-encoded for a URL. */
        fun csvPath(): String = "results/" + encodePath(path) + "/" + encodeSegment("$name.csv")
    }

    private val LINE = Regex("""^- \[(.*)]\(\./(\S+)\) by (.+)$""")

    fun parseLine(line: String): Entry? {
        val m = LINE.matchEntire(line.trimEnd('\r', '\n')) ?: return null
        val path = percentDecode(m.groupValues[2]) ?: return null
        return Entry(m.groupValues[1], path, m.groupValues[3].trim())
    }

    fun parse(text: String): List<Entry> = text.lineSequence().mapNotNull { parseLine(it) }.toList()

    fun find(entries: List<Entry>, name: String, source: String?): Entry? =
        entries.firstOrNull { it.name == name && (source == null || it.source == source) }
            ?: entries.firstOrNull { it.name.equals(name, ignoreCase = true) && (source == null || it.source.equals(source, ignoreCase = true)) }

    /**
     * Percent-decoding only. Not URLDecoder: that also turns "+" into a space,
     * and "+" is a real character in some folder names ("EARS + 711").
     */
    fun percentDecode(s: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(v)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeSegment(it) }

    /** Encodes everything except the unreserved characters of RFC 3986. */
    fun encodeSegment(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~')
                sb.append(ch)
            else
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }
}
