package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine.PluginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Telling the root module's builds apart by the name in its descriptor. */
class EngineNameTest {

    @Test
    fun `a module from before the shaper and binaural upmix is outdated, not available`() {
        assertEquals(PluginState.Outdated, EngineName.state("RootlessViPER4Android engine v4"))
        assertEquals(PluginState.Outdated, EngineName.state("RootlessViPER4Android engine"))
    }

    @Test
    fun `the current module and later ones are available`() {
        assertEquals(PluginState.Available, EngineName.state("RootlessViPER4Android engine v5"))
        assertEquals(PluginState.Available, EngineName.state("RootlessViPER4Android engine v12"))
        assertEquals(PluginState.Available, EngineName.state("RootfulViPER4Android engine v5"))
    }

    @Test
    fun `a module that still calls itself Rootless is ours`() {
        assertEquals(PluginState.Outdated, EngineName.state("RootlessViPER4Android engine v4"))
        assertEquals(PluginState.Outdated, EngineName.state("RootfulViPER4Android engine v4"))
    }

    @Test
    fun `other engines behind the same uuid are still told apart`() {
        assertEquals(PluginState.Unsupported, EngineName.state("JamesDSP v3.06"))
        assertEquals(PluginState.Foreign, EngineName.state("JamesDSP v4.01"))
    }

    @Test
    fun `the module built from this tree is the version the app asks for`() {
        // Run from the module directory under Gradle, the project root in some IDEs.
        val src = listOf("src/main/cpp/hal/JamesDspHalEffect.cpp", "app/src/main/cpp/hal/JamesDspHalEffect.cpp")
            .map(::File).first { it.isFile }.readText()
        val name = Regex(""""(RootfulViPER4Android engine[^"]*)"""").find(src)?.groupValues?.get(1)
        assertNotNull(name)
        assertEquals(EngineName.REQUIRED_VERSION, EngineName.version(name!!))
        assertEquals(PluginState.Available, EngineName.state(name))
    }

    @Test
    fun `the AIDL plugin carries the legacy name, so the same version`() {
        // It runs the same dispatch as the legacy module, so it has to carry
        // the same number, or the app takes it for a module left over from an
        // older build and asks for a flash that cannot help. It reads the
        // legacy descriptor's name at run time and adds " AIDL" to it.
        val src = listOf("../magisk/aidl/src/Rv4aAidlEffect.cpp", "magisk/aidl/src/Rv4aAidlEffect.cpp")
            .map(::File).first { it.isFile }.readText()
        assertTrue(src.contains("""desc.common.name = std::string(rv4a_legacy_name()) + " AIDL";"""))
        assertFalse(Regex("""common\.name = """").containsMatchIn(src))
        assertEquals(PluginState.Available, EngineName.state("RootlessViPER4Android engine v5 AIDL"))
        assertEquals(PluginState.Outdated, EngineName.state("RootlessViPER4Android engine v4 AIDL"))
    }
}
