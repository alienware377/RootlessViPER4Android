package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom sorting, hiding and grouping for a file library (convolver IRs,
 * DDC profiles), stored per preference key.
 *
 * The whole layout is one flat token list: plain tokens are file names,
 * tokens starting with [HEADER] are group headers. A file belongs to the
 * nearest header above it, which makes reordering, grouping and header
 * deletion all fall out of simple list moves.
 */
class FileLibraryLayout(context: Context, prefKey: String) {

    // Kept in a dsp_ namespace so preset backups carry library organisation.
    private val prefs =
        context.getSharedPreferences(Constants.PREF_FILELIBRARY, Context.MODE_MULTI_PROCESS)
    private val storeKey = "filelib_layout_$prefKey"

    init {
        // One-time migration from the old app-namespace location
        val legacy = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_MULTI_PROCESS)
        if (!prefs.contains(storeKey) && legacy.contains(storeKey)) {
            prefs.edit().putString(storeKey, legacy.getString(storeKey, null)).apply()
            legacy.edit().remove(storeKey).apply()
        }
    }

    var tokens: MutableList<String> = mutableListOf()
        private set
    var hidden: MutableSet<String> = mutableSetOf()
        private set

    init { load() }


    private fun load() {
        tokens = mutableListOf(); hidden = mutableSetOf()
        val raw = prefs.getString(storeKey, null) ?: return
        runCatching {
            val o = JSONObject(raw)
            val t = o.optJSONArray("tokens") ?: JSONArray()
            for (i in 0 until t.length()) tokens.add(t.getString(i))
            val h = o.optJSONArray("hidden") ?: JSONArray()
            for (i in 0 until h.length()) hidden.add(h.getString(i))
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("tokens", JSONArray(tokens))
        o.put("hidden", JSONArray(hidden.toList()))
        prefs.edit().putString(storeKey, o.toString()).apply()
    }

    /**
     * Merges the layout with what's actually on disk: files the layout hasn't
     * seen yet are appended to the very start (ungrouped, most visible), and
     * tokens whose files were deleted are dropped. Headers always survive.
     */
    fun sync(existing: List<String>) {
        val known = tokens.filter { isHeader(it) || existing.contains(it) }.toMutableList()
        val unseen = existing.filter { !tokens.contains(it) }
        tokens = (unseen + known).toMutableList()
        hidden.retainAll(existing.toSet())
        save()
    }

    fun isHeader(token: String) = token.startsWith(HEADER)
    fun headerName(token: String) = token.removePrefix(HEADER)
    fun headerToken(name: String) = HEADER + name

    fun addGroup(name: String) { tokens.add(headerToken(name)); save() }

    fun removeGroup(token: String) { tokens.remove(token); save() }

    fun renameGroup(token: String, name: String) {
        val i = tokens.indexOf(token)
        if (i >= 0) { tokens[i] = headerToken(name); save() }
    }

    fun groupNames(): List<String> = tokens.filter { isHeader(it) }.map { headerName(it) }

    fun move(token: String, up: Boolean) {
        val i = tokens.indexOf(token)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= tokens.size) return
        tokens[i] = tokens[j].also { tokens[j] = token }
        save()
    }

    /** Moves a file to the end of the given group's block (null = ungrouped top). */
    fun assignToGroup(file: String, groupToken: String?) {
        tokens.remove(file)
        if (groupToken == null) {
            tokens.add(0, file)
        } else {
            var i = tokens.indexOf(groupToken)
            if (i < 0) { tokens.add(file); save(); return }
            i++
            while (i < tokens.size && !isHeader(tokens[i])) i++
            tokens.add(i, file)
        }
        save()
    }

    fun setHidden(file: String, hide: Boolean) {
        if (hide) hidden.add(file) else hidden.remove(file)
        save()
    }

    fun setAllHidden(files: List<String>, hide: Boolean) {
        if (hide) hidden.addAll(files) else hidden.removeAll(files.toSet())
        save()
    }

    companion object { private const val HEADER = "\u0001G:" }
}
