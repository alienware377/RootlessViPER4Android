package me.timschneeberger.rootlessjamesdsp.capture

import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapturePolicy.LossAction
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapturePolicy.StartAction
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapturePolicy.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuCapturePolicyTest {

    private val own = 10234

    private fun state(
        selected: Boolean = true,
        isRootless: Boolean = true,
        sdkInt: Int = 34,
        binderAlive: Boolean = true,
        preV11: Boolean = false,
        permissionGranted: Boolean = true,
        serverUid: Int = 2000,
    ) = ShizukuCapturePolicy.state(selected, isRootless, sdkInt, binderAlive, preV11, permissionGranted, serverUid)

    @Test
    fun `ready only when everything is in place`() {
        assertEquals(State.Ready, state())
        assertEquals(State.Ready, state(sdkInt = 33))
    }

    @Test
    fun `each missing piece gives its own reason`() {
        assertEquals(State.NotSelected, state(selected = false))
        assertEquals(State.NotRootless, state(isRootless = false))
        assertEquals(State.AndroidTooOld, state(sdkInt = 32))
        assertEquals(State.AndroidTooOld, state(sdkInt = 29))
        assertEquals(State.NotRunning, state(binderAlive = false))
        assertEquals(State.ShizukuTooOld, state(preV11 = true))
        assertEquals(State.NoPermission, state(permissionGranted = false))
        assertEquals(State.RunningAsRoot, state(serverUid = 0))
    }

    @Test
    fun `not picking the setting wins over everything else`() {
        // Someone who never turned it on must never hear about Shizuku
        assertEquals(State.NotSelected,
            state(selected = false, sdkInt = 29, binderAlive = false, permissionGranted = false))
    }

    @Test
    fun `old Android is reported before Shizuku problems`() {
        // Starting Shizuku would not help on Android 12, so do not suggest it
        assertEquals(State.AndroidTooOld, state(sdkInt = 31, binderAlive = false))
    }

    @Test
    fun `start action follows the state and the fallback switch`() {
        assertEquals(StartAction.UseShizuku, ShizukuCapturePolicy.startAction(State.Ready, false))
        assertEquals(StartAction.UseStandard, ShizukuCapturePolicy.startAction(State.NotSelected, false))
        assertEquals(StartAction.UseStandard, ShizukuCapturePolicy.startAction(State.NotRootless, true))
        for (s in listOf(State.AndroidTooOld, State.NotRunning, State.ShizukuTooOld,
                State.NoPermission, State.RunningAsRoot)) {
            assertEquals(s.name, StartAction.FallBackToStandard, ShizukuCapturePolicy.startAction(s, true))
            assertEquals(s.name, StartAction.GiveUp, ShizukuCapturePolicy.startAction(s, false))
        }
    }

    @Test
    fun `a capture lost while running never pops up a prompt`() {
        assertEquals(LossAction.StopAndNotify, ShizukuCapturePolicy.lossAction(duringStartup = false, fallbackAllowed = true))
        assertEquals(LossAction.StopAndNotify, ShizukuCapturePolicy.lossAction(duringStartup = false, fallbackAllowed = false))
    }

    @Test
    fun `a failed start falls back only when allowed`() {
        assertEquals(LossAction.FallBackToStandard, ShizukuCapturePolicy.lossAction(duringStartup = true, fallbackAllowed = true))
        assertEquals(LossAction.GiveUp, ShizukuCapturePolicy.lossAction(duringStartup = true, fallbackAllowed = false))
    }

    @Test
    fun `blocklist mode excludes the listed apps and always us`() {
        val r = ShizukuCapturePolicy.mixUids(false, listOf(10500, 10600), own)
        assertEquals(0, r.match.size)
        assertArrayEquals(intArrayOf(10500, 10600, own), r.exclude)
    }

    @Test
    fun `with an empty list we are still excluded`() {
        val r = ShizukuCapturePolicy.mixUids(false, emptyList(), own)
        assertEquals(0, r.match.size)
        assertArrayEquals(intArrayOf(own), r.exclude)
    }

    @Test
    fun `we are excluded once even when on the list ourselves`() {
        val r = ShizukuCapturePolicy.mixUids(false, listOf(own, 10500, 10500), own)
        assertArrayEquals(intArrayOf(own, 10500), r.exclude)
    }

    @Test
    fun `allowlist mode matches only the listed apps and never us`() {
        val r = ShizukuCapturePolicy.mixUids(true, listOf(10500, own, 10600), own)
        assertArrayEquals(intArrayOf(10500, 10600), r.match)
        assertEquals(0, r.exclude.size)
    }

    @Test
    fun `an empty allowlist captures everything except us`() {
        for (list in listOf(emptyList(), listOf(own))) {
            val r = ShizukuCapturePolicy.mixUids(true, list, own)
            assertEquals(0, r.match.size)
            assertArrayEquals(intArrayOf(own), r.exclude)
        }
    }

    @Test
    fun `match and exclude are never both used, and we are never matched`() {
        val lists = listOf(emptyList(), listOf(own), listOf(10500), listOf(10500, own), listOf(1, 2, 3))
        for (mode in listOf(true, false)) for (list in lists) {
            val r = ShizukuCapturePolicy.mixUids(mode, list, own)
            assertTrue("$mode $list", r.match.isEmpty() || r.exclude.isEmpty())
            assertFalse("$mode $list", r.match.contains(own))
            // Either we are excluded, or only other apps are matched: in both
            // cases our own output can never be routed back into the capture
            assertTrue("$mode $list", r.exclude.contains(own) || r.match.isNotEmpty())
        }
    }
}
