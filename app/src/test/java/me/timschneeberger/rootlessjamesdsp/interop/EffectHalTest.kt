package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.interop.EffectHal.Kind
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine.PluginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Telling an AIDL effect HAL phone from an older one, for the missing engine screen. */
class EffectHalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun hal(format: String?, name: String, version: String? = null) =
        """<hal${format?.let { " format=\"$it\"" } ?: ""}>
            <name>$name</name>
            ${version?.let { "<version>$it</version>" } ?: ""}
            <fqname>IFactory/default</fqname>
        </hal>"""

    private fun manifest(vararg hals: String) =
        """<manifest version="8.0" type="device">${hals.joinToString("\n")}</manifest>"""

    @Test
    fun `AIDL effect factory and AIDL core HAL is the AIDL path`() {
        val m = manifest(
            hal("aidl", "android.hardware.audio.effect", "2"),
            hal("aidl", "android.hardware.audio.core", "2"),
        )
        assertEquals(Kind.AIDL, EffectHal.fromManifests(listOf(m)))
    }

    @Test
    fun `the two declarations may sit in different fragments`() {
        val effect = manifest(hal("aidl", "android.hardware.audio.effect", "3"))
        val core = manifest(hal("aidl", "android.hardware.audio.core", "3"))
        assertEquals(Kind.AIDL, EffectHal.fromManifests(listOf(effect, core)))
    }

    @Test
    fun `an HIDL effect HAL is the legacy path, with or without a format attribute`() {
        assertEquals(Kind.HIDL, EffectHal.fromManifests(listOf(manifest(hal("hidl", "android.hardware.audio.effect", "7.0")))))
        assertEquals(Kind.HIDL, EffectHal.fromManifests(listOf(manifest(hal(null, "android.hardware.audio.effect", "6.0")))))
    }

    @Test
    fun `an AIDL factory next to an HIDL core HAL is not the AIDL path`() {
        val m = manifest(
            hal("aidl", "android.hardware.audio.effect", "2"),
            hal("hidl", "android.hardware.audio.effect", "7.0"),
            hal("hidl", "android.hardware.audio", "7.0"),
        )
        assertEquals(Kind.HIDL, EffectHal.fromManifests(listOf(m)))
    }

    @Test
    fun `a commented out declaration does not count`() {
        val m = manifest(
            "<!-- " + hal("aidl", "android.hardware.audio.effect", "2") + " -->",
            hal("hidl", "android.hardware.audio.effect", "7.0"),
        )
        assertEquals(Kind.HIDL, EffectHal.fromManifests(listOf(m)))
    }

    @Test
    fun `nothing about audio effects is unknown`() {
        assertEquals(Kind.UNKNOWN, EffectHal.fromManifests(listOf(manifest(hal("aidl", "android.hardware.light")))))
        assertEquals(Kind.UNKNOWN, EffectHal.fromManifests(emptyList()))
    }

    @Test
    fun `manifests are found in the places devices keep them`() {
        val root = tmp.root
        fun put(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        put("vendor/etc/vintf/manifest.xml", manifest(hal("aidl", "android.hardware.light")))
        put("vendor/etc/vintf/manifest/audio.xml", manifest(hal("aidl", "android.hardware.audio.core", "2")))
        put("apex/com.vendor.audio/etc/vintf/effect.xml", manifest(hal("aidl", "android.hardware.audio.effect", "2")))
        put("odm/etc/vintf/manifest.txt", "not a manifest")
        assertEquals(3, EffectHal.manifestFiles(root).size)
        assertEquals(Kind.AIDL, EffectHal.detect(root))
        assertEquals(Kind.UNKNOWN, EffectHal.detect(File(root, "missing")))
    }

    @Test
    fun `only a missing engine on an AIDL phone asks for the AIDL module`() {
        assertTrue(EffectHal.needsAidlModule(PluginState.Unavailable, Kind.AIDL))
        assertFalse(EffectHal.needsAidlModule(PluginState.Unavailable, Kind.HIDL))
        assertFalse(EffectHal.needsAidlModule(PluginState.Unavailable, Kind.UNKNOWN))
        assertFalse(EffectHal.needsAidlModule(PluginState.Available, Kind.AIDL))
        assertFalse(EffectHal.needsAidlModule(PluginState.Outdated, Kind.AIDL))
    }
}
