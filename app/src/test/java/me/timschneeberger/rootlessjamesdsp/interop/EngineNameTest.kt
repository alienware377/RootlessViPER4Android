package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine.PluginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** Telling the root module's builds apart by the name in its descriptor. */
class EngineNameTest {

    @Test
    fun `a module from before the root bass exciter, dynamic system and surround is outdated, not available`() {
        assertEquals(PluginState.Outdated, EngineName.state("RootfulViPER4Android engine v5"))
        assertEquals(PluginState.Outdated, EngineName.state("RootlessViPER4Android engine v4"))
        assertEquals(PluginState.Outdated, EngineName.state("RootlessViPER4Android engine"))
    }

    @Test
    fun `the current module and later ones are available`() {
        assertEquals(PluginState.Available, EngineName.state("RootfulViPER4Android engine v6"))
        assertEquals(PluginState.Available, EngineName.state("RootlessViPER4Android engine v12"))
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
    fun `the AIDL service built from this tree is the version the app asks for`() {
        // It runs the same dispatch as the legacy module, so it has to carry
        // the same number, or the app takes it for a module left over from an
        // older build and asks for a flash that cannot help.
        val src = listOf("../magisk/aidl/src/EffectImpl.h", "magisk/aidl/src/EffectImpl.h")
            .map(::File).first { it.isFile }.readText()
        val name = Regex("""kEngineName = "([^"]*)"""").find(src)?.groupValues?.get(1)
        assertNotNull(name)
        assertEquals(EngineName.REQUIRED_VERSION, EngineName.version(name!!))
        assertEquals(PluginState.Available, EngineName.state(name))
        // Both places that describe the effect use that one name.
        val factory = listOf("../magisk/aidl/src/FactoryImpl.cpp", "magisk/aidl/src/FactoryImpl.cpp")
            .map(::File).first { it.isFile }.readText()
        assertFalse(Regex("""common\.name = """").containsMatchIn(src + factory))
    }
}
