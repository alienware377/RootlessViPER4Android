package me.timschneeberger.rootlessjamesdsp.utils.github

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Just enough of a Context for code that keeps its state in shared
 * preferences. Each preferences file is a real file under
 * dataDir/shared_prefs and is read again on every getSharedPreferences, so
 * deleting the file loses what was in it, as it does on a device.
 */
class FakePrefsContext(private val dataDir: File) : ContextWrapper(null) {
    private val prefsDir = File(dataDir, "shared_prefs").apply { mkdirs() }

    override fun getApplicationInfo(): ApplicationInfo =
        ApplicationInfo().also { it.dataDir = dataDir.absolutePath }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        FilePrefs(File(prefsDir, "$name.xml"))
}

private class FilePrefs(private val file: File) : SharedPreferences {
    @Suppress("UNCHECKED_CAST")
    private fun read(): HashMap<String, Any?> =
        if (!file.isFile) HashMap()
        else ObjectInputStream(file.inputStream()).use { it.readObject() as HashMap<String, Any?> }

    private fun write(map: HashMap<String, Any?>) =
        ObjectOutputStream(file.outputStream()).use { it.writeObject(map) }

    override fun getAll(): MutableMap<String, *> = read()
    override fun getString(key: String, defValue: String?) = read()[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        (read()[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = read()[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = read()[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = read()[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = read()[key] as Boolean? ?: defValue
    override fun contains(key: String) = read().containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { puts[key] = values?.let { HashSet(it) } }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            val map = if (clear) HashMap() else read()
            removes.forEach { map.remove(it) }
            map.putAll(puts)
            write(map)
            return true
        }
        override fun apply() { commit() }
    }
}
