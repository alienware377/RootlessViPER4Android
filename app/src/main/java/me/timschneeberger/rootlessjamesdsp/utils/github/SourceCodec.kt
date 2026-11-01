package me.timschneeberger.rootlessjamesdsp.utils.github

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.io.StringWriter

/** Reads and writes the list of sources the user added. */
object SourceCodec {
    fun encode(sources: List<Source>): String {
        val sw = StringWriter()
        JsonWriter(sw).use { w ->
            w.beginArray()
            sources.filterNot { it.builtIn }.forEach {
                w.beginObject()
                w.name("repo").value(it.repo)
                w.name("branch").value(it.branch)
                w.name("label").value(it.label)
                if (it.folder.isNotEmpty()) w.name("folder").value(it.folder)
                w.endObject()
            }
            w.endArray()
        }
        return sw.toString()
    }

    /** Unreadable entries are skipped rather than losing the whole list. */
    fun decode(text: String?): List<Source> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Source>()
        runCatching {
            JsonReader(StringReader(text)).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    if (j.peek() != JsonToken.BEGIN_OBJECT) { j.skipValue(); continue }
                    val m = HashMap<String, String>()
                    j.beginObject()
                    while (j.hasNext()) {
                        val k = j.nextName()
                        if (j.peek() == JsonToken.STRING) m[k] = j.nextString() else j.skipValue()
                    }
                    j.endObject()
                    val repo = m["repo"] ?: continue
                    val branch = m["branch"]?.takeIf { it.isNotBlank() } ?: continue
                    out += Source(repo, branch, m["label"] ?: repo, m["folder"].orEmpty().trim('/'))
                }
                j.endArray()
            }
        }
        return out
    }

    /** [mine] first, then anything from [incoming] it does not already have. */
    fun merge(mine: List<Source>, incoming: List<Source>): List<Source> {
        val keys = mine.map { it.key }.toHashSet()
        return mine + incoming.filter { keys.add(it.key) }
    }
}
