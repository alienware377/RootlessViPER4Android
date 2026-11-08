package me.timschneeberger.rootlessjamesdsp.interop

import java.io.File

/**
 * Which audio effect HAL the phone runs, read from its VINTF manifests.
 *
 * The normal root module installs a legacy effect library, which only the
 * older (HIDL) effect HAL loads. On a phone with the AIDL effect HAL the root
 * engine can only come from the separate AIDL module, so when the engine is
 * missing there, asking for the normal module would send people round in a
 * circle: they flash it, nothing changes, and nothing says why.
 *
 * Manifests are plain files every app may read (vendor_configs_file), so this
 * needs neither root nor a binder call.
 */
object EffectHal {
    enum class Kind { AIDL, HIDL, UNKNOWN }

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val HAL = Regex("""<hal\b([^>]*)>(.*?)</hal>""", RegexOption.DOT_MATCHES_ALL)
    private val EFFECT = Regex("""<name>\s*android\.hardware\.audio\.effect\s*</name>""")
    private val CORE = Regex("""<name>\s*android\.hardware\.audio\.core\s*</name>""")
    private val AIDL = Regex("""format\s*=\s*"aidl"""")

    /**
     * The kind these manifest texts declare. The framework only takes the AIDL
     * path when both the effect factory and the core HAL are AIDL; an HAL entry
     * without a format attribute is HIDL.
     */
    fun fromManifests(manifests: List<String>): Kind {
        var aidlEffect = false
        var aidlCore = false
        var hidlEffect = false
        for (text in manifests) {
            for (hal in HAL.findAll(COMMENT.replace(text, ""))) {
                val aidl = AIDL.containsMatchIn(hal.groupValues[1])
                val body = hal.groupValues[2]
                when {
                    EFFECT.containsMatchIn(body) -> if (aidl) aidlEffect = true else hidlEffect = true
                    CORE.containsMatchIn(body) -> if (aidl) aidlCore = true
                }
            }
        }
        return when {
            aidlEffect && aidlCore -> Kind.AIDL
            hidlEffect -> Kind.HIDL
            aidlEffect -> Kind.AIDL
            else -> Kind.UNKNOWN
        }
    }

    /** Where devices keep VINTF manifests, under [root]. Missing ones are skipped. */
    fun manifestFiles(root: File = File("/")): List<File> {
        val files = mutableListOf<File>()
        for (dir in listOf("vendor/etc/vintf", "odm/etc/vintf")) {
            val d = File(root, dir)
            d.listFiles()?.filterTo(files) { it.isFile && it.name.endsWith(".xml") }
            File(d, "manifest").listFiles()?.filterTo(files) { it.isFile && it.name.endsWith(".xml") }
        }
        listOf("vendor/manifest.xml", "odm/manifest.xml").map { File(root, it) }.filterTo(files) { it.isFile }
        File(root, "apex").listFiles()?.forEach { apex ->
            File(apex, "etc/vintf").listFiles()?.filterTo(files) { it.isFile && it.name.endsWith(".xml") }
        }
        return files
    }

    fun detect(root: File = File("/")): Kind = try {
        fromManifests(manifestFiles(root).mapNotNull { runCatching { it.readText() }.getOrNull() })
    } catch (e: Exception) {
        Kind.UNKNOWN
    }

    /** The running phone's kind, read once. */
    val current: Kind by lazy { detect() }

    /** Whether a missing root engine needs the AIDL module rather than the normal one. */
    fun needsAidlModule(state: JamesDspRemoteEngine.PluginState, kind: Kind): Boolean =
        state == JamesDspRemoteEngine.PluginState.Unavailable && kind == Kind.AIDL

    const val AIDL_ISSUE_URL =
        "https://github.com/alienware377/RootlessJamesDSP-ViPER4Android-Edition/issues/12"
}
