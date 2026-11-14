package me.timschneeberger.rootlessjamesdsp.voiceisolation

import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationStatus.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.function.BiConsumer

/**
 * Whether the voice isolation add-on may be loaded, decided from what the package manager says,
 * and how the app talks to the add-on's entry point once it is.
 */
class VoiceAddonTest {
    private val ours = setOf("aa11")

    private class FakePackages(
        private val own: Set<String>,
        private val installed: VoiceAddon.Installed?,
    ) : VoiceAddon.Packages {
        var addonAsked = 0
        override fun ownSigners() = own
        override fun addon(): VoiceAddon.Installed? {
            addonAsked++
            return installed
        }
    }

    private fun addon(signers: Set<String> = ours, api: Int = VoiceAddon.API_VERSION, version: String = "4.6.0") =
        VoiceAddon.Installed(version, api, signers)

    private fun check(packages: VoiceAddon.Packages, abi: String? = "arm64-v8a") = VoiceAddon.check(packages, abi)

    @Test
    fun notInstalled() {
        assertSame(VoiceAddon.Check.NotInstalled, check(FakePackages(ours, null)))
    }

    @Test
    fun signedWithAnotherKeyIsRefused() {
        assertSame(VoiceAddon.Check.WrongSignature, check(FakePackages(ours, addon(signers = setOf("bb22")))))
        // A second signer the app does not have, or one missing, is not the same certificate either.
        assertSame(VoiceAddon.Check.WrongSignature, check(FakePackages(ours, addon(signers = ours + "bb22"))))
        assertSame(VoiceAddon.Check.WrongSignature, check(FakePackages(ours, addon(signers = emptySet()))))
    }

    @Test
    fun anAppWhoseOwnSignersAreUnknownLoadsNothing() {
        assertSame(VoiceAddon.Check.WrongSignature, check(FakePackages(emptySet(), addon(signers = emptySet()))))
    }

    @Test
    fun theSignatureIsCheckedBeforeTheVersion() {
        // A package that is not ours says nothing we can trust, its interface number included.
        assertSame(VoiceAddon.Check.WrongSignature,
            check(FakePackages(ours, addon(signers = setOf("bb22"), api = VoiceAddon.API_VERSION + 1))))
    }

    @Test
    fun anOlderAddonAsksForTheAddonToBeUpdated() {
        assertEquals(VoiceAddon.Check.AddonTooOld("4.5.9"),
            check(FakePackages(ours, addon(api = VoiceAddon.API_VERSION - 1, version = "4.5.9"))))
        // An add-on with no interface number at all is older than any the app knows.
        assertEquals(VoiceAddon.Check.AddonTooOld("4.6.0"), check(FakePackages(ours, addon(api = 0))))
    }

    @Test
    fun aNewerAddonAsksForTheAppToBeUpdated() {
        assertEquals(VoiceAddon.Check.AppTooOld("5.0.0"),
            check(FakePackages(ours, addon(api = VoiceAddon.API_VERSION + 1, version = "5.0.0"))))
    }

    @Test
    fun ready() {
        assertEquals(VoiceAddon.Check.Ready("4.6.0"), check(FakePackages(ours, addon())))
        assertEquals(VoiceAddon.Check.Ready("4.6.0"), check(FakePackages(ours, addon()), abi = "armeabi-v7a"))
    }

    @Test
    fun onlyArmRunsIt() {
        for (abi in listOf("x86_64", "x86", "riscv64", null)) {
            val packages = FakePackages(ours, addon())
            assertSame("$abi", VoiceAddon.Check.Unsupported, check(packages, abi))
            assertEquals("the add-on was looked up on $abi", 0, packages.addonAsked)
        }
    }

    @Test
    fun eachCheckBecomesTheMatchingReason() {
        fun reason(check: VoiceAddon.Check): Reason? = try {
            VoiceIsolationLoader.requireReady(check)
            null
        } catch (e: VoiceIsolationLoader.Failure) {
            e.reason
        }
        assertEquals(null, reason(VoiceAddon.Check.Ready("1")))
        assertEquals(Reason.Device, reason(VoiceAddon.Check.Unsupported))
        assertEquals(Reason.AddonMissing, reason(VoiceAddon.Check.NotInstalled))
        assertEquals(Reason.AddonSignature, reason(VoiceAddon.Check.WrongSignature))
        assertEquals(Reason.AddonTooOld, reason(VoiceAddon.Check.AddonTooOld("1")))
        assertEquals(Reason.AppTooOld, reason(VoiceAddon.Check.AppTooOld("1")))
    }

    // The add-on's entry point, as the app finds it by reflection. Stand-ins with the same shape:
    // a static API_VERSION and a static open(byte[], int).

    class FakeHop : BiConsumer<FloatArray, FloatArray>, AutoCloseable {
        var hops = 0
        var closed = false
        override fun accept(mix: FloatArray, enhanced: FloatArray) {
            hops++
            mix.copyInto(enhanced)
        }
        override fun close() { closed = true }
    }

    object MatchingEntry {
        @JvmField val API_VERSION = VoiceAddon.API_VERSION
        var lastCaller = 0
        var lastModelSize = 0
        val opened = ArrayList<FakeHop>()

        @JvmStatic
        fun open(model: ByteArray, callerApiVersion: Int): Any {
            lastCaller = callerApiVersion
            lastModelSize = model.size
            return FakeHop().also { opened += it }
        }
    }

    object NewerEntry {
        @JvmField val API_VERSION = VoiceAddon.API_VERSION + 1

        @JvmStatic
        fun open(model: ByteArray, callerApiVersion: Int): Any = FakeHop()
    }

    object WrongHandleEntry {
        @JvmField val API_VERSION = VoiceAddon.API_VERSION
        var handedOut: FakeCloseable? = null

        @JvmStatic
        fun open(model: ByteArray, callerApiVersion: Int): Any = FakeCloseable().also { handedOut = it }
    }

    class FakeCloseable : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    object ThrowingEntry {
        @JvmField val API_VERSION = VoiceAddon.API_VERSION

        @JvmStatic
        fun open(model: ByteArray, callerApiVersion: Int): Any = throw IllegalStateException("no runtime")
    }

    private fun load(entry: Any) = VoiceAddon.load(javaClass.classLoader!!, entry.javaClass.name)

    @Test
    fun theEntryPointGetsTheModelAndTheAppsInterfaceVersion() {
        val backend = load(MatchingEntry).openSession(ByteArray(12))
        assertEquals(VoiceAddon.API_VERSION, MatchingEntry.lastCaller)
        assertEquals(12, MatchingEntry.lastModelSize)

        val hop = MatchingEntry.opened.last()
        backend.process(FloatArray(GtcrnModel.HOP_SIZE), FloatArray(GtcrnModel.HOP_SIZE))
        assertEquals(1, hop.hops)
        assertFalse(hop.closed)
        backend.close()
        assertTrue(hop.closed)
    }

    @Test
    fun anEntryPointOnAnotherInterfaceIsRefusedBeforeAnythingOpens() {
        try {
            load(NewerEntry)
            fail("loaded an add-on on interface ${NewerEntry.API_VERSION}")
        } catch (e: VoiceAddon.VersionMismatch) {
            assertEquals(VoiceAddon.API_VERSION + 1, e.addonVersion)
        }
    }

    @Test
    fun aHandleThatIsNotAHopIsClosedAndRefused() {
        try {
            load(WrongHandleEntry).openSession(ByteArray(1))
            fail("took a handle that cannot run a hop")
        } catch (e: IllegalStateException) {
            assertTrue(WrongHandleEntry.handedOut!!.closed)
        }
    }

    @Test
    fun theAddonsOwnErrorComesThroughUnwrapped() {
        try {
            load(ThrowingEntry).openSession(ByteArray(1))
            fail("no error")
        } catch (e: IllegalStateException) {
            assertEquals("no runtime", e.message)
        }
    }
}
