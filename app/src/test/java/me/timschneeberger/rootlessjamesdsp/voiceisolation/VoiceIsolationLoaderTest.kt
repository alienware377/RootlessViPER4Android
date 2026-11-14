package me.timschneeberger.rootlessjamesdsp.voiceisolation

import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationStatus.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The order the loader takes the add-on apart in. Anything the card promises to retry when the
 * add-on changes has to be found before the add-on's class loader is made, because from then on
 * a new add-on ends the app's process instead.
 */
class VoiceIsolationLoaderTest {
    private val shippedModel: ByteArray by lazy {
        listOf("../voiceaddon/src/main/assets/${GtcrnModel.ASSET}", "voiceaddon/src/main/assets/${GtcrnModel.ASSET}")
            .map(::File).first { it.isFile }.readBytes()
    }

    private open inner class FakeSource(
        private val checks: List<VoiceAddon.Check> = listOf(VoiceAddon.Check.Ready("4.6.0")),
        private val model: () -> ByteArray = { shippedModel },
    ) : VoiceIsolationLoader.Source {
        val calls = ArrayList<String>()
        private var checked = 0

        override fun check(): VoiceAddon.Check {
            calls += "check"
            return checks[minOf(checked++, checks.size - 1)]
        }
        override fun readModel(): ByteArray {
            calls += "model"
            return model()
        }
        override fun classLoader(): ClassLoader {
            calls += "classLoader"
            return VoiceIsolationLoaderTest::class.java.classLoader!!
        }
    }

    private fun failure(source: VoiceIsolationLoader.Source, entry: String = VoiceAddon.ENTRY_CLASS): Reason {
        try {
            VoiceIsolationLoader.open(source, 48_000, entry)
        } catch (e: VoiceIsolationLoader.Failure) {
            return e.reason
        }
        fail("the stage opened")
        throw AssertionError()
    }

    @Test
    fun aMissingModelIsFoundWithoutLoadingAnyCode() {
        val source = FakeSource(model = { throw IOException("no such asset") })
        assertEquals(Reason.ModelMissing, failure(source))
        assertFalse("made the class loader", "classLoader" in source.calls)
        assertTrue(Reason.ModelMissing in VoiceIsolationStatus.RETRIED_WHEN_ADDON_CHANGES)
    }

    @Test
    fun aDamagedModelIsFoundWithoutLoadingAnyCode() {
        val source = FakeSource(model = { shippedModel.copyOf().also { it[100] = (it[100] + 1).toByte() } })
        assertEquals(Reason.ModelDamaged, failure(source))
        assertFalse("made the class loader", "classLoader" in source.calls)
        assertTrue(Reason.ModelDamaged in VoiceIsolationStatus.RETRIED_WHEN_ADDON_CHANGES)
    }

    @Test
    fun anAddonThatFailsTheCheckIsNeitherReadNorLoaded() {
        val source = FakeSource(checks = listOf(VoiceAddon.Check.WrongSignature))
        assertEquals(Reason.AddonSignature, failure(source))
        assertEquals(listOf("check"), source.calls)
    }

    @Test
    fun theCheckRunsAgainOnceTheClassLoaderExists() {
        // Replaced by another package between the first check and the class loader.
        val source = FakeSource(checks = listOf(VoiceAddon.Check.Ready("4.6.0"), VoiceAddon.Check.WrongSignature))
        assertEquals(Reason.AddonSignature, failure(source, VoiceAddonTest.MatchingEntry::class.java.name))
        assertEquals(listOf("check", "model", "classLoader", "check"), source.calls)
    }

    @Test
    fun codeThatDisagreesWithItsManifestDoesNotPromiseARetry() {
        // Found after the class loader, where a new add-on would end the app's process.
        val source = FakeSource()
        val reason = failure(source, VoiceAddonTest.NewerEntry::class.java.name)
        assertEquals(Reason.Runtime, reason)
        assertTrue("classLoader" in source.calls)
        assertFalse(reason in VoiceIsolationStatus.RETRIED_WHEN_ADDON_CHANGES)
    }

    @Test
    fun anUnsupportedRateIsRefusedBeforeTheAddonIsLookedAt() {
        val source = FakeSource()
        try {
            VoiceIsolationLoader.open(source, 7_000)
            fail("opened at 7 kHz")
        } catch (e: VoiceIsolationLoader.Failure) {
            assertEquals(Reason.Rate, e.reason)
        }
        assertTrue(source.calls.isEmpty())
    }
}
