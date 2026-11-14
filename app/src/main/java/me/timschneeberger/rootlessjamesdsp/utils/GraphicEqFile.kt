package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNode
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Graphic EQ text files: the single "GraphicEQ: f g; f g; ..." line that
 * EqualizerAPO reads and AutoEq writes.
 *
 * The node list's own deserialize is deliberately forgiving, because it reads
 * what the app itself stored. A file picked from storage is another matter: a
 * wrong file read that way comes back as an empty curve and wipes the user's
 * nodes without a word. So a file is checked entry by entry first and refused
 * as a whole if anything in it is not a node, and only then handed to
 * deserialize, which keeps one reading of the format for the whole app.
 */
object GraphicEqFile {

    /** Far above any real curve (AutoEq's are about 3 KB); stops a wrong pick of a huge file. */
    const val MAX_BYTES = 1 shl 20

    /** The node editor's ranges, so everything imported can also be edited. */
    const val MIN_FREQ = 1.0
    const val MAX_FREQ = 24000.0
    const val MAX_GAIN = 32.0

    sealed class Parsed {
        class Ok(val nodes: List<GraphicEqNode>) : Parsed()
        class Error(val reason: Reason, val entry: String? = null) : Parsed()
    }

    enum class Reason {
        TOO_LARGE,
        NO_GRAPHIC_EQ_LINE,
        NO_NODES,
        BAD_ENTRY,
        OUT_OF_RANGE,
    }

    /**
     * Reads at most [MAX_BYTES] as UTF-8, or null when the stream holds more.
     */
    fun read(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun parse(text: String): Parsed {
        if (text.length > MAX_BYTES) return Parsed.Error(Reason.TOO_LARGE)

        // An EqualizerAPO config can hold other lines too (Preamp, Device,
        // comments). Only the curve means anything here, and a graphic EQ has
        // no preamp of its own to put the rest in.
        val line = text.removePrefix("﻿").lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(PREFIX, ignoreCase = true) }
            ?: return Parsed.Error(Reason.NO_GRAPHIC_EQ_LINE)

        val body = line.substring(PREFIX.length).replace('\t', ' ')
        val entries = body.split(";").map { it.trim() }.filter(String::isNotBlank)
        if (entries.isEmpty()) return Parsed.Error(Reason.NO_NODES)

        for (entry in entries) {
            val parts = entry.split(" ").filter(String::isNotBlank)
            val freq = parts.getOrNull(0)?.toDoubleOrNull()
            val gain = parts.getOrNull(1)?.toDoubleOrNull()
            if (parts.size != 2 || freq == null || gain == null || !freq.isFinite() || !gain.isFinite())
                return Parsed.Error(Reason.BAD_ENTRY, entry)
            if (freq < MIN_FREQ || freq > MAX_FREQ || gain < -MAX_GAIN || gain > MAX_GAIN)
                return Parsed.Error(Reason.OUT_OF_RANGE, entry)
        }

        val nodes = GraphicEqNodeList()
        nodes.deserialize(PREFIX + " " + body)
        // Every entry passed above, so a shortfall here means the two readings
        // disagree. Better refused than imported with nodes missing.
        if (nodes.size != entries.size) return Parsed.Error(Reason.BAD_ENTRY)
        return Parsed.Ok(nodes.sortedBy { it.freq })
    }

    /**
     * The nodes as a file: the stored form without its trailing separator,
     * which is exactly the line AutoEq writes.
     */
    fun format(nodes: GraphicEqNodeList): String =
        nodes.serialize().trimEnd().removeSuffix(";") + "\n"

    private const val PREFIX = "GraphicEQ:"
}
